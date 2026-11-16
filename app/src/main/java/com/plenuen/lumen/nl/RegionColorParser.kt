package com.plenuen.lumen.nl

import com.plenuen.lumen.core.HslBand
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.core.RecipeDelta.Op.Add
import kotlin.math.abs

/**
 * Reads the two kinds of request that point at *part* of a picture instead of the whole frame:
 *
 *  - a region — "인물만 화사하게", "배경만 어둡게", "하늘만 파랗게", "배경 채도 빼줘"
 *  - a colour band — "빨강만 살리고 초록은 차분하게", "단풍 색만 진하게", "피부 주황기 빼줘"
 *
 * A region mention only becomes a directive when an effect word is found beside it, so "인물 사진"
 * stays the portrait look it always was. Whatever is used here is marked consumed, which is what
 * keeps the general intent table from applying the same word to the whole photo afterwards.
 *
 * The output is a [RecipeDelta] over the region/HSL parameters only; the shader then weights it by
 * the on-device segmentation mask.
 */
object RegionColorParser {

    enum class Region(val label: String, val words: List<String>) {
        SUBJECT("인물", listOf("인물", "사람", "피사체", "주제", "주인공", "모델", "얼굴", "셀카")),
        BACKGROUND("배경", listOf("배경", "뒷배경", "뒤배경", "뒤쪽", "뒷쪽", "바탕", "주변")),
        SKY("하늘", listOf("하늘", "창밖")),
    }

    data class RegionHit(
        val region: Region,
        val delta: RecipeDelta,
        val label: String,
        val phrase: String,
        val position: Int,
    )

    data class ColorHit(
        val band: HslBand,
        val delta: RecipeDelta,
        val label: String,
        val phrase: String,
        val position: Int,
    )

    data class Scan(val regions: List<RegionHit> = emptyList(), val colors: List<ColorHit> = emptyList()) {
        val isEmpty: Boolean get() = regions.isEmpty() && colors.isEmpty()
        val labels: List<String> get() = regions.map { it.label } + colors.map { it.label }
    }

    private data class Mention(
        val start: Int,
        val end: Int,
        val region: Region?,
        val band: HslBand?,
        val word: String,
    ) {
        fun overlapsRange(other: Mention): Boolean = start < other.end && other.start < end
    }

    /** One directive being built: the ops, the words that carried them, and their label. */
    private class Draft(val label: String) {
        val ops = mutableListOf<RecipeDelta.Op>()
        val tokens = mutableListOf<String>()

        fun see(window: String, candidates: List<String>): Boolean {
            val found = candidates.firstOrNull { window.contains(it) } ?: return false
            tokens += found
            return true
        }

        fun add(param: Param, amount: Float) {
            if (abs(amount) < 1e-4f) return
            ops += Add(param, amount)
        }

        fun build(): RecipeDelta? = if (ops.isEmpty()) null else RecipeDelta(label, ops)
    }

    // ── 어휘 ──────────────────────────────────────────────────────────────

    private val BRIGHT_UP = listOf("화사", "밝", "환하", "하얗", "뽀얗", "생기", "맑")
    private val DARK_UP = listOf("어둡", "어두", "가라앉", "칙칙", "무겁", "차분", "죽여")
    private val SAT_UP = listOf("채도", "색감", "진하", "짙", "살리", "살려", "알록", "화려")
    private val SAT_DOWN = listOf("빼", "줄", "없애", "죽", "낮", "덜", "약하", "톤다운", "차분", "정돈", "단순", "가라앉")
    private val CLARITY_UP = listOf("선명", "또렷", "쨍", "샤프", "디테일", "생생", "촘촘", "강조", "살려")
    private val SOFT = listOf("부드", "흐리", "날려", "날아", "보케", "아웃포커스", "심도", "소프트", "뭉개")
    private val WARM = listOf("따뜻", "따스", "노르스름", "핑크", "온기")
    private val COOL = listOf("차갑", "차가", "시원", "푸르스름", "푸른")
    private val SKY_BLUE_UP = listOf("파랗", "파란", "파랑", "푸르", "푸른", "맑", "시원")
    private val SKY_DEEP = listOf("깊", "진하", "짙", "풍부")
    private val SKY_RECOVER = listOf("날아간", "날아가", "날아", "하얗게", "떠 버린", "살려", "살리")

    /** Words that turn a colour amount downward ("빼줘", "줄여", "차분하게"). */
    private val DOWN_MARKERS = listOf("빼", "줄", "없애", "죽", "낮", "덜", "약하", "차분", "가라앉", "톤다운", "억제")

    /** Words that follow a region noun but make it a genre, not a scope ("인물 사진", "배경 컷"). */
    private val GENRE_SUFFIX = listOf("사진", "보정", "컷", "샷", "필터", "모드", "느낌")

    /**
     * What may introduce a region scope without the "만" particle: the quality being asked about
     * ("배경 채도 빼줘", "인물 밝기 올려"). A look name right after the noun ("인물 뽀얗게") is not
     * in this list, so it stays the look it always was.
     */
    private val QUALITY_NOUNS = listOf(
        "채도", "색감", "색조", "밝기", "밝게", "밝힘", "명도", "선명", "디테일", "부드", "흐림",
        "톤",
    )

    /** A negation right after a noun or colour means the restraint table should read it instead. */
    private val NEGATION_TAIL = listOf(
        "지 않", "지않", "하지 않", "하지않", "지 말", "지말", "지 마", "않게", "않은", "말고", "빼고", "없이",
    )

    private val COLOR_WORDS: Map<HslBand, List<String>> = mapOf(
        HslBand.RED to listOf("빨강", "빨간", "빨갛", "적색", "단풍", "홍색", "새빨"),
        HslBand.ORANGE to listOf("주황", "오렌지", "살구", "피부", "황토"),
        HslBand.YELLOW to listOf("노랑", "노란", "노랗", "옐로"),
        HslBand.GREEN to listOf("초록", "녹색", "나뭇잎", "나뭇", "잎사귀", "풀잎", "그린"),
        HslBand.CYAN to listOf("청록", "민트", "터콰이즈", "시안", "옥색"),
        HslBand.BLUE to listOf("파랑", "파란", "파랗", "푸른", "푸르", "하늘색", "남색", "코발트"),
        HslBand.PURPLE to listOf("보라", "자주", "퍼플", "바이올렛", "연보라"),
        HslBand.MAGENTA to listOf("자홍", "마젠타", "핑크", "분홍"),
    )

    private val CLAUSE_BREAKS = charArrayOf(',', '.', ';', '·', '!', '?', '/', '\n', '、')

    // ── 스캔 ──────────────────────────────────────────────────────────────

    fun scan(text: String, consumed: BooleanArray): Scan {
        if (text.isBlank()) return Scan()
        val mentions = mutableListOf<Mention>()
        for (region in Region.entries) {
            for (word in region.words) {
                forEachOccurrence(text, word) { at ->
                    if (overlaps(consumed, at, word.length)) return@forEachOccurrence
                    if (region != Region.SKY && genreFollows(text, at + word.length)) return@forEachOccurrence
                    if (negatedNear(text, at + word.length)) return@forEachOccurrence
                    // "인물 사진"·"배경 날리기" 같은 기존 룩 이름 안의 낱말은 영역 지시가 아니다.
                    if (insideCatalogPhrase(text, at, word.length)) return@forEachOccurrence
                    if (!isRegionScope(text, at + word.length, region)) return@forEachOccurrence
                    mentions += Mention(at, at + word.length, region, null, word)
                }
            }
        }
        for (band in HslBand.entries) {
            for (word in COLOR_WORDS[band].orEmpty()) {
                forEachOccurrence(text, word) { at ->
                    if (overlaps(consumed, at, word.length)) return@forEachOccurrence
                    if (negatedNear(text, at + word.length)) return@forEachOccurrence
                    // "틸앤오렌지", "피부 맑게"처럼 룩 이름 안에 든 색 이름은 그 룩의 것이다.
                    if (insideCatalogPhrase(text, at, word.length)) return@forEachOccurrence
                    mentions += Mention(at, at + word.length, null, band, word)
                }
            }
        }
        if (mentions.isEmpty()) return Scan()

        val ordered = mentions
            .sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
            .fold(mutableListOf<Mention>()) { acc, mention ->
                if (acc.none { it.overlapsRange(mention) }) acc += mention
                acc
            }

        val regions = mutableListOf<RegionHit>()
        val colors = mutableListOf<ColorHit>()
        val usedRegions = mutableSetOf<Region>()
        val usedBands = mutableSetOf<HslBand>()

        ordered.forEachIndexed { index, mention ->
            val window = windowFor(text, ordered, index)
            val phrase = text.substring(window.start, window.end).trim()
            val strength = NaturalLanguageParser.strengthIn(phrase)
            val region = mention.region
            val band = mention.band
            if (region != null) {
                if (region in usedRegions) return@forEachIndexed
                val draft = Draft("${region.label} 보정")
                // 효과 낱말이 없으면 영역 지시가 아니다: 소비하지 않고 일반 룩 해석에 맡긴다.
                val delta = regionDelta(region, phrase, strength, draft)
                if (delta != null) {
                    usedRegions += region
                    regions += RegionHit(region, delta, delta.label, mention.word, mention.start)
                    mark(consumed, mention.start, mention.end - mention.start)
                    markTokens(consumed, text, window, draft.tokens)
                }
            } else if (band != null) {
                // "하늘 파란색만 깊게" — 하늘 옆의 파랑은 색 대역이 아니라 하늘 자체를 말한다.
                val skyBeside = skyMentionBeside(text, ordered, index)
                if (band == HslBand.BLUE && skyBeside && Region.SKY !in usedRegions) {
                    val draft = Draft("하늘 보정")
                    val delta = skyDeepDelta(phrase, strength, draft)
                    if (delta != null) {
                        usedRegions += Region.SKY
                        regions += RegionHit(Region.SKY, delta, delta.label, mention.word, mention.start)
                        mark(consumed, mention.start, mention.end - mention.start)
                        markTokens(consumed, text, window, draft.tokens)
                        return@forEachIndexed
                    }
                }
                if (band in usedBands) return@forEachIndexed
                val draft = Draft("${band.label} 색")
                val delta = colorDelta(band, phrase, strength, draft) ?: return@forEachIndexed
                usedBands += band
                colors += ColorHit(band, delta, delta.label, mention.word, mention.start)
                mark(consumed, mention.start, mention.end - mention.start)
                markTokens(consumed, text, window, draft.tokens)
            }
        }
        // 같은 대상을 가리키는 여분의 낱말(초록색/나뭇잎, 하늘/파란색)도 소비해 자투리로 남기지 않는다.
        for (mention in ordered) {
            val used = (mention.region != null && mention.region in usedRegions) ||
                (mention.band != null && mention.band in usedBands)
            if (used) mark(consumed, mention.start, mention.end - mention.start)
        }
        return Scan(regions, colors)
    }

    // ── 영역별 규칙 ───────────────────────────────────────────────────────

    private fun regionDelta(region: Region, window: String, strength: Float, draft: Draft): RecipeDelta? {
        val down = DOWN_MARKERS.any { window.contains(it) }
        if (down) draft.see(window, DOWN_MARKERS)
        when (region) {
            Region.SUBJECT -> {
                if (draft.see(window, BRIGHT_UP)) {
                    draft.add(Param.SUBJECT_EXPOSURE, 0.22f * strength)
                    draft.add(Param.SUBJECT_SATURATION, 0.06f * strength)
                }
                if (draft.see(window, DARK_UP)) draft.add(Param.SUBJECT_EXPOSURE, -0.22f * strength)
                if (draft.see(window, CLARITY_UP)) draft.add(Param.SUBJECT_CLARITY, 0.30f * strength)
                if (draft.see(window, SOFT)) draft.add(Param.SUBJECT_CLARITY, -0.20f * strength)
                if (down && draft.see(window, SAT_DOWN)) {
                    draft.add(Param.SUBJECT_SATURATION, -0.26f * strength)
                } else if (draft.see(window, SAT_UP)) {
                    draft.add(Param.SUBJECT_SATURATION, 0.22f * strength)
                }
                if (draft.see(window, WARM)) draft.add(Param.SUBJECT_WARMTH, 0.22f * strength)
                if (draft.see(window, COOL)) draft.add(Param.SUBJECT_WARMTH, -0.22f * strength)
            }
            Region.BACKGROUND -> {
                if (draft.see(window, BRIGHT_UP)) draft.add(Param.BACKGROUND_EXPOSURE, 0.18f * strength)
                if (draft.see(window, DARK_UP)) draft.add(Param.BACKGROUND_EXPOSURE, -0.28f * strength)
                if (draft.see(window, SOFT)) draft.add(Param.BACKGROUND_SOFT, 0.70f * strength)
                if (draft.see(window, CLARITY_UP)) draft.add(Param.BACKGROUND_CLARITY, 0.25f * strength)
                if (down && draft.see(window, SAT_DOWN)) {
                    draft.add(Param.BACKGROUND_SATURATION, -0.30f * strength)
                } else if (draft.see(window, SAT_UP)) {
                    draft.add(Param.BACKGROUND_SATURATION, 0.15f * strength)
                }
                if (draft.see(window, WARM)) draft.add(Param.BACKGROUND_WARMTH, 0.18f * strength)
                if (draft.see(window, COOL)) draft.add(Param.BACKGROUND_WARMTH, -0.18f * strength)
            }
            Region.SKY -> {
                if (draft.see(window, SKY_RECOVER)) {
                    draft.add(Param.SKY_EXPOSURE, -0.32f * strength)
                    draft.add(Param.SKY_SATURATION, 0.35f * strength)
                }
                if (draft.see(window, SKY_DEEP)) {
                    draft.add(Param.SKY_SATURATION, 0.30f * strength)
                    draft.add(Param.SKY_BLUE, 0.45f * strength)
                    draft.add(Param.SKY_EXPOSURE, -0.12f * strength)
                }
                if (draft.see(window, SKY_BLUE_UP)) {
                    draft.add(Param.SKY_BLUE, 0.60f * strength)
                    draft.add(Param.SKY_SATURATION, 0.20f * strength)
                }
                if (draft.see(window, DARK_UP)) draft.add(Param.SKY_EXPOSURE, -0.20f * strength)
                if (draft.see(window, BRIGHT_UP)) draft.add(Param.SKY_EXPOSURE, 0.20f * strength)
                if (down) draft.add(Param.SKY_SATURATION, -0.28f * strength)
                if (draft.see(window, WARM)) draft.add(Param.SKY_WARMTH, 0.20f * strength)
                if (draft.see(window, COOL)) draft.add(Param.SKY_WARMTH, -0.20f * strength)
            }
        }
        return draft.build()
    }

    /** The blue-band path for a sky the user asked to deepen ("하늘 파란색만 깊게"). */
    private fun skyDeepDelta(window: String, strength: Float, draft: Draft): RecipeDelta? {
        val deep = draft.see(window, SKY_DEEP)
        if (!deep && draft.see(window, SKY_RECOVER)) {
            draft.add(Param.SKY_EXPOSURE, -0.30f * strength)
            draft.add(Param.SKY_SATURATION, 0.35f * strength)
        }
        if (deep) {
            draft.add(Param.SKY_SATURATION, 0.28f * strength)
            draft.add(Param.SKY_BLUE, 0.45f * strength)
            return draft.build()
        }
        // "깊게"가 아니면 색 이름만으로도 하늘을 조금 진하게 한다.
        draft.see(window, SKY_BLUE_UP)
        draft.add(Param.SKY_BLUE, 0.55f * strength)
        draft.add(Param.SKY_SATURATION, 0.20f * strength)
        return draft.build()
    }

    /** One colour band's saturation, plus a luminance nudge when the wording asks for it. */
    private fun colorDelta(band: HslBand, window: String, strength: Float, draft: Draft): RecipeDelta? {
        val down = DOWN_MARKERS.any { window.contains(it) }
        val base = 0.36f * strength
        if (down) {
            draft.see(window, DOWN_MARKERS)
            draft.add(band.saturation, -base)
        } else {
            draft.see(window, SAT_UP)
            draft.add(band.saturation, base)
        }
        if (draft.see(window, BRIGHT_UP)) draft.add(band.luminance, 0.16f * strength)
        if (draft.see(window, listOf("어둡", "어두", "가라앉"))) draft.add(band.luminance, -0.16f * strength)
        // 붉게·노랗게처럼 색조를 직접 말하면 그 방향으로 조금 돌린다.
        if (draft.see(window, listOf("붉게", "붉어", "빨갛게"))) draft.add(band.hue, 0.30f * strength)
        if (draft.see(window, listOf("노랗게", "노래지"))) draft.add(band.hue, -0.20f * strength)
        return draft.build()
    }

    // ── 문맥 ──────────────────────────────────────────────────────────────

    /** The stretch of the sentence that belongs to one mention, bounded by neighbours and commas. */
    private fun windowFor(text: String, mentions: List<Mention>, index: Int): Span {
        val mention = mentions[index]
        // 같은 대상(같은 색·같은 영역)을 가리키는 이웃 낱말은 한 지시로 묶는다:
        // "초록색 나뭇잎 차분하게"의 낱말들이 서로의 창을 잘라 먹지 않게.
        var first = index
        while (first > 0 && sameTarget(mentions[first - 1], mention)) first--
        var last = index
        while (last + 1 < mentions.size && sameTarget(mentions[last + 1], mention)) last++
        val prevEnd = if (first > 0) mentions[first - 1].end else 0
        val nextStart = if (last + 1 < mentions.size) mentions[last + 1].start else text.length
        val clauseStart = lastBreak(text, mention.start) + 1
        val clauseEnd = nextBreak(text, mention.end)
        val start = maxOf(prevEnd, clauseStart).coerceIn(0, mention.start)
        val end = minOf(nextStart, clauseEnd).coerceIn(mention.end, text.length)
        return Span(start, end)
    }

    private fun sameTarget(a: Mention, b: Mention): Boolean =
        (a.region != null && a.region == b.region) || (a.band != null && a.band == b.band)

    private data class Span(val start: Int, val end: Int)

    private fun lastBreak(text: String, before: Int): Int {
        var at = -1
        for (i in 0 until before.coerceAtMost(text.length)) {
            if (text[i] in CLAUSE_BREAKS) at = i
        }
        return at
    }

    private fun nextBreak(text: String, after: Int): Int {
        for (i in after.coerceAtLeast(0) until text.length) {
            if (text[i] in CLAUSE_BREAKS) return i
        }
        return text.length
    }

    /** True when a sky mention sits right beside this colour mention ("하늘 파란색"). */
    private fun skyMentionBeside(text: String, mentions: List<Mention>, index: Int): Boolean {
        val mention = mentions[index]
        for (offset in listOf(-1, 1)) {
            val other = mentions.getOrNull(index + offset) ?: continue
            if (other.region != Region.SKY) continue
            val gap = if (offset < 0) mention.start - other.end else other.start - mention.end
            if (gap in -1..6) return true
        }
        // 같은 절 안에 하늘이 있으면 그 색은 하늘을 말한다.
        val clauseStart = lastBreak(text, mention.start) + 1
        val clauseEnd = nextBreak(text, mention.end)
        return mentions.any {
            it.region == Region.SKY && it.start in clauseStart until clauseEnd
        }
    }

    // ── 낱말 찾기 ─────────────────────────────────────────────────────────

    private fun forEachOccurrence(text: String, word: String, action: (Int) -> Unit) {
        if (word.isEmpty()) return
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return
            action(at)
            from = at + 1
        }
    }

    /** "인물 사진", "배경 컷" — the noun is naming a genre, so it is not a region scope. */
    private fun genreFollows(text: String, end: Int): Boolean {
        if (end > text.length) return false
        val tail = text.substring(end, minOf(text.length, end + 3)).trimStart()
        return GENRE_SUFFIX.any { tail.startsWith(it) }
    }

    /** "인물 뽀얗게"는 룩 이름, "인물만 화사하게"·"배경 채도"는 영역 지시. */
    private fun isRegionScope(text: String, end: Int, region: Region): Boolean {
        if (region == Region.SKY) return true
        if (end > text.length) return false
        val tail = text.substring(end, minOf(text.length, end + 4)).trimStart()
        if (tail.startsWith("만")) return true
        return QUALITY_NOUNS.any { tail.startsWith(it) }
    }

    /** "노랗지 않게", "하늘이 날아가지 않게" — 부정은 영역·색 지시가 아니라 제한이다. */
    private fun negatedNear(text: String, end: Int): Boolean {
        if (end > text.length) return false
        val tail = text.substring(end, minOf(text.length, end + 12))
        return NEGATION_TAIL.any { tail.contains(it) }
    }

    /** True when a longer catalog phrase ("틸앤오렌지", "피부 맑게") covers this stretch. */
    private fun insideCatalogPhrase(text: String, at: Int, length: Int): Boolean {
        val end = at + length
        for ((phrase, _) in IntentCatalog.phraseIndex) {
            if (phrase.length <= length) continue
            var from = 0
            while (true) {
                val start = text.indexOf(phrase, from)
                if (start < 0) break
                if (start <= at && start + phrase.length >= end) return true
                from = start + 1
            }
        }
        return false
    }

    private fun mark(consumed: BooleanArray, start: Int, length: Int) {
        for (i in start until (start + length).coerceAtMost(consumed.size)) consumed[i] = true
    }

    private fun markTokens(consumed: BooleanArray, text: String, window: Span, tokens: List<String>) {
        for (token in tokens.distinct()) {
            var from = window.start
            while (true) {
                val at = text.indexOf(token, from)
                if (at < 0 || at + token.length > window.end) break
                mark(consumed, at, token.length)
                from = at + token.length
            }
        }
    }

    private fun overlaps(consumed: BooleanArray, start: Int, length: Int): Boolean {
        for (i in start until (start + length).coerceAtMost(consumed.size)) {
            if (consumed[i]) return true
        }
        return false
    }
}
