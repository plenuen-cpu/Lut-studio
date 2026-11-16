package com.plenuen.lumen.ui

import android.graphics.Bitmap
import com.plenuen.lumen.core.EditNarrator
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.ParamGroup
import com.plenuen.lumen.core.RecipeSession
import com.plenuen.lumen.LumenDiagnostics
import com.plenuen.lumen.nl.PromptEngine
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
        safeLaunch("openPhoto") {
            _state.update { it.copy(busy = "사진을 여는 중…") }
            val loaded = withContext(Dispatchers.IO) { photoStore.loadPreview(uri) }
            if (loaded == null) {
                _state.update { it.copy(busy = null, message = "사진을 열 수 없습니다.") }
                return@safeLaunch
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
        safeLaunch("buildMask") {
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

    /**
     * Applies one typed sentence.
     *
     * The rules themselves live in [PromptEngine] so this method only moves the UI state; that also
     * means the exact code the app runs is covered by unit tests instead of only by hand on a phone.
     * This is the one place that must never take the app down with it: whatever the engine, the card
     * or the model probe throws is recorded in [LumenDiagnostics] and shown as a card, so a bad
     * sentence costs a message rather than the session the user was in.
     */
    fun submitPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !_state.value.loaded) return
        try {
            val result = PromptEngine.apply(
                text = trimmed,
                session = session,
                scene = _state.value.scene,
                lutFinder = ::findLut,
            )
            // "자동 보정", "NIMA 높여줘", "점수 잘 나오게" — 점수판(NIMA)을 쓴 자동 보정으로 바로 넘긴다.
            if (result.nimaAuto) {
                autoEnhance()
                return
            }
            if (result.compareRequested) {
                _state.update { it.copy(compare = true) }
                safeLaunch("compare") {
                    delay(1500)
                    _state.update { it.copy(compare = false) }
                }
            }
            if (!result.applied) {
                // 사전에 없는 표현은 곧바로 로컬 LLM 추론 경로로 넘긴다.
                safeLaunch("submitPrompt/model") { interpretWithLlm(trimmed, result.before) }
                return
            }
            if (result.autoStraightenRequested) autoStraighten(announce = false)
            result.lutPreset?.let { preset -> decodeLut(preset) }

            val maskWarning = if (result.wantsRegionMask && !_state.value.maskReady) {
                "영역 마스크를 아직 만들지 못했어요. 잠시 뒤 자동으로 다시 시도해 드릴게요."
            } else {
                null
            }
            _state.update { state ->
                val note = when (result.noteAction) {
                    PromptEngine.NoteAction.CLEAR -> null
                    PromptEngine.NoteAction.REPLACE ->
                        Note(result.noteText.orEmpty(), caption = result.noteCaption)
                    PromptEngine.NoteAction.KEEP -> state.note
                }
                state.copy(
                    message = maskWarning ?: result.message,
                    chips = (state.chips + result.labels).distinct().takeLast(8),
                    note = note,
                )
            }
            pushState()

            result.leftoverForLlm?.let { leftover ->
                safeLaunch("submitPrompt/leftover") {
                    // 룰이 이미 반영한 뒤의 자투리 문장이라, 모델이 없을 때 다운로드 안내로 덮지 않는다.
                    if (interpretWithLlm(leftover, result.before, notifyMissingModel = false)) pushState()
                }
            }
        } catch (t: Throwable) {
            LumenDiagnostics.record("submitPrompt", t)
            _state.update {
                it.copy(
                    busy = null,
                    message = null,
                    note = Note(
                        "이 요청을 처리하지 못했어요. 다른 말로 한 번 더 시도해 주세요. " +
                            "(오류를 기록해 두었어요: ${t.javaClass.simpleName})",
                        caption = "해석 오류",
                    ),
                )
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
        safeLaunch("autoEnhance") {
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
                return@safeLaunch
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
                return@safeLaunch
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
        scoreJob = safeLaunch("scheduleNimaScore") {
            if (!immediate) delay(700)
            // 그사이 자동 보정이 시작됐으면 중간 레시피를 재지 않는다.
            if (_state.value.nimaBusy) return@safeLaunch
            val engine = ensureEvaluator(bitmap, _state.value.lutBytes) ?: return@safeLaunch
            val recipe = session.current
            val score = withContext(nimaThread) { engine.score(recipe) } ?: return@safeLaunch
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
        safeLaunch("autoStraighten") {
            val before = session.current
            val result = withContext(Dispatchers.Default) { estimateStraight(preview) }
            if (!result.isReliable) {
                _state.update {
                    it.copy(
                        straighten = result,
                        message = if (announce) "기울기를 찾지 못했어요. 수동으로 맞춰 주세요." else it.message,
                    )
                }
                return@safeLaunch
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
        safeLaunch("decodeLut") {
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
        safeLaunch("export") {
            _state.update { it.copy(busy = "원본 해상도로 내보내는 중…") }
            val full = withContext(Dispatchers.IO) { photoStore.loadFull(uri) }
            if (full == null) {
                _state.update { it.copy(busy = null, message = "원본 사진을 다시 열 수 없습니다.") }
                return@safeLaunch
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
        safeLaunch("downloadModel") {
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
        safeLaunch("deleteModel") {
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

    // ── 안전 실행 ──────────────────────────────────────────────────────────

    /**
     * Runs [block] in the view model's scope without letting a failure end the app.
     *
     * A throwable that escapes a coroutine goes to the thread's uncaught handler, and on Android that
     * means the process is gone — no dialog, no trace, exactly the "입력하면 꺼져" the user reported.
     * Everything the editor starts in the background (opening a photo, the region mask, the local
     * model, the NIMA pass, a chart of LUT bytes) now ends here instead: recorded for the next report
     * and shown as one card, while the open photo and its history stay as they were.
     */
    private fun safeLaunch(where: String, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job =
        scope.launch {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onBackgroundFailure(where, error)
            }
        }

    /** Records a background failure and says so on screen instead of dying. */
    private fun onBackgroundFailure(where: String, error: Throwable) {
        LumenDiagnostics.record(where, error)
        // NIMA가 한 번 넘어지면 점수 재측정을 멈춘다: 카드만 쌓이고 사진은 그대로면 실패를 반복할 이유가 없다.
        if (where.startsWith("scheduleNimaScore") || where == "autoEnhance") nimaUnavailable = true
        _state.update {
            it.copy(
                busy = null,
                nimaBusy = false,
                note = Note(
                    "하던 작업을 끝내지 못했어요. 사진과 지금까지의 보정은 그대로예요. " +
                        "(오류를 기록해 두었어요: ${error.javaClass.simpleName})",
                    caption = "오류",
                ),
            )
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
