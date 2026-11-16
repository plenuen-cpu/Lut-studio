package com.plenuen.lumen

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.core.RecipeSession
import com.plenuen.lumen.nl.NaturalLanguageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history is now a stack of layers rather than a stack of pictures, so that one step can be
 * lifted out while the others stay. These tests pin that behaviour down: removing the middle of
 * three steps leaves the first and third exactly as they were, the bottom line cannot be removed,
 * and a mistaken deletion can be undone.
 */
class HistoryLayersTest {

    private fun session(): RecipeSession {
        val session = RecipeSession()
        session.apply("따뜻하게", RecipeDelta.add("따뜻하게", Param.TEMPERATURE, 0.25f))
        session.apply("필름처럼", RecipeDelta.of(
            "필름처럼",
            RecipeDelta.Op.Add(Param.GRAIN, 0.3f),
            RecipeDelta.Op.Add(Param.FADE, 0.2f),
        ))
        session.apply("수평", RecipeDelta.set("수평", Param.STRAIGHTEN_DEG, 2.0f))
        return session
    }

    @Test
    fun removingTheMiddleStepKeepsItsNeighbours() {
        val session = session()
        val steps = session.steps()
        assertEquals("원본 + 3단계", 4, steps.size)
        val warm = steps[1]
        assertEquals("따뜻하게", warm.title)
        val film = steps[2]
        assertEquals("필름처럼", film.title)

        val warmBefore = session.current[Param.TEMPERATURE]
        val straightBefore = session.current[Param.STRAIGHTEN_DEG]
        assertTrue(warmBefore > 0.2f)
        assertTrue(session.current[Param.GRAIN] > 0f)

        session.remove(film.id)

        assertEquals("앞 단계(따뜻하게)는 그대로", warmBefore, session.current[Param.TEMPERATURE], 1e-4f)
        assertEquals("뒤 단계(수평)도 그대로", straightBefore, session.current[Param.STRAIGHTEN_DEG], 1e-4f)
        assertEquals("가운데 단계(필름)만 빠진다", 0f, session.current[Param.GRAIN], 1e-4f)
        assertEquals(0f, session.current[Param.FADE], 1e-4f)
        assertEquals("목록에서도 사라진다", 3, session.steps().size)
        assertFalse(session.steps().any { it.id == film.id })
    }

    @Test
    fun removingAnAbsoluteStepLeavesTheDeltaStepsIntact() {
        val session = session()
        session.replace(
            session.current.with(Param.LUT_INDEX, 12f).with(Param.VIBRANCE, 0.2f),
            "룩 Portra",
        )
        val look = session.steps().last()
        assertEquals("룩 Portra", look.title)
        val warm = session.current[Param.TEMPERATURE]

        session.remove(look.id)

        assertEquals("룩은 지워진다", -1f, session.current[Param.LUT_INDEX], 1e-4f)
        assertEquals("활기도 되돌아간다", 0f, session.current[Param.VIBRANCE], 1e-4f)
        assertEquals("말로 한 보정은 그대로", warm, session.current[Param.TEMPERATURE], 1e-4f)
        assertTrue(session.current[Param.GRAIN] > 0f)
        assertEquals(2.0f, session.current[Param.STRAIGHTEN_DEG], 1e-4f)
    }

    @Test
    fun theBottomLineCannotBeRemoved() {
        val session = session()
        val origin = session.steps().first()
        assertTrue(origin.isOrigin)
        val before = session.current

        session.remove(origin.id)

        assertEquals("원본은 남는다", before, session.current)
        assertEquals(4, session.steps().size)
        assertFalse(session.canRemove(origin.id))
    }

    @Test
    fun undoTakesBackAWrongDeletion() {
        val session = session()
        val film = session.steps()[2]
        val before = session.current
        session.remove(film.id)
        assertTrue("지운 직후에는 필름 보정이 없다", session.current[Param.GRAIN] == 0f)
        assertTrue(session.canUndo)

        session.undo()

        assertEquals("되돌리면 원래대로", before[Param.GRAIN], session.current[Param.GRAIN], 1e-4f)
        assertTrue(session.steps().any { it.id == film.id })
        assertTrue("다시 실행할 수 있다", session.canRedo)

        session.redo()
        assertEquals("다시 지운다", 0f, session.current[Param.GRAIN], 1e-4f)
    }

    @Test
    fun everyStepHoldsOnlyItsOwnChange() {
        val session = RecipeSession()
        session.apply("따뜻하게", RecipeDelta.add("따뜻하게", Param.TEMPERATURE, 0.3f))
        session.apply("차갑게", RecipeDelta.add("차갑게", Param.TEMPERATURE, -0.1f))
        val steps = session.steps()
        assertEquals(0.3f, steps[1].recipe[Param.TEMPERATURE], 1e-4f)
        assertEquals(0.2f, steps[2].recipe[Param.TEMPERATURE], 1e-4f)

        // 첫 단계를 지우면 남은 '차갑게'가 -0.1부터 다시 합산된다.
        session.remove(steps[1].id)
        assertEquals(-0.1f, session.current[Param.TEMPERATURE], 1e-4f)
    }

    @Test
    fun resetClearsEveryLayerButIsUndoable() {
        val session = session()
        assertFalse(session.current.isNeutral)
        session.reset()
        assertTrue(session.current.isNeutral)
        assertEquals("목록에는 원본만 남는다", 1, session.steps().size)
        session.undo()
        assertTrue("되돌리면 다시 보정이 살아난다", session.current[Param.GRAIN] > 0f)
    }

    @Test
    fun halvingAnAbsoluteStepIsUndoableToo() {
        val session = RecipeSession()
        session.replace(EditRecipe.NEUTRAL.with(Param.EXPOSURE, 0.4f), "노출")
        session.halveLast()
        assertEquals("절반으로 줄어든다", 0.2f, session.current[Param.EXPOSURE], 1e-4f)
        session.undo()
        assertEquals("되돌리면 원래 값", 0.4f, session.current[Param.EXPOSURE], 1e-4f)
    }

    @Test
    fun stepIdentitiesStayStableWhileStepsAreAdded() {
        val session = session()
        val ids = session.steps().map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        session.apply("더 따뜻하게", RecipeDelta.add("더 따뜻하게", Param.TEMPERATURE, 0.1f))
        val after = session.steps().map { it.id }
        assertEquals("기존 스텝의 id는 그대로", ids, after.take(ids.size))
        assertTrue(after.size == ids.size + 1)
    }

    // ── 말로 하는 자동 보정 ────────────────────────────────────────────────

    @Test
    fun autoBeautifyRequestsAreUnderstood() {
        listOf(
            "자동 보정",
            "자동보정 해줘",
            "알아서 보정해줘",
            "NIMA 높여줘",
            "니마 점수 올려줘",
            "점수 잘 나오게 해줘",
            "알아서 예쁘게",
        ).forEach { sentence ->
            val parsed = NaturalLanguageParser.parse(sentence)
            assertTrue("nimaAuto for '$sentence'", parsed.nimaAuto)
        }

        assertFalse(NaturalLanguageParser.parse("따뜻하게").nimaAuto)
        assertFalse(NaturalLanguageParser.parse("수평 맞춰줘").nimaAuto)
        assertFalse(NaturalLanguageParser.parse("필름처럼").nimaAuto)
    }

    @Test
    fun autoRequestLeavesTheWordsItRead() {
        val parsed = NaturalLanguageParser.parse("자동 보정")
        assertTrue(parsed.matched)
        assertNotNull(parsed.summary)
        assertEquals("", parsed.unmatched.trim())
    }
}
