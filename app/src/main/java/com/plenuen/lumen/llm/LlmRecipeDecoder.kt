package com.plenuen.lumen.llm

import com.plenuen.lumen.core.HslBand
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
        return stripTrailingCommas(builder.toString())
    }

    /**
     * Accepts single quotes, bare keys and trailing commas, which a small model likes to write.
     *
     * This is a character scan and not a regular expression on purpose. `Regex` is ICU on Android,
     * where a `}` that does not close a quantifier is a syntax error (`U_REGEX_RULE_SYNTAX`, reported
     * as [java.util.regex.PatternSyntaxException]), while the desktop JVM reads it as a literal — so a
     * pattern that passes every JVM unit test can still throw on the phone, which is exactly how
     * v2.2.2 failed. Nothing on the model path may depend on that difference.
     */
    private fun relax(json: String): String {
        if (json.isEmpty()) return json
        val out = StringBuilder(json.length + 16)
        var i = 0
        while (i < json.length) {
            when (val ch = json[i]) {
                '"' -> i = copyDoubleQuoted(json, i, out)
                '\'' -> i = copySingleQuotedAsDouble(json, i, out)
                '{' -> {
                    out.append(ch)
                    i = quoteBareKeyAt(json, i + 1, out)
                }
                ',' -> {
                    if (commaIsTrailing(json, i)) {
                        i++
                    } else {
                        out.append(ch)
                        i = quoteBareKeyAt(json, i + 1, out)
                    }
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Copies one `"..."` run verbatim, escapes included. */
    private fun copyDoubleQuoted(json: String, start: Int, out: StringBuilder): Int {
        var i = start + 1
        out.append('"')
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\' && i + 1 < json.length) {
                out.append(ch).append(json[i + 1])
                i += 2
                continue
            }
            out.append(ch)
            i++
            if (ch == '"') return i
        }
        return i
    }

    /** Rewrites one `'...'` run as a JSON string, leaving an unterminated one as it is. */
    private fun copySingleQuotedAsDouble(json: String, start: Int, out: StringBuilder): Int {
        var i = start + 1
        val body = StringBuilder()
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\' && i + 1 < json.length) {
                body.append(ch).append(json[i + 1])
                i += 2
                continue
            }
            if (ch == '\'') {
                out.append('"').append(body.toString().replace("\"", "\\\"")).append('"')
                return i + 1
            }
            body.append(ch)
            i++
        }
        out.append('\'').append(body)
        return i
    }

    /** Writes leading blanks, then quotes a bare `key:` that starts here, if there is one. */
    private fun quoteBareKeyAt(json: String, from: Int, out: StringBuilder): Int {
        var i = from
        while (i < json.length && json[i].isWhitespace()) {
            out.append(json[i])
            i++
        }
        if (i >= json.length) return i
        val start = i
        if (!isKeyStart(json[i])) return start
        i++
        while (i < json.length && isKeyPart(json[i])) i++
        var colon = i
        while (colon < json.length && json[colon].isWhitespace()) colon++
        if (colon < json.length && json[colon] == ':') {
            out.append('"').append(json, start, i).append('"')
            return i
        }
        return start
    }

    private fun isKeyStart(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch == '_' || ch in '가'..'힣'

    private fun isKeyPart(ch: Char): Boolean = isKeyStart(ch) || ch in '0'..'9'

    /** True when the only thing between this comma and a closing brace or bracket is blank space. */
    private fun commaIsTrailing(json: String, comma: Int): Boolean {
        var i = comma + 1
        while (i < json.length && json[i].isWhitespace()) i++
        return i < json.length && (json[i] == '}' || json[i] == ']')
    }

    /**
     * Drops a comma that sits straight in front of `}` or `]`, which JSON forbids.
     *
     * String-aware, so a comma inside a label ("채도, 대비") survives untouched.
     */
    internal fun stripTrailingCommas(text: String): String {
        if (text.indexOf(',') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                out.append(ch)
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                i++
                continue
            }
            if (ch == '"') {
                inString = true
                out.append(ch)
                i++
                continue
            }
            if (ch == ',' && commaIsTrailing(text, i)) {
                i++
                continue
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun repair(fragment: String): String? {
        closeBraces(fragment)?.let { return it }
        val lastBrace = fragment.lastIndexOf('}')
        if (lastBrace > 0) return fragment.substring(0, lastBrace + 1)
        return null
    }

    /**
     * Longest part of a request that reaches the prompt.
     *
     * A pasted paragraph must not be able to grow the prompt past [LlmBudget.PROMPT_BUDGET]: the
     * engine treats an oversized prompt as fatal, so the size is capped here and not only trusted to
     * the caller.
     */
    const val MAX_REQUEST_CHARS = 120

    /**
     * Builds the prompt.
     *
     * The size is part of the contract, not an accident: the engine's `maxTokens` counts prompt plus
     * answer, and overrunning it is fatal at the native level rather than a failed call (see
     * [LlmBudget]). So the rules keep their meaning but lose their long explanations, the key list
     * carries the English keys only (the parser already accepts them, and the Korean labels cost
     * twice the tokens), and three examples cover shape, slang/negation and region/colour.
     */
    fun buildPrompt(request: String, lutNames: List<String>, sceneHint: String = ""): String {
        val keys = baseKeys().joinToString(",") { it.key }
        val lutSample = lutNames.take(6).joinToString(", ")
        val scene = sceneHint.trim()
        return """
            사진 보정 앱 '루멘'의 의도 분석기다. 요청을 읽고 적용할 보정값을 JSON 하나로만 답한다.
            규칙:
            1. 사전에 없는 표현도 사진미학적으로 해석해 서로 다른 파라미터 2개 이상을 조정한다.
            2. 값은 -1.0~1.0 상대값. label 한국어 12자, summary 한 문장.
            3. JSON 외 문장은 쓰지 않는다. 말버릇(\"그거 아시죠\")은 무시한다.
            4. "~하지 않게/~말고"는 그 방향으로 올리지 말라는 뜻이고 "과하지 않게"는 값을 작게 잡는다.
            5. 구어체: 샤하게=중간톤+글로우, 뽀샤시=밝고 부드럽게, 꾸안꾸=티 안 나게, 느좋=힘 뺀 필름, 감다살=감성 최대, 힙하게=대비·윤곽.
            6. "인물만/배경만/하늘만"이면 subject*, background*, sky*만 쓴다.
            7. 색 이름이 나오면 redSaturation처럼 색+역할 키만 쓴다(색: red orange yellow green cyan blue purple magenta, 역할: Hue Saturation Luminance). "빼줘"는 그 색 채도를 낮춘다.
            출력: {"label":"차분하게","summary":"채도를 낮추고 대비를 부드럽게 했어요.","adjust":{"saturation":-0.3,"contrast":-0.2,"fade":0.15}}
            예: "샤하게, 너무 화려하지는 않게" → {"label":"샤하게","summary":"중간톤과 글로우를 올리고 채도는 낮췄어요.","adjust":{"shadows":0.2,"glow":0.2,"saturation":-0.1}}
            예: "배경만 차분하게, 하늘은 파랗게" → {"label":"영역","summary":"배경은 차분히, 하늘은 파랗게. ","adjust":{"backgroundSaturation":-0.3,"skyBlue":0.45,"redSaturation":-0.1}}
            키: $keys
            룩(선택): $lutSample
            ${if (scene.isNotEmpty()) "사진 상태: $scene" else ""}
            요청: ${request.take(MAX_REQUEST_CHARS)}
            JSON:
        """.trimIndent()
    }

    /**
     * The prompt used when even the compact one measures over budget (a long request, an unfamiliar
     * tokenizer): the rules stay, the examples and the scene line go.
     */
    fun buildShortPrompt(request: String): String {
        val keys = baseKeys().joinToString(",") { it.key }
        return """
            사진 보정 앱 '루멘'의 의도 분석기다. 요청을 읽고 JSON 하나로만 답한다.
            서로 다른 파라미터 2개 이상을 -1.0~1.0 상대값으로 조정하고 label(한국어)·summary(한 문장)를 넣는다.
            "~하지 않게"는 그 방향으로 올리지 않는다. 색 이름은 redSaturation처럼 색+역할 키로 쓴다.
            출력: {"label":"차분하게","summary":"채도를 낮췄어요.","adjust":{"saturation":-0.3,"contrast":-0.2}}
            키: $keys
            요청: ${request.take(MAX_REQUEST_CHARS)}
            JSON:
        """.trimIndent()
    }

    /** Tone and detail keys; the 24 colour-band keys are described by rule 7 instead of listed. */
    private fun baseKeys(): List<Param> {
        val band = HslBand.entries.flatMap { it.params }.toSet()
        return LlmScale.allowed.filter { it !in band }
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
            // 색 대역을 영어 색 이름 하나로만 말한 경우
            "red" to Param.RED_SAT,
            "orange" to Param.ORANGE_SAT,
            "yellow" to Param.YELLOW_SAT,
            "green" to Param.GREEN_SAT,
            "cyan" to Param.CYAN_SAT,
            "blue" to Param.BLUE_SAT,
            "purple" to Param.PURPLE_SAT,
            "magenta" to Param.MAGENTA_SAT,
            "sky" to Param.SKY_SATURATION,
            "background" to Param.BACKGROUND_EXPOSURE,
            "subject" to Param.SUBJECT_FOCUS,
            // 자연스러운 한국어 키("인물 밝기", "하늘 파랑")도 받아들인다.
            "인물밝기" to Param.SUBJECT_EXPOSURE,
            "인물채도" to Param.SUBJECT_SATURATION,
            "인물선명도" to Param.SUBJECT_CLARITY,
            "인물색온도" to Param.SUBJECT_WARMTH,
            "사람밝기" to Param.SUBJECT_EXPOSURE,
            "피사체" to Param.SUBJECT_EXPOSURE,
            "하늘" to Param.SKY_BLUE,
            "하늘파랑" to Param.SKY_BLUE,
            "배경" to Param.BACKGROUND_EXPOSURE,
        )
        synonyms.forEach { (key, param) -> put(normalizeKey(key), param) }
    }

    private val NONE_WORDS = setOf("none", "null", "no", "없음", "-", "0")
}
