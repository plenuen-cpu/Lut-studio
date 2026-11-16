package com.plenuen.lumen

import com.plenuen.lumen.core.EditNarrator
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.core.RecipeSession
import com.plenuen.lumen.nl.NaturalLanguageParser
import com.plenuen.lumen.nl.PromptEngine
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole prompt path, from the sentence the user types to the state the editor renders.
 *
 * The view model delegates this work to [PromptEngine], so these tests run the same code the phone
 * runs — including the parts that produce the card, the chips and the leftover hand-off to the local
 * model. Reaching the end without an exception is itself the assertion: a request must never be able
 * to take the editor down, whatever the sentence.
 */
class PromptSubmitTest {

    /** The chips above the prompt bar; every one of them has to work with one tap. */
    private val quickPrompts = listOf(
        "자동 보정", "NIMA 높여줘", "부드럽게", "따뜻하게", "영화처럼", "필름처럼", "청량하게",
        "흑백으로", "예쁘게", "샤하게", "꾸안꾸", "포인트 있게", "눈에 띄게", "너무 화려하지는 않게",
        "수평 맞춰줘", "인물만 화사하게", "배경만 어둡게", "하늘만 파랗게", "빨강만 살리고 초록은 차분하게",
    )

    /** One run of the engine plus the state bookkeeping the view model does around it. */
    private data class Run(
        val result: PromptEngine.Result,
        val note: String?,
        val caption: String?,
        val message: String?,
        val chips: List<String>,
    )

    private fun submit(
        session: RecipeSession,
        text: String,
        chips: List<String> = emptyList(),
        scene: com.plenuen.lumen.nl.SceneStats = com.plenuen.lumen.nl.SceneStats.UNKNOWN,
    ): Run {
        val result = PromptEngine.apply(text, session, scene) { null }
        val note = when (result.noteAction) {
            PromptEngine.NoteAction.CLEAR -> null
            PromptEngine.NoteAction.REPLACE -> result.noteText
            PromptEngine.NoteAction.KEEP -> "이전 카드"
        }
        val message = result.message
        return Run(
            result = result,
            note = note,
            caption = if (result.noteAction == PromptEngine.NoteAction.REPLACE) result.noteCaption else null,
            message = message,
            chips = (chips + result.labels).distinct().takeLast(8),
        )
    }

    private fun assertSessionSane(session: RecipeSession) {
        val steps = session.steps()
        assertTrue("history always keeps its bottom line", steps.isNotEmpty())
        assertTrue("bottom line is the origin", steps.first().isOrigin)
        val ids = steps.map { it.id }
        assertEquals("step ids are unique (they are LazyRow keys)", ids.size, ids.distinct().size)
        for (param in Param.entries) {
            val value = session.current[param]
            assertTrue("${param.key} stays finite", value.isFinite())
            assertTrue("${param.key} stays in range", value >= param.min - 1e-3f && value <= param.max + 1e-3f)
        }
    }

    // ── 빠른 요청 칩 ────────────────────────────────────────────────────────

    @Test
    fun everyQuickPromptCompletes() {
        for (prompt in quickPrompts) {
            val session = RecipeSession()
            val run = submit(session, prompt)
            assertSessionSane(session)
            if (prompt == "자동 보정" || prompt == "NIMA 높여줘") {
                assertTrue("$prompt → NIMA 경로", run.result.nimaAuto)
                continue
            }
            assertTrue("$prompt → 알아들었다", run.result.applied)
            assertNotNull("$prompt → 칩", run.result.labels.isNotEmpty().takeIf { it }?.let { run.result.labels })
            // 카드가 없으면 스낵바가, 카드가 있으면 카드가 무슨 일이 있었는지 말해 준다.
            assertTrue(
                "$prompt → 사용자에게 알린다",
                run.note != null || run.message != null,
            )
        }
    }

    @Test
    fun spokenRequestFromARealUserCompletes() {
        val text = "거기 깔끔하면서도 포인트가 딱 되게요. 그거 아시죠? 거기에 확 피어나는… " +
            "눈길을 딱 끌면서도 그거 있잖아요. 근데 너무 화려하지는 않은… 멀리서 봐도 딱 눈에 띄었으면"
        val session = RecipeSession()
        val run = submit(session, text)
        assertSessionSane(session)
        assertTrue("알아들은 부분이 있다", run.result.applied || run.result.nimaAuto)
        assertNotEquals("레시피가 움직였다", EditRecipe.NEUTRAL, session.current)
    }

    // ── 연속 입력: 히스토리·되돌리기 ─────────────────────────────────────────

    @Test
    fun aChainOfRequestsKeepsTheHistoryConsistent() {
        val session = RecipeSession()
        var chips = emptyList<String>()
        var lastNote: String? = null
        for (prompt in listOf("따뜻하게", "부드럽게", "채도 올려줘", "수평 맞춰줘", "되돌리기", "다시 실행", "흑백으로")) {
            val run = submit(session, prompt, chips)
            chips = run.chips
            if (run.result.applied) assertSessionSane(session)
            run.note?.let { lastNote = it }
        }
        assertTrue("카드가 한 번은 나왔다", lastNote != null)
        assertTrue("칩이 쌓인다", chips.isNotEmpty())
        assertTrue("칩은 8개까지만", chips.size <= 8)
    }

    /**
     * Removing a chip in the middle is the v2.2.0 feature that touches the same history the prompt
     * path writes to, so the two are exercised together.
     */
    @Test
    fun removingAStepAfterPromptsKeepsTheRest() {
        val session = RecipeSession()
        submit(session, "따뜻하게")
        submit(session, "부드럽게")
        submit(session, "채도 올려줘")
        val steps = session.steps()
        assertTrue("단계가 쌓였다", steps.size >= 3)
        val middle = steps[steps.size - 2]
        session.remove(middle.id)
        assertSessionSane(session)
        assertFalse("지운 단계는 사라진다", session.steps().any { it.id == middle.id })
        session.undo()
        assertSessionSane(session)
        assertTrue("되돌리면 살아난다", session.steps().any { it.id == middle.id })
    }

    // ── 대화 제어 ─────────────────────────────────────────────────────────

    @Test
    fun controlSentencesDoNotPretendToBeNewCorrections() {
        val session = RecipeSession()
        submit(session, "따뜻하게")
        val undo = submit(session, "되돌리기")
        assertEquals("되돌리기는 카드를 지운다", PromptEngine.NoteAction.CLEAR, undo.result.noteAction)
        assertNull("되돌리기에 카드 없음", undo.note)
        assertSessionSane(session)

        submit(session, "따뜻하게")
        val reset = submit(session, "초기화")
        assertEquals(PromptEngine.NoteAction.REPLACE, reset.result.noteAction)
        assertEquals("초기화", reset.caption)
        assertTrue(EditRecipe.NEUTRAL.isNeutral)
        assertTrue("초기화 뒤에는 중립", session.current.isNeutral)
    }

    // ── 문장 하나가 모든 파라미터를 건드려도 ─────────────────────────────────

    @Test
    fun everyParameterCanBeNarrated() {
        val before = EditRecipe.NEUTRAL
        for (param in Param.entries) {
            if (param == Param.LUT_INDEX) continue
            val bumped = before.with(param, param.default + (param.max - param.default) * 0.4f)
            if (bumped == before) continue
            // 문장 생성이 던지지 않아야 하고, 스위치가 아닌 값은 반드시 말이 되어야 한다.
            val sentence = EditNarrator.describe(before, bumped)
            // 스위치(켜고 끄는 값)와 색상 번호는 눈에 보이는 "양"이 아니라 문장으로 말하지 않는다.
            val silent = param == Param.AUTO_STRAIGHTEN || param == Param.MASK_ON ||
                param == Param.SPLIT_SHADOW_HUE || param == Param.SPLIT_HIGHLIGHT_HUE
            if (silent) {
                assertNull("${param.key} has no amount to announce", sentence)
            } else {
                assertNotNull("${param.key} has wording", sentence)
                assertTrue("${param.key} wording is not blank", sentence!!.isNotBlank())
            }
        }
    }

    @Test
    fun everyParameterDeltaReachesTheSession() {
        for (param in Param.entries) {
            if (param == Param.AUTO_STRAIGHTEN || param == Param.MASK_ON) continue
            val session = RecipeSession()
            val target = RecipeDelta.Op.Add(param, (param.max - param.default) * 0.3f)
            session.apply(param.label, RecipeDelta("t", listOf(target)))
            assertSessionSane(session)
            assertTrue("${param.key} moved", session.current[param] != param.default ||
                kotlin.math.abs(param.max - param.default) < 1e-4f)
        }
    }

    // ── 무작위 문장: 어떤 입력도 앱을 죽이지 않는다 ───────────────────────────

    @Test
    fun randomSentencesNeverThrow() {
        val alphabet = (('가'..'힣').toList() + ('a'..'z') + ('0'..'9') +
            listOf(' ', ' ', ' ', ',', '.', '!', '?', '~', '…', '·', '°', '&', '+', '-', '\n', '\t', '\u0000'))
        val random = Random(20261116)
        val session = RecipeSession()
        var completed = 0
        repeat(4000) { round ->
            val length = random.nextInt(0, 40)
            val text = buildString {
                repeat(length) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
            val run = submit(session, text)
            assertSessionSane(session)
            // 칩·카드 문자열은 언제나 그릴 수 있어야 한다.
            run.chips.forEach { assertFalse(it.isEmpty()) }
            run.note?.let { assertTrue(it.isNotBlank()) }
            completed++
            if (round % 500 == 0) session.reset()
        }
        assertEquals(4000, completed)
    }

    @Test
    fun pathologicalSentencesAreStillSafe() {
        val session = RecipeSession()
        val long = "따뜻하게 ".repeat(400)
        val cases = listOf(
            "",
            " ",
            "\n\n",
            ".",
            "0도",
            "-3도",
            "99999999도",
            "ㅋㅋㅋㅋㅋㅋ",
            "인물인물인물인물",
            "빨강빨강빨강",
            "수평수평수평수평",
            long,
            long + "빨강",
            "\u0000\u0001\u0002",
            "😀😀😀",
            "더",
            "덜",
            "취소",
            "비교",
            "절반",
            "한 번 더",
            "초기화",
        )
        for (text in cases) {
            submit(session, text)
            assertSessionSane(session)
        }
    }

    @Test
    fun leftoverIsOnlyHandedToTheModelWhenItCarriesMeaning() {
        assertNull(PromptEngine.worthAsking(""))
        assertNull(PromptEngine.worthAsking("   "))
        assertNull("한 낱말짜리 꼬리는 이미 읽은 문장의 일부", PromptEngine.worthAsking("피부"))
        assertNull(PromptEngine.worthAsking("하늘"))
        assertNotNull("두 낱말이면 물어본다", PromptEngine.worthAsking("저녁 골목"))
        assertNotNull("세 글자면 물어본다", PromptEngine.worthAsking("네온사인"))
    }

    @Test
    fun unknownSentenceFallsBackToTheModelPath() {
        val session = RecipeSession()
        val run = submit(session, "zzz qqq vvv")
        assertFalse("규칙이 읽지 못했다", run.result.applied)
        assertEquals("규칙이 아무것도 바꾸지 않는다", EditRecipe.NEUTRAL, session.current)
    }

    @Test
    fun parserStillProducesAConfidenceForEveryOutcome() {
        for (prompt in quickPrompts) {
            val parsed = NaturalLanguageParser.parse(prompt)
            if (parsed.matched) {
                assertTrue("$prompt confidence", parsed.confidence in 0f..1f)
                assertTrue("$prompt confidence > 0", parsed.confidence > 0f)
            }
        }
    }
}
