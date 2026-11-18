package com.plenuen.lumen.engine

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.nima.NimaOptimizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The 36-scene comparison: v2.2.5's single-parameter walk against v2.3's diagnosis-guided,
 * multi-variable, multi-metric search.
 *
 * Everything here runs on the JVM: scenes are drawn by [SyntheticScenes], candidates are rendered by
 * [CpuPipeline] (the arithmetic of the shader's tone/colour/region blocks) and ranked by
 * [ProxyAesthetic] standing in for NIMA. The report says plainly which numbers are synthetic and
 * which still need the S25 Ultra; the Python script `tools/bench_nima.py` re-scores the same recipes
 * with the real TFLite model outside the search loop.
 *
 * Metrics are measured *after* the search, on the final frame, by code the engines cannot see: they
 * are the questions a person would ask — is the sky still there, is the face still a face, does the
 * subject stand apart, did the picture change more than it needed to.
 */
class EngineBenchmarkTest {

    private val size = SyntheticScenes.SIZE

    @Test
    fun diagnosisRecognisesTheSceneFamilies() {
        val scenes = SyntheticScenes.all()
        assertEquals("36 synthetic frames", 36, scenes.size)
        var matched = 0
        val rows = mutableListOf<String>()
        for (scene in scenes) {
            val diagnosis = PhotoDiagnosisEngine.diagnose(
                scene.argb, scene.width, scene.height, scene.subject, scene.sky,
            )
            val ok = familyMatches(scene.category, diagnosis.scene)
            if (ok) matched++
            rows += String.format(
                Locale.US,
                "%-12s %-8s → %-10s subject=%s sky=%s clip=%.1f%% shadow=%.1f%% wb=%+.2f",
                scene.name,
                scene.category,
                diagnosis.scene.label,
                if (diagnosis.hasSubject) "Y" else "-",
                if (diagnosis.hasSky) "Y" else "-",
                diagnosis.global.highlightClip * 100,
                diagnosis.global.shadowClip * 100,
                diagnosis.whiteBalance.temperatureFix,
            )
        }
        println("── 사진 진단 분류 ──")
        rows.forEach { println(it) }
        assertTrue("장면 분류 정확도 ${matched}/36", matched >= 30)
    }

    @Test
    fun v23BeatsV225OnTheSyntheticSet() {
        val scenes = SyntheticScenes.all()
        val rows = mutableListOf<Row>()
        val json = StringBuilder()
        json.append("{\"scenes\":[")
        val outDir = File("build/engine-benchmark").apply { mkdirs() }
        val sceneDir = File(outDir, "scenes").apply { mkdirs() }

        scenes.forEachIndexed { index, scene ->
            val night = SyntheticScenes.isNight(scene.argb)
            val original = PixelSample(scene.argb, size, size, scene.subject, scene.sky)
            val diagnosis = PhotoDiagnosisEngine.diagnose(
                scene.argb, size, size, scene.subject, scene.sky,
            )

            // ── v2.2.5: 한 번에 한 슬라이더, NIMA(프록시)만 보고 판단 ──
            val legacyStart = System.nanoTime()
            val legacy = LegacyV225.optimize(
                base = EditRecipe.NEUTRAL,
                scorer = { recipe ->
                    ProxyAesthetic.score(
                        CpuPipeline.render(scene.argb, size, size, recipe, scene.subject, scene.sky),
                        size,
                        size,
                        night,
                    )
                },
            )
            val legacyMs = (System.nanoTime() - legacyStart) / 1_000_000

            // ── v2.3: 진단 → 다변수 후보 → 다중 품질 평가 → 하드 탈락 게이트 ──
            val v23Start = System.nanoTime()
            val v23 = RecipeSearch.search(
                base = EditRecipe.NEUTRAL,
                diagnosis = diagnosis,
                renderer = RecipeSearch.Renderer(
                    render = { recipe -> CpuPipeline.render(scene.argb, size, size, recipe, scene.subject, scene.sky) },
                    nima = { pixels -> ProxyAesthetic.score(pixels, size, size, night) },
                    baseSample = original,
                    baseNima = ProxyAesthetic.score(scene.argb, size, size, night),
                ),
                settings = RecipeSearch.Settings(
                    maxEvaluations = 48,
                    timeBudgetMs = 20_000,
                    coarsePool = 14,
                    finalists = 3,
                    minGain = 0.004f,
                ),
            )
            val v23Ms = (System.nanoTime() - v23Start) / 1_000_000

            val legacyFrame = CpuPipeline.render(scene.argb, size, size, legacy.recipe, scene.subject, scene.sky)
            val v23Frame = CpuPipeline.render(scene.argb, size, size, v23.recipe, scene.subject, scene.sky)

            val legacyMetrics = metrics(scene, legacyFrame, diagnosis, night)
            val v23Metrics = metrics(scene, v23Frame, diagnosis, night)

            val row = Row(
                name = scene.name,
                category = scene.category,
                scene = diagnosis.scene.label,
                legacy = legacyMetrics,
                v23 = v23Metrics,
                legacyEvaluations = legacy.evaluations,
                v23Evaluations = v23.evaluations,
                legacyMs = legacyMs,
                v23Ms = v23Ms,
                rejected = v23.rejected,
                legacyRecipe = legacy.recipe,
                v23Recipe = v23.recipe,
            )
            rows += row

            // 유닛 테스트 클래스패스에는 java.desktop(ImageIO)이 없다 → 표준 PPM(P6)로 쓴다.
            // Pillow·ImageMagick·GIMP 모두 읽고, Python 교차 검증(tools/bench_nima.py)이 그대로 쓴다.
            writePpm(File(sceneDir, "${scene.name}-original.ppm"), scene.argb, size, size)
            writePpm(File(sceneDir, "${scene.name}-v225.ppm"), legacyFrame, size, size)
            writePpm(File(sceneDir, "${scene.name}-v230.ppm"), v23Frame, size, size)
            writeMask(File(sceneDir, "${scene.name}-mask.ppm"), scene.subject, scene.sky, size)

            json.append(row.toJson())
            if (index < scenes.lastIndex) json.append(',')
        }
        json.append("]}")
        File(outDir, "benchmark-v23.json").writeText(json.toString())

        report(rows)

        // ── 결과 비교: 방향이 아니라 실제 수치로 확인한다 ──
        val legacyClip = rows.map { it.legacy.highlightClip }.average()
        val v23Clip = rows.map { it.v23.highlightClip }.average()
        val legacySkin = rows.mapNotNull { it.legacy.skinDrift }.average()
        val v23Skin = rows.mapNotNull { it.v23.skinDrift }.average()
        val legacySeparation = rows.mapNotNull { it.legacy.separation }.average()
        val v23Separation = rows.mapNotNull { it.v23.separation }.average()
        val legacyChange = rows.map { it.legacy.meanChange }.average()
        val v23Change = rows.map { it.v23.meanChange }.average()
        val legacyQuality = rows.map { it.legacy.quality }.average()
        val v23Quality = rows.map { it.v23.quality }.average()
        val legacyBlew = rows.count { it.legacy.highlightClip > it.legacy.baseHighlightClip + 0.02 }
        val v23Blew = rows.count { it.v23.highlightClip > it.v23.baseHighlightClip + 0.02 }
        val legacySkinBad = rows.count { (it.legacy.skinDrift ?: 0f) > 1f }
        val v23SkinBad = rows.count { (it.v23.skinDrift ?: 0f) > 1f }

        println(
            String.format(
                Locale.US,
                "요약: 하이라이트 클리핑 %.2f%% → %.2f%%, 피부 이탈 %.2f → %.2f, " +
                    "분리 %.3f → %.3f, 평균 변화 %.3f → %.3f, 품질 %.3f → %.3f, " +
                    "클리핑 악화 %d→%d장, 피부 악화 %d→%d장",
                legacyClip * 100, v23Clip * 100, legacySkin, v23Skin,
                legacySeparation, v23Separation, legacyChange, v23Change,
                legacyQuality, v23Quality, legacyBlew, v23Blew, legacySkinBad, v23SkinBad,
            ),
        )

        assertTrue("v2.3이 하이라이트 클리핑을 늘리지 않아야 한다", v23Clip <= legacyClip + 0.002)
        // 피부 이탈은 1.0이 하드 게이트 문턱이다. 두 엔진 모두 그보다 훨씬 아래여야 하고,
        // v2.3은 '게이트에 걸리는 사진'이 한 장도 없어야 한다.
        assertTrue("v2.3의 평균 피부 이탈이 게이트 문턱보다 충분히 낮아야 한다 ($v23Skin)", v23Skin < 0.35)
        assertTrue("v2.3에서 피부가 무너진 사진이 없어야 한다 ($v23SkinBad)", v23SkinBad == 0)
        assertTrue("v2.3이 피사체 분리를 더 살려야 한다", v23Separation >= legacySeparation - 0.01)
        assertTrue("v2.3이 사진을 덜 망가뜨려야 한다", v23Change <= legacyChange + 0.02)
        assertTrue("v2.3 품질 점수가 높아야 한다 ($legacyQuality → $v23Quality)", v23Quality > legacyQuality)
        assertTrue("하늘을 태우는 사진이 v2.3에서 늘어나서는 안 된다 ($legacyBlew → $v23Blew)", v23Blew <= legacyBlew)
    }

    @Test
    fun theSearchIsDeterministic() {
        val scene = SyntheticScenes.all().first { it.category == "인물" }
        val diagnosis = PhotoDiagnosisEngine.diagnose(scene.argb, size, size, scene.subject, scene.sky)
        fun run(): EditRecipe {
            val base = PixelSample(scene.argb, size, size, scene.subject, scene.sky)
            return RecipeSearch.search(
                base = EditRecipe.NEUTRAL,
                diagnosis = diagnosis,
                renderer = RecipeSearch.Renderer(
                    render = { recipe -> CpuPipeline.render(scene.argb, size, size, recipe, scene.subject, scene.sky) },
                    nima = { pixels -> ProxyAesthetic.score(pixels, size, size, false) },
                    baseSample = base,
                    baseNima = ProxyAesthetic.score(scene.argb, size, size, false),
                ),
                settings = RecipeSearch.Settings(maxEvaluations = 24, coarsePool = 10, timeBudgetMs = 20_000),
            ).recipe
        }
        assertEquals(run(), run())
    }

    @Test
    fun theGuidedSearchNeverChangesGeometryOrTheLook() {
        val scene = SyntheticScenes.all().first { it.category == "인물" }
        val diagnosis = PhotoDiagnosisEngine.diagnose(scene.argb, size, size, scene.subject, scene.sky)
        val base = EditRecipe.of(
            com.plenuen.lumen.core.Param.LUT_INDEX to 12f,
            com.plenuen.lumen.core.Param.LUT_INTENSITY to 0.8f,
            com.plenuen.lumen.core.Param.STRAIGHTEN_DEG to 1.5f,
        )
        val sample = PixelSample(scene.argb, size, size, scene.subject, scene.sky)
        val outcome = RecipeSearch.search(
            base = base,
            diagnosis = diagnosis,
            renderer = RecipeSearch.Renderer(
                render = { recipe -> CpuPipeline.render(scene.argb, size, size, recipe, scene.subject, scene.sky) },
                nima = { pixels -> ProxyAesthetic.score(pixels, size, size, false) },
                baseSample = sample,
                baseNima = ProxyAesthetic.score(scene.argb, size, size, false),
            ),
            settings = RecipeSearch.Settings(maxEvaluations = 24, coarsePool = 10, timeBudgetMs = 20_000),
        )
        assertEquals("룩은 건드리지 않는다", 12f, outcome.recipe[com.plenuen.lumen.core.Param.LUT_INDEX], 0f)
        assertEquals("수평도 건드리지 않는다", 1.5f, outcome.recipe[com.plenuen.lumen.core.Param.STRAIGHTEN_DEG], 0f)
        assertNotNull(outcome.baseQuality)
    }

    @Test
    fun thePhonePathCanStillRunWithoutPixels() {
        // NIMA만 아는 경로(픽셀 없음)도 다변수 탐색으로 동작해야 한다.
        val adjustment = NimaOptimizer.optimize(
            base = EditRecipe.NEUTRAL,
            scorer = { recipe -> 5f + 2f * recipe[com.plenuen.lumen.core.Param.EXPOSURE] },
        )
        assertTrue(adjustment.recipe[com.plenuen.lumen.core.Param.EXPOSURE] > 0.1f)
    }

    // ── 측정 ─────────────────────────────────────────────────────────────

    private data class Metrics(
        val highlightClip: Float,
        val shadowClip: Float,
        val meanLuma: Float,
        val skinDrift: Float?,
        val separation: Float?,
        val meanChange: Float,
        val proxyScore: Float,
        val quality: Float,
        val qualityRejected: Boolean,
        val baseHighlightClip: Float,
    )

    private fun metrics(
        scene: SyntheticScene,
        frame: IntArray,
        diagnosis: PhotoDiagnosis,
        night: Boolean,
    ): Metrics {
        val original = PixelSample(scene.argb, size, size, scene.subject, scene.sky)
        val candidate = PixelSample(frame, size, size, scene.subject, scene.sky)
        val skinDrift = skinDriftOf(original, candidate)
        val separation = QualityEvaluator.separationOf(candidate, diagnosis)
        val report = QualityEvaluator.evaluate(
            base = original,
            candidate = candidate,
            baseRecipe = EditRecipe.NEUTRAL,
            candidateRecipe = EditRecipe.NEUTRAL,
            diagnosis = diagnosis,
            nima = ProxyAesthetic.score(frame, size, size, night),
            nimaBase = ProxyAesthetic.score(scene.argb, size, size, night),
        )
        return Metrics(
            highlightClip = candidate.highlightClip,
            shadowClip = candidate.shadowClip,
            meanLuma = candidate.meanLuma,
            skinDrift = skinDrift,
            separation = separation,
            meanChange = QualityEvaluator.meanLumaChange(scene.argb, frame),
            proxyScore = ProxyAesthetic.score(frame, size, size, night),
            quality = report.total,
            qualityRejected = report.rejected != null,
            baseHighlightClip = original.highlightClip,
        )
    }

    /** 0 = 피부가 그대로, 1을 넘으면 하드 게이트가 버리는 수준. */
    private fun skinDriftOf(before: PixelSample, after: PixelSample): Float? {
        val a = before.skin ?: return null
        val b = after.skin ?: return null
        if (a.coverage < 0.02f) return null
        var hue = kotlin.math.abs(b.hueDeg - a.hueDeg)
        if (hue > 180f) hue = 360f - hue
        val huePart = ((hue - 4f).coerceAtLeast(0f)) / 11f
        val chromaPart = ((b.chroma - a.chroma - 0.03f).coerceAtLeast(0f)) / 0.16f
        val lumaPart = ((kotlin.math.abs(b.luma - a.luma) - 0.16f).coerceAtLeast(0f)) / 0.30f
        return (huePart + chromaPart + lumaPart).coerceIn(0f, 2f)
    }

    private data class Row(
        val name: String,
        val category: String,
        val scene: String,
        val legacy: Metrics,
        val v23: Metrics,
        val legacyEvaluations: Int,
        val v23Evaluations: Int,
        val legacyMs: Long,
        val v23Ms: Long,
        val rejected: Int,
        val legacyRecipe: EditRecipe,
        val v23Recipe: EditRecipe,
    ) {
        fun toJson(): String = buildString {
            append("{")
            append("\"name\":\"").append(name).append("\",")
            append("\"category\":\"").append(category).append("\",")
            append("\"scene\":\"").append(scene).append("\",")
            append("\"legacyRecipe\":").append(recipeJson(legacyRecipe)).append(',')
            append("\"v23Recipe\":").append(recipeJson(v23Recipe)).append(',')
            append("\"legacy\":").append(metricsJson(legacy)).append(',')
            append("\"v23\":").append(metricsJson(v23)).append(',')
            append("\"legacyEvaluations\":").append(legacyEvaluations).append(',')
            append("\"v23Evaluations\":").append(v23Evaluations).append(',')
            append("\"legacyMs\":").append(legacyMs).append(',')
            append("\"v23Ms\":").append(v23Ms).append(',')
            append("\"rejected\":").append(rejected)
            append("}")
        }

        private fun metricsJson(m: Metrics): String = String.format(
            Locale.US,
            "{\"highlightClip\":%.4f,\"shadowClip\":%.4f,\"meanLuma\":%.4f,\"skinDrift\":%s," +
                "\"separation\":%s,\"meanChange\":%.4f,\"proxyScore\":%.3f,\"quality\":%.4f," +
                "\"rejected\":%s}",
            m.highlightClip, m.shadowClip, m.meanLuma,
            m.skinDrift?.let { String.format(Locale.US, "%.3f", it) } ?: "null",
            m.separation?.let { String.format(Locale.US, "%.3f", it) } ?: "null",
            m.meanChange, m.proxyScore, m.quality, m.qualityRejected,
        )

        private fun recipeJson(recipe: EditRecipe): String =
            recipe.toMap().entries.joinToString(",", "{", "}") { (key, value) ->
                String.format(Locale.US, "\"%s\":%.4f", key, value)
            }
    }

    private fun report(rows: List<Row>) {
        println("── 36장 비교 (합성 장면 · CPU 파이프라인 · NIMA 프록시) ──")
        println(
            String.format(
                Locale.US,
                "%-14s %-8s %-8s | %-17s | %-17s | %s",
                "장면", "진단", "v2.2.5", "클리핑 H/S", "v2.3 클리핑 H/S", "품질 L→V23",
            ),
        )
        rows.forEach { row ->
            println(
                String.format(
                    Locale.US,
                    "%-14s %-8s %-8s | %4.1f/%4.1f (피부 %s) | %4.1f/%4.1f (피부 %s) | %.3f→%.3f %s",
                    row.name,
                    row.scene,
                    row.category,
                    row.legacy.highlightClip * 100,
                    row.legacy.shadowClip * 100,
                    row.legacy.skinDrift?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
                    row.v23.highlightClip * 100,
                    row.v23.shadowClip * 100,
                    row.v23.skinDrift?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
                    row.legacy.quality,
                    row.v23.quality,
                    if (row.v23.qualityRejected) "탈락" else "",
                ),
            )
        }
        val legacyQuality = rows.map { it.legacy.quality }.average()
        val v23Quality = rows.map { it.v23.quality }.average()
        println(
            String.format(
                Locale.US,
                "평균 평가 횟수 v2.2.5 %.1f → v2.3 %.1f, 평균 시간 v2.2.5 %dms → v2.3 %dms",
                rows.map { it.legacyEvaluations }.average(),
                rows.map { it.v23Evaluations }.average(),
                rows.map { it.legacyMs }.average().toLong(),
                rows.map { it.v23Ms }.average().toLong(),
            ),
        )
        println(String.format(Locale.US, "평균 품질 %.3f → %.3f", legacyQuality, v23Quality))
    }

    private fun familyMatches(category: String, kind: SceneKind): Boolean = when (category) {
        "인물" -> kind == SceneKind.PORTRAIT || kind == SceneKind.INDOOR
        "역광 인물" -> kind == SceneKind.BACKLIT_PORTRAIT || kind == SceneKind.PORTRAIT
        "풍경" -> kind == SceneKind.LANDSCAPE || kind == SceneKind.FOOD || kind == SceneKind.GENERAL
        "과노출 풍경" -> kind == SceneKind.OVEREXPOSED_LANDSCAPE || kind == SceneKind.LANDSCAPE
        "야경" -> kind == SceneKind.NIGHT || kind == SceneKind.INDOOR
        "실내" -> kind == SceneKind.INDOOR || kind == SceneKind.NIGHT
        "음식" -> kind == SceneKind.FOOD || kind == SceneKind.GENERAL || kind == SceneKind.INDOOR
        "저대비" -> kind == SceneKind.FLAT || kind == SceneKind.GENERAL
        "색편향" -> kind == SceneKind.COLOR_CAST || kind == SceneKind.GENERAL ||
            kind == SceneKind.FOOD || kind == SceneKind.FLAT
        else -> true
    }

    /** The region mask in the channel order the shader uses: R = subject, G = sky. */
    private fun writeMask(file: File, subject: FloatArray?, sky: FloatArray?, side: Int) {
        val argb = IntArray(side * side) { index ->
            val s = ((subject?.getOrNull(index) ?: 0f).coerceIn(0f, 1f) * 255f).toInt()
            val k = ((sky?.getOrNull(index) ?: 0f).coerceIn(0f, 1f) * 255f).toInt()
            (0xFF shl 24) or (s shl 16) or (k shl 8)
        }
        writePpm(file, argb, side, side)
    }

    /** Binary PPM (P6): the simplest lossless format a test can write without java.desktop. */
    private fun writePpm(file: File, argb: IntArray, width: Int, height: Int) {
        file.outputStream().buffered().use { out ->
            out.write("P6\n$width $height\n255\n".toByteArray())
            val row = ByteArray(width * 3)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val px = argb[y * width + x]
                    row[x * 3] = ((px shr 16) and 0xFF).toByte()
                    row[x * 3 + 1] = ((px shr 8) and 0xFF).toByte()
                    row[x * 3 + 2] = (px and 0xFF).toByte()
                }
                out.write(row)
            }
        }
    }
}
