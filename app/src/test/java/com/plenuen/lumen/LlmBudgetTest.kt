package com.plenuen.lumen

import com.plenuen.lumen.core.HslBand
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.llm.LlmBudget
import com.plenuen.lumen.llm.LlmRecipeDecoder
import com.plenuen.lumen.llm.LlmScale
import com.plenuen.lumen.nl.PromptEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프롬프트 크기는 이 앱에서 안전 문제다.
 *
 * v2.2.1은 엔진에 `maxTokens = 512`를 주면서 프롬프트를 1,607토큰(실측) 보냈다. MediaPipe에서
 * maxTokens는 "입력+출력" 합계라 그 호출은 첫 줄부터 상한을 넘겼고, 네이티브 엔진이 프로세스를
 * abort시켜 앱이 "문장을 읽는 중…"에서 사라졌다. 그래서 예산과 프롬프트 크기를 테스트로 고정한다.
 */
class LlmPromptBudgetTest {

    private val lutNames = listOf(
        "Kodak Portra 400", "Kodak Portra 800", "Kodak Gold 200", "Fujifilm Pro 400H",
        "Fujifilm Superia", "Agfa Ultra", "Ilford HP5", "Leica Classic", "Ricoh GR Positive",
        "Kodak Ektar",
    )

    @Test
    fun budgetLeavesRoomForTheAnswerInsideTheBundlesKvCache() {
        // 두 번들 모두 ..._ekv1280.task — KV 캐시가 1,280토큰이다.
        assertTrue("maxTokens는 KV 캐시(1280)를 넘을 수 없다", LlmBudget.MAX_TOKENS <= 1280)
        assertTrue("v2.2.1의 512는 프롬프트보다 작았다", LlmBudget.MAX_TOKENS > 512)
        assertEquals(LlmBudget.MAX_TOKENS, LlmBudget.PROMPT_BUDGET + LlmBudget.ANSWER_TOKENS)
        // 답(JSON 한 줄)과 채팅 템플릿을 빼고도 규칙·예시를 담을 여지가 남아야 한다.
        assertTrue("프롬프트 예산이 너무 작다", LlmBudget.PROMPT_BUDGET >= 900)
    }

    @Test
    fun compactPromptFitsTheBudgetEvenWithTheLongestRequest() {
        val longest = "가".repeat(LlmRecipeDecoder.MAX_REQUEST_CHARS)
        val prompt = LlmRecipeDecoder.buildPrompt(longest, lutNames, "밝기 어둡다, 채도 보통, 색온도 따뜻하다.")
        val estimate = LlmBudget.estimate(prompt)
        assertTrue(
            "프롬프트 추정치($estimate)가 상한(${LlmBudget.PROMPT_BUDGET})을 넘었다: ${prompt.length}자",
            estimate <= LlmBudget.PROMPT_BUDGET,
        )
        assertTrue(LlmBudget.fits(estimate))
        // 실측(Qwen2.5 토크나이저) 약 0.53토큰/자. 1,700자를 넘으면 실측으로도 예산이 위태롭다.
        assertTrue("프롬프트가 길다: ${prompt.length}자", prompt.length < 1750)
    }

    @Test
    fun promptDoesNotGrowWithAPastedParagraph() {
        val short = LlmRecipeDecoder.buildPrompt("차분하게", lutNames)
        val pasted = LlmRecipeDecoder.buildPrompt("차분하게 ".repeat(200), lutNames)
        assertTrue(pasted.length - short.length <= LlmRecipeDecoder.MAX_REQUEST_CHARS + 32)
        assertTrue(LlmBudget.fits(LlmBudget.estimate(pasted)))
    }

    @Test
    fun shortPromptIsSmallerAndStillUsable() {
        val request = "저녁 골목의 공기처럼 쓸쓸하면서도 포근하게"
        val compact = LlmRecipeDecoder.buildPrompt(request, lutNames, "밝기 어둡다.")
        val short = LlmRecipeDecoder.buildShortPrompt(request)
        assertTrue(LlmBudget.estimate(short) < LlmBudget.estimate(compact))
        assertTrue(short.contains("2개 이상"))
        assertTrue(short.contains("saturation"))
        assertTrue(short.contains(request))
    }

    @Test
    fun everyKeyTheDecoderAcceptsIsNamedInThePrompt() {
        val prompt = LlmRecipeDecoder.buildPrompt("차분하게", lutNames)
        val bandParams = HslBand.entries.flatMap { it.params }.toSet()
        LlmScale.allowed.filter { it !in bandParams }.forEach { param ->
            assertTrue("프롬프트에 키가 없다: ${param.key}", prompt.contains(param.key))
        }
        // 색 대역 24개는 예시로 규칙에 적는다(키를 다 나열하면 예산을 넘는다).
        assertTrue(prompt.contains("redSaturation"))
        listOf("red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta").forEach { color ->
            assertTrue("색 이름이 빠졌다: $color", prompt.contains(color))
        }
    }

    @Test
    fun tokenEstimateIsConservativeForKorean() {
        // 실측(Qwen2.5 토크나이저): 한국어 산문은 0.5~0.6토큰/자. 추정은 그보다 크게 잡아야 안전하다.
        val korean = "사진 보정 앱에서 문장을 읽고 보정값을 정하는 아주 긴 한국어 문장입니다. ".repeat(10)
        val estimate = LlmBudget.estimate(korean)
        assertTrue(estimate > korean.length / 2)
        assertTrue(estimate <= korean.length + 8)
    }
}

/** 규칙도 모델도 읽지 못한 문장의 마지막 보정 — 작고, 되돌리기 쉬워야 한다. */
class FeelFallbackTest {

    @Test
    fun moodWordBecomesASmallCorrection() {
        val feel = PromptEngine.feelFallback("몽환적인 밤 골목")
        assertNotNull(feel)
        assertTrue(feel!!.delta.ops.size >= 2)
        feel.delta.ops.forEach { op ->
            val amount = (op as com.plenuen.lumen.core.RecipeDelta.Op.Add).amount
            assertTrue("느낌 보정은 작아야 한다: $amount", kotlin.math.abs(amount) <= 0.25f)
        }
        val recipe = EditRecipe.NEUTRAL.apply(feel.delta)
        assertTrue(recipe[Param.GLOW] > 0f)
    }

    @Test
    fun wordsTheTableDoesNotKnowStayUnread() {
        assertNull(PromptEngine.feelFallback("asdf qwer"))
        assertNull(PromptEngine.feelFallback(""))
    }

    @Test
    fun colorBandsAndRegionsStillDecode() {
        val decoded = LlmRecipeDecoder.decode(
            """{"label":"색","summary":"빨강만 살렸어요.","adjust":{"redSaturation":0.4,"skyBlue":0.3}}""",
        )
        assertNotNull(decoded)
        val recipe = EditRecipe.NEUTRAL.apply(decoded!!.delta)
        assertTrue(recipe[Param.RED_SAT] > 0f)
        assertTrue(recipe[Param.SKY_BLUE] > 0f)
    }
}
