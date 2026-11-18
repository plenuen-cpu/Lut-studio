package com.plenuen.lumen.engine

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.nima.NimaOptimizer
import com.plenuen.lumen.nl.PromptEngine
import com.plenuen.lumen.nl.NaturalLanguageParser
import com.plenuen.lumen.core.RecipeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The v2.3 engine, one guarantee at a time.
 *
 * The three pieces are tested separately because they fail differently: the diagnosis can mislabel a
 * scene (cheap), the quality evaluator can accept a photo that was wrecked (expensive), and the
 * search can ignore what it was told (silent). Each test below states the promise in the failure
 * message so a red build says what broke, not just where.
 */
class PhotoDiagnosisTest {

    private val size = SyntheticScenes.SIZE

    private fun scene(category: String): SyntheticScene =
        SyntheticScenes.all().first { it.category == category }

    private fun diagnose(scene: SyntheticScene): PhotoDiagnosis =
        PhotoDiagnosisEngine.diagnose(scene.argb, size, size, scene.subject, scene.sky)

    @Test
    fun readsExposureClipAndRegionsFromTheFrame() {
        val backlit = diagnose(scene("역광 인물"))
        assertEquals("역광 인물로 읽어야 한다", SceneKind.BACKLIT_PORTRAIT, backlit.scene)
        assertNotNull("피사체 영역이 있어야 한다", backlit.subject)
        assertNotNull("배경 영역이 있어야 한다", backlit.background)
        assertTrue(
            "배경이 피사체보다 밝아야 한다",
            backlit.background!!.meanLuma > backlit.subject!!.meanLuma + 0.1f,
        )
        assertTrue("피사체가 배경에 묻히는 문제를 찾아야 한다", backlit.issues.contains(Issue.LOW_SEPARATION))

        val overexposed = diagnose(scene("과노출 풍경"))
        assertTrue("하늘 하이라이트 클리핑을 재야 한다", overexposed.global.highlightClip > 0.02f)
        assertTrue("하늘이 날아간 문제를 찾아야 한다", overexposed.issues.contains(Issue.BLOWN_SKY) ||
            overexposed.issues.contains(Issue.HIGHLIGHT_CLIP))
        assertEquals(SceneKind.OVEREXPOSED_LANDSCAPE, overexposed.scene)
    }

    @Test
    fun readsTheSceneFamilyOfEveryFrame() {
        val night = diagnose(scene("야경"))
        assertEquals(SceneKind.NIGHT, night.scene)
        assertTrue("야경은 어두워야 한다", night.global.meanLuma < 0.33f)

        val flat = diagnose(scene("저대비"))
        assertTrue(
            "저대비는 명암 범위가 좁아야 한다 (${flat.global.contrast})",
            flat.global.contrast < 0.5f,
        )
        assertTrue(flat.issues.contains(Issue.FLAT_CONTRAST))

        val cast = diagnose(scene("색편향"))
        assertTrue("색편향을 잡아야 한다 (${cast.whiteBalance.magnitude})", cast.whiteBalance.magnitude > 0.03f)
        assertNotEqualsZero(cast.whiteBalance.temperatureFix)

        val food = diagnose(scene("음식"))
        assertTrue("음식/일반으로 읽어야 한다: ${food.scene}", food.scene == SceneKind.FOOD || food.scene == SceneKind.GENERAL)
    }

    private fun assertNotEqualsZero(value: Float) {
        assertTrue("색온도 교정 방향이 계산되어야 한다", abs(value) > 1e-4f)
    }

    @Test
    fun readsSkinInsideTheSubjectMask() {
        val portrait = diagnose(scene("인물"))
        val skin = portrait.skin
        assertNotNull("인물 사진에서 피부를 찾아야 한다", skin)
        assertTrue("피부 비율 ${skin!!.coverage}", skin.coverage > 0.05f)
        assertTrue("피부 색조 ${skin.meanHueDeg}°", skin.meanHueDeg in 8f..52f)
        assertTrue("피부 채도 ${skin.meanChroma}", skin.meanChroma in 0.08f..0.62f)
    }

    @Test
    fun eachSceneGivesItsOwnStrategy() {
        val portrait = diagnose(scene("인물"))
        val night = diagnose(scene("야경"))
        val landscape = diagnose(scene("풍경"))

        assertTrue("인물은 피부톤 보호를 후보에 넣는다",
            portrait.strategy.seeds.any { it.containsKey(Param.SUBJECT_SKIN_PROTECT) })
        assertTrue("풍경은 하늘을 후보에 넣는다",
            landscape.strategy.seeds.any { it.containsKey(Param.SKY_EXPOSURE) })
        // 야경은 섀도우를 크게 열지 않는다 — 노이즈가 되기 때문.
        val nightShadowLimit = night.strategy.limits[Param.SHADOWS] ?: 1f
        val flatShadowLimit = diagnose(scene("저대비")).strategy.limits[Param.SHADOWS] ?: 1f
        assertTrue("야경의 섀도우 상한($nightShadowLimit)이 저대비($flatShadowLimit)보다 낮아야 한다",
            nightShadowLimit < flatShadowLimit)

        assertTrue("전략마다 다른 라벨", portrait.strategy.label != night.strategy.label)
    }

    @Test
    fun anEmptyFrameIsNotMeasuredAndSteersNothing() {
        val unknown = PhotoDiagnosis.UNKNOWN
        assertFalse(unknown.measured)
        assertTrue(unknown.strategy.seeds.isEmpty())
        assertEquals("사진을 읽지 못했어요.", unknown.summary())
        assertEquals("", unknown.promptHint())
    }

    @Test
    fun promptHintCarriesOnlyWhatChangesTheAnswer() {
        val hint = diagnose(scene("역광 인물")).promptHint()
        assertTrue(hint, hint.contains("역광 인물"))
        assertTrue("한 줄이어야 한다: $hint", hint.length < 120)
    }
}

class QualityEvaluatorTest {

    private val size = SyntheticScenes.SIZE

    private fun scene(category: String): SyntheticScene =
        SyntheticScenes.all().first { it.category == category }

    private fun report(
        scene: SyntheticScene,
        recipe: EditRecipe,
        nima: Float? = null,
    ): QualityReport {
        val base = PixelSample(scene.argb, size, size, scene.subject, scene.sky)
        val candidatePixels = CpuPipeline.render(scene.argb, size, size, recipe, scene.subject, scene.sky)
        val candidate = PixelSample(candidatePixels, size, size, scene.subject, scene.sky)
        val diagnosis = PhotoDiagnosisEngine.diagnose(scene.argb, size, size, scene.subject, scene.sky)
        return QualityEvaluator.evaluate(
            base = base,
            candidate = candidate,
            baseRecipe = EditRecipe.NEUTRAL,
            candidateRecipe = recipe,
            diagnosis = diagnosis,
            nima = nima ?: ProxyAesthetic.score(candidatePixels, size, size),
            nimaBase = ProxyAesthetic.score(scene.argb, size, size),
        )
    }

    @Test
    fun aCandidateThatBlowsTheSkyIsThrownAwayEvenWithABetterScore() {
        val landscape = scene("풍경")
        // 과보정이 아니라 '하늘만 태우는' 후보: 전체 변화량은 작고 클리핑만 늘어난다.
        val harsh = EditRecipe.of(
            Param.EXPOSURE to 0.30f,
            Param.HIGHLIGHTS to 0.55f,
            Param.WHITES to 0.40f,
        )
        val report = report(landscape, harsh, nima = 9.4f)
        assertFalse("하늘을 태운 후보는 점수와 무관하게 탈락", report.accepted)
        assertEquals(Rejection.HIGHLIGHT_CLIP, report.rejected)
        assertTrue("탈락 후보의 총점은 낮게 눌린다", report.total <= 0.35f)
    }

    @Test
    fun aCandidateThatWrecksTheSkinIsThrownAway() {
        val portrait = scene("인물")
        val orange = EditRecipe.of(
            Param.TEMPERATURE to 0.6f,
            Param.SATURATION to 0.55f,
            Param.VIBRANCE to 0.5f,
            Param.SUBJECT_SATURATION to 0.5f,
        )
        val report = report(portrait, orange)
        assertFalse("피부가 주황으로 간 후보는 탈락", report.accepted)
        assertTrue(
            "탈락 사유가 피부이거나 과포화여야 한다 (${report.rejected})",
            report.rejected == Rejection.SKIN_DRIFT || report.rejected == Rejection.SATURATION_BLOWOUT,
        )
    }

    @Test
    fun aCandidateThatChangesEverythingIsThrownAway() {
        val flat = scene("저대비")
        val extreme = EditRecipe.of(
            Param.CONTRAST to 1f,
            Param.EXPOSURE to 1.4f,
            Param.SHADOWS to -0.9f,
            Param.CLARITY to 0.9f,
        )
        val report = report(flat, extreme)
        assertFalse("과보정 후보는 탈락", report.accepted)
        assertNotNull(report.rejected)
    }

    @Test
    fun aGentleCorrectionPassesAndScoresAboveTheBaseline() {
        val portrait = scene("인물")
        val gentle = EditRecipe.of(
            Param.EXPOSURE to 0.10f,
            Param.HIGHLIGHTS to -0.10f,
            Param.SUBJECT_SKIN_PROTECT to 0.30f,
            Param.SUBJECT_EXPOSURE to 0.10f,
        )
        val base = PixelSample(portrait.argb, size, size, portrait.subject, portrait.sky)
        val diagnosis = PhotoDiagnosisEngine.diagnose(portrait.argb, size, size, portrait.subject, portrait.sky)
        val baseline = QualityEvaluator.baseline(base, diagnosis, ProxyAesthetic.score(portrait.argb, size, size))
        val report = report(portrait, gentle)
        assertTrue("자연스러운 보정은 통과해야 한다 (${report.rejected})", report.accepted)
        assertTrue("기준선보다 높아야 한다 ${baseline.total} → ${report.total}", report.total > baseline.total)
        assertTrue("과보정 정도가 낮아야 한다 ${report.meanChange}", report.meanChange < 0.08f)
    }

    @Test
    fun theRecipeDistanceUsesTheGuardrails() {
        val base = EditRecipe.NEUTRAL
        val near = base.with(Param.SATURATION, 0.05f)
        val far = base.with(Param.SATURATION, 0.9f).with(Param.EXPOSURE, 1.5f)
        val nearDistance = QualityEvaluator.recipeDistance(base, near)
        val farDistance = QualityEvaluator.recipeDistance(base, far)
        assertTrue("가까운 변화가 더 작아야 한다", nearDistance < farDistance)
        assertEquals("변화 없음은 0", 0f, QualityEvaluator.recipeDistance(base, base), 0.0001f)
        assertEquals("최대값은 1", 1f, QualityEvaluator.recipeDistance(base, far), 1e-4f)
    }
}

class RecipeSearchTest {

    private val size = SyntheticScenes.SIZE

    private fun scene(category: String): SyntheticScene =
        SyntheticScenes.all().first { it.category == category }

    private fun search(
        scene: SyntheticScene,
        settings: RecipeSearch.Settings = RecipeSearch.Settings(maxEvaluations = 40, coarsePool = 12, timeBudgetMs = 20_000),
    ): SearchOutcome {
        val diagnosis = PhotoDiagnosisEngine.diagnose(scene.argb, size, size, scene.subject, scene.sky)
        val base = PixelSample(scene.argb, size, size, scene.subject, scene.sky)
        return RecipeSearch.search(
            base = EditRecipe.NEUTRAL,
            diagnosis = diagnosis,
            renderer = RecipeSearch.Renderer(
                render = { recipe -> CpuPipeline.render(scene.argb, size, size, recipe, scene.subject, scene.sky) },
                nima = { pixels -> ProxyAesthetic.score(pixels, size, size) },
                baseSample = base,
                baseNima = ProxyAesthetic.score(scene.argb, size, size),
            ),
            settings = settings,
        )
    }

    @Test
    fun theSearchStaysInsideTheSceneCeilings() {
        val night = scene("야경")
        val outcome = search(night)
        val shadowLimit = outcome.diagnosis.strategy.limits[Param.SHADOWS] ?: 0.24f
        assertTrue(
            "야경에서 섀도우를 ${outcome.recipe[Param.SHADOWS]}만큼 열었다 (상한 $shadowLimit)",
            outcome.recipe[Param.SHADOWS] <= shadowLimit + 1e-4f,
        )
        assertEquals("룩은 건드리지 않는다", -1f, outcome.recipe[Param.LUT_INDEX], 0f)
    }

    @Test
    fun aJointMoveBeatsAnySingleSlider() {
        val portrait = scene("인물")
        val diagnosis = PhotoDiagnosisEngine.diagnose(portrait.argb, size, size, portrait.subject, portrait.sky)
        val base = PixelSample(portrait.argb, size, size, portrait.subject, portrait.sky)
        val outcome = search(portrait)

        // 한 슬라이더씩만 움직인 최고 후보를 같은 품질 평가로 재 본다.
        val singleBest = Param.entries
            .filter { RegionLimits.isSearchable(it) || com.plenuen.lumen.nima.NimaGuardrails.isSearchable(it) }
            .flatMap { param ->
                val limit = (RegionLimits.limitOf(param) ?: 0f).takeIf { it > 0f }
                    ?: com.plenuen.lumen.nima.NimaGuardrails.limitOf(param)
                listOf(
                    EditRecipe.NEUTRAL.with(param, limit * 0.6f),
                    EditRecipe.NEUTRAL.with(param, -limit * 0.6f),
                )
            }
            .map { recipe ->
                val pixels = CpuPipeline.render(portrait.argb, size, size, recipe, portrait.subject, portrait.sky)
                val candidate = PixelSample(pixels, size, size, portrait.subject, portrait.sky)
                QualityEvaluator.evaluate(
                    base, candidate, EditRecipe.NEUTRAL, recipe, diagnosis,
                    ProxyAesthetic.score(pixels, size, size),
                    ProxyAesthetic.score(portrait.argb, size, size),
                )
            }
            .filter { it.accepted }
            .maxByOrNull { it.total }
            ?.total ?: 0f

        assertNotNull(outcome.bestQuality)
        assertTrue(
            "조합 후보(${outcome.bestQuality!!.total})가 단일 슬라이더 최고($singleBest)를 이겨야 한다",
            outcome.bestQuality!!.total >= singleBest - 0.01f,
        )
        assertTrue("여러 파라미터를 함께 움직여야 한다 (${outcome.recipe.changedParams()})",
            outcome.recipe.changedParams().size >= 2)
    }

    @Test
    fun theBudgetIsRespected() {
        val outcome = search(scene("인물"), RecipeSearch.Settings(maxEvaluations = 9, coarsePool = 14, timeBudgetMs = 20_000))
        assertTrue("평가 ${outcome.evaluations}회 <= 9", outcome.evaluations <= 9)
        assertTrue(outcome.evaluations > 0)
    }

    @Test
    fun aBadRecipeCannotWinOnTheModelScoreAlone() {
        // 프록시 모델은 이런 후보를 좋아하지만 품질 게이트는 버려야 한다.
        val landscape = scene("풍경")
        val diagnosis = PhotoDiagnosisEngine.diagnose(landscape.argb, size, size, landscape.subject, landscape.sky)
        val base = PixelSample(landscape.argb, size, size, landscape.subject, landscape.sky)
        val nasty = EditRecipe.of(Param.EXPOSURE to 0.9f, Param.HIGHLIGHTS to 0.7f, Param.WHITES to 0.5f)
        val nastyPixels = CpuPipeline.render(landscape.argb, size, size, nasty, landscape.subject, landscape.sky)
        val nastyQuality = QualityEvaluator.evaluate(
            base,
            PixelSample(nastyPixels, size, size, landscape.subject, landscape.sky),
            EditRecipe.NEUTRAL,
            nasty,
            diagnosis,
            nima = 9.6f,
            nimaBase = 4.6f,
        )
        assertFalse("모델 점수가 9.6이어도 품질 게이트가 막아야 한다", nastyQuality.accepted)
        val outcome = search(landscape)
        assertTrue(
            "최종 레시피가 모델만 좋아하는 후보여서는 안 된다",
            outcome.recipe[Param.EXPOSURE] < 0.85f,
        )
    }

    @Test
    fun theSameSceneAlwaysGivesTheSameRecipe() {
        val scene = scene("역광 인물")
        assertEquals(search(scene).recipe, search(scene).recipe)
    }
}

class SceneAwareLanguageTest {

    private val size = SyntheticScenes.SIZE

    private fun scene(category: String): SyntheticScene =
        SyntheticScenes.all().first { it.category == category }

    private fun apply(text: String, scene: SyntheticScene): Pair<EditRecipe, PromptEngine.Result> {
        val diagnosis = PhotoDiagnosisEngine.diagnose(scene.argb, size, size, scene.subject, scene.sky)
        val stats = com.plenuen.lumen.nl.SceneAnalyzer.analyze(scene.argb, size, size)
        val session = RecipeSession()
        val result = PromptEngine.apply(
            text = text,
            session = session,
            scene = stats,
            lutFinder = { null },
            diagnosis = diagnosis,
        )
        return session.current to result
    }

    @Test
    fun theSameWordIsAnsweredDifferentlyByScene() {
        val (portraitRecipe, portraitResult) = apply("고급스럽게", scene("인물"))
        val (nightRecipe, _) = apply("고급스럽게", scene("야경"))
        assertTrue("인물에서는 피부톤 보호가 붙는다", portraitRecipe[Param.SUBJECT_SKIN_PROTECT] > 0f)
        assertFalse("야경에서는 피부톤 보호가 붙지 않는다", nightRecipe[Param.SUBJECT_SKIN_PROTECT] > 0f)
        assertTrue("두 사진의 레시피가 달라야 한다", portraitRecipe != nightRecipe)
        assertTrue("해석 카드가 나와야 한다", portraitResult.applied)

        val (flatRecipe, _) = apply("선명하게", scene("저대비"))
        val (nightCrisp, _) = apply("선명하게", scene("야경"))
        assertTrue(
            "저대비에서는 선명도를 크게, 야경에서는 노이즈를 고려해 낮춘다 (${flatRecipe[Param.CLARITY]} vs ${nightCrisp[Param.CLARITY]})",
            flatRecipe[Param.CLARITY] > nightCrisp[Param.CLARITY],
        )
        assertTrue("야경에는 하이라이트 톤 억제가 붙는다", nightCrisp[Param.TONE_HIGH_CURVE] < 0f)
    }

    @Test
    fun anOversaturatedFrameHoldsBackASaturationRequest() {
        val (portraitRecipe, _) = apply("화사하게", scene("인물"))
        // "화사하게"는 활기를 올리지만, 이미 채도가 높은 사진에서는 폭을 줄인다.
        assertTrue(
            "채도/활기가 가드레일 안에 있어야 한다 (${portraitRecipe[Param.VIBRANCE]})",
            portraitRecipe[Param.VIBRANCE] in -0.35f..0.35f,
        )
    }

    @Test
    fun anUnmeasuredFrameBehavesExactlyLikeBefore() {
        val session = RecipeSession()
        val result = PromptEngine.apply(
            text = "고급스럽게",
            session = session,
            scene = com.plenuen.lumen.nl.SceneStats.UNKNOWN,
            lutFinder = { null },
            diagnosis = PhotoDiagnosis.UNKNOWN,
        )
        assertTrue(result.applied)
        assertEquals("진단이 없으면 색 정돈은 그대로", 0.36f, session.current[Param.COLOR_HARMONY], 0.001f)
        assertEquals("피부톤 보호는 붙지 않는다", 0f, session.current[Param.SUBJECT_SKIN_PROTECT], 0f)
    }

    @Test
    fun confidenceFollowsHowMuchOfTheSentenceWasRead() {
        val simple = NaturalLanguageParser.parse("차분하게")
        val leftover = NaturalLanguageParser.parse("차분하게 근데 뭔가 저녁 골목 같은 분위기가 났으면 좋겠어요")
        assertTrue("짧은 문장 ${simple.confidence}", simple.confidence >= 0.85f)
        assertTrue(
            "읽지 못한 꼬리가 길면 확신이 낮아야 한다 (단순 ${simple.confidence} vs 복합 ${leftover.confidence}, " +
                "남은 말 '${leftover.unmatched}')",
            leftover.confidence < simple.confidence,
        )
        assertTrue(leftover.confidence in 0.3f..0.98f)
        assertFalse("확신이 낮으면 모델에게 묻는다 (${leftover.confidence})", leftover.wellUnderstood)
        assertTrue("단순 문장은 확신이 높다", simple.wellUnderstood)
    }

    @Test
    fun aShortLeftoverIsStillAskedWhenTheRulesWereUnsure() {
        assertNull("확신이 높으면 짧은 꼬리는 묻지 않는다", PromptEngine.worthAsking("피부", 0.95f))
        assertNotNull("확신이 낮으면 두 글자도 묻는다", PromptEngine.worthAsking("피부", 0.5f))
    }
}
