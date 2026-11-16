package com.plenuen.lumen.ui

import android.graphics.Bitmap
import com.plenuen.lumen.core.EditNarrator
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.ParamGroup
import com.plenuen.lumen.core.RecipeSession
import com.plenuen.lumen.data.LutLibrary
import com.plenuen.lumen.data.LutPreset
import com.plenuen.lumen.geom.StraightenEstimator
import com.plenuen.lumen.gl.OffscreenExporter
import com.plenuen.lumen.image.MaskGenerator
import com.plenuen.lumen.image.PhotoStore
import com.plenuen.lumen.llm.LlmModel
import com.plenuen.lumen.llm.LlmModelStore
import com.plenuen.lumen.llm.LlmModels
import com.plenuen.lumen.llm.LocalLlmEngine
import com.plenuen.lumen.nima.NimaEvaluator
import com.plenuen.lumen.nima.NimaOptimizer
import com.plenuen.lumen.nima.NimaScores
import com.plenuen.lumen.nl.AestheticPlanner
import com.plenuen.lumen.nl.AestheticProfile
import com.plenuen.lumen.nl.NaturalLanguageParser
import com.plenuen.lumen.nl.SceneAnalyzer
import com.plenuen.lumen.nl.SceneStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

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

    /** One chip in the history bar; the bottom line cannot be deleted. */
    data class StepView(val id: Long, val title: String, val isOrigin: Boolean)

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
        /** On-device segmentation mask: R = subject, G = sky. Null until it is generated. */
        val mask: Bitmap? = null,
        val maskReady: Boolean = false,
        /** How the mask was produced (MediaPipe model or the colour/position fallback). */
        val maskMethod: String = "",
        /** Paints the mask over the preview so the user can see what a region request targets. */
        val maskPreview: Boolean = false,
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
        /** Editable history: each chip is one step and tapping it lifts that step out. */
        val steps: List<StepView> = emptyList(),
        /** NIMA 미적 점수 of the current recipe (1~10); null until it has been measured. */
        val nimaScore: Float? = null,
        /** True while the automatic NIMA pass is running. */
        val nimaBusy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val photoStore = PhotoStore(appContext)
    private val maskGenerator = MaskGenerator(appContext)
    private val lutLibrary: LutLibrary? = runCatching { LutLibrary(appContext) }.getOrNull()
    private val modelStore = LlmModelStore(appContext)
    private val llm = LocalLlmEngine(appContext)
    private var session = RecipeSession()

    // NIMA는 EGL 컨텍스트를 열어 두고 여러 번 그리므로, 한 스레드에서만 만진다.
    private val nimaThread = Executors
        .newSingleThreadExecutor { runnable -> Thread(runnable, "lumen-nima") }
        .asCoroutineDispatcher()
    private var evaluator: NimaEvaluator? = null
    private var evaluatorSource: Bitmap? = null
    private var nimaUnavailable = false
    private var scoreJob: Job? = null

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
            nimaUnavailable = false
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
                    mask = null,
                    maskReady = false,
                    maskMethod = "",
                    maskPreview = false,
                    chips = buildList {
                        if (straight.isReliable) add("자동 수평 ${"%.1f".format(straight.degrees)}°")
                    },
                    scene = stats,
                    straighten = straight,
                    historyLabels = session.titleList,
                    steps = session.steps().map { step -> StepView(step.id, step.title, step.isOrigin) },
                    message = if (straight.isReliable) {
                        "수평을 ${"%.1f".format(straight.degrees)}° 자동으로 맞췄어요."
                    } else {
                        null
                    },
                )
            }
            buildMask(loaded.bitmap)
            scheduleNimaScore(immediate = true)
        }
    }

    /** Builds the region mask off the UI thread; region sliders stay inert until it lands. */
    private fun buildMask(bitmap: Bitmap) {
        scope.launch {
            val mask = withContext(Dispatchers.Default) {
                runCatching { maskGenerator.generate(bitmap) }.getOrNull()
            }
            _state.update { state ->
                if (mask == null) {
                    state.copy(maskReady = false, maskMethod = "마스크를 만들지 못했어요.")
                } else {
                    state.copy(
                        mask = mask.bitmap,
                        maskReady = true,
                        maskMethod = maskGenerator.describe(mask.method),
                    )
                }
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
        // "자동 보정", "NIMA 높여줘", "점수 잘 나오게" — 점수판(NIMA)을 쓴 자동 보정으로 바로 넘긴다.
        if (parsed.nimaAuto) {
            autoEnhance()
            return
        }
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

        // 3-2) 영역·색상 지시 ("인물만 화사하게", "빨강만 살리고 초록은 차분하게")
        if (parsed.regionHits.isNotEmpty() || parsed.colorHits.isNotEmpty()) {
            val wantsRegion = parsed.regionHits.isNotEmpty()
            for (hit in parsed.regionHits) {
                session.apply(hit.label, hit.delta, 1f)
                summaryParts += hit.label
                applied = true
            }
            for (hit in parsed.colorHits) {
                session.apply(hit.label, hit.delta, 1f)
                summaryParts += hit.label
                applied = true
            }
            if (wantsRegion && !_state.value.maskReady) {
                _state.update {
                    it.copy(
                        message = it.message
                            ?: "영역 마스크를 아직 만들지 못했어요. 잠시 뒤 자동으로 다시 시도해 드릴게요.",
                    )
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

    // ── 자동 보정 (NIMA) ──────────────────────────────────────────────────

    /**
     * The ✨ 자동 button and its spoken equivalents.
     *
     * Straightens the frame first when the photo is tilted, then asks the on-device NIMA model which
     * direction raises the score and applies what it found as one removable step. The measured
     * before/after scores travel with the result, so the card states the real change rather than
     * claiming one.
     */
    fun autoEnhance() {
        val current = _state.value
        if (!current.loaded || current.busy != null) return
        val preview = current.preview ?: return
        scope.launch {
            _state.update { it.copy(busy = "사진을 읽는 중…", nimaBusy = true) }

            // 1) 수평·수직 — 기운 사진을 먼저 바로 세운다(점수 후보도 이 프레임 위에서 잰다).
            val straight = withContext(Dispatchers.Default) { estimateStraight(preview) }
            val currentDegrees = session.current[Param.STRAIGHTEN_DEG]
            if (straight.isReliable && kotlin.math.abs(currentDegrees - straight.degrees) > 0.05f) {
                session.apply(
                    "자동 수평·수직",
                    com.plenuen.lumen.core.RecipeDelta.set("수평", Param.STRAIGHTEN_DEG, straight.degrees),
                )
                _state.update { it.copy(straighten = straight) }
            }
            pushState()

            // 2) NIMA 후보 탐색
            _state.update { it.copy(busy = "NIMA 점수를 재는 중…") }
            val engine = ensureEvaluator(preview, _state.value.lutBytes)
            if (engine == null) {
                applyRuleBasedAuto()
                return@launch
            }
            val base = session.current
            val baseScore = withContext(nimaThread) { engine.score(base) }
            val result = withContext(nimaThread) {
                NimaOptimizer.optimize(
                    base = base,
                    baseScore = baseScore,
                    scorer = engine.scorer(),
                    onProgress = { done, best ->
                        if (done % 6 == 0) {
                            _state.update {
                                it.copy(
                                    busy = "NIMA 후보 ${done}개 · 지금 최고 ${NimaScores.format(best)}",
                                )
                            }
                        }
                    },
                )
            }

            if (result.improved) {
                session.replace(result.recipe, "✨ 자동")
                val gainText = String.format(java.util.Locale.US, "%+.2f", result.gain)
                _state.update {
                    it.copy(
                        busy = null,
                        nimaBusy = false,
                        nimaScore = result.after,
                        note = Note(
                            "NIMA 미적 점수 ${NimaScores.format(result.before)} → " +
                                "${NimaScores.format(result.after)} ($gainText)로 자동 보정했어요. " +
                                "${result.summary()}를 조정했어요.",
                            caption = "✨ 자동 보정",
                        ),
                        message = null,
                        chips = (it.chips + "✨ 자동 $gainText").distinct().takeLast(8),
                    )
                }
                pushState()
                return@launch
            }

            val measured = result.after ?: result.before
            val message = when {
                measured == null -> "NIMA 점수를 재지 못했어요. 모델을 준비하지 못했습니다."
                else -> "지금 사진이 이미 NIMA ${NimaScores.format(measured)}이라 억지로 건드리지 않았어요."
            }
            _state.update {
                it.copy(
                    busy = null,
                    nimaBusy = false,
                    nimaScore = measured ?: it.nimaScore,
                    note = Note(message, caption = "✨ 자동 보정"),
                    message = null,
                )
            }
            pushState()
        }
    }

    /** NIMA를 쓸 수 없을 때: 사진을 재 본 규칙(예쁘게)으로 최소한의 보정을 한다. */
    private fun applyRuleBasedAuto() {
        val stats = _state.value.scene
        val before = session.current
        session.apply("✨ 자동(규칙)", AestheticPlanner.plan(AestheticProfile.PRETTY, stats), 1f)
        val sentence = EditNarrator.describe(before, session.current)
        _state.update {
            it.copy(
                busy = null,
                nimaBusy = false,
                note = Note(
                    (sentence ?: "사진에 맞춰 자동으로 보정했어요.") +
                        " (NIMA 모델을 쓸 수 없어 사진 통계로 보정했어요.)",
                    caption = "✨ 자동(규칙)",
                ),
                message = null,
            )
        }
        pushState()
    }

    /**
     * Makes sure one NIMA evaluator is open for the current photo. It owns an EGL context, so it is
     * created, used and closed on [nimaThread] — never from the preview's GL thread.
     */
    private suspend fun ensureEvaluator(bitmap: Bitmap, lutBytes: ByteArray?): NimaEvaluator? =
        withContext(nimaThread) {
            val existing = evaluator
            if (existing != null && existing.ready && evaluatorSource === bitmap) {
                existing.updateLook(lutBytes)
                return@withContext existing
            }
            runCatching { existing?.close() }
            evaluator = null
            evaluatorSource = null
            val created = NimaEvaluator(appContext)
            val opened = created.prepare(bitmap, _state.value.mask, lutBytes)
            if (!opened) {
                NimaEvaluator.logUnavailable(created.status)
                created.close()
                // 모델을 못 열면 보정할 때마다 다시 시도하지 않는다(새 사진에서만 재시도).
                nimaUnavailable = true
                return@withContext null
            }
            evaluator = created
            evaluatorSource = bitmap
            created
        }

    /** Re-measures the NIMA score of the current recipe, debounced so slider drags stay smooth. */
    private fun scheduleNimaScore(immediate: Boolean = false) {
        val state = _state.value
        if (!state.loaded || state.nimaBusy || nimaUnavailable) return
        val bitmap = state.preview ?: return
        scoreJob?.cancel()
        scoreJob = scope.launch {
            if (!immediate) delay(700)
            // 그사이 자동 보정이 시작됐으면 중간 레시피를 재지 않는다.
            if (_state.value.nimaBusy) return@launch
            val engine = ensureEvaluator(bitmap, _state.value.lutBytes) ?: return@launch
            val recipe = session.current
            val score = withContext(nimaThread) { engine.score(recipe) } ?: return@launch
            _state.update { it.copy(nimaScore = score) }
        }
    }

    // ── 히스토리에서 한 스텝만 지우기 ──────────────────────────────────────

    /**
     * Lifts one step out of the history and re-adds the rest from the bottom line.
     *
     * The other steps keep their own changes, so removing "따뜻하게" as the second of three leaves
     * the first and third exactly as they were. Undo brings the removed step back.
     */
    fun removeStep(id: Long) {
        if (!_state.value.loaded) return
        val step = session.steps().firstOrNull { it.id == id } ?: return
        if (step.isOrigin) return
        session.remove(id)
        _state.update {
            it.copy(
                note = Note(
                    "‘${step.title}’ 보정을 지웠어요. 나머지 ${session.steps().size - 1}단계는 그대로예요." +
                        " 잘못 지웠으면 상단 ← 되돌리기로 되살릴 수 있어요.",
                    caption = "보정 지우기",
                ),
            )
        }
        pushState()
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

    fun toggleMaskPreview() = _state.update { it.copy(maskPreview = !it.maskPreview) }

    fun toggleMask(on: Boolean) {
        if (!_state.value.loaded) return
        session.replace(session.current.with(Param.MASK_ON, if (on) 1f else 0f), "영역 마스크")
        pushState()
    }

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
            val recipe = current.recipe
            // 영역 파라미터를 실제로 썼을 때만 원본 크기 사진에서 마스크를 다시 만든다.
            val needsMask = recipe[Param.MASK_ON] >= 0.5f && Param.entries.any { param ->
                param.group == ParamGroup.REGION && param != Param.MASK_ON &&
                    kotlin.math.abs(recipe[param] - param.default) > 1e-4f
            }
            val mask = if (needsMask) {
                // 프리뷰와 같은 생성기로 원본 크기 사진에서 다시 만든다(같은 방식·같은 페더링).
                withContext(Dispatchers.Default) {
                    runCatching { maskGenerator.generate(full.bitmap).bitmap }.getOrNull()
                }
            } else {
                null
            }
            val outcome = withContext(Dispatchers.Default) {
                OffscreenExporter().render(full.bitmap, recipe, current.lutBytes, mask)
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
        maskGenerator.close()
        scoreJob?.cancel()
        // EGL 컨텍스트는 만든 스레드에서 닫는다(다른 스레드에서 닫으면 누수가 생긴다).
        scope.launch(nimaThread) {
            runCatching { evaluator?.close() }
            evaluator = null
            evaluatorSource = null
        }
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    private fun pushState() {
        val recipe = session.current
        _state.update {
            it.copy(
                recipe = recipe,
                canUndo = session.canUndo,
                canRedo = session.canRedo,
                historyLabels = session.titleList,
                steps = session.steps().map { step -> StepView(step.id, step.title, step.isOrigin) },
            )
        }
        scheduleNimaScore()
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
