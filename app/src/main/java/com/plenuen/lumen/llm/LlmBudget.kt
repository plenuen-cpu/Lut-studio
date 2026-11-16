package com.plenuen.lumen.llm

/**
 * How much text the on-device engine may be given, and how the app stays below it.
 *
 * `LlmInferenceOptions.maxTokens` is not an answer-length limit. MediaPipe counts the **prompt and
 * the answer together** against it, and both downloaded bundles carry a KV cache of 1,280 tokens
 * (`..._ekv1280.task`). v2.2.1 asked for 512 while its prompt measured 1,607 tokens, which is the
 * crash the user reported: the engine answers an oversized prompt by aborting the process at the
 * native level, so no Kotlin `try/catch` and no uncaught-exception handler can see it.
 *
 * The numbers here are the contract between [LlmRecipeDecoder] (which must stay under
 * [PROMPT_BUDGET]) and [LocalLlmEngine] (which refuses to call the engine when it does not).
 */
object LlmBudget {

    /**
     * The largest sequence the bundle's KV cache can hold. Kept a little under the 1,280 of the
     * `ekv1280` bundles so a session can never be created wider than the weights it runs on.
     */
    const val MAX_TOKENS = 1152

    /** Room the JSON answer and the chat template need after the prompt. */
    const val ANSWER_TOKENS = 160

    /** Largest prompt the app may send: what is left of [MAX_TOKENS] once the answer is reserved. */
    const val PROMPT_BUDGET = MAX_TOKENS - ANSWER_TOKENS

    /**
     * Falls back to a character count when the engine's tokenizer cannot be asked.
     *
     * The Qwen tokenizer spends about 0.55 tokens per Korean character and far less per ASCII one,
     * so counting a Korean character as a whole token is a deliberate overestimate: this estimate is
     * only ever used to *refuse* a prompt, and refusing one step early costs a shorter prompt while
     * guessing one step low would cost the process.
     */
    fun estimate(text: String): Int {
        var tokens = 0
        var asciiRun = 0
        fun flushAscii() {
            if (asciiRun > 0) {
                tokens += (asciiRun + 2) / 3
                asciiRun = 0
            }
        }
        for (ch in text) {
            when {
                ch.code in 0x20..0x7E -> asciiRun++
                ch == '\n' || ch == ' ' -> {
                    flushAscii()
                    tokens++
                }
                else -> {
                    flushAscii()
                    tokens++
                }
            }
        }
        flushAscii()
        return tokens
    }

    /** True when [tokens] still leaves room for the answer under [MAX_TOKENS]. */
    fun fits(tokens: Int): Boolean = tokens in 1..PROMPT_BUDGET
}
