package com.plenuen.lumen

import com.plenuen.lumen.core.EditNarrator
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.gl.RenderSpec
import com.plenuen.lumen.llm.LlmRecipeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 보정 내역 문장은 말이 아니라 실제 전/후 레시피 차이에서 만든다. */
class EditNarratorTest {

    @Test
    fun singleChangeReadsNaturally() {
        val before = EditRecipe.NEUTRAL
        val after = before.with(Param.SATURATION, 0.18f)
        assertEquals("채도를 18% 올렸어요", EditNarrator.describe(before, after))
    }

    @Test
    fun severalChangesAreJoinedInOneSentence() {
        val before = EditRecipe.NEUTRAL
        val after = before
            .with(Param.SATURATION, 0.18f)
            .with(Param.HIGHLIGHTS, -0.2f)
            .with(Param.TEMPERATURE, 0.25f)
        val sentence = EditNarrator.describe(before, after)
        assertNotNull(sentence)
        assertTrue(sentence!!.contains("채도를 18% 올렸어요"))
        assertTrue(sentence.contains("하이라이트를 20% 낮추고"))
        assertTrue(sentence.contains("색온도를 따뜻하게"))
        // 세 항목이 한 문장으로 이어진다.
        assertEquals(2, sentence.count { it == ',' })
    }

    @Test
    fun lookChangeIsNamed() {
        val before = EditRecipe.NEUTRAL
        val added = before.with(Param.LUT_INDEX, 12f)
        val sentence = EditNarrator.describe(before, added, "Kodak Portra 400")
        assertNotNull(sentence)
        assertTrue(sentence!!.contains("필름 룩"))
        assertTrue(sentence.contains("Kodak Portra 400"))

        val removed = EditNarrator.describe(added, before)
        assertEquals("필름 룩을 뺐어요", removed)
    }

    @Test
    fun straightenAndExposureUseTheirOwnUnits() {
        val before = EditRecipe.NEUTRAL
        val turned = EditNarrator.describe(before, before.with(Param.STRAIGHTEN_DEG, 2.4f))
        assertNotNull(turned)
        assertTrue(turned!!.contains("2.4°"))

        val brighter = EditNarrator.describe(before, before.with(Param.EXPOSURE, 0.35f))
        assertNotNull(brighter)
        assertTrue(brighter!!.contains("0.35 EV"))
        assertTrue(brighter.contains("밝게"))
    }

    @Test
    fun nothingVisibleChangedIsSilent() {
        assertNull(EditNarrator.describe(EditRecipe.NEUTRAL, EditRecipe.NEUTRAL))
        // 자동 수평 스위치는 화면에 보이는 양이 아니라서 문장에 넣지 않는다.
        assertNull(
            EditNarrator.describe(
                EditRecipe.NEUTRAL.with(Param.AUTO_STRAIGHTEN, 0f),
                EditRecipe.NEUTRAL.with(Param.AUTO_STRAIGHTEN, 1f),
            ),
        )
    }

    @Test
    fun biggestChangeIsNamedFirstAndTheRestIsCounted() {
        val before = EditRecipe.NEUTRAL
        val after = before
            .with(Param.CONTRAST, 0.10f)
            .with(Param.CLARITY, 0.42f)
            .with(Param.SATURATION, 0.20f)
            .with(Param.GRAIN, 0.30f)
            .with(Param.VIGNETTE, 0.50f)
        val sentence = EditNarrator.describe(before, after)
        assertNotNull(sentence)
        assertTrue(sentence!!.indexOf("비네팅") < sentence.indexOf("명료도"))
        assertTrue(sentence.contains("외 1가지"))
    }
}

/** 작은 모델의 지저분한 답도 읽어 내는지. */
class LlmRobustnessTest {

    @Test
    fun truncatedJsonIsStillRead() {
        val raw = "{\"label\":\"차분하게\",\"summary\":\"채도를 낮췄어요\",\"adjust\":{\"saturation\":-0.3,\"contrast\":-0.1"
        val decoded = LlmRecipeDecoder.decode(raw)
        assertNotNull(decoded)
        val recipe = EditRecipe.NEUTRAL.apply(decoded!!.delta)
        assertTrue(recipe[Param.SATURATION] < -0.1f)
        assertTrue(recipe[Param.CONTRAST] < 0f)
    }

    @Test
    fun percentagesAndNumericStringsAreAccepted() {
        val decoded = LlmRecipeDecoder.decode("{\"adjust\":{\"saturation\":\"-35%\",\"contrast\":\"0.2\"}}")
        assertNotNull(decoded)
        val recipe = EditRecipe.NEUTRAL.apply(decoded!!.delta)
        assertTrue(recipe[Param.SATURATION] < -0.1f)
        assertTrue(recipe[Param.CONTRAST] > 0.05f)
    }

    @Test
    fun koreanLabelsAndNestedGroupsAreAccepted() {
        val raw = "{\"label\":\"몽환적으로\",\"adjust\":{\"색감\":{\"채도\":0.3},\"톤\":{\"대비\":-0.2}}}"
        val decoded = LlmRecipeDecoder.decode(raw)
        assertNotNull(decoded)
        val recipe = EditRecipe.NEUTRAL.apply(decoded!!.delta)
        assertTrue(recipe[Param.SATURATION] > 0.1f)
        assertTrue(recipe[Param.CONTRAST] < 0f)
    }

    @Test
    fun looseSyntaxWithBareKeysAndSingleQuotesIsAccepted() {
        val raw = "```json\n{adjust:{'exposure':0.3, temperature:0.2, 'highlights':-0.15,},}\n```"
        val decoded = LlmRecipeDecoder.decode(raw)
        assertNotNull(decoded)
        val recipe = EditRecipe.NEUTRAL.apply(decoded!!.delta)
        assertTrue(recipe[Param.EXPOSURE] > 0f)
        assertTrue(recipe[Param.TEMPERATURE] > 0f)
        assertTrue(recipe[Param.HIGHLIGHTS] < 0f)
    }

    @Test
    fun leakedChatTemplateTokensDoNotBreakDecoding() {
        val raw = "<|im_start|>assistant\n" +
            "{\"label\":\"필름\",\"summary\":\"그레인을 넣었어요\",\"adjust\":{\"grain\":0.4,\"fade\":0.2}}" +
            "<|im_end|>"
        val decoded = LlmRecipeDecoder.decode(raw)
        assertNotNull(decoded)
        assertEquals("필름", decoded!!.label)
        assertTrue(EditRecipe.NEUTRAL.apply(decoded.delta)[Param.GRAIN] > 0f)
    }

    @Test
    fun promptAvoidsDoubleChatTemplatingAndAsksForTwoParams() {
        val prompt = LlmRecipeDecoder.buildPrompt("골목의 저녁 공기처럼", listOf("Kodak Portra 400"))
        // MediaPipe .task가 템플릿을 직접 입히므로 프롬프트에는 특수 토큰이 없어야 한다.
        assertFalse(prompt.contains("<|im_start|>"))
        assertFalse(prompt.contains("<|im_end|>"))
        assertTrue(prompt.contains("2개 이상"))
        assertTrue(prompt.contains("summary"))
        assertTrue(prompt.contains("saturation"))
        assertTrue(prompt.contains("Kodak Portra 400"))
    }

    @Test
    fun sceneHintIsFoldedIntoThePrompt() {
        val prompt = LlmRecipeDecoder.buildPrompt("차분하게", emptyList(), "밝기 어둡다, 채도 낮다.")
        assertTrue(prompt.contains("사진 상태: 밝기 어둡다"))
        // 요청과 JSON 표시가 사진 상태 뒤에 온다.
        assertTrue(prompt.indexOf("사진 상태") < prompt.indexOf("요청:"))
    }
}

/** 세로 비교선은 프리뷰 표시일 뿐, 저장 경로에는 절대 들어가지 않는다. */
class RenderSpecTest {

    @Test
    fun exportDefaultHasNoSplit() {
        val spec = RenderSpec(EditRecipe.NEUTRAL, 1200, 1600)
        assertFalse(spec.splitEnabled)
        assertFalse(spec.compare)
        assertEquals(0.5f, spec.splitX, 0.001f)
    }

    @Test
    fun splitIsCarriedOnlyWhenAsked() {
        val spec = RenderSpec(EditRecipe.NEUTRAL, 1200, 1600, compare = false, splitEnabled = true, splitX = 0.3f)
        assertTrue(spec.splitEnabled)
        assertEquals(0.3f, spec.splitX, 0.001f)
    }
}
