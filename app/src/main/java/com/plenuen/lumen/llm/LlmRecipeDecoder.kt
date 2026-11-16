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
            5. "그거 아시죠", "뭔가", "음...", "약간 그런", "됐으면 좋겠어요" 같은 말버릇은 뜻이 없다. 무시하고 보정을 가리키는 말만 읽는다.
            6. "~하지 않게", "~하지는 않은", "~는 말고"는 그 방향으로 올리지 말라는 뜻이다. 예: "너무 화려하지는 않게" → saturation을 올리지 않고 낮춘다. "과하지 않게"는 모든 값을 작게 잡는다.
            7. "~하면서도 ~하지 않게"처럼 상반된 요구가 한 문장에 있으면 둘 다 지킨다. 예: "깔끔하면서도 포인트, 너무 화려하지는 않게" → subjectFocus·clarity는 올리고 saturation은 올리지 않는다.
            8. 구어체·신조어는 사진 현장의 말로 읽는다. 샤하게=중간톤+글로우+피부톤, 뽀샤시=하얗고 부드럽게, 꾸안꾸=티 안 나게, 느좋=힘 뺀 필름 무드, 감다살=감성 최대, 힙하게=대비·윤곽 강하게.
            9. "인물만·피사체만·배경만·하늘만"처럼 영역을 지정하면 그 영역 파라미터만 쓴다(인물=subject*, 배경=background*, 하늘=sky*). 영역을 말하지 않았으면 영역 파라미터를 쓰지 않는다.
            10. 색 이름(빨강·주황·노랑·초록·청록·파랑·보라·자홍)이 나오면 그 대역의 색조/채도/밝기 파라미터만 쓴다(예: redSaturation, greenSaturation, yellowLuminance). "빼줘·줄여"는 그 색의 채도를 낮추라는 뜻이다.

            출력 예시:
            {"label":"차분하게","summary":"채도를 낮추고 대비를 부드럽게 했어요.","adjust":{"saturation":-0.35,"contrast":-0.2,"fade":0.15}}

            구어체 예시:
            "샤하게" → {"label":"샤하게","summary":"중간톤과 글로우를 올려 뽀샤시하게 했어요.","adjust":{"shadows":0.2,"skinToneClean":0.35,"glow":0.2,"texture":-0.2}}
            "꾸안꾸 느낌, 근데 너무 화려하지는 않게" → {"label":"꾸안꾸","summary":"피부톤만 정리하고 채도는 올리지 않았어요.","adjust":{"skinToneClean":0.3,"saturation":-0.1,"colorHarmony":0.15}}
            "느좋하게, 살짝 필름 느낌으로" → {"label":"느좋","summary":"페이드와 그레인으로 담백한 필름 무드를 냈어요.","adjust":{"fade":0.12,"grain":0.1,"saturation":-0.05}}

            영역·색상 예시:
            "인물만 화사하게" → {"label":"인물 화사","summary":"인물만 밝고 화사하게 했어요.","adjust":{"subjectExposure":0.3,"subjectSaturation":0.15}}
            "배경만 차분하게, 하늘은 파랗게" → {"label":"영역 보정","summary":"배경은 차분하게, 하늘은 파랗게 했어요.","adjust":{"backgroundSaturation":-0.3,"backgroundExposure":-0.15,"skyBlue":0.45}}
            "빨강만 살리고 초록은 차분하게" → {"label":"색 핀셋","summary":"빨강은 살리고 초록은 낮췄어요.","adjust":{"redSaturation":0.4,"greenSaturation":-0.35}}

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
