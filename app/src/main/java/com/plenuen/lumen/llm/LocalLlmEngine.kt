package com.plenuen.lumen.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
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
 */
class LocalLlmEngine(private val context: Context) {

    sealed interface Status {
        data object NoModel : Status
        data object Loading : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private val mutex = Mutex()
    private var inference: LlmInference? = null
    private var loadedModelId: String? = null

    @Volatile
    var status: Status = Status.NoModel
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun isReady(): Boolean = inference != null && status is Status.Ready

    suspend fun ensureLoaded(model: LlmModel, file: java.io.File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (inference != null && loadedModelId == model.id) return@withLock true
            closeLocked()
            status = Status.Loading
            return@withLock try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(512)
                    // greedy decoding: a JSON parser does not benefit from sampling
                    .setMaxTopK(1)
                    .build()
                inference = LlmInference.createFromOptions(context, options)
                loadedModelId = model.id
                status = Status.Ready
                lastError = null
                true
            } catch (t: Throwable) {
                Log.w(TAG, "local LLM load failed", t)
                lastError = t.message ?: t.javaClass.simpleName
                status = Status.Failed(lastError ?: "알 수 없는 오류")
                closeLocked()
                false
            }
        }
    }

    /**
     * Asks the model to turn [request] into the JSON shape of [LlmRecipeDecoder]. Returns null when
     * the model is not loaded or produced nothing usable.
     */
    suspend fun interpret(
        request: String,
        stats: SceneStats,
        lutNames: List<String>,
    ): LlmRecipeDecoder.Decoded? = withContext(Dispatchers.Default) {
        val engine = inference ?: return@withContext null
        val prompt = LlmRecipeDecoder.buildPrompt(request, lutNames) + sceneHint(stats)
        val answer = try {
            engine.generateResponse(prompt)
        } catch (t: Throwable) {
            Log.w(TAG, "local LLM inference failed", t)
            lastError = t.message ?: t.javaClass.simpleName
            return@withContext null
        }
        LlmRecipeDecoder.decode(answer)
    }

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
        return "\n사진 상태: 밝기 $luma, 채도 $sat, 색온도 $cast."
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
    }
}
