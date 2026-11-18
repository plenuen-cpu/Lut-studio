package com.plenuen.lumen.nima

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.engine.PhotoDiagnosis
import com.plenuen.lumen.engine.RecipeSearch
import com.plenuen.lumen.engine.SearchSpace
import com.plenuen.lumen.engine.SearchOutcome
import kotlin.math.abs

/**
 * Scores a recipe by what it looks like. The app answers with the NIMA model; tests answer with a
 * stub, which is why the search below stays free of Android and of OpenGL.
 */
fun interface RecipeScorer {
    /** @return the NIMA aesthetic score (1…10) of [recipe] as it would be rendered, or null. */
    fun score(recipe: EditRecipe): Float?
}

/** One parameter the automatic pass moved. */
data class NimaChange(val param: Param, val from: Float, val to: Float) {
    val delta: Float get() = to - from

    /** "+12%", "+0.12 EV", "−0.08" — the amount in the slider's own units. */
    fun amountText(): String {
        val sign = if (delta >= 0f) "+" else "−"
        val size = abs(delta)
        return when (param.suffix) {
            "EV" -> sign + String.format(java.util.Locale.US, "%.2f", size) + " EV"
            "°" -> sign + String.format(java.util.Locale.US, "%.1f", size) + "°"
            else -> sign + (size * 100f).toInt() + "%"
        }
    }

    fun text(): String = "${param.label} ${amountText()}"
}

/**
 * The result of one automatic pass.
 *
 * [before] and [after] are the real NIMA scores measured around the change, so the card can say
 * "4.85 → 5.38 (+0.53)" instead of claiming an improvement. When nothing beat the photo as it was,
 * [changes] stays empty and the caller keeps the recipe.
 *
 * From v2.3 the pass can also be accepted on [qualityAfter] even when NIMA dips slightly — a sky
 * that stops being white or a face that stays believable is worth a hundredth of a NIMA point — so
 * [accepted] says so explicitly and [improved] honours it.
 */
data class NimaAdjustment(
    val recipe: EditRecipe,
    val before: Float?,
    val after: Float?,
    val changes: List<NimaChange>,
    val evaluations: Int,
    val acceptedRounds: Int,
    /** 다중 품질 평가 점수(0..1). 진단 기반 경로에서만 채워진다. */
    val qualityBefore: Float? = null,
    val qualityAfter: Float? = null,
    /** true면 NIMA가 조금 내려도 품질 게이트를 통과해 채택된 결과다. */
    val accepted: Boolean = false,
    /** 사진 진단 요약 — 카드에 "역광 인물 · …"처럼 그대로 보여 준다. */
    val diagnosis: String? = null,
    /** 하드 게이트에 걸려 버려진 후보 수. */
    val rejected: Int = 0,
    val elapsedMs: Long = 0,
) {
    val gain: Float get() = if (before != null && after != null) after - before else 0f
    val improved: Boolean get() = changes.isNotEmpty() && (gain > 0f || accepted)

    /** "노출 +0.12 EV, 대비 +9%, 활기 +7%" (가장 큰 변화 네 개). */
    fun summary(): String = changes.take(4).joinToString(", ") { it.text() }
}

/**
 * Finds the combination of adjustments that makes the photo best, using the model as one judge
 * among several.
 *
 * v2.2.5 walked a single ladder of parameters and kept the best single move per round, judged only
 * by NIMA. v2.3 asks [com.plenuen.lumen.engine.CandidateGenerator] for whole recipes seeded by the
 * photo's diagnosis, scores them with [com.plenuen.lumen.engine.QualityEvaluator] (model + clipping +
 * colour + skin + separation + scene fit − over-correction), refines the best few with coordinate
 * moves at two step sizes, and stops on both a call count and a clock so a phone stays responsive.
 *
 * Two things keep it from wrecking a photo. Each parameter has a ceiling on how far it may drift
 * from the recipe the user already had ([NimaGuardrails] and [com.plenuen.lumen.engine.RegionLimits]),
 * so a model that likes punchier frames cannot push saturation to the top; and a candidate is only
 * kept if it beats the frame's own baseline by [Settings.minGain], so a good photo is left alone.
 */
object NimaOptimizer {

    data class Settings(
        /** Refinement passes per finalist (kept from v2.2.5 for call-site compatibility). */
        val rounds: Int = 3,
        /** A result must beat the current NIMA score by this much to be kept (score-only path). */
        val minGain: Float = 0.02f,
        /** The same threshold in quality units (0..1) for the diagnosis-guided path. */
        val qualityMinGain: Float = 0.004f,
        /** Hard ceiling on model evaluations, so a big photo cannot stall the UI. */
        val budget: Int = 72,
        /** Wall-clock ceiling for the whole pass. */
        val timeBudgetMs: Long = 6000,
    )

    /** One candidate move. Kept because v2.2.5 tests and diagnostics refer to the ladder. */
    data class Probe(val param: Param, val amount: Float)

    /** The probes the v2.2.5 single-slider walk used; still reported for comparison. */
    fun ladder(round: Int): List<Probe> = if (round <= 0) FIRST_SWEEP else REFINEMENT

    private val FIRST_SWEEP = listOf(
        Probe(Param.EXPOSURE, 0.12f),
        Probe(Param.CONTRAST, 0.10f),
        Probe(Param.HIGHLIGHTS, 0.12f),
        Probe(Param.SHADOWS, 0.12f),
        Probe(Param.WHITES, 0.09f),
        Probe(Param.BLACKS, 0.09f),
        Probe(Param.VIBRANCE, 0.12f),
        Probe(Param.SATURATION, 0.08f),
        Probe(Param.CLARITY, 0.10f),
        Probe(Param.TEMPERATURE, 0.06f),
        Probe(Param.TEXTURE, 0.08f),
        Probe(Param.VIGNETTE, 0.08f),
    )

    private val REFINEMENT = listOf(
        Probe(Param.EXPOSURE, 0.07f),
        Probe(Param.CONTRAST, 0.06f),
        Probe(Param.HIGHLIGHTS, 0.07f),
        Probe(Param.SHADOWS, 0.07f),
        Probe(Param.VIBRANCE, 0.07f),
        Probe(Param.SATURATION, 0.05f),
        Probe(Param.CLARITY, 0.06f),
        Probe(Param.TEMPERATURE, 0.04f),
    )

    /**
     * The classic entry point: only a score function, no pixels.
     *
     * Runs the same multivariate search as the phone does, with the model guardrails as its space
     * and the generic candidate table as its pool, so a caller that can only answer "what does NIMA
     * think" still gets joint moves instead of a one-slider walk.
     */
    fun optimize(
        base: EditRecipe,
        baseScore: Float? = null,
        scorer: RecipeScorer,
        settings: Settings = Settings(),
        onProgress: (evaluations: Int, best: Float) -> Unit = { _, _ -> },
    ): NimaAdjustment {
        val outcome = RecipeSearch.searchScores(
            base = base,
            baseScore = baseScore,
            scorer = { recipe -> scorer.score(recipe) },
            settings = RecipeSearch.Settings(
                maxEvaluations = settings.budget,
                timeBudgetMs = settings.timeBudgetMs,
                minGain = settings.minGain,
                refinePasses = 2,
            ),
            space = SearchSpace.unguided(base),
        )
        val changes = changesOf(base, outcome.recipe)
        if (changes.isNotEmpty()) onProgress(outcome.evaluations, outcome.bestNima ?: 0f)
        return NimaAdjustment(
            recipe = outcome.recipe,
            before = outcome.baseNima,
            after = outcome.bestNima,
            changes = changes,
            evaluations = outcome.evaluations,
            acceptedRounds = if (changes.isEmpty()) 0 else 1,
            elapsedMs = outcome.elapsedMs,
        )
    }

    /**
     * The v2.3 path: the pass knows what the photo looks like and judges candidates on more than the
     * model's score. [renderer] draws candidates through the preview's own shader at 224×224.
     */
    fun enhance(
        base: EditRecipe,
        baseNima: Float?,
        diagnosis: PhotoDiagnosis,
        renderer: RecipeSearch.Renderer,
        settings: Settings = Settings(),
        onProgress: (evaluations: Int, best: Float) -> Unit = { _, _ -> },
    ): NimaAdjustment {
        val outcome: SearchOutcome = RecipeSearch.search(
            base = base,
            diagnosis = diagnosis,
            renderer = RecipeSearch.Renderer(
                render = renderer.render,
                nima = renderer.nima,
                baseSample = renderer.baseSample,
                baseNima = baseNima,
                onProgress = { count, _ -> onProgress(count, 0f) },
            ),
            settings = RecipeSearch.Settings(
                maxEvaluations = settings.budget,
                timeBudgetMs = settings.timeBudgetMs,
                minGain = settings.qualityMinGain,
                refinePasses = 2,
            ),
        )
        val changes = changesOf(base, outcome.recipe)
        return NimaAdjustment(
            recipe = outcome.recipe,
            before = outcome.baseNima,
            after = outcome.bestNima,
            changes = changes,
            evaluations = outcome.evaluations,
            acceptedRounds = if (changes.isEmpty()) 0 else 1,
            qualityBefore = outcome.baseQuality?.total,
            qualityAfter = outcome.bestQuality?.total,
            accepted = changes.isNotEmpty() && outcome.bestQuality?.accepted == true,
            diagnosis = diagnosis.summary(),
            rejected = outcome.rejected,
            elapsedMs = outcome.elapsedMs,
        )
    }

    private fun changesOf(base: EditRecipe, result: EditRecipe): List<NimaChange> =
        Param.entries.mapNotNull { param ->
            val from = base[param]
            val to = result[param]
            if (abs(to - from) < 1e-4f) null else NimaChange(param, from, to)
        }.sortedByDescending { abs(it.delta) }
}

/**
 * How far the automatic pass may move a parameter away from what the user already had.
 *
 * These are deliberately modest: NIMA likes contrast and saturation a little more than a person does
 * on a phone screen, and a "새하얀 하늘" or "형광 피부" would score well while looking wrong. Anything
 * not listed is never touched — geometry, the chosen film look, region masks and HSL pins belong to
 * the user.
 */
object NimaGuardrails {

    private val limits: Map<Param, Float> = mapOf(
        Param.EXPOSURE to 0.55f,
        Param.CONTRAST to 0.35f,
        Param.HIGHLIGHTS to 0.45f,
        Param.SHADOWS to 0.45f,
        Param.WHITES to 0.30f,
        Param.BLACKS to 0.30f,
        Param.VIBRANCE to 0.35f,
        Param.SATURATION to 0.22f,
        Param.CLARITY to 0.35f,
        Param.TEXTURE to 0.20f,
        Param.DEHAZE to 0.18f,
        Param.TEMPERATURE to 0.16f,
        Param.TINT to 0.10f,
        Param.VIGNETTE to 0.22f,
        Param.SHARPNESS to 0.30f,
        Param.GLOW to 0.18f,
        Param.FADE to 0.12f,
        Param.COLOR_HARMONY to 0.30f,
        Param.SPLIT_TONE to 0.20f,
        Param.CROSS_PROCESS to 0.12f,
        Param.SKIN_CLEAN to 0.25f,
    )

    fun limitOf(param: Param): Float = limits[param] ?: 0f

    fun isSearchable(param: Param): Boolean = limits.containsKey(param)

    /** True when [value] stays inside what this parameter is allowed to do to the photo. */
    fun allows(param: Param, base: Float, value: Float): Boolean {
        val limit = limits[param] ?: return false
        return abs(value - base) <= limit + 1e-4f
    }
}
