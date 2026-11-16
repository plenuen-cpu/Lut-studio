package com.plenuen.lumen.llm

import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.data.Json

/**
 * Reads the small JSON object a local model returns and turns it into a [RecipeDelta].
 *
 * A 0.5B model does not always answer cleanly: the object may be wrapped in prose, a markdown
 * fence, cut off before its closing brace, or its numbers may arrive as strings. Everything here is
 * about accepting what is usable and discarding the rest, never trusting the model with ranges or
 * unknown keys.
 *
 * The MediaPipe `.task` bundle already applies Qwen's chat template inside the runtime, so the
 * prompt deliberately carries no `<|im_start|>` / `<|im_end|>` markers: adding them a second time
 * makes a small model echo template tokens instead of JSON.
 */
object LlmRecipeDecoder {

    data class Decoded(
        val delta: RecipeDelta,
        val label: String,
        val summary: String,
        /** Free-text look name the model mentioned, e.g. "portra". */
        val lutHint: String?,
    )

    /** Chinese-ChatML and similar control tokens some models leak into their answer. */
    private val CHAT_TOKEN = Regex("<\\|[^<>|]{1,24}\\|>")

    private val FENCE = Regex("```[a-zA-Z]*")

    /** Pulls the first balanced `{...}` block out of a model answer. */
    fun extractJson(text: String): String? {
        val cleaned = sanitize(text)
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
        // 잘려 나간 답: 마지막으로 완성된 객체까지 쓰거나, 괄호를 닫아 준다.
        return repair(cleaned.substring(start))
    }

    fun decode(raw: String): Decoded? {
        for (candidate in candidates(raw)) {
            val root = runCatching { Json.parse(candidate) }.getOrNull() ?: continue
            val map = runCatching { Json.asMap(root) }.getOrNull() ?: continue
            decodeMap(map)?.let { return it }
        }
        return null
    }

    private fun decodeMap(map: Map<String, Any?>): Decoded? {
        val adjust = firstMap(map, "adjust", "delta", "params", "changes", "settings", "보정", "조정")
            ?: map
        val values = LinkedHashMap<Param, Float>()
        flatten(adjust, 0, values)
        if (values.isEmpty()) return null

        val ops = values.entries
            .filter { (param, value) -> param in LlmScale.allowed && kotlin.math.abs(value) >= 0.005f }
            .map { (param, value) -> RecipeDelta.Op.Add(param, value) }
        if (ops.isEmpty()) return null

        val label = firstString(map, "label", "name", "title", "이름", "label_kr")
            ?.take(24)
            ?: "로컬 LLM"
        val summary = firstString(map, "summary", "reason", "description", "설명", "이유", "요약")
            ?.take(160)
            ?: "로컬 LLM이 문장을 읽고 보정을 정했어요."
        val lutHint = firstString(map, "lut", "look", "preset", "룩", "필름")
            ?.takeIf { it.isNotBlank() && it.lowercase() !in NONE_WORDS }
        return Decoded(
            RecipeDelta(label = label, ops = ops, summary = summary),
            label,
            summary,
            lutHint,
        )
    }

    /** Collects `param -> amount` from a possibly nested adjust object. */
    private fun flatten(source: Map<*, *>, depth: Int, out: MutableMap<Param, Float>) {
        source.forEach { (rawKey, rawValue) ->
            val key = rawKey?.toString() ?: return@forEach
            if (rawValue is Map<*, *>) {
                if (depth < 2) flatten(rawValue, depth + 1, out)
                return@forEach
            }
            val param = paramFor(key) ?: return@forEach
            if (param !in LlmScale.allowed) return@forEach
            val number = numberOf(rawValue) ?: return@forEach
            out[param] = LlmScale.amount(param, normalizeAmount(number))
        }
    }

    /**
     * Turns whatever the model wrote for a key into a [Param]: our English key, the Korean label
     * shown in the slider panel, or a common English synonym.
     */
    fun paramFor(key: String): Param? {
        val normalized = normalizeKey(key)
        if (normalized.isEmpty()) return null
        KEY_INDEX[normalized]?.let { return it }
        // "temperature_celsius", "lookIntensity" 따위를 한 번 더 관대하게 본다.
        return KEY_INDEX.entries.firstOrNull { (name, _) ->
            name.length >= 4 && normalized.length >= 4 &&
                (normalized.contains(name) || name.contains(normalized))
        }?.value
    }

    private fun normalizeKey(key: String): String =
        key.lowercase().filter { it.isLetterOrDigit() || it in '가'..'힣' }

    /** Model numbers: 0.35, "0.35", "+35%", "-0.2", 35 (meaning percent). */
    private fun numberOf(value: Any?): Float? = when (value) {
        is Number -> value.toFloat()
        is String -> {
            val token = value.trim().removeSuffix("%").trim()
            token.replace(',', '.').toFloatOrNull()
        }
        else -> null
    }

    /** Models sometimes answer in percent (35) instead of -1..1 (0.35). */
    private fun normalizeAmount(value: Float): Float {
        if (!value.isFinite()) return 0f
        return when {
            kotlin.math.abs(value) <= 1f -> value
            kotlin.math.abs(value) <= 100f -> value / 100f
            else -> if (value > 0f) 1f else -1f
        }
    }

    private fun firstMap(map: Map<String, Any?>, vararg keys: String): Map<*, *>? {
        for (key in keys) {
            val value = map.entries.firstOrNull { normalizeKey(it.key) == normalizeKey(key) }?.value
            if (value is Map<*, *>) return value
        }
        return null
    }

    private fun firstString(map: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val value = map.entries.firstOrNull { normalizeKey(it.key) == normalizeKey(key) }?.value
            if (value is String && value.isNotBlank()) return value.trim()
        }
        return null
    }

    /** Strips chat tokens and code fences so what remains starts at the JSON. */
    private fun sanitize(text: String): String =
        text.replace(CHAT_TOKEN, " ")
            .replace(FENCE, " ")
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')

    private fun candidates(raw: String): List<String> {
        val cleaned = sanitize(raw)
        val out = LinkedHashSet<String>()
        extractJson(cleaned)?.let { out += it }
        val start = cleaned.indexOf('{')
        if (start >= 0) {
            val tail = cleaned.substring(start)
            out += relax(tail)
            val lastBrace = tail.lastIndexOf('}')
            if (lastBrace > 0) out += relax(tail.substring(0, lastBrace + 1))
            var body = tail
            repeat(4) {
                val cut = body.lastIndexOf(',')
                if (cut < 0) return@repeat
                body = body.substring(0, cut)
                closeBraces(body)?.let { out += relax(it) }
            }
        }
        return out.toList()
    }

    /** Closes a truncated object and drops trailing commas. */
    private fun closeBraces(fragment: String): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in fragment) {
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
                '}' -> depth--
            }
        }
        if (depth <= 0 && !inString) return null
        val builder = StringBuilder(fragment)
        if (inString) builder.append('"')
        val trimmed = builder.toString().trimEnd()
        if (trimmed.endsWith(":") || trimmed.endsWith(",")) builder.append("0")
        repeat(depth.coerceAtLeast(0)) { builder.append('}') }
        return builder.toString().replace(Regex(",\\s*}"), "}")
    }

    /** Accepts single quotes, bare keys and trailing commas, which a small model likes to write. */
    private fun relax(json: String): String {
        var text = json.replace(Regex(",\\s*([}\\]])"), "\$1")
        text = text.replace(Regex("'([^'\\\\]*)'\\s*:"), "\"\$1\":")
        text = text.replace(Regex("([{,]\\s*)([A-Za-z_가-힣][A-Za-z0-9_가-힣]*)\\s*:"), "\$1\"\$2\":")
        // 남은 작은따옴표 문자열
        text = Regex(":\\s*'([^']*)'").replace(text) { "\"${it.groupValues[1]}\"" }
        return text
    }

    private fun repair(fragment: String): String? {
        closeBraces(fragment)?.let { return it }
        val lastBrace = fragment.lastIndexOf('}')
        if (lastBrace > 0) return fragment.substring(0, lastBrace + 1)
        return null
    }

    /**
     * Builds the prompt. The key list, the shape and the "two or more parameters" rule are fixed so
     * a small model only has to fill in numbers.
     */
    fun buildPrompt(request: String, lutNames: List<String>, sceneHint: String = ""): String {
        val keys = LlmScale.allowed.joinToString(", ") { "${it.key}(${it.label})" }
        val lutSample = lutNames.take(10).joinToString(", ")
        val scene = sceneHint.trim()
        return """
            너는 사진 보정 앱 '루멘'의 의도 분석기다. 사용자의 요청을 읽고 사진에 적용할 보정값을 JSON 하나로만 답한다.

            규칙:
            1. 사전에 없는 표현, 추상적·감성적·비유적·구체적 묘사라도 사진학적·미학적으로 해석해서 반드시 서로 다른 파라미터 2개 이상을 조정한다.
            2. 값은 -1.0(줄임) ~ 1.0(늘림) 사이의 상대값 실수다.
            3. label은 12자 이내 한국어 이름, summary는 사용자가 읽을 한 문장 한국어 설명이다.
            4. JSON 외의 문장, 인사말, 설명, 코드블록을 쓰지 않는다.

            출력 예시:
            {"label":"차분하게","summary":"채도를 낮추고 대비를 부드럽게 했어요.","adjust":{"saturation":-0.35,"contrast":-0.2,"fade":0.15}}

            쓸 수 있는 파라미터 키(이름): $keys
            룩을 고르고 싶을 때만 "lut"에 다음 중 하나를 넣는다: $lutSample
            ${if (scene.isNotEmpty()) "사진 상태: $scene" else ""}

            요청: $request
            JSON:
        """.trimIndent()
    }

    private val KEY_INDEX: Map<String, Param> = buildMap {
        Param.entries.forEach { param ->
            put(normalizeKey(param.key), param)
            put(normalizeKey(param.label), param)
        }
        val synonyms = mapOf(
            "brightness" to Param.EXPOSURE,
            "brightnessev" to Param.EXPOSURE,
            "ev" to Param.EXPOSURE,
            "light" to Param.EXPOSURE,
            "warmth" to Param.TEMPERATURE,
            "warm" to Param.TEMPERATURE,
            "cool" to Param.TEMPERATURE,
            "colortemperature" to Param.TEMPERATURE,
            "whitepoint" to Param.WHITES,
            "blackpoint" to Param.BLACKS,
            "highlightrecovery" to Param.HIGHLIGHTS,
            "shadowlift" to Param.SHADOWS,
            "sat" to Param.SATURATION,
            "colorfulness" to Param.VIBRANCE,
            "hue" to Param.COLOR_HARMONY,
            "colorgrade" to Param.COLOR_HARMONY,
            "film" to Param.GRAIN,
            "haze" to Param.DEHAZE,
            "mono" to Param.MONOCHROME,
            "bw" to Param.MONOCHROME,
            "gray" to Param.MONOCHROME,
            "greyscale" to Param.MONOCHROME,
            "grayscale" to Param.MONOCHROME,
            "skin" to Param.SKIN_CLEAN,
            "portrait" to Param.SKIN_CLEAN,
            "focus" to Param.SUBJECT_FOCUS,
            "subject" to Param.SUBJECT_FOCUS,
            "sharp" to Param.SHARPNESS,
            "denoise" to Param.TEXTURE,
            "soft" to Param.CONTRAST,
            "shadowhue" to Param.SPLIT_SHADOW_HUE,
            "highlightHue" to Param.SPLIT_HIGHLIGHT_HUE,
        )
        synonyms.forEach { (key, param) -> put(normalizeKey(key), param) }
    }

    private val NONE_WORDS = setOf("none", "null", "no", "없음", "-", "0")
}
