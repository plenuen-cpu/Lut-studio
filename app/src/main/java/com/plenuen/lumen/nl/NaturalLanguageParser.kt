package com.plenuen.lumen.nl

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param

/**
 * Turns what the user typed into look intents, limits, geometry requests and dialogue commands.
 *
 * Spoken Korean arrives in a shape no dictionary matches literally: "거기 깔끔하면서도 포인트가 딱
 * 되게요, 그거 아시죠? 근데 너무 화려하지는 않은…". So before any phrase is looked up the parser
 * steps over the filler ("그거 아시죠", "뭔가", "약간 그런") and reads the negated part of the
 * sentence as a limit on the look ("~하지는 않게", "~는 말고", "~은 빼고") instead of as a phrase
 * of its own. What is left untouched is handed to the local model.
 */
object NaturalLanguageParser {

    enum class Control { UNDO, REDO, RESET, HALF, MORE, LESS, COMPARE }

    data class IntentHit(
        val intent: Intent,
        val strength: Float,
        val phrase: String,
        val position: Int,
    )

    /** One limit the sentence set, with the words that carried it. */
    data class RestraintHit(
        val restraint: Restraint,
        val phrase: String,
        val position: Int,
        /** true when a look was left out ("부드럽게 말고") rather than limited. */
        val excluded: Boolean = false,
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
        /** Negated descriptions and exclusions, in the order they appear. */
        val restraints: List<RestraintHit> = emptyList(),
    ) {
        val matched: Boolean get() = hits.isNotEmpty() || autoStraighten || straightenDeg != null ||
            lutQuery != null || control != null || restraints.isNotEmpty()

        /** 0..1 confidence in the instant interpretation (rules are exact by nature). */
        val confidence: Float
            get() = when {
                hits.size >= 2 -> 0.98f
                hits.size == 1 && restraints.isNotEmpty() -> 0.97f
                hits.size == 1 -> 0.95f
                restraints.isNotEmpty() -> 0.9f
                autoStraighten || straightenDeg != null -> 0.9f
                lutQuery != null -> 0.85f
                control != null -> 0.9f
                else -> 0f
            }

        /** Absolute ceilings the sentence put on the look ("너무 화려하지는 않게"). */
        val caps: Map<Param, Float>
            get() = restraints.fold(emptyMap()) { acc, hit ->
                acc + hit.restraint.caps.mapValues { (param, ceiling) ->
                    minOf(ceiling, acc[param] ?: ceiling)
                }
            }

        /** Absolute floors ("어둡지 않게", "암부 뭉개지지 않게"). */
        val floors: Map<Param, Float>
            get() = restraints.fold(emptyMap()) { acc, hit ->
                acc + hit.restraint.floors.mapValues { (param, floor) ->
                    maxOf(floor, acc[param] ?: floor)
                }
            }

        /** Parameters the sentence takes out altogether ("그레인 빼고"). */
        val clears: List<Param>
            get() = restraints.flatMap { it.restraint.clears }.distinct()

        /** Whole-sentence strength clamp: "과하지 않게" asks for everything a little softer. */
        val strengthScale: Float
            get() = restraints.fold(1f) { acc, hit -> acc * hit.restraint.strengthScale }
                .coerceIn(0.25f, 1f)

        /** Strength to apply [hit] with, after this sentence's clamp. */
        fun strengthOf(hit: IntentHit): Float = hit.strength * strengthScale

        /** Pushes [recipe] back inside the limits this sentence set. */
        fun restrained(recipe: EditRecipe): EditRecipe {
            if (restraints.isEmpty()) return recipe
            var next = recipe
            for (param in clears) next = next.with(param, param.default)
            caps.forEach { (param, ceiling) ->
                if (next[param] > ceiling) next = next.with(param, ceiling)
            }
            floors.forEach { (param, floor) ->
                if (next[param] < floor) next = next.with(param, floor)
            }
            return next
        }

        val summary: String
            get() = buildList {
                if (hits.isNotEmpty()) add(hits.joinToString(", ") { it.intent.label })
                if (restraints.isNotEmpty()) {
                    add(restraints.joinToString(", ") { it.restraint.label })
                }
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

    /**
     * Spoken-language noise. These carry no request of their own, so they are stepped over before
     * phrases are looked up: "그거 아시죠", "뭔가", "약간 그런", "했으면 좋겠어요".
     */
    private val FILLERS: List<String> = listOf(
        "그거 아시죠", "그거 아세요", "그거 알죠", "아시죠", "아세요", "알죠", "그쵸", "그죠",
        "그거 있잖아요", "그거 있죠", "있잖아요", "있죠", "그런 거 있잖아요", "그런 느낌", "이런 느낌",
        "좋겠어요", "좋겠다", "좋을 것 같", "좋았으면", "했으면 좋겠", "하면 좋겠", "으면 좋겠",
        "부탁해요", "부탁해", "해주세요", "해줘요", "해줘", "해봐요", "해봐", "해주고",
        "살리게", "살리는", "살려", "살리", "줄이게", "줄여", "없애",
        "뭐랄까", "뭔가요", "뭔가", "뭐지", "뭐랄", "약간 그런", "뭐 그런", "그런", "이런", "저런",
        "조런", "요런", "머", "뭐", "뭘", "뭐가", "뭣",
        "그거", "그게", "그건", "그걸", "그럼", "그러면", "거기", "거기에", "거긴", "거길",
        "여기", "여기에", "저기", "근데", "그런데", "그래도", "그치만", "하지만", "그러니까", "그니까",
        "하면서도", "하면서", "면서도", "다시", "한번", "한 번", "좀요",
    )

    /** Words that only decorate a request; they are not shown to the model as leftover text. */
    private val DECORATIVE: List<String> = MODIFIERS.map { it.first }

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

    /**
     * Korean negation. Everything after one of these turns the description before it into a limit
     * the sentence puts on the look rather than a phrase to match.
     */
    private val NEGATION = Regex(
        "하지는\\s*말|하지\\s*말|지는\\s*말|지\\s*말|하지마|지마|말구|말고" +
            "|하지는\\s*않|하지\\s*않|지지는\\s*않|지는\\s*않|지지\\s*않|지도\\s*않|지\\s*않" +
            "|지\\s*못|빼고|빼|없이|없게",
    )

    /** How far before a negation marker the described thing may sit ("노랗게 뜨지 않게"). */
    private const val LOOK_BACK = 18

    /** How far the described words may end before the marker and still belong to it. */
    private const val MARKER_GAP = 6

    /** Endings and particles shaved off leftover words so only real content reaches the model. */
    private val TAIL_ENDINGS: List<String> = listOf(
        "으로부터", "으로서", "으로써", "에서는", "에게는", "이라고", "이라는", "었으면",
        "았으면", "었는데", "았는데", "스럽게", "하면서", "처럼", "같이", "하게", "되게",
        "으면", "는데", "지만", "해서", "해도", "하고", "하면", "이고", "이라", "까지",
        "부터", "보다", "에서", "에게", "에는", "에도", "으로", "정도", "만큼",
        "랑", "만", "은", "는", "이", "가", "을", "를", "에", "의", "도", "면", "서", "고",
        "지", "요", "죠", "네", "다", "까", "며", "게", "어", "아", "여", "워", "해", "했",
        "한", "할", "함", "임", "든", "던",
    ).sortedByDescending { it.length }

    /** Stems that are still filler after endings are shaved off. */
    private val LEFTOVER_JUNK = setOf(
        "되게", "그런", "이런", "저런", "그거", "거기", "뭔가", "뭐", "딱", "좀", "너무", "아주",
        "정말", "약간", "조금", "살짝", "완전", "엄청", "진짜", "그냥", "느낌", "같은", "여기",
        "저기", "그게", "그걸", "하", "음", "아", "어", "보통", "많이", "세게", "강하게",
    )

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

    /** The sentence without spoken-language filler, for the local model path. */
    fun salient(text: String): String {
        val normalized = normalize(text)
        if (normalized.isBlank()) return normalized
        val consumed = BooleanArray(normalized.length)
        consumeFillers(normalized, consumed)
        return buildString {
            normalized.forEachIndexed { index, ch ->
                if (!consumed[index]) append(ch) else append(' ')
            }
        }.split(' ').filter { it.isNotBlank() }.joinToString(" ")
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

        // 3) 말버릇·수식어 — 의도와 부정 판정 전에 걸러 낸다. "그거 아시죠"가 문장을 흐리지 않게.
        consumeFillers(normalized, consumed)
        consumeDecorations(normalized, consumed)

        // 4) 부정·억제 ("~하지는 않게", "~는 말고", "~은 빼고")
        val restraints = scanRestraints(normalized, consumed)

        // 5) 룩 이름 (포트라, 벨비아, 라이카 …)
        var lutQuery: String? = null
        for ((alias, query) in LUT_ALIASES_SORTED) {
            val at = normalized.indexOf(alias)
            if (at >= 0) {
                lutQuery = query
                mark(consumed, at, alias.length)
                break
            }
        }

        // 6) 룩 의도
        val rawHits = mutableListOf<IntentHit>()
        for ((phrase, intent) in IntentCatalog.phraseIndex) {
            var from = 0
            while (true) {
                val at = normalized.indexOf(phrase, from)
                if (at < 0) break
                if (!overlaps(consumed, at, phrase.length)) {
                    mark(consumed, at, phrase.length)
                    rawHits.add(IntentHit(intent, modifierBefore(normalized, at), phrase, at))
                }
                from = at + phrase.length
            }
        }
        // 같은 룩을 두 번 말해도("눈길을 끌면서도 ... 눈에 띄었으면") 한 번만 적용한다.
        val ordered = rawHits.sortedBy { it.position }
            .distinctBy { it.intent.key }

        // 7) "더" / "덜" — 붙은 룩이 없을 때만 대화 제어로 읽는다.
        if (ordered.isEmpty() && control == null) {
            val moreWord = normalized.startsWith("더") || normalized.contains(" 더 ")
            val lessWord = normalized.startsWith("덜") || normalized.contains(" 덜 ")
            if (moreWord) control = Control.MORE
            if (lessWord) control = Control.LESS
        }

        val intentLut = ordered.mapNotNull { it.intent.lutQuery }.firstOrNull()
        return ParseResult(
            hits = ordered,
            autoStraighten = autoStraighten,
            straightenDeg = degrees,
            lutQuery = intentLut ?: lutQuery,
            lutIntensity = ordered.mapNotNull { it.intent.lutIntensity }.firstOrNull(),
            control = control,
            normalized = normalized,
            unmatched = leftovers(normalized, consumed),
            restraints = restraints,
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

    // ── 부정·억제 ─────────────────────────────────────────────────────────

    /**
     * Reads every negated stretch of the sentence: the words in front of the negation marker say
     * what is being limited. A known limit ("화려", "칙칙", "비네팅") becomes a [Restraint]; an
     * ordinary look ("부드럽게 말고") becomes its own inverse, so the sentence still moves.
     */
    private fun scanRestraints(text: String, consumed: BooleanArray): List<RestraintHit> {
        val out = mutableListOf<RestraintHit>()
        val seen = mutableSetOf<String>()
        for (match in NEGATION.findAll(text)) {
            val markerAt = match.range.first
            val markerEnd = match.range.last + 1
            if (overlaps(consumed, markerAt, markerEnd - markerAt)) continue
            val windowStart = (markerAt - LOOK_BACK).coerceAtLeast(0)
            // "과하지는"처럼 어간의 마지막 글자가 표시에 붙어 있어도 찾도록 표시 앞 두 글자까지 본다.
            val searchEnd = (markerAt + 2).coerceAtMost(text.length)

            val limit = nearestRestraint(text, windowStart, searchEnd, markerAt, consumed)
            if (limit != null) {
                val (restraint, phrase, at) = limit
                mark(consumed, at, markerEnd - at)
                if (seen.add(restraint.key)) {
                    out += RestraintHit(restraint, phrase, at)
                }
                continue
            }

            val look = nearestIntent(text, windowStart, searchEnd, markerAt, consumed)
            if (look != null) {
                val (intent, phrase, at) = look
                mark(consumed, at, markerEnd - at)
                val inverse = intent.delta.negated().scaled(EXCLUSION_STRENGTH)
                if (!inverse.isEmpty || !intent.delta.isEmpty) {
                    val exclusion = Restraint(
                        key = "exclude_${intent.key}",
                        label = "${intent.label} 빼고",
                        phrases = emptyList(),
                        delta = inverse.takeUnless { it.isEmpty },
                    )
                    if (seen.add(exclusion.key)) {
                        out += RestraintHit(exclusion, phrase, at, excluded = true)
                    }
                }
                continue
            }

            // 무엇을 부정하는지 모르겠으면 표시만 해 둔다 ("~하지는 않은"의 꼬리).
            mark(consumed, markerAt, markerEnd - markerAt)
        }
        return out
    }

    private fun nearestRestraint(
        text: String,
        windowStart: Int,
        searchEnd: Int,
        markerAt: Int,
        consumed: BooleanArray,
    ): Triple<Restraint, String, Int>? {
        var best: Triple<Restraint, String, Int>? = null
        for ((phrase, restraint) in Restraints.phraseIndex) {
            val at = lastOccurrence(text, phrase, windowStart, searchEnd) ?: continue
            if (overlaps(consumed, at, phrase.length)) continue
            if (!belongsToMarker(at, phrase.length, markerAt)) continue
            val end = at + phrase.length
            val bestEnd = best?.let { it.third + it.second.length } ?: -1
            if (end > bestEnd) best = Triple(restraint, phrase, at)
        }
        return best
    }

    private fun nearestIntent(
        text: String,
        windowStart: Int,
        searchEnd: Int,
        markerAt: Int,
        consumed: BooleanArray,
    ): Triple<Intent, String, Int>? {
        var best: Triple<Intent, String, Int>? = null
        for ((phrase, intent) in IntentCatalog.phraseIndex) {
            val at = lastOccurrence(text, phrase, windowStart, searchEnd) ?: continue
            if (overlaps(consumed, at, phrase.length)) continue
            if (!belongsToMarker(at, phrase.length, markerAt)) continue
            val end = at + phrase.length
            val bestEnd = best?.let { it.third + it.second.length } ?: -1
            if (end > bestEnd) best = Triple(intent, phrase, at)
        }
        return best
    }

    /** True when the words right before the marker are what the marker negates. */
    private fun belongsToMarker(at: Int, length: Int, markerAt: Int): Boolean {
        val gap = markerAt - (at + length)
        return gap in -2..MARKER_GAP
    }

    /** Last place [phrase] starts inside [from]..[until]. */
    private fun lastOccurrence(text: String, phrase: String, from: Int, until: Int): Int? {
        var at = text.indexOf(phrase, from)
        var last = -1
        while (at >= 0 && at + phrase.length <= until) {
            last = at
            at = text.indexOf(phrase, at + 1)
        }
        return if (last >= 0) last else null
    }

    private const val EXCLUSION_STRENGTH = 0.6f

    // ── 남은 문장 ─────────────────────────────────────────────────────────

    /**
     * What the rules did not read. Filler, modifiers and the endings Korean glues onto words are
     * dropped, so only real content ("골목의 저녁 공기") is left for the local model.
     */
    private fun leftovers(normalized: String, consumed: BooleanArray): String =
        buildString {
            normalized.forEachIndexed { index, ch ->
                if (!consumed[index]) append(ch) else append(' ')
            }
        }.replace('.', ' ').replace('°', ' ')
            .split(' ')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { stem(it) }
            .filter { it.length >= 2 && it !in LEFTOVER_JUNK }
            .joinToString(" ")

    private fun stem(token: String): String {
        var value = token
        var guard = 0
        while (value.length > 1 && guard++ < 4) {
            val suffix = TAIL_ENDINGS.firstOrNull { value.endsWith(it) } ?: break
            value = value.dropLast(suffix.length)
        }
        return value
    }

    // ── 소비 표시 ─────────────────────────────────────────────────────────

    private fun consumeFillers(text: String, consumed: BooleanArray) {
        for (filler in FILLERS.sortedByDescending { it.length }) {
            var from = 0
            while (true) {
                val at = text.indexOf(filler, from)
                if (at < 0) break
                val end = at + filler.length
                val wholeWord = at == 0 || text[at - 1] == ' '
                val endBoundary = end == text.length || text[end] == ' '
                // 긴 말버릇과 띄어 쓴 말버릇은 문장 안 어디서든, 짧은 낱말은 낱말 단위로만 지운다.
                val allowed = filler.contains(' ') || filler.length >= 4 || (wholeWord && endBoundary)
                if (allowed && !overlaps(consumed, at, filler.length)) {
                    mark(consumed, at, filler.length)
                }
                from = at + 1
            }
        }
    }

    /** Modifiers are read by [modifierBefore]; marking them keeps them out of the leftover text. */
    private fun consumeDecorations(text: String, consumed: BooleanArray) {
        for (word in DECORATIVE) {
            var from = 0
            while (true) {
                val at = text.indexOf(word, from)
                if (at < 0) break
                val end = at + word.length
                val wholeWord = at == 0 || text[at - 1] == ' '
                val endBoundary = end == text.length || text[end] == ' '
                if ((wholeWord && endBoundary) && !overlaps(consumed, at, word.length)) {
                    mark(consumed, at, word.length)
                }
                from = at + 1
            }
        }
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
