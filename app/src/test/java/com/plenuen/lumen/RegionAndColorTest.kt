package com.plenuen.lumen

import com.plenuen.lumen.core.EditNarrator
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.HslBand
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.llm.LlmRecipeDecoder
import com.plenuen.lumen.nl.NaturalLanguageParser
import com.plenuen.lumen.nl.RegionColorParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 영역 지시(인물·배경·하늘)와 8색 HSL 핀셋.
 *
 * 사용자가 실제로 타이핑한 문장("인물만 화사하게", "배경 채도 빼줘", "빨강만 살리고 초록은
 * 차분하게", "하늘 날아간 거 살려줘")이 사진 전체가 아니라 그 부분에만 걸리는지, 그리고 그
 * 결과가 보정 내역 문장으로 제대로 읽히는지 고정한다.
 */
class RegionAndColorTest {

    /** 앱과 같은 순서로 적용한다: 영역 → 8색 → 일반 룩 → 제한. */
    private fun apply(text: String, from: EditRecipe = EditRecipe.NEUTRAL): EditRecipe {
        val parsed = NaturalLanguageParser.parse(text)
        var recipe = from
        parsed.regionHits.forEach { hit -> recipe = recipe.apply(hit.delta, 1f) }
        parsed.colorHits.forEach { hit -> recipe = recipe.apply(hit.delta, 1f) }
        parsed.hits.forEach { hit -> recipe = recipe.apply(hit.intent.delta, parsed.strengthOf(hit)) }
        parsed.restraints.forEach { hit ->
            hit.restraint.delta?.let { delta -> recipe = recipe.apply(delta, parsed.strengthScale) }
        }
        return parsed.restrained(recipe)
    }

    // ── 영역 ─────────────────────────────────────────────────────────────

    @Test
    fun subjectOnlyWordingTargetsTheSubjectRegion() {
        val parsed = NaturalLanguageParser.parse("인물만 화사하게")
        assertEquals(1, parsed.regionHits.size)
        assertEquals(RegionColorParser.Region.SUBJECT, parsed.regionHits[0].region)
        val recipe = apply("인물만 화사하게")
        assertTrue("인물 밝기가 올라가야 한다", recipe[Param.SUBJECT_EXPOSURE] > 0.1f)
        assertEquals("사진 전체 노출은 건드리지 않는다", 0f, recipe[Param.EXPOSURE], 1e-4f)
        assertEquals("사진 전체 채도도 건드리지 않는다", 0f, recipe[Param.SATURATION], 1e-4f)
        assertTrue("일반 '화사하게' 룩이 겹쳐 적용되면 안 된다", parsed.hits.isEmpty())
    }

    @Test
    fun subjectCrispWordingRaisesSubjectClarityOnly() {
        val recipe = apply("피사체만 선명하게")
        assertTrue(recipe[Param.SUBJECT_CLARITY] > 0.2f)
        assertEquals(0f, recipe[Param.SHARPNESS], 1e-4f)
        assertEquals(0f, recipe[Param.CLARITY], 1e-4f)
    }

    @Test
    fun backgroundDarkWordingLowersBackgroundExposure() {
        val parsed = NaturalLanguageParser.parse("배경만 어둡게")
        assertEquals(RegionColorParser.Region.BACKGROUND, parsed.regionHits.single().region)
        val recipe = apply("배경만 어둡게")
        assertTrue(recipe[Param.BACKGROUND_EXPOSURE] < -0.2f)
        assertTrue("피사체는 그대로", abs(recipe[Param.SUBJECT_EXPOSURE]) < 1e-4f)
    }

    @Test
    fun backgroundSaturationRemovalWorksWithoutTheParticle() {
        val recipe = apply("배경 채도 빼줘")
        assertTrue(recipe[Param.BACKGROUND_SATURATION] < -0.2f)
        assertEquals(0f, recipe[Param.SATURATION], 1e-4f)
    }

    @Test
    fun backgroundSoftWordingUsesTheRealBackgroundBlur() {
        val recipe = apply("배경만 부드럽게")
        assertTrue(recipe[Param.BACKGROUND_SOFT] > 0.4f)
    }

    @Test
    fun skyBlueWordingTargetsTheSkyRegion() {
        val parsed = NaturalLanguageParser.parse("하늘만 파랗게")
        assertEquals(RegionColorParser.Region.SKY, parsed.regionHits.single().region)
        val recipe = apply("하늘만 파랗게")
        assertTrue(recipe[Param.SKY_BLUE] > 0.3f)
        assertTrue(recipe[Param.SKY_SATURATION] > 0.05f)
        assertEquals(0f, recipe[Param.BLUE_SAT], 1e-4f)
    }

    @Test
    fun skyDeeperWordingDarkensAndSaturatesSky() {
        val recipe = apply("하늘 파란색만 깊게")
        assertTrue(recipe[Param.SKY_BLUE] > 0.2f)
        assertTrue(recipe[Param.SKY_SATURATION] > 0.1f)
        assertEquals("하늘 옆의 파랑은 색 대역이 아니라 하늘 자체다", 0f, recipe[Param.BLUE_SAT], 1e-4f)
    }

    @Test
    fun blownSkyRecoveryPullsSkyDownAndRestoresColour() {
        val recipe = apply("하늘 날아간 거 살려줘")
        assertTrue(recipe[Param.SKY_EXPOSURE] < -0.2f)
        assertTrue(recipe[Param.SKY_SATURATION] > 0.2f)
    }

    @Test
    fun portraitLookNameIsNotSwallowedByTheRegionScanner() {
        // "인물 사진"은 장르 이름이지 영역 지시가 아니다. 기존 룩이 그대로 살아야 한다.
        val parsed = NaturalLanguageParser.parse("인물 사진 뽀얗게")
        assertTrue("영역 지시로 잡히면 안 된다", parsed.regionHits.isEmpty())
        assertTrue("기존 인물 룩이 잡혀야 한다", parsed.hits.isNotEmpty())
    }

    // ── 8색 HSL ──────────────────────────────────────────────────────────

    @Test
    fun twoColoursInOneSentenceSplitByTheirOwnDirection() {
        val parsed = NaturalLanguageParser.parse("빨강만 살리고 초록은 차분하게")
        assertEquals(2, parsed.colorHits.size)
        val recipe = apply("빨강만 살리고 초록은 차분하게")
        assertTrue("빨강은 살린다", recipe[Param.RED_SAT] > 0.2f)
        assertTrue("초록은 낮춘다", recipe[Param.GREEN_SAT] < -0.2f)
        assertEquals("사진 전체 채도는 그대로", 0f, recipe[Param.SATURATION], 1e-4f)
    }

    @Test
    fun mapleColourWordingMapsToRedBand() {
        val recipe = apply("단풍 색만 진하게")
        assertTrue(recipe[Param.RED_SAT] > 0.2f)
    }

    @Test
    fun leafWordingRemovesGreenSaturation() {
        val recipe = apply("초록색 나뭇잎 차분하게")
        assertTrue(recipe[Param.GREEN_SAT] < -0.2f)
    }

    @Test
    fun skinOrangeCastWordingRemovesOrangeSaturation() {
        val recipe = apply("피부 주황기 빼줘")
        assertTrue(recipe[Param.ORANGE_SAT] < -0.2f)
    }

    @Test
    fun yellowCastWordingRemovesYellowSaturation() {
        val recipe = apply("노란기 빼줘")
        assertTrue(recipe[Param.YELLOW_SAT] < -0.2f)
    }

    @Test
    fun softlyWordingIsWeakerThanStrongly() {
        val mild = apply("보라색 살짝")[Param.PURPLE_SAT]
        val strong = apply("보라색 진하게")[Param.PURPLE_SAT]
        assertTrue("살짝은 올라가되 진하게보다 약하다", mild > 0.05f && mild < strong)
    }

    @Test
    fun colourBandChangesAreNarratedByBandAndAmount() {
        val before = EditRecipe.NEUTRAL
        val after = before
            .with(Param.SUBJECT_EXPOSURE, 0.15f)
            .with(Param.RED_SAT, 0.30f)
            .with(Param.GREEN_SAT, -0.25f)
        val sentence = EditNarrator.describe(before, after)
        assertNotNull(sentence)
        requireNotNull(sentence)
        assertTrue(sentence, sentence.contains("빨강 채도를 30%"))
        assertTrue(sentence, sentence.contains("초록 채도를 25%"))
        assertTrue(sentence, sentence.contains("인물 밝기를 15%"))
    }

    @Test
    fun skyAndBackgroundChangesAreNarratedWithTheirRegion() {
        val sentence = EditNarrator.describe(
            EditRecipe.NEUTRAL,
            EditRecipe.NEUTRAL
                .with(Param.BACKGROUND_EXPOSURE, -0.28f)
                .with(Param.BACKGROUND_SOFT, 0.5f)
                .with(Param.SKY_BLUE, 0.6f),
        )
        requireNotNull(sentence)
        assertTrue(sentence, sentence.contains("배경 밝기를 28%"))
        assertTrue(sentence, sentence.contains("배경 흐림을 50%"))
        assertTrue(sentence, sentence.contains("하늘을 더 파랗게"))
    }

    // ── 레시피·LLM ───────────────────────────────────────────────────────

    @Test
    fun regionAndBandParamsSurviveJsonRoundTrip() {
        val recipe = EditRecipe.NEUTRAL
            .with(Param.SUBJECT_EXPOSURE, 0.2f)
            .with(Param.BACKGROUND_SOFT, 0.6f)
            .with(Param.SKY_BLUE, -0.4f)
            .with(Param.RED_HUE, 0.3f)
            .with(Param.MAGENTA_LUM, -0.2f)
        val restored = EditRecipe.fromJson(recipe.toJson())
        assertEquals(recipe, restored)
        assertFalse(restored.isNeutral)
        assertTrue(restored.changedParams().contains(Param.SKY_BLUE))
    }

    @Test
    fun llmAnswerWithRegionAndBandKeysIsDecoded() {
        val decoded = LlmRecipeDecoder.decode(
            """{"label":"영역","summary":"인물만 밝게, 파랑은 차분하게","adjust":
               {"인물 밝기":0.3,"배경 채도":-0.25,"blueSaturation":-0.2}}""",
        )
        requireNotNull(decoded)
        val ops = decoded.delta.ops.associate { it.param to it }
        assertTrue(ops.containsKey(Param.SUBJECT_EXPOSURE))
        assertTrue(ops.containsKey(Param.BACKGROUND_SATURATION))
        assertTrue(ops.containsKey(Param.BLUE_SAT))
    }

    @Test
    fun llmPromptListsRegionAndBandKeys() {
        val prompt = LlmRecipeDecoder.buildPrompt("인물만 화사하게", emptyList())
        assertTrue(prompt.contains("subjectExposure"))
        assertTrue(prompt.contains("redSaturation"))
        assertTrue(prompt.contains("skyBlue"))
    }

    // ── 8색 대역 수학 ────────────────────────────────────────────────────

    @Test
    fun bandWeightsCoverTheHueCircleWithoutSteps() {
        var hue = 0f
        while (hue < 360f) {
            val weights = HslBand.weights(hue)
            val sum = weights.sum()
            assertTrue("hue=$hue sum=$sum", sum > 0.97f && sum < 1.03f)
            weights.forEach { assertTrue("weight $it", it >= 0f && it <= 1.0001f) }
            hue += 5f
        }
    }

    @Test
    fun eachBandPeaksAtItsOwnCentre() {
        HslBand.entries.forEach { band ->
            val weights = HslBand.weights(band.centerDeg)
            val peak = weights.maxOrNull() ?: 0f
            assertEquals("${band.label} 이 자기 중심에서 1", 1f, peak, 1e-3f)
            assertEquals(1f, weights[band.ordinal], 1e-3f)
        }
    }

    @Test
    fun neutralBandValuesLeaveThePixelUntouched() {
        val samples = listOf(
            floatArrayOf(0.85f, 0.12f, 0.10f),
            floatArrayOf(0.10f, 0.70f, 0.20f),
            floatArrayOf(0.20f, 0.35f, 0.90f),
            floatArrayOf(0.60f, 0.60f, 0.62f),
        )
        for (rgb in samples) {
            val out = HslBand.apply(rgb) { 0f }
            for (channel in 0..2) {
                assertEquals("neutral keeps the pixel", rgb[channel], out[channel], 1e-4f)
            }
        }
    }

    @Test
    fun redBandSaturationBoostLeavesGreenAlmostAlone() {
        val red = floatArrayOf(0.80f, 0.15f, 0.15f)
        val green = floatArrayOf(0.15f, 0.70f, 0.20f)
        val boost = { param: Param -> if (param == Param.RED_SAT) 0.9f else 0f }
        val redOut = HslBand.apply(red, boost)
        val greenOut = HslBand.apply(green, boost)
        val redChromaBefore = red.max() - red.min()
        val redChromaAfter = redOut.max() - redOut.min()
        val greenChromaBefore = green.max() - green.min()
        val greenChromaAfter = greenOut.max() - greenOut.min()
        assertTrue("빨강은 진해진다", redChromaAfter > redChromaBefore)
        assertEquals("초록은 그대로", greenChromaBefore, greenChromaAfter, 0.02f)
    }

    @Test
    fun hueKnobTurnsOnlyItsOwnBand() {
        val orange = floatArrayOf(0.85f, 0.45f, 0.10f)
        val turnRed = { param: Param -> if (param == Param.ORANGE_HUE) -1f else 0f }
        val out = HslBand.apply(orange, turnRed)
        val before = HslBand.rgbToHsl(orange)[0] * 360f
        val after = HslBand.rgbToHsl(out)[0] * 360f
        val beforeToRed = minOf(before, 360f - before)
        val afterToRed = minOf(after, 360f - after)
        assertTrue("주황(약 30°)이 빨강 쪽으로 돈다: $before → $after", afterToRed < beforeToRed)
    }

    @Test
    fun bandValuesAreClampedToTheSliderRange() {
        val recipe = EditRecipe.NEUTRAL.with(Param.RED_SAT, 5f)
        assertEquals(1f, recipe[Param.RED_SAT], 1e-4f)
        val lowered = recipe.with(Param.RED_SAT, -5f)
        assertEquals(-1f, lowered[Param.RED_SAT], 1e-4f)
    }
}
