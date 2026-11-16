package com.plenuen.lumen.nl

import com.plenuen.lumen.core.EditNarrator
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.core.RecipeSession
import com.plenuen.lumen.data.LutPreset

/**
 * Everything a typed sentence does to a [RecipeSession], with no Android and no UI in the way.
 *
 * The view model used to hold this whole block inline, which meant the one path that runs on every
 * request could only be checked by hand on a phone. Kept here it is plain Kotlin: the code the app
 * runs is the code the unit tests (and the fuzz pass over odd sentences) exercise, so a request that
 * would throw is caught in the test run rather than on the user's phone.
 *
 * The engine only *decides*; the caller still owns the state flow, the local model probe, the NIMA
 * re-measurement and the async straighten pass.
 */
object PromptEngine {

    /** Where the "방금 한 보정" card ends up after this sentence. */
    enum class NoteAction {
        /** Show a new card with [Result.noteText]. */
        REPLACE,

        /** Drop the card (undo/redo are not new corrections). */
        CLEAR,

        /** Leave whatever card is already on screen. */
        KEEP,
    }

    data class Result(
        /** Something in the sentence actually moved the recipe (or was understood). */
        val applied: Boolean = false,
        /** Short labels for the history chips and the snackbar ("채도 18%", "인물 보정"). */
        val labels: List<String> = emptyList(),
        val noteAction: NoteAction = NoteAction.KEEP,
        val noteText: String? = null,
        val noteCaption: String = "방금 한 보정",
        /** A film look the sentence picked, so the caller can decode its LUT bytes. */
        val lutPreset: LutPreset? = null,
        /** The part of the sentence the rules did not read, when it is worth asking the model. */
        val leftoverForLlm: String? = null,
        /** "자동 보정" / "점수 높여줘": the caller should run the NIMA pass instead. */
        val nimaAuto: Boolean = false,
        /** "수평 맞춰줘": the caller should run the tilt estimate. */
        val autoStraightenRequested: Boolean = false,
        /** "비교": show the original for a moment. */
        val compareRequested: Boolean = false,
        /** A region directive arrived before the mask was ready. */
        val wantsRegionMask: Boolean = false,
        /** Recipe the sentence started from, for the model path's narration. */
        val before: EditRecipe = EditRecipe.NEUTRAL,
        /** Snackbar text to show when the card was not replaced. */
        val message: String? = null,
        /** Normalised sentence, for logs. */
        val normalized: String = "",
    )

    /**
     * Applies [text] to [session] (which it mutates) and reports what happened.
     *
     * @param scene the photo's measurements, used when a spoken look has to be planned against it.
     * @param lutFinder resolves a spoken look name ("포트라") to a preset in the library.
     */
    fun apply(
        text: String,
        session: RecipeSession,
        scene: SceneStats = SceneStats.UNKNOWN,
        lutFinder: (String) -> LutPreset? = { null },
    ): Result {
        val before = session.current
        val parsed = NaturalLanguageParser.parse(text)
        if (parsed.nimaAuto) {
            return Result(nimaAuto = true, before = before, normalized = parsed.normalized)
        }

        var applied = false
        var appliedLutName: String? = null
        var lutPreset: LutPreset? = null
        val labels = mutableListOf<String>()
        var compareRequested = false

        // 1) 대화 제어
        when (parsed.control) {
            NaturalLanguageParser.Control.UNDO -> {
                session.undo()
                applied = true
                labels += "되돌림"
            }
            NaturalLanguageParser.Control.REDO -> {
                session.redo()
                applied = true
                labels += "다시 실행"
            }
            NaturalLanguageParser.Control.RESET -> {
                session.reset()
                applied = true
                labels += "초기화"
            }
            NaturalLanguageParser.Control.HALF -> {
                session.halveLast()
                applied = true
                labels += "절반으로"
            }
            NaturalLanguageParser.Control.MORE -> {
                session.repeatLast(1f)
                applied = true
                labels += "한 번 더"
            }
            NaturalLanguageParser.Control.LESS -> {
                session.repeatLast(-0.5f)
                applied = true
                labels += "조금 덜"
            }
            NaturalLanguageParser.Control.COMPARE -> {
                applied = true
                compareRequested = true
                labels += "원본 비교"
            }
            null -> {}
        }

        // 2) 수평·수직: 각도가 적혀 있으면 여기서 바로, "수평 맞춰줘"면 기울기 추정을 맡긴다.
        if (parsed.autoStraighten) {
            applied = true
            labels += "수평·수직 맞춤"
        }
        parsed.straightenDeg?.let { degrees ->
            session.apply(
                "수평 ${"%.1f".format(degrees)}°",
                RecipeDelta.set("수평", Param.STRAIGHTEN_DEG, degrees),
            )
            applied = true
            labels += "수평 ${"%.1f".format(degrees)}°"
        }

        // 3) 룩 의도
        for (hit in parsed.hits) {
            val delta = hit.intent.aesthetic?.let { profile ->
                AestheticPlanner.plan(profile, scene)
            } ?: hit.intent.delta
            val strength = parsed.strengthOf(hit)
            if (!delta.isEmpty && strength != 0f) {
                session.apply(hit.intent.label, delta, strength)
                applied = true
                labels += "${hit.intent.label} ${(strength * 100).toInt()}%"
            }
        }

        // 3-1) 문장이 건 제한 ("너무 화려하지는 않게", "그레인 빼고", "부드럽게 말고")
        if (parsed.restraints.isNotEmpty()) {
            val label = parsed.restraints.joinToString(" · ") { it.restraint.label }
            var next = session.current
            for (hit in parsed.restraints) {
                hit.restraint.delta?.let { delta -> next = next.apply(delta, parsed.strengthScale) }
            }
            next = parsed.restrained(next)
            if (next != session.current) session.replace(next, label)
            // 이미 그런 상태라 바뀐 값이 없어도 문장은 알아들은 것이므로 모델로 넘기지 않는다.
            applied = true
            labels += label
        }

        // 3-2) 영역·색상 지시 ("인물만 화사하게", "빨강만 살리고 초록은 차분하게")
        for (hit in parsed.regionHits) {
            session.apply(hit.label, hit.delta, 1f)
            labels += hit.label
            applied = true
        }
        for (hit in parsed.colorHits) {
            session.apply(hit.label, hit.delta, 1f)
            labels += hit.label
            applied = true
        }

        // 4) LUT 지정
        parsed.lutQuery?.let { query ->
            val preset = runCatching { lutFinder(query) }.getOrNull()
            if (preset != null) {
                val intensity = parsed.lutIntensity ?: session.current[Param.LUT_INTENSITY]
                val recipe = session.current
                    .with(Param.LUT_INDEX, preset.idx.toFloat())
                    .with(Param.LUT_INTENSITY, intensity)
                session.replace(recipe, "룩 ${preset.display}")
                applied = true
                appliedLutName = preset.display
                lutPreset = preset
                labels += preset.display
            }
        }

        if (!applied) {
            return Result(
                applied = false,
                compareRequested = compareRequested,
                autoStraightenRequested = parsed.autoStraighten,
                before = before,
                normalized = parsed.normalized,
            )
        }

        val after = session.current
        val sentence = EditNarrator.describe(before, after, appliedLutName)
        val noteAction = when (parsed.control) {
            // 되돌리기는 새 보정이 아니라서 "방금 한 보정" 카드를 지운다.
            NaturalLanguageParser.Control.UNDO,
            NaturalLanguageParser.Control.REDO,
            -> NoteAction.CLEAR
            NaturalLanguageParser.Control.COMPARE -> NoteAction.KEEP
            NaturalLanguageParser.Control.RESET -> NoteAction.REPLACE
            else -> if (sentence != null) NoteAction.REPLACE else NoteAction.KEEP
        }
        val isReset = parsed.control == NaturalLanguageParser.Control.RESET
        val noteText = when {
            noteAction != NoteAction.REPLACE -> null
            isReset -> "모든 보정을 지우고 원본으로 되돌렸어요."
            else -> sentence
        }
        val summary = labels.joinToString(" · ").ifBlank { null }

        return Result(
            applied = true,
            labels = labels.toList(),
            noteAction = noteAction,
            noteText = noteText,
            noteCaption = if (isReset) "초기화" else "방금 한 보정",
            lutPreset = lutPreset,
            leftoverForLlm = worthAsking(parsed.unmatched),
            autoStraightenRequested = parsed.autoStraighten,
            compareRequested = compareRequested,
            wantsRegionMask = parsed.regionHits.isNotEmpty(),
            before = before,
            // 카드가 같은 내용을 이미 보여 주므로 스낵바는 카드가 없을 때만 띄운다.
            message = if (noteAction == NoteAction.KEEP) summary else null,
            normalized = parsed.normalized,
        )
    }

    /**
     * The rules left this much of the sentence unread; ask the model only when it carries meaning.
     *
     * 한 낱말짜리 꼬리("피부", "하늘")는 이미 읽은 문장의 일부다. 두 낱말 이상이거나 세 글자
     * 이상 남았을 때만 모델에게 물어, 알아들은 문장을 다시 해석하게 만들지 않는다.
     */
    fun worthAsking(leftover: String): String? {
        val text = leftover.trim()
        if (text.isEmpty()) return null
        return if (text.split(' ').size >= 2 || text.length >= 3) text else null
    }
}
