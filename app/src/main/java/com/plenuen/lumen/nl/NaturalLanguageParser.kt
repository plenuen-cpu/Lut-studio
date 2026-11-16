package com.plenuen.lumen.nl

/** Turns what the user typed into look intents, geometry requests and dialogue commands. */
object NaturalLanguageParser {

    enum class Control { UNDO, REDO, RESET, HALF, MORE, LESS, COMPARE }

    data class IntentHit(
        val intent: Intent,
        val strength: Float,
        val phrase: String,
        val position: Int,
    )

    data class ParseResult(
        val hits: List<IntentHit> = emptyList(),
        val autoStraighten: Boolean = false,
        /** Explicit degrees the user asked for, already signed for the recipe. */
        val straightenDeg: Float? = null,
        val lutQuery: String? = null,
        val lutIntensity: Float? = null,
        val control: Control? = null,
        val normalized: String = "",
        val unmatched: String = "",
    ) {
        val matched: Boolean get() = hits.isNotEmpty() || autoStraighten || straightenDeg != null ||
            lutQuery != null || control != null

        /** 0..1 confidence in the instant interpretation (rules are exact by nature). */
        val confidence: Float
            get() = when {
                hits.size >= 2 -> 0.98f
                hits.size == 1 -> 0.95f
                autoStraighten || straightenDeg != null -> 0.9f
                lutQuery != null -> 0.85f
                control != null -> 0.9f
                else -> 0f
            }

        val summary: String
            get() = buildList {
                if (hits.isNotEmpty()) add(hits.joinToString(", ") { it.intent.label })
                if (autoStraighten) add("자동 수평·수직")
                straightenDeg?.let { add("수평 ${"%.1f".format(it)}°") }
                lutQuery?.let { add("룩 $it") }
            }.joinToString(" · ")
    }

    // ── 어휘 ──────────────────────────────────────────────────────────────

    /** Strength adverbs. Longer phrases first so "아주 살짝" beats "아주". */
    private val MODIFIERS: List<Pair<String, Float>> = listOf(
        "아주 살짝" to 0.25f, "아주 조금" to 0.25f, "아주 약간" to 0.3f, "정말 살짝" to 0.3f,
        "은은하게" to 0.45f, "은은한" to 0.45f, "옅게" to 0.45f, "살짝" to 0.55f,
        "약간" to 0.55f, "조금" to 0.60f, "좀" to 0.60f, "적당히" to 0.85f,
        "적당하게" to 0.85f, "보통" to 0.9f, "진하게" to 1.35f, "짙게" to 1.35f,
        "많이" to 1.30f, "아주" to 1.30f, "매우" to 1.30f, "정말" to 1.25f, "너무" to 1.25f,
        "엄청" to 1.60f, "완전" to 1.45f, "강하게" to 1.45f, "세게" to 1.50f, "확" to 1.50f,
        "팍" to 1.50f, "빡세게" to 1.55f,
    )

    private val STRAIGHTEN_TOKENS = listOf(
        "수평수직", "수직수평", "수평 수직", "수직 수평", "자동 수평", "수평 맞", "수평을 맞",
        "수평좀", "수평", "수직", "기울기", "기울", "반듯", "똑바로", "똑바르", "바르게",
        "비뚤", "삐뚤", "바로잡", "각도", "수평선", "지평선",
    )

    private val CONTROL_WORDS: List<Pair<String, Control>> = listOf(
        "다시 실행" to Control.REDO, "재실행" to Control.REDO, "redo" to Control.REDO,
        "실행 취소" to Control.UNDO, "되돌리기" to Control.UNDO, "되돌려" to Control.UNDO,
        "되돌" to Control.UNDO, "취소" to Control.UNDO, "언두" to Control.UNDO,
        "undo" to Control.UNDO, "롤백" to Control.UNDO,
        "초기화" to Control.RESET, "리셋" to Control.RESET, "원본으로" to Control.RESET,
        "처음으로" to Control.RESET, "다 지워" to Control.RESET, "모두 지워" to Control.RESET,
        "reset" to Control.RESET,
        "절반만" to Control.HALF, "절반" to Control.HALF, "반만" to Control.HALF,
        "반으로" to Control.HALF, "줄여" to Control.LESS, "약하게" to Control.LESS,
        "덜하게" to Control.LESS, "덜 " to Control.LESS,
        "비교" to Control.COMPARE, "비포" to Control.COMPARE, "애프터" to Control.COMPARE,
    )

    /** Film and camera names understood without opening the LUT strip. */
    private val LUT_ALIASES: List<Pair<String, String>> = listOf(
        "포트라" to "Portra", "portra" to "Portra", "에크타" to "Ektar", "ektar" to "Ektar",
        "골드" to "Gold", "gold" to "Gold", "울트라맥스" to "Ultra", "엘리트크롬" to "Elite Chrome",
        "엘리트 크롬" to "Elite Chrome", "코다크롬" to "Kodachrome", "kodachrome" to "Kodachrome",
        "트라이엑스" to "Tri-X", "트라이 엑스" to "Tri-X", "tri-x" to "Tri-X", "trix" to "Tri-X",
        "티맥스" to "T-Max", "t-max" to "T-Max", "tmz" to "T-Max",
        "프로400h" to "Pro 400H", "프로 400h" to "Pro 400H", "400h" to "Pro 400H",
        "벨비아" to "Velvia", "velvia" to "Velvia", "슈페리아" to "Superia", "superia" to "Superia",
        "리얼라" to "Reala", "reala" to "Reala", "아스티아" to "Astia", "astia" to "Astia",
        "프로비아" to "Provia", "provia" to "Provia", "네오판" to "Neopan",
        "비스타" to "Vista", "vista" to "Vista", "아그파" to "Agfa", "agfa" to "Agfa",
        "에이치피5" to "HP5", "hp5" to "HP5", "일포드" to "Ilford", "ilford" to "Ilford",
        "델타" to "Delta", "라이카" to "Leica", "leica" to "Leica", "모노크롬" to "Monochrom",
        "monochrom" to "Monochrom", "리코" to "Ricoh", "ricoh" to "Ricoh",
        "코닥" to "Kodak", "kodak" to "Kodak", "후지" to "Fujifilm", "fuji" to "Fujifilm",
        "fujifilm" to "Fujifilm",
    )

    private val LUT_ALIASES_SORTED = LUT_ALIASES.sortedByDescending { it.first.length }

    // ── 정규화 ────────────────────────────────────────────────────────────

    fun normalize(text: String): String {
        val lowered = text.lowercase().trim()
        val builder = StringBuilder(lowered.length)
        var lastSpace = true
        for (ch in lowered) {
            val keep = ch.isLetterOrDigit() || ch == '°' || ch == '.' || ch == '&' ||
                ch == '+' || ch == '-'
            if (keep) {
                builder.append(ch)
                lastSpace = false
            } else if (!lastSpace) {
                builder.append(' ')
                lastSpace = true
            }
        }
        return builder.toString().trim()
    }

    // ── 파싱 ──────────────────────────────────────────────────────────────

    fun parse(text: String): ParseResult {
        val normalized = normalize(text)
        if (normalized.isBlank()) return ParseResult(normalized = normalized)

        val consumed = BooleanArray(normalized.length)

        // 1) 대화 제어 (되돌리기·초기화·절반만 …)
        var control: Control? = null
        for ((word, action) in CONTROL_WORDS) {
            val at = normalized.indexOf(word)
            if (at >= 0) {
                control = action
                mark(consumed, at, word.length)
                break
            }
        }

        // 2) 수평·수직 (자동 + 각도 지정)
        var autoStraighten = false
        for (token in STRAIGHTEN_TOKENS) {
            val at = normalized.indexOf(token)
            if (at >= 0) {
                autoStraighten = true
                mark(consumed, at, token.length)
                break
            }
        }
        val degrees = parseDegrees(normalized)
        if (degrees != null) {
            autoStraighten = false
            DIGIT_PATTERN.findAll(normalized).forEach { match ->
                mark(consumed, match.range.first, match.value.length)
            }
        }

        // 3) 룩 이름 (포트라, 벨비아, 라이카 …)
        var lutQuery: String? = null
        for ((alias, query) in LUT_ALIASES_SORTED) {
            val at = normalized.indexOf(alias)
            if (at >= 0) {
                lutQuery = query
                mark(consumed, at, alias.length)
                break
            }
        }

        // 4) 룩 의도
        val hits = mutableListOf<IntentHit>()
        for ((phrase, intent) in IntentCatalog.phraseIndex) {
            var from = 0
            while (true) {
                val at = normalized.indexOf(phrase, from)
                if (at < 0) break
                if (!overlaps(consumed, at, phrase.length)) {
                    mark(consumed, at, phrase.length)
                    hits.add(IntentHit(intent, modifierBefore(normalized, at), phrase, at))
                }
                from = at + phrase.length
            }
        }
        val ordered = hits.sortedBy { it.position }

        // 5) "더" / "덜" — 붙은 룩이 없을 때만 대화 제어로 읽는다.
        if (ordered.isEmpty() && control == null) {
            val moreWord = normalized.startsWith("더") || normalized.contains(" 더 ")
            val lessWord = normalized.startsWith("덜") || normalized.contains(" 덜 ")
            if (moreWord) control = Control.MORE
            if (lessWord) control = Control.LESS
        }

        val unmatched = buildString {
            normalized.forEachIndexed { index, ch ->
                if (!consumed[index]) append(ch) else append(' ')
            }
        }.split(' ').filter { it.isNotBlank() }.joinToString(" ")

        val intentLut = ordered.mapNotNull { it.intent.lutQuery }.firstOrNull()
        return ParseResult(
            hits = ordered,
            autoStraighten = autoStraighten,
            straightenDeg = degrees,
            lutQuery = intentLut ?: lutQuery,
            lutIntensity = ordered.mapNotNull { it.intent.lutIntensity }.firstOrNull(),
            control = control,
            normalized = normalized,
            unmatched = unmatched,
        )
    }

    private val DIGIT_PATTERN = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(?:도|°)""")

    /** "오른쪽으로 3도", "왼쪽 2.5도", "3도만큼" → signed recipe degrees. */
    private fun parseDegrees(normalized: String): Float? {
        val match = DIGIT_PATTERN.find(normalized) ?: return null
        if (!normalized.contains("도") && !normalized.contains("°")) return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        if (value <= 0f) return null
        val clockwise = normalized.contains("오른쪽") || normalized.contains("시계 방향") ||
            normalized.contains("시계방향")
        val counter = normalized.contains("왼쪽") || normalized.contains("반시계") ||
            normalized.contains("시계 반대")
        val signed = when {
            clockwise -> -value
            counter -> value
            else -> value
        }
        return signed.coerceIn(-15f, 15f)
    }

    private fun modifierBefore(text: String, position: Int): Float {
        val start = (position - 12).coerceAtLeast(0)
        val window = text.substring(start, position)
        var best: Float? = null
        var bestLength = -1
        for ((word, strength) in MODIFIERS) {
            val at = window.lastIndexOf(word)
            if (at >= 0 && word.length > bestLength) {
                best = strength
                bestLength = word.length
            }
        }
        return best ?: 1f
    }

    private fun mark(consumed: BooleanArray, start: Int, length: Int) {
        for (i in start until (start + length).coerceAtMost(consumed.size)) consumed[i] = true
    }

    private fun overlaps(consumed: BooleanArray, start: Int, length: Int): Boolean {
        for (i in start until (start + length).coerceAtMost(consumed.size)) {
            if (consumed[i]) return true
        }
        return false
    }
}
