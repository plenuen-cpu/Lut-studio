package com.plenuen.lumen.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.plenuen.lumen.LumenDiagnostics
import com.plenuen.lumen.nl.NaturalLanguageParser
import com.plenuen.lumen.nl.SceneStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the downloaded Qwen2.5 bundle on the phone through MediaPipe's LLM Inference API
 * (`tasks-genai`, the native engine ships inside the AAR — no NDK build, no server, no network).
 *
 * The instant rule parser always answers first; this is only asked when the sentence is beyond the
 * rules or when the user has turned the model on and it is available.
 *
 * Two things keep a 0.5B model steady on spoken Korean: the spoken-language filler is stripped from
 * the request before it is put into the prompt ([NaturalLanguageParser.salient]), and the prompt
 * itself carries the filler/negation rules and three examples (see
 * [LlmRecipeDecoder.buildPrompt]).
 *
 * The engine is a native library, so a mistake here is not an exception — it is the process dying.
 * Everything in this class therefore obeys two rules:
 * 1. **Never send a prompt larger than the engine's budget** ([LlmBudget]). The size is measured
 *    with the engine's own tokenizer before the call, a shorter prompt is tried, and if neither
 *    fits the model is not called at all.
 * 2. **Never call the engine twice at once.** MediaPipe documents that simultaneous generation on
 *    one `LlmInference` is not allowed; a second sentence typed while the first is still being read
 *    would otherwise hand the same native session two prompts.
 * Before and after each native call a breadcrumb goes to [LumenDiagnostics] and is flushed to disk,
 * so if the engine does take the process down the next run can say exactly which call it was.
 */
class LocalLlmEngine(private val context: Context) {

    sealed interface Status {
        data object NoModel : Status
        data object Loading : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    /** Guards loading and closing of the native engine. */
    private val loadMutex = Mutex()

    /** Guards a single `generateResponse`: the native engine must never see two at once. */
    private val generateMutex = Mutex()

    private var inference: LlmInference? = null
    private var loadedModelId: String? = null

    @Volatile
    var status: Status = Status.NoModel
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 가중치 파일이 작은 표본과 실제 토큰 수 — 화면과 기록에 그대로 남겨 다음 진단에 쓴다. */
    @Volatile
    var lastPromptTokens: Int = 0
        private set

    fun isReady(): Boolean = inference != null && status is Status.Ready

    suspend fun ensureLoaded(model: LlmModel, file: java.io.File): Boolean = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (inference != null && loadedModelId == model.id) return@withLock true
            closeLocked()
            status = Status.Loading
            return@withLock try {
                if (!file.isFile || file.length() < MIN_MODEL_BYTES) {
                    // 잘린 다운로드를 네이티브에 넘기면 그 자리에서 죽는다. 여기서 멈춘다.
                    lastError = "모델 파일이 온전하지 않습니다(${file.length() / 1024 / 1024}MB)."
                    status = Status.Failed(lastError!!)
                    LumenDiagnostics.trace("llm/load/reject", lastError.orEmpty())
                    return@withLock false
                }
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    // 프롬프트와 답을 합친 상한이다. 512로 두던 동안 프롬프트가 1,600토큰이라
                    // 엔진이 SIGABRT로 프로세스를 죽였다(LlmBudget 주석 참고).
                    .setMaxTokens(LlmBudget.MAX_TOKENS)
                    // greedy decoding: a JSON parser does not benefit from sampling
                    .setMaxTopK(1)
                    .build()
                LumenDiagnostics.trace(
                    "llm/load/start",
                    "${model.id} ${file.length() / 1024 / 1024}MB maxTokens=${LlmBudget.MAX_TOKENS}",
                )
                inference = LlmInference.createFromOptions(context, options)
                loadedModelId = model.id
                status = Status.Ready
                lastError = null
                LumenDiagnostics.trace("llm/load/done", model.id)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "local LLM load failed", t)
                lastError = t.message ?: t.javaClass.simpleName
                status = Status.Failed(lastError ?: "알 수 없는 오류")
                LumenDiagnostics.record("llm/load", t)
                closeLocked()
                false
            }
        }
    }

    /**
     * Asks the model to turn [request] into the JSON shape of [LlmRecipeDecoder]. Returns null when
     * the model is not loaded, nothing usable came back, or the prompt could not be made small
     * enough to be safe.
     */
    suspend fun interpret(
        request: String,
        stats: SceneStats,
        lutNames: List<String>,
    ): LlmRecipeDecoder.Decoded? = withContext(Dispatchers.Default) {
        val engine = inference ?: return@withContext null
        // MediaPipe .task 번들이 Qwen 채팅 템플릿을 직접 입힌다. 여기서 <|im_start|>를 또 넣으면
        // 템플릿이 두 번 적용돼 모델이 JSON 대신 특수 토큰을 토해 낸다.
        // 말버릇("그거 아시죠", "뭔가", "약간 그런")은 작은 모델을 흔들기만 하므로 먼저 덜어 낸다.
        val text = NaturalLanguageParser.salient(request).take(LlmRecipeDecoder.MAX_REQUEST_CHARS)

        var prompt = LlmRecipeDecoder.buildPrompt(text, lutNames, sceneHint(stats))
        var tokens = countTokens(engine, prompt)
        if (!LlmBudget.fits(tokens)) {
            // 예시·사진 상태를 덜어 낸 짧은 프롬프트로 한 번 더. 그래도 넘으면 호출하지 않는다.
            LumenDiagnostics.trace("llm/prompt/shrink", "tokens=$tokens budget=${LlmBudget.PROMPT_BUDGET}")
            prompt = LlmRecipeDecoder.buildShortPrompt(text)
            tokens = countTokens(engine, prompt)
        }
        if (!LlmBudget.fits(tokens)) {
            lastError = "요청이 너무 깁니다(프롬프트 ${tokens}토큰, 상한 ${LlmBudget.PROMPT_BUDGET})."
            LumenDiagnostics.trace("llm/prompt/refuse", "tokens=$tokens")
            return@withContext null
        }
        lastPromptTokens = tokens

        val answer = generateMutex.withLock {
            LumenDiagnostics.trace("llm/generate/start", "tokens=$tokens/${LlmBudget.MAX_TOKENS}")
            try {
                engine.generateResponse(prompt)
            } catch (t: Throwable) {
                Log.w(TAG, "local LLM inference failed", t)
                lastError = t.message ?: t.javaClass.simpleName
                LumenDiagnostics.record("llm/generate", t)
                return@withLock null
            }
        }
        LumenDiagnostics.trace("llm/generate/done", "chars=${answer?.length ?: 0}")
        if (answer.isNullOrBlank()) {
            lastError = "모델이 빈 답을 냈습니다."
            LumenDiagnostics.trace("llm/generate/empty")
            return@withContext null
        }
        LlmRecipeDecoder.decode(answer)
    }

    /**
     * Real token count from the engine's own tokenizer, with a character estimate as the fallback
     * when the delegate does not implement it. `sizeInTokens` only tokenises — it never runs the
     * graph — so asking cannot be the call that kills the process.
     */
    private fun countTokens(engine: LlmInference, prompt: String): Int {
        val measured = runCatching { engine.sizeInTokens(prompt) }.getOrNull() ?: 0
        return if (measured > 0) measured else LlmBudget.estimate(prompt)
    }

    /** One line of measured photo state, folded into the prompt before the request. */
    private fun sceneHint(stats: SceneStats): String {
        if (stats.samples == 0) return ""
        val luma = when {
            stats.isDark -> "어둡다"
            stats.isBright -> "밝다"
            else -> "보통"
        }
        val sat = when {
            stats.isLowSaturation -> "낮다"
            stats.isHighSaturation -> "높다"
            else -> "보통"
        }
        val cast = when {
            stats.isWarm -> "따뜻하다"
            stats.isCool -> "차갑다"
            else -> "중립"
        }
        return "밝기 $luma, 채도 $sat, 색온도 $cast."
    }

    private fun closeLocked() {
        runCatching { inference?.close() }
        inference = null
        loadedModelId = null
    }

    fun close() {
        runCatching { closeLocked() }
        status = Status.NoModel
    }

    private companion object {
        const val TAG = "LumenLlm"

        /** 가장 작은 번들(0.5B q8, 547MB)보다 작으면 잘린 파일이다. */
        const val MIN_MODEL_BYTES = 400L * 1024 * 1024
    }
}
