package com.plenuen.lumen

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.nima.NimaGuardrails
import com.plenuen.lumen.nima.NimaOptimizer
import com.plenuen.lumen.nima.NimaScores
import com.plenuen.lumen.nima.RecipeScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic pass has to be judged by the score it is told to maximise, so these tests give it a
 * scorer whose answers are known: a formula that prefers a warmer exposure, or saturation without
 * limit, or nothing at all. What is checked is the search's own behaviour — does it pick the
 * candidate with the higher score, does it stay inside the guardrails, does it leave a good photo
 * alone, and does it keep to its evaluation budget.
 */
class NimaOptimizerTest {

    @Test
    fun picksTheCandidateThatScoresHigher() {
        // 노출과 대비를 올리면 점수가 오르는 사진.
        val scorer = RecipeScorer { recipe ->
            5f + 0.6f * recipe[Param.EXPOSURE] + 0.4f * recipe[Param.CONTRAST]
        }
        val result = NimaOptimizer.optimize(EditRecipe.NEUTRAL, null, scorer)

        assertTrue("점수가 올라야 한다", result.improved)
        assertTrue("노출을 올리는 쪽을 골라야 한다", result.recipe[Param.EXPOSURE] > 0.05f)
        assertEquals("시작 점수", 5f, result.before!!, 0.001f)
        assertTrue("끝 점수가 시작보다 높아야 한다", result.after!! > result.before!!)
        assertEquals("변화량과 점수 차이가 같아야 한다", result.after!! - result.before!!, result.gain, 0.001f)

        val exposure = result.changes.firstOrNull { it.param == Param.EXPOSURE }
        assertNotNull("노출 변경이 내역에 있어야 한다", exposure)
        assertTrue(exposure!!.delta > 0f)
        assertEquals(Param.EXPOSURE, result.changes.first().param)
    }

    @Test
    fun walksDownWhenTheLowerValueScoresBetter() {
        val scorer = RecipeScorer { recipe -> 5f - 2f * recipe[Param.EXPOSURE] }
        val result = NimaOptimizer.optimize(EditRecipe.NEUTRAL, null, scorer)
        assertTrue(result.improved)
        assertTrue("어두운 쪽이 높으면 노출을 내려야 한다", result.recipe[Param.EXPOSURE] < -0.05f)
    }

    @Test
    fun leavesAGoodPhotoAlone() {
        // 어떤 후보든 같은 점수 — 건드릴 이유가 없다.
        val scorer = RecipeScorer { 5.2f }
        val base = EditRecipe.of(Param.EXPOSURE to 0.1f, Param.VIBRANCE to 0.05f)
        val result = NimaOptimizer.optimize(base, null, scorer)

        assertFalse("바꿀 것이 없으면 그대로 둔다", result.improved)
        assertTrue(result.changes.isEmpty())
        assertEquals("레시피가 그대로여야 한다", base, result.recipe)
        assertEquals(5.2f, result.after!!, 0.001f)
    }

    @Test
    fun aGainSmallerThanTheThresholdIsNotApplied() {
        // 0.01만 오르는 후보는 minGain(0.02)에 못 미친다.
        val scorer = RecipeScorer { recipe ->
            if (recipe[Param.EXPOSURE] > 0.05f) 5.01f else 5f
        }
        val result = NimaOptimizer.optimize(EditRecipe.NEUTRAL, null, scorer)
        assertFalse(result.improved)
        assertEquals(EditRecipe.NEUTRAL, result.recipe)
    }

    @Test
    fun neverPushesAParameterPastItsGuardrail() {
        // 모델이 채도를 무한히 좋아한다고 해도 사용자의 사진을 망치지 않는다.
        val scorer = RecipeScorer { recipe -> 5f + 10f * recipe[Param.SATURATION] }
        val result = NimaOptimizer.optimize(EditRecipe.NEUTRAL, null, scorer)

        val limit = NimaGuardrails.limitOf(Param.SATURATION)
        assertTrue("채도는 가드레일 안에서만 오른다 (${result.recipe[Param.SATURATION]})",
            result.recipe[Param.SATURATION] <= limit + 1e-4f)
        assertTrue(result.recipe[Param.SATURATION] > 0f)
    }

    @Test
    fun keepsTheUserLookAndMasksOutOfTheSearch() {
        val base = EditRecipe.of(
            Param.LUT_INDEX to 12f,
            Param.LUT_INTENSITY to 0.8f,
            Param.STRAIGHTEN_DEG to 1.5f,
            Param.SUBJECT_EXPOSURE to 0.2f,
        )
        val scorer = RecipeScorer { recipe -> 6f + recipe[Param.LUT_INDEX] + recipe[Param.SUBJECT_EXPOSURE] }
        val result = NimaOptimizer.optimize(base, null, scorer)

        assertEquals("룩 선택은 그대로", 12f, result.recipe[Param.LUT_INDEX], 0.001f)
        assertEquals("수평도 그대로", 1.5f, result.recipe[Param.STRAIGHTEN_DEG], 0.001f)
        assertEquals("영역 보정도 그대로", 0.2f, result.recipe[Param.SUBJECT_EXPOSURE], 0.001f)
    }

    @Test
    fun respectsTheEvaluationBudget() {
        val counter = intArrayOf(0)
        val scorer = RecipeScorer { recipe ->
            counter[0]++
            5f + 0.5f * recipe[Param.EXPOSURE] + 0.3f * recipe[Param.CONTRAST]
        }
        val result = NimaOptimizer.optimize(
            EditRecipe.NEUTRAL,
            null,
            scorer,
            NimaOptimizer.Settings(rounds = 5, budget = 7),
        )
        assertTrue("예산을 넘겨 재지 않는다 (${result.evaluations})", result.evaluations <= 7)
        assertTrue("예산 안에서 몇 번은 잰다", result.evaluations > 0)
    }

    @Test
    fun aGivenBaseScoreIsNotMeasuredAgain() {
        val calls = intArrayOf(0)
        val scorer = RecipeScorer { recipe ->
            calls[0]++
            5f + 0.5f * recipe[Param.EXPOSURE]
        }
        val withBase = NimaOptimizer.optimize(EditRecipe.NEUTRAL, 4.4f, scorer)
        assertEquals("준 점수를 그대로 쓴다", 4.4f, withBase.before!!, 0.0001f)
        // 4.4에서 시작하면 후보 점수가 그보다 높아야 채택된다 — 실제로 재지 않고도 판단한다.
        assertTrue(withBase.evaluations > 0)
        assertTrue(calls[0] > 0)
    }

    @Test
    fun reportsNothingWhenTheModelCannotAnswer() {
        val scorer = RecipeScorer { null }
        val result = NimaOptimizer.optimize(EditRecipe.NEUTRAL, null, scorer)
        assertNull(result.before)
        assertNull(result.after)
        assertTrue(result.changes.isEmpty())
        assertEquals(EditRecipe.NEUTRAL, result.recipe)
    }

    @Test
    fun summaryNamesTheParametersItTouched() {
        val summary = NimaOptimizer.optimize(
            EditRecipe.NEUTRAL,
            null,
            RecipeScorer { recipe -> 5f + 3f * recipe[Param.EXPOSURE] },
        ).summary()
        assertTrue(summary, summary.contains("노출"))
        assertTrue(summary, summary.contains("EV"))
    }

    // ── 점수 계산 ─────────────────────────────────────────────────────────

    @Test
    fun expectedScoreIsTheWeightedMean() {
        val uniform = FloatArray(10) { 0.1f }
        assertEquals(5.5f, NimaScores.expectedScore(uniform)!!, 0.001f)

        val topBucket = FloatArray(10) { 0f }
        topBucket[9] = 1f
        assertEquals(10f, NimaScores.expectedScore(topBucket)!!, 0.001f)

        val bottomBucket = FloatArray(10) { 0f }
        bottomBucket[0] = 1f
        assertEquals(1f, NimaScores.expectedScore(bottomBucket)!!, 0.001f)

        assertNull(NimaScores.expectedScore(FloatArray(10)))
    }

    @Test
    fun normaliseRepairsBadOutput() {
        val withBadValues = floatArrayOf(0.2f, Float.NaN, -1f, 0.2f, 0f, 0f, 0f, 0f, 0f, 0.6f)
        val clean = NimaScores.normalise(withBadValues)
        assertEquals(1f, clean.sum(), 0.0001f)
        assertTrue(clean.all { it.isFinite() && it >= 0f })

        val allBad = NimaScores.normalise(FloatArray(10) { Float.NaN })
        assertEquals(1f, allBad.sum(), 0.0001f)
    }

    @Test
    fun deviationMeasuresHowSureTheModelIs() {
        val certain = FloatArray(10) { 0f }.also { it[6] = 1f }
        assertEquals(0f, NimaScores.deviation(certain, 7f), 0.001f)

        val spread = FloatArray(10) { 0.1f }
        assertTrue(NimaScores.deviation(spread, 5.5f) > 1f)
    }

    @Test
    fun preprocessBuildsAFullInput() {
        val side = 8
        val pixels = IntArray(side * side) { index ->
            val v = (index * 255 / (side * side - 1))
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val input = NimaScores.toInput(pixels, side, side)
        assertEquals(NimaScores.INPUT_FLOATS, input.size)
        // 첫 픽셀은 검정(−1), 마지막은 흰색(+1) 근처다.
        assertEquals(-1f, input[0], 0.03f)
        assertEquals(1f, input[input.size - 1], 0.08f)
        assertTrue(input.all { it >= -1.001f && it <= 1.001f })
    }
}
