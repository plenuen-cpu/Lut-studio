package com.plenuen.lumen

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.core.RecipeSession
import com.plenuen.lumen.data.LorenzoDecoder
import com.plenuen.lumen.data.PresetCatalog
import com.plenuen.lumen.data.Json
import com.plenuen.lumen.geom.GeometryMath
import com.plenuen.lumen.geom.StraightenEstimator
import com.plenuen.lumen.llm.LlmRecipeDecoder
import com.plenuen.lumen.nl.AestheticPlanner
import com.plenuen.lumen.nl.AestheticProfile
import com.plenuen.lumen.nl.IntentCatalog
import com.plenuen.lumen.nl.NaturalLanguageParser
import com.plenuen.lumen.nl.SceneAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 자연어 파서: 17개 정밀 매핑, 대표 룩, 강도 부사, 연속 대화 제어, 수평·수직, 룩 이름. */
class NaturalLanguageParserTest {

    private fun apply(text: String, strengthOverride: Float? = null): EditRecipe {
        val parsed = NaturalLanguageParser.parse(text)
        var recipe = EditRecipe.NEUTRAL
        parsed.hits.forEach { hit ->
            val delta = hit.intent.aesthetic?.let { AestheticPlanner.plan(it, syntheticStats()) }
                ?: hit.intent.delta
            recipe = recipe.apply(delta, strengthOverride ?: hit.strength)
        }
        return recipe
    }

    private fun syntheticStats() = SceneAnalyzer.analyze(buildImage(120, 80) { x, y -> gray(60 + (x + y) % 120) }, 120, 80)

    // ── 17개 정밀 매핑 ───────────────────────────────────────────────────
    @Test
    fun seventeenPreciseMappings() {
        val soft = apply("부드럽게")
        assertTrue(soft[Param.CONTRAST] < 0f)
        assertTrue(soft[Param.TEXTURE] < 0f)

        assertTrue(apply("선명하게")[Param.SHARPNESS] > 0f)
        assertTrue(apply("묵직하게")[Param.BLACKS] < 0f)
        assertTrue(apply("화사하게")[Param.EXPOSURE] > 0f)
        assertTrue(apply("고급스럽게")[Param.COLOR_HARMONY] > 0f)
        assertTrue(apply("감성적으로")[Param.FADE] > 0f)
        assertTrue(apply("청량하게")[Param.TEMPERATURE] < 0f)
        assertTrue(apply("따스하게")[Param.TEMPERATURE] > 0f)
        assertTrue(apply("쓸쓸하게")[Param.SATURATION] < 0f)
        assertTrue(apply("영화처럼")[Param.SPLIT_TONE] > 0f)
        assertTrue(apply("아련하게")[Param.GLOW] > 0f)
        assertTrue(apply("생동감 있게")[Param.VIBRANCE] > 0f)
        assertTrue(apply("정갈하게")[Param.COLOR_HARMONY] > 0f)
        assertTrue(apply("입체적으로")[Param.CLARITY] > 0f)
        assertTrue(apply("투명하게")[Param.DEHAZE] > 0f)
        assertTrue(apply("필름처럼")[Param.GRAIN] > 0f)

        // 자연스럽게: 이미 보정된 사진을 원본 쪽으로 되돌린다
        val edited = EditRecipe.NEUTRAL
            .with(Param.CONTRAST, 0.6f)
            .with(Param.SATURATION, 0.5f)
            .with(Param.LUT_INDEX, 12f)
        val natural = edited.apply(IntentCatalog.get(IntentCatalog.NATURAL_KEY)!!.delta, 1f)
        assertTrue(abs(natural[Param.CONTRAST]) < 0.05f)
        assertTrue(abs(natural[Param.SATURATION]) < 0.05f)
        assertTrue(natural[Param.LUT_INDEX] < 0f)
    }

    // ── 대표 룩 ─────────────────────────────────────────────────────────
    @Test
    fun representativeLooks() {
        val tealOrange = apply("틸앤오렌지로 해줘")
        assertTrue(tealOrange[Param.SPLIT_TONE] > 0.4f)
        assertEquals(195f, tealOrange[Param.SPLIT_SHADOW_HUE], 1f)

        assertEquals(1f, apply("흑백으로")[Param.MONOCHROME], 0.001f)
        assertTrue(apply("인물 뽀얗게")[Param.SKIN_CLEAN] > 0.4f)
        assertTrue(apply("야경 느낌")[Param.GLOW] > 0f)
        assertTrue(apply("드라마틱하게")[Param.CONTRAST] > 0.2f)
        assertTrue(apply("빈티지하게")[Param.FADE] > 0.2f)
        assertTrue(apply("화보처럼")[Param.SKIN_CLEAN] > 0.2f)
    }

    @Test
    fun lutNamesAreRecognised() {
        assertEquals("Portra", NaturalLanguageParser.parse("코닥 포트라 400으로").lutQuery)
        assertEquals("Velvia", NaturalLanguageParser.parse("벨비아로 해줘").lutQuery)
        assertEquals("Pro 400H", NaturalLanguageParser.parse("일본 스냅 느낌").lutQuery)
        assertEquals("Portra 400", NaturalLanguageParser.parse("필름처럼").lutQuery)
    }

    // ── 강도 부사 ───────────────────────────────────────────────────────
    @Test
    fun intensityAdverbs() {
        val veryLight = NaturalLanguageParser.parse("아주 살짝 부드럽게").hits.single()
        assertTrue(veryLight.strength < 0.3f)

        val light = NaturalLanguageParser.parse("살짝 부드럽게").hits.single()
        assertTrue(light.strength in 0.5f..0.6f)

        val strong = NaturalLanguageParser.parse("확 부드럽게").hits.single()
        assertTrue(strong.strength >= 1.4f)

        val neutralStrength = NaturalLanguageParser.parse("부드럽게").hits.single()
        assertEquals(1f, neutralStrength.strength, 0.001f)

        // 같은 문장이라도 강도에 따라 결과가 작아야 한다
        val gentle = apply("살짝 부드럽게")[Param.CONTRAST]
        val hard = apply("세게 부드럽게")[Param.CONTRAST]
        assertTrue(abs(gentle) < abs(hard))
    }

    // ── 연속 대화 제어 ──────────────────────────────────────────────────
    @Test
    fun dialogueControls() {
        assertEquals(NaturalLanguageParser.Control.UNDO, NaturalLanguageParser.parse("취소").control)
        assertEquals(NaturalLanguageParser.Control.UNDO, NaturalLanguageParser.parse("되돌려줘").control)
        assertEquals(NaturalLanguageParser.Control.REDO, NaturalLanguageParser.parse("다시 실행").control)
        assertEquals(NaturalLanguageParser.Control.RESET, NaturalLanguageParser.parse("초기화").control)
        assertEquals(NaturalLanguageParser.Control.HALF, NaturalLanguageParser.parse("절반만").control)
        assertEquals(NaturalLanguageParser.Control.MORE, NaturalLanguageParser.parse("더").control)
        assertEquals(NaturalLanguageParser.Control.LESS, NaturalLanguageParser.parse("덜").control)
    }

    @Test
    fun halfAndMoreMoveTheRightWay() {
        val session = RecipeSession()
        session.apply("따스하게", IntentCatalog.get("warm")!!.delta)
        val warm = session.current[Param.TEMPERATURE]
        assertTrue(warm > 0.2f)

        session.halveLast()
        val half = session.current[Param.TEMPERATURE]
        assertEquals(warm / 2f, half, 0.02f)

        session.apply("따스하게", IntentCatalog.get("warm")!!.delta)
        val more = session.current[Param.TEMPERATURE]
        assertTrue(more > half)
    }

    // ── 수평·수직 ───────────────────────────────────────────────────────
    @Test
    fun straightenPhrases() {
        listOf(
            "수평 맞춰줘", "수직 수평 맞춰줘", "기울기 잡아줘", "반듯하게", "똑바로",
            "수평이 안 맞아", "삐뚤어졌어 바로잡아줘",
        ).forEach { sentence ->
            val parsed = NaturalLanguageParser.parse(sentence)
            assertTrue("autoStraighten for '$sentence'", parsed.autoStraighten)
        }
    }

    @Test
    fun manualDegrees() {
        val left = NaturalLanguageParser.parse("왼쪽으로 3도 돌려줘")
        assertEquals(3f, left.straightenDeg!!, 0.01f)

        val right = NaturalLanguageParser.parse("오른쪽으로 2.5도")
        assertEquals(-2.5f, right.straightenDeg!!, 0.01f)

        val plain = NaturalLanguageParser.parse("수평 4도")
        assertEquals(4f, plain.straightenDeg!!, 0.01f)
    }

    @Test
    fun multipleIntentsInOneSentence() {
        val parsed = NaturalLanguageParser.parse("조금 따뜻하게 하고 부드럽게")
        assertEquals(2, parsed.hits.size)
        assertTrue(parsed.hits.any { it.intent.key == "warm" })
        assertTrue(parsed.hits.any { it.intent.key == "soft" })
        // "조금"은 바로 뒤 따뜻하게에만 붙는다
        assertTrue(parsed.hits.first { it.intent.key == "warm" }.strength < 1f)
    }

    // ── 미적 평가 (사진 분석 기반) ───────────────────────────────────────
    @Test
    fun aestheticProfilesUseSceneStats() {
        val darkPixels = buildImage(96, 96) { _, _ -> gray(24) }
        val darkStats = SceneAnalyzer.analyze(darkPixels, 96, 96)
        val planDark = AestheticPlanner.plan(AestheticProfile.PRETTY, darkStats)
        val dark = EditRecipe.NEUTRAL.apply(planDark)
        assertTrue(dark[Param.EXPOSURE] > 0f)

        val brightPixels = buildImage(96, 96) { _, _ -> gray(230) }
        val brightStats = SceneAnalyzer.analyze(brightPixels, 96, 96)
        val planBright = AestheticPlanner.plan(AestheticProfile.PRETTY, brightStats)
        val bright = EditRecipe.NEUTRAL.apply(planBright)
        assertTrue(bright[Param.EXPOSURE] < 0f)

        AestheticProfile.entries.forEach { profile ->
            val delta = AestheticPlanner.plan(profile, darkStats)
            assertTrue("$profile should do something", delta.ops.isNotEmpty())
            assertNotNull(delta.summary)
        }
    }

    @Test
    fun typedAestheticRequestsResolve() {
        val parsed = NaturalLanguageParser.parse("SNS 감성으로 예쁘게")
        assertTrue(parsed.matched)
        assertTrue(parsed.hits.any { it.intent.aesthetic != null })
    }
}

internal fun gray(value: Int): Int {
    val v = value.coerceIn(0, 255)
    return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
}

internal fun buildImage(width: Int, height: Int, pixel: (Int, Int) -> Int): IntArray {
    val array = IntArray(width * height)
    for (y in 0 until height) for (x in 0 until width) array[y * width + x] = pixel(x, y)
    return array
}

/** 기울인 줄무늬 패턴을 만들고, 되돌린 뒤 줄이 수평이 되는지 소프트웨어로 확인한다. */
class StraightenEstimatorTest {

    /** 부드러운 사인 줄무늬: 광학적으로 대역 제한된 실제 사진과 가장 비슷한 패턴. */
    private fun sineStripes(width: Int, height: Int, tiltDeg: Float, period: Float = 26f): IntArray {
        val t = Math.toRadians(tiltDeg.toDouble())
        val c = cos(t).toFloat()
        val s = sin(t).toFloat()
        return buildImage(width, height) { x, y ->
            val u = (x - width / 2f) * c + (y - height / 2f) * s
            val v = 128f + 118f * sin(2.0 * Math.PI * (u / period)).toFloat()
            gray(v.toInt())
        }
    }

    /** 살짝 각진(하지만 계단이 아닌) 줄무늬: 3°~5° 기울인 테스트 패턴. */
    private fun softStripes(
        width: Int,
        height: Int,
        tiltDeg: Float,
        period: Float = 34f,
        band: Float = 10f,
    ): IntArray {
        val t = Math.toRadians(tiltDeg.toDouble())
        val c = cos(t).toFloat()
        val s = sin(t).toFloat()
        return buildImage(width, height) { x, y ->
            val d = (x - width / 2f) * -s + (y - height / 2f) * c
            var m = d % period
            if (m < 0f) m += period
            // 줄 중심에서의 부호 있는 거리 (양수 = 줄 안쪽)
            val signed = band / 2f - minOf(m, period - m)
            val alpha = (0.5f + signed / 1.5f).coerceIn(0f, 1f) // 1.5px 폭의 부드러운 경계
            gray((18f + 227f * alpha).toInt())
        }
    }

    @Test
    fun estimatesTiltOfSyntheticStripes() {
        listOf(3f, 5f, -4f).forEach { tilt ->
            val pixels = softStripes(320, 240, tilt)
            val result = StraightenEstimator.estimate(pixels, 320, 240)
            assertEquals("tilt $tilt", tilt, result.degrees, 0.8f)
            assertTrue("confidence for $tilt was ${result.confidence}", result.isReliable)
        }
    }

    @Test
    fun sinePatternIsMeasuredExactly() {
        listOf(3f, 5f, -4f, 0f).forEach { tilt ->
            val pixels = sineStripes(320, 240, tilt)
            val result = StraightenEstimator.estimate(pixels, 320, 240)
            assertEquals("tilt $tilt", tilt, result.degrees, 0.45f)
        }
    }

    @Test
    fun straightImageNeedsNoCorrection() {
        val pixels = softStripes(320, 240, 0f)
        val result = StraightenEstimator.estimate(pixels, 320, 240)
        assertTrue("degrees was ${result.degrees}", abs(result.degrees) < 0.5f)
    }

    @Test
    fun flatImageHasNoEstimate() {
        val pixels = buildImage(200, 150) { _, _ -> gray(128) }
        val result = StraightenEstimator.estimate(pixels, 200, 150)
        assertEquals(0f, result.degrees, 0.001f)
        assertTrue(!result.isReliable)
    }

    /**
     * 추정값을 레시피에 넣고 [GeometryMath.sourceUv]로 되돌려 샘플링하면 줄이 수평이어야 한다.
     * 보정 전보다 줄 안의 편차가 확실히 작아지는지로 확인한다.
     */
    @Test
    fun applyingEstimateLevelsTheLines() {
        val width = 320
        val height = 240
        val aspect = width.toFloat() / height.toFloat()
        listOf(3f, 5f).forEach { tilt ->
            val pixels = softStripes(width, height, tilt)
            val result = StraightenEstimator.estimate(pixels, width, height)
            val corrected = rowSpread(pixels, width, height, result.degrees, aspect)
            val uncorrected = rowSpread(pixels, width, height, 0f, aspect)
            assertTrue(
                "tilt $tilt corrected=$corrected uncorrected=$uncorrected",
                corrected < uncorrected * 0.45f,
            )
        }
    }

    private fun rowSpread(
        pixels: IntArray,
        width: Int,
        height: Int,
        degrees: Float,
        aspect: Float,
    ): Float {
        val scale = GeometryMath.cropScale(degrees, aspect)
        var total = 0f
        var rows = 0
        for (v in 60 until height - 60 step 10) {
            val values = ArrayList<Float>()
            for (u in 60 until width - 60 step 4) {
                val uv = GeometryMath.sourceUv(
                    u.toFloat() / width, v.toFloat() / height, degrees, scale, aspect,
                )
                val sx = (uv[0] * width).toInt().coerceIn(0, width - 1)
                val sy = (uv[1] * height).toInt().coerceIn(0, height - 1)
                values.add((pixels[sy * width + sx] and 0xFF).toFloat())
            }
            val mean = values.average().toFloat()
            total += kotlin.math.sqrt(values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size).toFloat()
            rows++
        }
        return if (rows > 0) total / rows else Float.MAX_VALUE
    }

    @Test
    fun verticalLinesAreHandled() {
        val width = 320
        val height = 240
        val pixels = buildImage(width, height) { x, _ ->
            val v = 128f + 118f * sin(2.0 * Math.PI * (x / 26.0)).toFloat()
            gray(v.toInt())
        }
        val result = StraightenEstimator.estimate(pixels, width, height)
        assertEquals(0f, result.degrees, 0.5f)
        assertTrue(
            "vertical ${result.verticalShare} horizontal ${result.horizontalShare}",
            result.verticalShare > result.horizontalShare,
        )
    }
}

class GeometryMathTest {

    @Test
    fun cropScaleKeepsCornersInside() {
        val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
        listOf(3f / 2f, 1f, 4f / 3f).forEach { aspect ->
            listOf(-12f, -5f, 0f, 3f, 7.5f, 12f).forEach { deg ->
                val scale = GeometryMath.cropScale(deg, aspect)
                assertTrue("scale >= 1 for $deg", scale >= 0.999f)
                corners.forEach { (u, v) ->
                    val uv = GeometryMath.sourceUv(u, v, deg, scale, aspect)
                    assertTrue("u inside at $deg/$aspect: ${uv[0]}", uv[0] in -0.0005f..1.0005f)
                    assertTrue("v inside at $deg/$aspect: ${uv[1]}", uv[1] in -0.0005f..1.0005f)
                }
            }
        }
    }

    @Test
    fun zeroRotationIsIdentity() {
        val uv = GeometryMath.sourceUv(0.3f, 0.7f, 0f, 1f, 1.5f)
        assertEquals(0.3f, uv[0], 1e-5f)
        assertEquals(0.7f, uv[1], 1e-5f)
    }

    @Test
    fun rotationIsSymmetric() {
        val aspect = 4f / 3f
        val forward = GeometryMath.sourceUv(0.5f, 0.2f, 6f, 1f, aspect)
        val backward = GeometryMath.sourceUv(0.5f, 0.2f, -6f, 1f, aspect)
        // 중앙선에서 좌우 대칭으로 벌어진다
        assertEquals(0.5f, (forward[0] + backward[0]) / 2f, 0.001f)
        assertEquals(forward[1], backward[1], 0.001f)
    }
}

class EditRecipeTest {

    @Test
    fun clampsToParamRange() {
        val recipe = EditRecipe.NEUTRAL.with(Param.EXPOSURE, 12f).with(Param.GRAIN, -4f)
        assertEquals(2f, recipe[Param.EXPOSURE], 0.001f)
        assertEquals(0f, recipe[Param.GRAIN], 0.001f)
    }

    @Test
    fun accumulatesAndSaturates() {
        val delta = RecipeDelta.add("따뜻하게", Param.TEMPERATURE, 0.4f)
        val recipe = EditRecipe.NEUTRAL.apply(delta).apply(delta).apply(delta)
        assertEquals(1f, recipe[Param.TEMPERATURE], 0.001f) // 1.2 → clamp 1.0
        assertTrue(recipe.changedParams().contains(Param.TEMPERATURE))
    }

    @Test
    fun setOpsBlendByStrength() {
        val delta = RecipeDelta.set("흑백", Param.MONOCHROME, 1f)
        assertEquals(0.5f, EditRecipe.NEUTRAL.apply(delta, 0.5f)[Param.MONOCHROME], 0.001f)
        assertEquals(1f, EditRecipe.NEUTRAL.apply(delta, 1f)[Param.MONOCHROME], 0.001f)
    }

    @Test
    fun jsonRoundTrip() {
        val recipe = EditRecipe.NEUTRAL
            .with(Param.EXPOSURE, 0.42f)
            .with(Param.SPLIT_SHADOW_HUE, 197f)
            .with(Param.LUT_INDEX, 23f)
            .with(Param.STRAIGHTEN_DEG, -2.4f)
        val restored = EditRecipe.fromJson(recipe.toJson())
        Param.entries.forEach { param ->
            assertEquals(param.name, recipe[param], restored[param], 1e-4f)
        }
    }

    @Test
    fun unknownJsonKeysAreIgnored() {
        val recipe = EditRecipe.fromJson("""{"version":1,"params":{"exposure":0.5,"bogus":3}}""")
        assertEquals(0.5f, recipe[Param.EXPOSURE], 0.001f)
        assertEquals(EditRecipe.NEUTRAL[Param.CONTRAST], recipe[Param.CONTRAST], 0.001f)
    }

    @Test
    fun undoRedoAndReset() {
        val session = RecipeSession()
        val warm = IntentCatalog.get("warm")!!.delta
        session.apply("따스하게", warm)
        session.apply("필름처럼", IntentCatalog.get("film")!!.delta)
        assertTrue(session.current[Param.TEMPERATURE] > 0f)
        assertTrue(session.current[Param.GRAIN] > 0f)

        session.undo()
        assertTrue(session.current[Param.GRAIN] == 0f)
        assertTrue(session.current[Param.TEMPERATURE] > 0f)

        session.redo()
        assertTrue(session.current[Param.GRAIN] > 0f)

        session.reset()
        assertTrue(session.current.isNeutral)
    }

    @Test
    fun repeatAndNegate() {
        val delta = RecipeDelta.add("따뜻하게", Param.TEMPERATURE, 0.2f)
        val recipe = EditRecipe.NEUTRAL.apply(delta).apply(delta.negated())
        assertEquals(0f, recipe[Param.TEMPERATURE], 1e-4f)
    }

    @Test
    fun neutralIsNeutral() {
        assertTrue(EditRecipe.NEUTRAL.isNeutral)
        assertTrue(EditRecipe.NEUTRAL.toJson().contains("params"))
        assertEquals(0, EditRecipe.NEUTRAL.changedParams().size)
    }
}

class LutIntegrityTest {

    private fun inflate(packed: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(packed)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val read = inflater.inflate(buffer)
            if (read == 0) break
            out.write(buffer, 0, read)
        }
        inflater.end()
        return out.toByteArray()
    }

    @Test
    fun allFiftySevenLutsDecodeToTheOriginalBytes() {
        val assets = File("src/main/assets/luts")
        val residuals = inflate(File(assets, "lut_residuals.bin").readBytes())
        val catalog = PresetCatalog.parse(File(assets, "presets.json").readText())
        assertEquals(57, catalog.size)
        assertEquals(PresetCatalog.EXPECTED_COUNTS, PresetCatalog.countByBrand(catalog))

        val golden = Json.asMap(Json.parse(File("src/test/resources/lut_golden.json").readText()))
        val entries = Json.asList(Json.mapOf(golden, "presets"))
        assertEquals(57, entries.size)

        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            val idx = Json.asInt(Json.mapOf(entry, "idx"))
            val lut = LorenzoDecoder.decode(residuals, idx)
            assertEquals(17 * 17 * 17 * 3, lut.size)
            val hash = digest.digest(lut).joinToString("") { "%02x".format(it) }
            assertEquals("LUT $idx sha256", Json.asString(Json.mapOf(entry, "sha256")), hash)

            Json.asList(Json.mapOf(entry, "samples")).forEach { sample ->
                val input = Json.asList(Json.mapOf(sample, "input")).map { Json.asInt(it) }
                val output = Json.asList(Json.mapOf(sample, "output")).map { Json.asInt(it) }
                val offset = LorenzoDecoder.offset(input[0], input[1], input[2])
                assertEquals(output[0], lut[offset].toInt() and 0xFF)
                assertEquals(output[1], lut[offset + 1].toInt() and 0xFF)
                assertEquals(output[2], lut[offset + 2].toInt() and 0xFF)
            }
        }
    }

    @Test
    fun lutPresetsAreOrderedAndUnique() {
        val catalog = PresetCatalog.parse(File("src/main/assets/luts/presets.json").readText())
        assertEquals((1..57).toList(), catalog.map { it.slot })
        assertEquals((0..56).toList(), catalog.map { it.idx })
        assertEquals(57, catalog.map { it.display }.distinct().size)
        assertTrue(catalog.any { it.brand == "Leica" })
        assertTrue(catalog.any { it.brand == "Ricoh GR" })
    }
}

class LlmRecipeDecoderTest {

    @Test
    fun parsesCleanJson() {
        val decoded = LlmRecipeDecoder.decode(
            """{"label":"따뜻하게","summary":"색온도를 올렸어요","adjust":{"temperature":0.6,"highlights":-0.2}}""",
        )
        assertNotNull(decoded)
        val recipe = EditRecipe.NEUTRAL.apply(decoded!!.delta)
        assertTrue(recipe[Param.TEMPERATURE] > 0.2f)
        assertTrue(recipe[Param.HIGHLIGHTS] < 0f)
    }

    @Test
    fun parsesJsonWrappedInProseAndFences() {
        val raw = """
            좋습니다. 아래처럼 적용할게요.
            ```json
            {"label":"필름","adjust":{"grain":0.5,"fade":0.3}}
            ```
            위 설정을 적용했습니다.
        """.trimIndent()
        val decoded = LlmRecipeDecoder.decode(raw)
        assertNotNull(decoded)
        val recipe = EditRecipe.NEUTRAL.apply(decoded!!.delta)
        assertTrue(recipe[Param.GRAIN] > 0f)
        assertTrue(recipe[Param.FADE] > 0f)
    }

    @Test
    fun ignoresUnknownAndDangerousKeys() {
        val decoded = LlmRecipeDecoder.decode(
            """{"adjust":{"exposure":0.4,"lutIndex":30,"straightenDeg":9,"bogus":1}}""",
        )
        assertNotNull(decoded)
        val recipe = EditRecipe.NEUTRAL.apply(decoded!!.delta)
        assertTrue(recipe[Param.EXPOSURE] > 0f)
        // 기하와 룩 선택은 모델이 건드리지 않는다
        assertEquals(EditRecipe.NEUTRAL[Param.LUT_INDEX], recipe[Param.LUT_INDEX], 0.001f)
        assertEquals(0f, recipe[Param.STRAIGHTEN_DEG], 0.001f)
    }

    @Test
    fun clampsOutOfRangeValues() {
        // 100 이하의 큰 수는 퍼센트로 읽는다 (모델이 17%, 40% 처럼 답하는 경우)
        val percent = LlmRecipeDecoder.decode("""{"adjust":{"exposure":17}}""")
        assertNotNull(percent)
        val small = EditRecipe.NEUTRAL.apply(percent!!.delta)
        assertTrue(small[Param.EXPOSURE] > 0f)
        assertTrue(small[Param.EXPOSURE] < 0.3f)

        // 100을 넘으면 +-1.0으로 자른다. 노출 최대는 +0.70 EV.
        val huge = LlmRecipeDecoder.decode("""{"adjust":{"exposure":500}}""")
        assertNotNull(huge)
        val strong = EditRecipe.NEUTRAL.apply(huge!!.delta)
        assertEquals(0.7f, strong[Param.EXPOSURE], 0.01f)
    }

    @Test
    fun rejectsGarbage() {
        assertEquals(null, LlmRecipeDecoder.decode("죄송합니다만 잘 모르겠습니다."))
        assertEquals(null, LlmRecipeDecoder.decode("""{"adjust":{}}"""))
        assertEquals(null, LlmRecipeDecoder.decode("""{"adjust":{"nothing":1}}"""))
    }

    @Test
    fun promptListsAllowedKeysAndRequest() {
        val prompt = LlmRecipeDecoder.buildPrompt("따뜻하고 부드럽게", listOf("Kodak Portra 400"))
        assertTrue(prompt.contains("따뜻하고 부드럽게"))
        assertTrue(prompt.contains("exposure"))
        assertTrue(prompt.contains("Kodak Portra 400"))
    }
}

class SceneAnalyzerTest {

    @Test
    fun measuresDarkAndBrightFrames() {
        val dark = SceneAnalyzer.analyze(buildImage(80, 60) { _, _ -> gray(20) }, 80, 60)
        assertTrue(dark.isDark)
        assertTrue(dark.meanLuma < 0.2f)

        val bright = SceneAnalyzer.analyze(buildImage(80, 60) { _, _ -> gray(253) }, 80, 60)
        assertTrue(bright.isBright)
        assertTrue(bright.highlightClip > 0f)
    }

    @Test
    fun detectsWarmCastAndLowSaturation() {
        val warm = buildImage(60, 60) { _, _ -> (0xFF shl 24) or (200 shl 16) or (120 shl 8) or 90 }
        val stats = SceneAnalyzer.analyze(warm, 60, 60)
        assertTrue(stats.isWarm)
        assertTrue(stats.saturationMean > 0.1f)
    }

    @Test
    fun emptyInputIsSafe() {
        val stats = SceneAnalyzer.analyze(IntArray(0), 0, 0)
        assertEquals(0, stats.samples)
        assertTrue(AestheticPlanner.plan(AestheticProfile.PRO, stats).ops.isNotEmpty())
    }
}
