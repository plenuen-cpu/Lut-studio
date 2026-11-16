package com.plenuen.lumen.llm

import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.data.Json

/**
 * Reads the small JSON object a local model returns and turns it into a [RecipeDelta].
 *
 * A 0.5B model does not always answer cleanly: the object may be wrapped in prose, a markdown
 * fence, or the quotes may be curly. Everything here is about accepting what is usable and
 * discarding the rest, never trusting the model with ranges or unknown keys.
 */
object LlmRecipeDecoder {

    data class Decoded(
        val delta: RecipeDelta,
        val label: String,
        val summary: String,
        /** Free-text look name the model mentioned, e.g. "portra". */
        val lutHint: String?,
    )

    /** Pulls the first balanced `{...}` block out of a model answer. */
    fun extractJson(text: String): String? {
        val cleaned = text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until cleaned.length) {
            val ch = cleaned[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return cleaned.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun decode(raw: String): Decoded? {
        val json = extractJson(raw) ?: return null
        val root = runCatching { Json.parse(json) }.getOrNull() ?: return null
        val map = runCatching { Json.asMap(root) }.getOrNull() ?: return null

        val adjust = (map["adjust"] ?: map["delta"] ?: map["params"] ?: map) as? Map<*, *>
            ?: return null
        val ops = mutableListOf<RecipeDelta.Op>()
        adjust.forEach { (rawKey, rawValue) ->
            val key = rawKey.toString().trim().lowercase()
            val param = Param.byKey[key] ?: return@forEach
            if (param !in LlmScale.allowed) return@forEach
            val number = (rawValue as? Number)?.toFloat() ?: return@forEach
            if (!number.isFinite()) return@forEach
            val amount = LlmScale.amount(param, number)
            if (kotlin.math.abs(amount) >= 0.005f) ops += RecipeDelta.Op.Add(param, amount)
        }
        if (ops.isEmpty()) return null

        val label = (map["label"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: "로컬 LLM"
        val summary = (map["summary"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: "로컬 LLM이 문장을 읽고 보정을 정했어요."
        val lutHint = (map["lut"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        return Decoded(RecipeDelta(label = label, ops = ops, summary = summary), label, summary, lutHint)
    }

    /**
     * Builds the prompt. The allowed key list and the JSON shape are fixed so a small model only
     * has to fill in numbers.
     */
    fun buildPrompt(request: String, lutNames: List<String>): String {
        val keys = LlmScale.allowed.joinToString(", ") { it.key }
        val lutSample = lutNames.take(12).joinToString(", ")
        return """
            <|im_start|>system
            너는 사진 보정 앱의 의도 분석기다. 사용자의 한국어 요청을 아래 형식의 JSON 하나로만 바꿔라. 다른 말은 절대 쓰지 마라.
            형식: {"label":"짧은 한국어 이름","summary":"한 문장 설명","adjust":{"키":값}}
            값은 -1.0(줄임) ~ 1.0(늘림) 사이 실수다. 필요한 키만 넣어라.
            쓸 수 있는 키: $keys
            사진 룩을 고르려면 "lut"에 다음 중 하나를 넣어라: $lutSample
            <|im_end|>
            <|im_start|>user
            $request
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()
    }
}
