package com.plenuen.lumen

import com.plenuen.lumen.core.EditNarrator
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.llm.LlmRecipeDecoder
import com.plenuen.lumen.nl.AestheticPlanner
import com.plenuen.lumen.nl.IntentCatalog
import com.plenuen.lumen.nl.NaturalLanguageParser
import com.plenuen.lumen.nl.SceneAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 구어체·신조어·현장 표현과 부정/억제 문장.
 *
 * 사용자가 실제로 쓰는 말("샤하게", "꾸안꾸", "눈길을 끌면서도 너무 화려하지는 않은")이 사전에
 * 없어서가 아니라, 사전이 그 말투를 몰라서 실패하지 않도록 고정한다.
 */
class ColloquialAndNegationTest {

    /** 앱과 같은 순서로 적용한다: 룩 → 제한(델타·상한·하한·해제). */
    private fun apply(text: String, from: EditRecipe = EditRecipe.NEUTRAL): EditRecipe {
        val parsed = NaturalLanguageParser.parse(text)
        var recipe = from
        parsed.hits.forEach { hit ->
            val delta = hit.intent.aesthetic?.let { profile ->
                AestheticPlanner.plan(profile, SceneAnalyzer.analyze(grayFrame(), 96, 96))
            } ?: hit.intent.delta
            recipe = recipe.apply(delta, parsed.strengthOf(hit))
        }
        parsed.restraints.forEach { hit ->
            hit.restraint.delta?.let { delta -> recipe = recipe.apply(delta, parsed.strengthScale) }
        }
        return parsed.restrained(recipe)
    }

    private fun grayFrame(): IntArray = buildImage(96, 96) { x, y -> gray(50 + (x + y) % 150) }

    // ── 구어체·감성 ─────────────────────────────────────────────────────

    @Test
    fun shyBrightFamilyRaisesMidtonesAndGlow() {
        listOf("샤하게", "샤방하게", "뽀샤시하게", "뽀샤시").forEach { sentence ->
            val recipe = apply(sentence)
            assertTrue("$sentence shadows", recipe[Param.SHADOWS] > 0.1f)
            assertTrue("$sentence glow", recipe[Param.GLOW] > 0.1f)
            assertTrue("$sentence skin", recipe[Param.SKIN_CLEAN] > 0.2f)
            assertTrue("$sentence texture", recipe[Param.TEXTURE] < 0f)
        }
    }

    @Test
    fun slangLooksMapToTheirOwnPalette() {
        val noMakeup = apply("꾸안꾸 느낌")
        assertTrue(noMakeup[Param.SKIN_CLEAN] > 0.2f)
        assertTrue(noMakeup[Param.SATURATION] <= 0f) // 티 안 나게: 채도는 올리지 않는다
        assertTrue(noMakeup[Param.COLOR_HARMONY] > 0.1f)

        val feelGood = apply("느좋하게")
        assertTrue(feelGood[Param.FADE] > 0f)
        assertTrue(feelGood[Param.GRAIN] > 0f)
        assertTrue(feelGood[Param.SATURATION] <= 0f)

        val charm = apply("감다살")
        assertTrue(charm[Param.GRAIN] > 0f)
        assertTrue(charm[Param.SPLIT_TONE] > 0f)

        val hip = apply("힙하게")
        assertTrue(hip[Param.CONTRAST] > 0.1f)
        assertTrue(hip[Param.GRAIN] > 0f)

        val cozy = apply("코지하게")
        assertTrue(cozy[Param.TEMPERATURE] > 0.1f)
        assertTrue(cozy[Param.SHADOWS] > 0.1f)

        val pure = apply("청순하게")
        assertTrue(pure[Param.SKIN_CLEAN] > 0.2f)
        assertTrue(pure[Param.SATURATION] < 0f)

        val delicate = apply("여리여리하게")
        assertTrue(delicate[Param.GLOW] > 0.1f)
        assertTrue(delicate[Param.CONTRAST] < 0f)

        assertTrue(apply("서늘하게")[Param.TEMPERATURE] < 0f)
        assertTrue(apply("나른하게")[Param.FADE] > 0f)
        assertTrue(apply("웅장하게")[Param.CONTRAST] > 0.15f)
        assertTrue(apply("몽글몽글하게")[Param.GLOW] > 0.1f)
    }

    @Test
    fun subjectAndGenrePhrasesWork() {
        assertTrue(apply("포인트 있게")[Param.SUBJECT_FOCUS] > 0.2f)
        assertTrue(apply("눈에 띄게")[Param.SUBJECT_FOCUS] > 0.3f)
        assertTrue(apply("확 피어나는")[Param.GLOW] > 0.1f)
        assertTrue(apply("칼핀처럼")[Param.SHARPNESS] > 0.35f)
        assertTrue(apply("피부 맑게")[Param.SKIN_CLEAN] > 0.3f)
        assertTrue(apply("음식 맛있게")[Param.VIBRANCE] > 0.2f)
        assertTrue(apply("풍경 시원하게")[Param.DEHAZE] > 0.15f)
        assertTrue(apply("제품 컷처럼")[Param.SHARPNESS] > 0.25f)
        assertTrue(apply("보케 살리게")[Param.SUBJECT_FOCUS] > 0.3f)

        val night = apply("야경 깔끔하게")
        assertTrue(night[Param.SHADOWS] > 0.1f)
        assertTrue(night[Param.TEXTURE] < 0f)

        val filmCam = NaturalLanguageParser.parse("필카 느낌")
        assertEquals("Portra 400", filmCam.lutQuery)
        assertTrue(apply("필카 느낌")[Param.GRAIN] > 0.15f)
        assertTrue(apply("디카 느낌")[Param.HALATION] > 0.15f)
        assertTrue(apply("인스타 감성")[Param.VIBRANCE] > 0f)
        assertTrue(NaturalLanguageParser.parse("핀터레스트 느낌").hits.any { it.intent.aesthetic != null })
    }

    // ── 부정·억제 ───────────────────────────────────────────────────────

    @Test
    fun negationKeepsTheLookInsideItsCeiling() {
        // 화려하게를 확 올려도 "너무 화려하지는 않게"가 채도 상한을 잡는다.
        val capped = apply("확 화려하게, 근데 너무 화려하지는 않게")
        assertTrue("saturation ${capped[Param.SATURATION]}", capped[Param.SATURATION] <= 0.10f)
        assertTrue("vibrance ${capped[Param.VIBRANCE]}", capped[Param.VIBRANCE] <= 0.55f)

        // 부정 표현이 없으면 그대로 올라간다.
        val plain = apply("확 화려하게")
        assertTrue(plain[Param.SATURATION] > 0.2f)
    }

    @Test
    fun negatedDescriptionsMoveAwayFromTheUnwantedColor() {
        val dull = apply("칙칙하지 않게")
        assertTrue(dull[Param.EXPOSURE] > 0f)
        assertTrue(dull[Param.VIBRANCE] > 0f)

        assertTrue(apply("누렇게 뜨지 않게")[Param.TEMPERATURE] < 0f)
        assertTrue(apply("노랗지 않게")[Param.TEMPERATURE] < 0f)
        assertTrue(apply("파래지지 않게")[Param.TEMPERATURE] > 0f)

        val sky = apply("하늘이 날아가지 않게")
        assertTrue(sky[Param.HIGHLIGHTS] < 0f)
        assertTrue(sky[Param.HIGHLIGHTS] <= 0.05f)

        val shadows = apply("암부가 뭉개지지 않게")
        assertTrue(shadows[Param.SHADOWS] > 0f)
        assertTrue(shadows[Param.BLACKS] > 0f)

        val coarse = apply("너무 거칠지 않게")
        assertTrue(coarse[Param.TEXTURE] < 0f)
        assertTrue(apply("너무 어둡지도 않게")[Param.EXPOSURE] > 0f)
    }

    @Test
    fun overdoneClampsTheWholeSentence() {
        val parsed = NaturalLanguageParser.parse("확 화려하게, 너무 과하지는 않게")
        assertTrue(parsed.strengthScale < 0.8f)
        // 강도 클램프 + 채도 상한이 함께 걸린다.
        val recipe = apply("확 화려하게, 너무 과하지는 않게")
        assertTrue(recipe[Param.SATURATION] <= 0.20f)
        assertTrue(recipe[Param.SATURATION] < apply("확 화려하게")[Param.SATURATION])
    }

    @Test
    fun exclusionRemovesWhatWasBuiltEarlier() {
        val built = EditRecipe.NEUTRAL
            .with(Param.GRAIN, 0.4f)
            .with(Param.VIGNETTE, 0.3f)
            .with(Param.MONOCHROME, 1f)
        val cleaned = apply("그레인 빼고 비네팅은 빼고 흑백도 빼고", from = built)
        assertEquals(0f, cleaned[Param.GRAIN], 0.001f)
        assertEquals(0f, cleaned[Param.VIGNETTE], 0.001f)
        assertEquals(0f, cleaned[Param.MONOCHROME], 0.001f)
    }

    @Test
    fun negatedLookBecomesItsOwnInverse() {
        val parsed = NaturalLanguageParser.parse("부드럽게 말고 선명하게")
        assertFalse(parsed.hits.any { it.intent.key == "soft" })
        assertTrue(parsed.hits.any { it.intent.key == "crisp" })
        assertTrue(parsed.restraints.any { it.excluded })

        val recipe = apply("부드럽게 말고 선명하게")
        assertTrue(recipe[Param.TEXTURE] > 0f)
        assertTrue(recipe[Param.CONTRAST] > 0f)
    }

    /** 불평하듯 쓴 문장("칙칙한데 살려줘")과 "차갑게". */
    @Test
    fun complaintsAndColdAreUnderstood() {
        assertTrue(apply("차갑게")[Param.TEMPERATURE] < -0.2f)

        val dull = apply("칙칙한데 좀 살려줘")
        assertTrue(dull[Param.EXPOSURE] > 0.1f)
        assertTrue(dull[Param.SHADOWS] > 0.1f)
        assertEquals("", NaturalLanguageParser.parse("칙칙한데 좀 살려줘").unmatched)
    }

    /** 더 긴 룩 이름이 부정 표시를 품고 있으면 룩이 이긴다("야경 노이즈 없이" = 야경을 깔끔하게). */
    @Test
    fun longerLookNameBeatsTheNegation() {
        val parsed = NaturalLanguageParser.parse("야경 노이즈 없이")
        assertEquals("night_clean", parsed.hits.single().intent.key)
        assertTrue(parsed.restraints.isEmpty())
        assertTrue(apply("야경 노이즈 없이")[Param.HALATION] <= 0f)
    }

    // ── 말버릇 ──────────────────────────────────────────────────────────

    @Test
    fun fillersAreSteppedOver() {
        val parsed = NaturalLanguageParser.parse("그거 아시죠? 뭔가 약간 그런... 부드럽게요")
        assertEquals(1, parsed.hits.size)
        assertEquals("soft", parsed.hits.first().intent.key)
        assertEquals("", parsed.unmatched)
        // 말버릇을 지워도 강도 부사는 살아 있어야 한다.
        assertTrue(parsed.hits.first().strength < 1f)
    }

    @Test
    fun unreadWordsStillReachTheModel() {
        assertTrue(NaturalLanguageParser.parse("골목의 저녁 공기처럼").unmatched.contains("골목"))
        assertEquals("부드럽게", NaturalLanguageParser.salient("그거 아시죠 뭔가 부드럽게"))
    }

    // ── 사용자가 보낸 대표 문장 ──────────────────────────────────────────

    @Test
    fun theLongSpokenSentenceBecomesOneHarmoniousLook() {
        val sentence = "거기 깔끔하면서도 포인트가 딱 되게요. 그거 아시죠? 거기에 확 피어나는...." +
            "그런 뭔가 눈길을 딱 끌면서도 그거 있잖아요. 근데 너무 화려하지는 않은...." +
            "하 그래도 멀리서 봐도 딱 눈에 띄었으면 좋겠어요"
        val parsed = NaturalLanguageParser.parse(sentence)

        // 남은 문장이 없어야 로컬 LLM으로 새지 않는다.
        assertEquals("", parsed.unmatched)
        val keys = parsed.hits.map { it.intent.key }
        assertTrue("keys $keys", keys.contains("neat"))
        assertTrue("keys $keys", keys.contains("point"))
        assertTrue("keys $keys", keys.contains("bloom"))
        assertTrue("keys $keys", keys.contains("eye_catching"))
        assertTrue(parsed.restraints.any { it.restraint.key == "flashy" })

        val recipe = apply(sentence)
        // 피사체 강조·명료도·활기·색 정돈이 올라가고,
        assertTrue("focus ${recipe[Param.SUBJECT_FOCUS]}", recipe[Param.SUBJECT_FOCUS] > 0.6f)
        assertTrue("clarity ${recipe[Param.CLARITY]}", recipe[Param.CLARITY] > 0.3f)
        assertTrue("vibrance ${recipe[Param.VIBRANCE]}", recipe[Param.VIBRANCE] in 0.2f..0.6f)
        assertTrue("harmony ${recipe[Param.COLOR_HARMONY]}", recipe[Param.COLOR_HARMONY] > 0.4f)
        assertTrue("vignette ${recipe[Param.VIGNETTE]}", recipe[Param.VIGNETTE] > 0f)
        // 채도는 오히려 억제되어 "화려하지 않게"가 지켜진다.
        assertTrue("saturation ${recipe[Param.SATURATION]}", recipe[Param.SATURATION] <= 0f)

        val note = EditNarrator.describe(EditRecipe.NEUTRAL, recipe)
        assertNotNull(note)
        assertTrue("note: $note", note!!.contains("피사체 강조"))
        assertTrue("note: $note", note.contains("색 정돈"))
        // 문장은 채도를 올렸다고 말하지 않는다 — 사용자가 "화려하지는 않게"라고 했기 때문이다.
        assertFalse("note: $note", note.contains("채도를"))
    }

    @Test
    fun promptTeachesSpokenKoreanAndNegation() {
        val prompt = LlmRecipeDecoder.buildPrompt("너무 화려하지는 않게 샤하게", emptyList())
        assertTrue(prompt.contains("말버릇"))
        assertTrue(prompt.contains("샤하게"))
        assertTrue(prompt.contains("꾸안꾸"))
        assertTrue(prompt.contains("느좋"))
        assertTrue(prompt.contains("올리지"))
        assertEquals(1, prompt.split("요청:").size - 1)
    }
}
