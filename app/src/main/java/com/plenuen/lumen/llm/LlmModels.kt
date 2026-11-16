package com.plenuen.lumen.llm

import com.plenuen.lumen.core.Param as P

/**
 * The local language models the app can run, downloaded on first use so the APK stays small.
 *
 * Both are MediaPipe LLM Inference (`tasks-genai`) `.task` bundles of Qwen2.5-Instruct, hosted
 * publicly on Hugging Face (no account, no API key, no request leaves the device after download).
 */
data class LlmModel(
    val id: String,
    val title: String,
    val sizeMb: Int,
    val url: String,
    val note: String,
) {
    val fileName: String get() = "lumen-$id.task"
}

object LlmModels {

    val SMALL = LlmModel(
        id = "qwen2.5-0.5b-q8",
        title = "Qwen2.5 0.5B (기본)",
        sizeMb = 547,
        url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/" +
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        note = "빠르고 가볍습니다. 짧은 요청 해석에 충분합니다.",
    )

    val LARGE = LlmModel(
        id = "qwen2.5-1.5b-q8",
        title = "Qwen2.5 1.5B (고품질)",
        sizeMb = 1598,
        url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        note = "문장이 길고 복잡할 때 더 정확합니다. 느리고 용량이 큽니다.",
    )

    val all: List<LlmModel> = listOf(SMALL, LARGE)

    val default: LlmModel = SMALL
}

/**
 * How a 0..1 "정도" from the model maps onto a parameter. The model speaks in relative amounts,
 * the recipe in slider units.
 */
object LlmScale {

    private val scale: Map<P, Float> = mapOf(
        P.EXPOSURE to 0.70f,
        P.CONTRAST to 0.50f,
        P.HIGHLIGHTS to 0.50f,
        P.SHADOWS to 0.50f,
        P.WHITES to 0.50f,
        P.BLACKS to 0.50f,
        P.FADE to 0.60f,
        P.TEMPERATURE to 0.50f,
        P.TINT to 0.50f,
        P.VIBRANCE to 0.50f,
        P.SATURATION to 0.50f,
        P.COLOR_HARMONY to 0.50f,
        P.CROSS_PROCESS to 0.40f,
        P.SPLIT_TONE to 0.50f,
        P.SPLIT_BALANCE to 0.40f,
        P.SKIN_CLEAN to 0.60f,
        P.MONOCHROME to 1.00f,
        P.CLARITY to 0.50f,
        P.TEXTURE to 0.50f,
        P.DEHAZE to 0.50f,
        P.SHARPNESS to 0.50f,
        P.GLOW to 0.50f,
        P.HALATION to 0.50f,
        P.GRAIN to 0.50f,
        P.VIGNETTE to 0.50f,
        P.SUBJECT_FOCUS to 0.60f,
    )

    /** Params the model may move; geometry and LUT selection stay under explicit control. */
    val allowed: List<P> = scale.keys.toList()

    fun amount(param: P, value: Float): Float = (value.coerceIn(-1f, 1f)) * (scale[param] ?: 0.5f)
}
