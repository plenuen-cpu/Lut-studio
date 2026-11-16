package com.plenuen.lumen.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeSession
import com.plenuen.lumen.data.LutLibrary
import com.plenuen.lumen.image.PhotoStore
import com.plenuen.lumen.gl.OffscreenExporter
import com.plenuen.lumen.data.LutPreset
import com.plenuen.lumen.geom.StraightenEstimator
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

    data class UiState(
        val loaded: Boolean = false,
        val preview: Bitmap? = null,
        val photoUri: android.net.Uri? = null,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        val recipe: com.plenuen.lumen.core.EditRecipe = com.plenuen.lumen.core.EditRecipe.NEUTRAL,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val compare: Boolean = false,
        val showSliders: Boolean = false,
        val busy: String? = null,
        val message: String? = null,
        val chips: List<String> = emptyList(),
        val scene: SceneStats = SceneStats.UNKNOWN,
        val straighten: StraightenEstimator.Result? = null,
        val presets: List<LutPreset> = emptyList(),
        val lutBytes: ByteArray? = null,
        val lut: com.plenuen.lumen.data.LutPreset? = null,
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
                com.plenuen.lumen.core.EditRecipe.NEUTRAL
                    .with(Param.STRAIGHTEN_DEG, straight.degrees)
                    .with(Param.AUTO_STRAIGHTEN, 1f)
            } else {
                com.plenuen.lumen.core.EditRecipe.NEUTRAL.with(Param.AUTO_STRAIGHTEN, 1f)
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

    // ── 자연어 ────────────────────────────────────────────────────────────

    fun submitPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !_state.value.loaded) return
        val parsed = NaturalLanguageParser.parse(trimmed)
        val current = _state.value
        var applied = false
        var summaryParts = mutableListOf<String>()

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
                if (!delta.isEmpty) {
                    session.apply(hit.intent.label, delta, hit.strength)
                    applied = true
                    summaryParts += "${hit.intent.label} ${(hit.strength * 100).toInt()}%"
                }
            }
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
                summaryParts += preset.display
            }
        }

        if (!applied) {
            // 규칙이 문장을 이해하지 못했을 때만 로컬 LLM에게 묻는다.
            scope.launch {
                val understood = llmFallback(trimmed, current)
                if (!understood) {
                    _state.update {
                        it.copy(message = "문장을 이해하지 못했어요. \"따뜻하게 조금\"처럼 말해 보세요.")
                    }
                    pushState()
                    return@launch
                }
                _state.update {
                    it.copy(
                        message = "로컬 LLM 해석 · ${it.llm.status}",
                        chips = (it.chips + "로컬 LLM").distinct().takeLast(8),
                    )
                }
                pushState()
            }
            return
        }

        _state.update {
            it.copy(
                message = summaryParts.joinToString(" · ").ifBlank { null },
                chips = (it.chips + summaryParts).distinct().takeLast(8),
            )
        }
        pushState()

        // 규칙이 다 읽지 못한 나머지 문장이 남아 있으면 로컬 LLM이 그 부분만 이어서 해석한다.
        val leftover = parsed.unmatched.trim()
        if (leftover.length >= 2) {
            scope.launch {
                val before = session.current
                if (llmFallback(leftover, _state.value)) {
                    if (session.current != before) {
                        _state.update { st ->
                            st.copy(message = "${st.message ?: ""} · 로컬 LLM: ${st.llm.status}".trim(' ', '·'))
                        }
                        pushState()
                    }
                }
            }
        }
    }

    private fun recipe(param: Param): Float = session.current[param]

    private fun findLut(query: String): LutPreset? {
        val presets = lutLibrary?.presets ?: return null
        val needle = query.lowercase()
        return presets.firstOrNull { it.display.lowercase().contains(needle) }
            ?: presets.firstOrNull { it.brand.lowercase().contains(needle) }
            ?: presets.firstOrNull { it.name.lowercase().contains(needle) }
    }

    private suspend fun llmFallback(text: String, current: UiState): Boolean {
        if (!current.llm.enabled) return false
        val model = LlmModels.all.firstOrNull { it.id == current.llm.modelId } ?: LlmModels.default
        if (!modelStore.isDownloaded(model)) {
            _state.update {
                it.copy(message = "로컬 LLM 모델이 아직 없습니다. 설정에서 내려받으면 더 복잡한 문장도 읽습니다.")
            }
            return false
        }
        _state.update { it.copy(busy = "로컬 LLM이 문장을 읽는 중…") }
        val loaded = llm.ensureLoaded(model, modelStore.fileFor(model))
        if (!loaded) {
            _state.update { it.copy(busy = null, llm = it.llm.copy(status = llm.lastError ?: "모델을 열 수 없습니다.")) }
            return false
        }
        val names = lutLibrary?.presets?.map { it.display } ?: emptyList()
        val decoded = llm.interpret(text, current.scene, names)
        _state.update { it.copy(busy = null) }
        if (decoded == null) return false
        session.apply(decoded.label, decoded.delta, 0.85f)
        decoded.lutHint?.let { hint -> findLut(hint)?.let { decodeLut(it) } }
        _state.update { it.copy(llm = it.llm.copy(lastRequest = text, status = decoded.summary)) }
        return true
    }

    // ── 수동 조정 ─────────────────────────────────────────────────────────

    fun setParam(param: Param, value: Float) {
        if (!_state.value.loaded) return
        session.replace(session.current.with(param, value), param.label)
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
            _state.update {
                it.copy(
                    straighten = result,
                    message = "수평을 ${"%.1f".format(result.degrees)}° 자동으로 맞췄어요.",
                )
            }
            pushState()
        }
    }

    fun selectLut(preset: LutPreset?) {
        if (!_state.value.loaded) return
        val recipe = if (preset == null) {
            session.current.with(Param.LUT_INDEX, -1f)
        } else {
            session.current.with(Param.LUT_INDEX, preset.idx.toFloat())
        }
        session.replace(recipe, preset?.display ?: "룩 없음")
        if (preset != null) decodeLut(preset) else _state.update { it.copy(lutBytes = null, lut = null) }
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
        pushState()
    }

    fun redo() {
        session.redo()
        pushState()
    }

    fun reset() {
        session.reset()
        pushState()
    }

    fun setCompare(on: Boolean) = _state.update { it.copy(compare = on) }

    fun toggleSliders() = _state.update { it.copy(showSliders = !it.showSliders) }

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
