package com.plenuen.lumen.ui

import android.graphics.Bitmap
import com.plenuen.lumen.core.EditNarrator
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeSession
import com.plenuen.lumen.data.LutLibrary
import com.plenuen.lumen.data.LutPreset
import com.plenuen.lumen.geom.StraightenEstimator
import com.plenuen.lumen.gl.OffscreenExporter
import com.plenuen.lumen.image.PhotoStore
import com.plenuen.lumen.llm.LlmModel
import com.plenuen.lumen.llm.LlmModelStore
import com.plenuen.lumen.llm.LlmModels
import com.plenuen.lumen.llm.LocalLlmEngine
import com.plenuen.lumen.nl.AestheticPlanner
import com.plenuen.lumen.nl.NaturalLanguageParser
import com.plenuen.lumen.nl.SceneAnalyzer
import com.plenuen.lumen.nl.SceneStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one open photo: its recipe history, the scene measurements, the LUT library and the local
 * language model. The UI only sends intents here and renders the state that comes back.
 */
class EditorViewModel(
    private val appContext: android.content.Context,
    private val scope: CoroutineScope,
) {

    data class LlmUi(
        val enabled: Boolean = true,
        val modelId: String = LlmModels.default.id,
        val downloadedIds: Set<String> = emptySet(),
        val downloadProgress: Float? = null,
        val status: String = "",
        val lastRequest: String? = null,
    )

    /** The translucent card above the prompt bar: what the last request actually changed. */
    data class Note(
        val text: String,
        val caption: String = "방금 한 보정",
        /** Shows a "설정" shortcut because the local model would have understood the sentence. */
        val suggestDownload: Boolean = false,
    )

    data class UiState(
        val loaded: Boolean = false,
        val preview: Bitmap? = null,
        val photoUri: android.net.Uri? = null,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        val recipe: EditRecipe = EditRecipe.NEUTRAL,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val compare: Boolean = false,
        /** Horizontal Before/After split line on the preview (never applied to exports). */
        val split: Boolean = false,
        val splitY: Float = 0.5f,
        val showSliders: Boolean = false,
        val busy: String? = null,
        val message: String? = null,
        val note: Note? = null,
        val chips: List<String> = emptyList(),
        val scene: SceneStats = SceneStats.UNKNOWN,
        val straighten: StraightenEstimator.Result? = null,
        val presets: List<LutPreset> = emptyList(),
        val lutBytes: ByteArray? = null,
        val lut: LutPreset? = null,
        val llm: LlmUi = LlmUi(),
        val historyLabels: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val photoStore = PhotoStore(appContext)
    private val lutLibrary: LutLibrary? = runCatching { LutLibrary(appContext) }.getOrNull()
    private val modelStore = LlmModelStore(appContext)
    private val llm = LocalLlmEngine(appContext)
    private var session = RecipeSession()

    init {
        val downloaded = modelStore.downloadedModels().map { it.id }.toSet()
        _state.update {
            it.copy(
                presets = lutLibrary?.presets ?: emptyList(),
                llm = it.llm.copy(downloadedIds = downloaded),
            )
        }
    }

    // ── 사진 열기 ─────────────────────────────────────────────────────────

    fun openPhoto(uri: android.net.Uri) {
        scope.launch {
            _state.update { it.copy(busy = "사진을 여는 중…") }
            val loaded = withContext(Dispatchers.IO) { photoStore.loadPreview(uri) }
            if (loaded == null) {
                _state.update { it.copy(busy = null, message = "사진을 열 수 없습니다.") }
                return@launch
            }
            val stats = withContext(Dispatchers.Default) { analyze(loaded.bitmap) }
            val straight = withContext(Dispatchers.Default) { estimateStraight(loaded.bitmap) }

            val initial = if (straight.isReliable) {
                EditRecipe.NEUTRAL
                    .with(Param.STRAIGHTEN_DEG, straight.degrees)
                    .with(Param.AUTO_STRAIGHTEN, 1f)
            } else {
                EditRecipe.NEUTRAL.with(Param.AUTO_STRAIGHTEN, 1f)
            }
            session = RecipeSession(initial)
            _state.update {
                it.copy(
                    loaded = true,
                    preview = loaded.bitmap,
                    photoUri = uri,
                    imageWidth = loaded.width,
                    imageHeight = loaded.height,
                    recipe = initial,
                    canUndo = false,
                    canRedo = false,
                    busy = null,
                    splitY = 0.5f,
                    note = null,
                    chips = buildList {
                        if (straight.isReliable) add("자동 수평 ${"%.1f".format(straight.degrees)}°")
                    },
                    scene = stats,
                    straighten = straight,
                    historyLabels = session.history.map { step -> step.label },
                    message = if (straight.isReliable) {
                        "수평을 ${"%.1f".format(straight.degrees)}° 자동으로 맞췄어요."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun dismissNote() = _state.update { it.copy(note = null) }

    // ── 자연어 ────────────────────────────────────────────────────────────

    fun submitPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !_state.value.loaded) return
        val parsed = NaturalLanguageParser.parse(trimmed)
        val current = _state.value
        val before = session.current
        var applied = false
        var appliedLutName: String? = null
        val summaryParts = mutableListOf<String>()

        // 1) 대화 제어
        when (parsed.control) {
            NaturalLanguageParser.Control.UNDO -> {
                session.undo()
                applied = true
                summaryParts += "되돌림"
            }
            NaturalLanguageParser.Control.REDO -> {
                session.redo()
                applied = true
                summaryParts += "다시 실행"
            }
            NaturalLanguageParser.Control.RESET -> {
                session.reset()
                applied = true
                summaryParts += "초기화"
            }
            NaturalLanguageParser.Control.HALF -> {
                session.halveLast()
                applied = true
                summaryParts += "절반으로"
            }
            NaturalLanguageParser.Control.MORE -> {
                session.repeatLast(1f)
                applied = true
                summaryParts += "한 번 더"
            }
            NaturalLanguageParser.Control.LESS -> {
                session.repeatLast(-0.5f)
                applied = true
                summaryParts += "조금 덜"
            }
            NaturalLanguageParser.Control.COMPARE -> {
                _state.update { it.copy(compare = true) }
                applied = true
                summaryParts += "원본 비교"
                scope.launch {
                    kotlinx.coroutines.delay(1500)
                    _state.update { it.copy(compare = false) }
                }
            }
            null -> {}
        }

        // 2) 수평·수직
        if (parsed.autoStraighten) {
            autoStraighten(announce = false)
            applied = true
            summaryParts += "수평·수직 맞춤"
        }
        parsed.straightenDeg?.let { degrees ->
            session.apply(
                "수평 ${"%.1f".format(degrees)}°",
                com.plenuen.lumen.core.RecipeDelta.set("수평", Param.STRAIGHTEN_DEG, degrees),
            )
            applied = true
            summaryParts += "수평 ${"%.1f".format(degrees)}°"
        }

        // 3) 룩 의도
        if (parsed.hits.isNotEmpty()) {
            for (hit in parsed.hits) {
                val delta = hit.intent.aesthetic?.let { profile ->
                    AestheticPlanner.plan(profile, current.scene)
                } ?: hit.intent.delta
                val strength = parsed.strengthOf(hit)
                if (!delta.isEmpty && strength != 0f) {
                    session.apply(hit.intent.label, delta, strength)
                    applied = true
                    summaryParts += "${hit.intent.label} ${(strength * 100).toInt()}%"
                }
            }
        }

        // 3-1) 문장이 건 제한 ("너무 화려하지는 않게", "그레인 빼고", "부드럽게 말고")
        if (parsed.restraints.isNotEmpty()) {
            val label = parsed.restraints.joinToString(" · ") { it.restraint.label }
            var next = session.current
            for (hit in parsed.restraints) {
                hit.restraint.delta?.let { delta -> next = next.apply(delta, parsed.strengthScale) }
            }
            next = parsed.restrained(next)
            if (next != session.current) session.replace(next, label)
            // 이미 그런 상태라 바뀐 값이 없어도 문장은 알아들은 것이므로 모델로 넘기지 않는다.
            applied = true
            summaryParts += label
        }

        // 4) LUT 지정
        parsed.lutQuery?.let { query ->
            val preset = findLut(query)
            if (preset != null) {
                val intensity = parsed.lutIntensity ?: session.current[Param.LUT_INTENSITY]
                val recipe = session.current
                    .with(Param.LUT_INDEX, preset.idx.toFloat())
                    .with(Param.LUT_INTENSITY, intensity)
                session.replace(recipe, "룩 ${preset.display}")
                decodeLut(preset)
                applied = true
                appliedLutName = preset.display
                summaryParts += preset.display
            }
        }

        if (!applied) {
            // 사전에 없는 표현은 곧바로 로컬 LLM 추론 경로로 넘긴다.
            scope.launch { interpretWithLlm(trimmed, before) }
            return
        }

        val after = session.current
        val sentence = EditNarrator.describe(before, after, appliedLutName)
        _state.update { state ->
            val note = when (parsed.control) {
                // 되돌리기는 새 보정이 아니라서 "방금 한 보정" 카드를 지운다.
                NaturalLanguageParser.Control.UNDO,
                NaturalLanguageParser.Control.REDO,
                -> null
                NaturalLanguageParser.Control.RESET -> Note(
                    "모든 보정을 지우고 원본으로 되돌렸어요.",
                    caption = "초기화",
                )
                NaturalLanguageParser.Control.COMPARE -> state.note
                else -> sentence?.let { text -> Note(text) } ?: state.note
            }
            state.copy(
                // 카드가 같은 내용을 이미 보여 주므로 스낵바는 카드가 없을 때만 띄운다.
                message = if (note === state.note) {
                    summaryParts.joinToString(" · ").ifBlank { null }
                } else {
                    null
                },
                chips = (state.chips + summaryParts).distinct().takeLast(8),
                note = note,
            )
        }
        pushState()

        // 규칙이 다 읽지 못한 나머지 문장이 남아 있으면 로컬 LLM이 그 부분만 이어서 해석한다.
        val leftover = parsed.unmatched.trim()
        // 한 낱말짜리 꼬리("피부", "하늘")는 이미 읽은 문장의 일부다. 두 낱말 이상이거나 세 글자
        // 이상 남았을 때만 모델에게 물어, 알아들은 문장을 다시 해석하게 만들지 않는다.
        val worthAsking = leftover.isNotEmpty() &&
            (leftover.split(' ').size >= 2 || leftover.length >= 3)
        if (worthAsking) {
            scope.launch {
                // 룰이 이미 반영한 뒤의 자투리 문장이라, 모델이 없을 때 다운로드 안내로 덮지 않는다.
                if (interpretWithLlm(leftover, before, notifyMissingModel = false)) pushState()
            }
        }
    }

    private fun findLut(query: String): LutPreset? {
        val presets = lutLibrary?.presets ?: return null
        val needle = query.lowercase()
        return presets.firstOrNull { it.display.lowercase().contains(needle) }
            ?: presets.firstOrNull { it.brand.lowercase().contains(needle) }
            ?: presets.firstOrNull { it.name.lowercase().contains(needle) }
    }

    /**
     * Sends [text] to the on-device model and applies what it understands. [before] is the recipe
     * the sentence started from, so the note can describe the whole change, not just the model's
     * share of it.
     */
    private suspend fun interpretWithLlm(
        text: String,
        before: EditRecipe,
        notifyMissingModel: Boolean = true,
    ): Boolean {
        val current = _state.value
        if (!current.llm.enabled) {
            _state.update {
                it.copy(
                    note = Note(
                        text = "자동 해석이 꺼져 있어 이 표현은 반영하지 못했어요. 설정에서 켜거나 " +
                            "\"더 따뜻하게\"처럼 익숙한 말로 말해 주세요.",
                        caption = "로컬 LLM 꺼짐",
                    ),
                )
            }
            return false
        }
        val model = LlmModels.all.firstOrNull { it.id == current.llm.modelId } ?: LlmModels.default
        if (!modelStore.isDownloaded(model)) {
            if (!notifyMissingModel) return false
            _state.update {
                it.copy(
                    note = Note(
                        text = "사전에 없는 표현이에요. 상단 설정(⚙️)에서 로컬 LLM(${model.sizeMb}MB)을 " +
                            "내려받으면 자유로운 문장도 추론해서 보정해 드려요!",
                        caption = "로컬 LLM 안내",
                        suggestDownload = true,
                    ),
                )
            }
            return false
        }

        _state.update { it.copy(busy = "로컬 LLM이 문장을 읽는 중…") }
        if (!llm.ensureLoaded(model, modelStore.fileFor(model))) {
            _state.update {
                it.copy(
                    busy = null,
                    llm = it.llm.copy(status = llm.lastError ?: "모델을 열 수 없습니다."),
                    note = Note(
                        text = "로컬 LLM을 열지 못했어요. 설정에서 모델을 다시 내려받아 주세요.",
                        caption = "로컬 LLM 오류",
                    ),
                )
            }
            return false
        }

        val names = lutLibrary?.presets?.map { it.display } ?: emptyList()
        val decoded = llm.interpret(text, current.scene, names)
        _state.update { it.copy(busy = null) }
        if (decoded == null) {
            _state.update {
                it.copy(
                    note = Note(
                        text = "이 문장은 아직 해석하지 못했어요. \"저녁 골목처럼 차분하게\" 같이 " +
                            "조금 더 구체적으로 말해 주시면 반영할게요.",
                        caption = "해석 실패",
                    ),
                )
            }
            return false
        }

        session.apply(decoded.label, decoded.delta, 0.85f)
        // 룩 제안은 레시피에도 반영해야 프리뷰와 저장에 실제로 적용된다.
        val preset = decoded.lutHint?.let { hint -> findLut(hint) }
        if (preset != null) {
            session.replace(
                session.current.with(Param.LUT_INDEX, preset.idx.toFloat()),
                "룩 ${preset.display}",
            )
            decodeLut(preset)
        }
        val sentence = EditNarrator.describe(before, session.current, preset?.display)
        _state.update {
            it.copy(
                note = Note(sentence ?: decoded.summary, caption = "로컬 LLM 해석"),
                message = decoded.summary,
                chips = (it.chips + "로컬 LLM").distinct().takeLast(8),
                llm = it.llm.copy(lastRequest = text, status = decoded.summary),
            )
        }
        pushState()
        return true
    }

    // ── 수동 조정 ─────────────────────────────────────────────────────────

    fun setParam(param: Param, value: Float) {
        if (!_state.value.loaded) return
        val before = session.current
        session.replace(session.current.with(param, value), param.label)
        val sentence = EditNarrator.describe(before, session.current)
        _state.update { state ->
            state.copy(note = sentence?.let { Note(it, caption = param.label) } ?: state.note)
        }
        pushState()
    }

    fun toggleAutoStraighten() {
        val next = if (_state.value.recipe.autoStraightenEnabled()) 0f else 1f
        session.replace(session.current.with(Param.AUTO_STRAIGHTEN, next), "자동 수평")
        if (next > 0.5f) autoStraighten(announce = false)
        pushState()
    }

    fun autoStraighten(announce: Boolean = true) {
        val preview = _state.value.preview ?: return
        scope.launch {
            val before = session.current
            val result = withContext(Dispatchers.Default) { estimateStraight(preview) }
            if (!result.isReliable) {
                _state.update {
                    it.copy(
                        straighten = result,
                        message = if (announce) "기울기를 찾지 못했어요. 수동으로 맞춰 주세요." else it.message,
                    )
                }
                return@launch
            }
            session.apply(
                "자동 수평·수직",
                com.plenuen.lumen.core.RecipeDelta.set("수평", Param.STRAIGHTEN_DEG, result.degrees),
            )
            val sentence = EditNarrator.describe(before, session.current)
            _state.update {
                it.copy(
                    straighten = result,
                    message = "수평을 ${"%.1f".format(result.degrees)}° 자동으로 맞췄어요.",
                    note = sentence?.let { text -> Note(text) } ?: it.note,
                )
            }
            pushState()
        }
    }

    fun selectLut(preset: LutPreset?) {
        if (!_state.value.loaded) return
        val before = session.current
        val recipe = if (preset == null) {
            session.current.with(Param.LUT_INDEX, -1f)
        } else {
            session.current.with(Param.LUT_INDEX, preset.idx.toFloat())
        }
        session.replace(recipe, preset?.display ?: "룩 없음")
        if (preset != null) decodeLut(preset) else _state.update { it.copy(lutBytes = null, lut = null) }
        val sentence = EditNarrator.describe(before, session.current, preset?.display)
        _state.update { it.copy(note = sentence?.let { text -> Note(text) } ?: it.note) }
        pushState()
    }

    private fun decodeLut(preset: LutPreset) {
        scope.launch {
            val bytes = withContext(Dispatchers.Default) { lutLibrary?.lutFor(preset) }
            _state.update { it.copy(lutBytes = bytes, lut = preset) }
        }
    }

    fun undo() {
        session.undo()
        _state.update { it.copy(note = null) }
        pushState()
    }

    fun redo() {
        session.redo()
        _state.update { it.copy(note = null) }
        pushState()
    }

    fun reset() {
        session.reset()
        _state.update { it.copy(note = Note("모든 보정을 지우고 원본으로 되돌렸어요.", caption = "초기화")) }
        pushState()
    }

    fun setCompare(on: Boolean) = _state.update { it.copy(compare = on) }

    fun toggleSliders() = _state.update { it.copy(showSliders = !it.showSliders) }

    // ── 가로 비교 ─────────────────────────────────────────────────────────

    fun toggleSplit() = _state.update { it.copy(split = !it.split) }

    /** Drags the split line; [delta] is a fraction of the photo height (positive = down). */
    fun nudgeSplit(delta: Float) = _state.update {
        it.copy(splitY = (it.splitY + delta).coerceIn(0.03f, 0.97f))
    }

    // ── 내보내기 ──────────────────────────────────────────────────────────

    fun export() {
        val current = _state.value
        val uri = current.photoUri ?: return
        if (current.busy != null) return
        scope.launch {
            _state.update { it.copy(busy = "원본 해상도로 내보내는 중…") }
            val full = withContext(Dispatchers.IO) { photoStore.loadFull(uri) }
            if (full == null) {
                _state.update { it.copy(busy = null, message = "원본 사진을 다시 열 수 없습니다.") }
                return@launch
            }
            // 저장에는 비교선이 들어가지 않는다: 셰이더는 전체 보정 이미지를 그린다.
            val outcome = withContext(Dispatchers.Default) {
                OffscreenExporter().render(full.bitmap, current.recipe, current.lutBytes)
            }
            when (outcome) {
                is OffscreenExporter.Outcome.Failure -> _state.update {
                    it.copy(busy = null, message = "내보내기 실패: ${outcome.message}")
                }
                is OffscreenExporter.Outcome.Success -> {
                    val saved = withContext(Dispatchers.IO) { photoStore.saveExport(outcome.bitmap) }
                    _state.update {
                        it.copy(
                            busy = null,
                            message = when {
                                saved == null -> "사진을 저장하지 못했습니다."
                                outcome.downscaled -> "저장했어요 (기기 한계로 ${outcome.bitmap.width}px)."
                                else -> "Pictures/Lumen에 저장했어요 (${outcome.bitmap.width}×${outcome.bitmap.height})."
                            },
                        )
                    }
                }
            }
        }
    }

    // ── 로컬 LLM 설정 ─────────────────────────────────────────────────────

    fun setLlmEnabled(enabled: Boolean) = _state.update { it.copy(llm = it.llm.copy(enabled = enabled)) }

    fun selectModel(model: LlmModel) {
        _state.update { it.copy(llm = it.llm.copy(modelId = model.id)) }
    }

    fun downloadModel(model: LlmModel) {
        if (_state.value.llm.downloadProgress != null) return
        scope.launch {
            _state.update { it.copy(llm = it.llm.copy(downloadProgress = 0f, status = "내려받는 중…")) }
            val result = runCatching {
                modelStore.download(model) { progress ->
                    _state.update { it.copy(llm = it.llm.copy(downloadProgress = progress)) }
                }
            }
            result.onSuccess {
                _state.update {
                    it.copy(
                        llm = it.llm.copy(
                            downloadProgress = null,
                            downloadedIds = it.llm.downloadedIds + model.id,
                            status = "${model.title} 준비 완료",
                        ),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(llm = it.llm.copy(downloadProgress = null, status = "내려받기 실패: ${error.message}"))
                }
            }
        }
    }

    fun deleteModel(model: LlmModel) {
        scope.launch {
            withContext(Dispatchers.IO) { modelStore.delete(model) }
            _state.update {
                it.copy(llm = it.llm.copy(downloadedIds = it.llm.downloadedIds - model.id, status = "삭제했어요"))
            }
        }
    }

    fun release() {
        llm.close()
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    private fun pushState() {
        val recipe = session.current
        _state.update {
            it.copy(
                recipe = recipe,
                canUndo = session.canUndo,
                canRedo = session.canRedo,
                historyLabels = session.history.map { step -> step.label },
            )
        }
    }

    private fun analyze(bitmap: Bitmap): SceneStats {
        val width = minOf(bitmap.width, 900)
        val height = (bitmap.height.toFloat() * width / bitmap.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        return SceneAnalyzer.analyze(pixels, width, height)
    }

    private fun estimateStraight(bitmap: Bitmap): StraightenEstimator.Result {
        val width = minOf(bitmap.width, 900)
        val height = (bitmap.height.toFloat() * width / bitmap.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        return StraightenEstimator.estimate(pixels, width, height)
    }
}
