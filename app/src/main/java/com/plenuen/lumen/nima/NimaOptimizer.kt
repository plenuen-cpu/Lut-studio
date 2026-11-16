package com.plenuen.lumen.nima

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param

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
        val size = kotlin.math.abs(delta)
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
 */
data class NimaAdjustment(
    val recipe: EditRecipe,
    val before: Float?,
    val after: Float?,
    val changes: List<NimaChange>,
    val evaluations: Int,
    val acceptedRounds: Int,
) {
    val gain: Float get() = if (before != null && after != null) after - before else 0f
    val improved: Boolean get() = changes.isNotEmpty() && gain > 0f

    /** "노출 +0.12 EV, 대비 +9%, 활기 +7%" (가장 큰 변화 네 개). */
    fun summary(): String = changes.take(4).joinToString(", ") { it.text() }
}

/**
 * Finds the direction that raises the NIMA score, using the model itself as the judge.
 *
 * A candidate ladder is walked from the current recipe (exposure, contrast, highlights, shadows,
 * whites, blacks, vibrance, saturation, clarity, temperature, texture, vignette — each way), the best
 * single move is kept, and the walk repeats from there with smaller steps. Every candidate is
 * rendered through the same shader as the preview and scored, so what wins here is what the user
 * actually sees — not a proxy.
 *
 * Two things keep it from wrecking a photo. Each parameter has a ceiling on how far it may drift from
 * the recipe the user already had ([NimaGuardrails]), so a model that likes punchier frames cannot
 * push saturation to the top; and a round is only accepted if it buys [Settings.minGain], so a photo
 * that is already good is left alone instead of being fidgeted with.
 */
object NimaOptimizer {

    data class Settings(
        /** Ladders to walk after the first sweep. */
        val rounds: Int = 3,
        /** A round must beat the current score by this much to be kept. */
        val minGain: Float = 0.02f,
        /** Hard ceiling on model evaluations, so a big photo cannot stall the UI. */
        val budget: Int = 60,
    )

    /** One candidate move: push [param] by [amount] in the direction being tried. */
    data class Probe(val param: Param, val amount: Float)

    /** Coarse sweep: the parameters a photograph usually answers to, at a visible size. */
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

    /** Refinement sweeps: fewer parameters, smaller moves, around the best point found. */
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

    private val FINAL_TOUCH = listOf(
        Probe(Param.EXPOSURE, 0.04f),
        Probe(Param.CONTRAST, 0.04f),
        Probe(Param.HIGHLIGHTS, 0.04f),
        Probe(Param.SHADOWS, 0.04f),
        Probe(Param.VIBRANCE, 0.04f),
        Probe(Param.SATURATION, 0.03f),
        Probe(Param.CLARITY, 0.04f),
        Probe(Param.TEMPERATURE, 0.03f),
    )

    /** The probes of each sweep, in the order they are tried. */
    fun ladder(round: Int): List<Probe> = when (round) {
        0 -> FIRST_SWEEP
        1 -> REFINEMENT
        else -> FINAL_TOUCH
    }

    fun optimize(
        base: EditRecipe,
        baseScore: Float? = null,
        scorer: RecipeScorer,
        settings: Settings = Settings(),
        onProgress: (evaluations: Int, best: Float) -> Unit = { _, _ -> },
    ): NimaAdjustment {
        var evaluations = 0
        val startScore = baseScore ?: scorer.score(base)?.also { evaluations++ }
        if (startScore == null) {
            return NimaAdjustment(base, null, null, emptyList(), evaluations, 0)
        }

        var bestScore: Float = startScore
        var best = base
        var acceptedRounds = 0
        val start = startScore

        for (round in 0 until settings.rounds) {
            if (evaluations >= settings.budget) break
            var roundRecipe: EditRecipe? = null
            var roundScore = bestScore
            val probes = ladder(round)
            for (probe in probes) {
                for (direction in DIRECTION) {
                    if (evaluations >= settings.budget) break
                    val target = best[probe.param] + probe.amount * direction
                    val candidate = best.with(probe.param, target)
                    if (candidate == best) continue
                    if (!NimaGuardrails.allows(probe.param, base[probe.param], candidate[probe.param])) continue
                    val score = scorer.score(candidate) ?: continue
                    evaluations++
                    if (score > roundScore + 1e-4f) {
                        roundScore = score
                        roundRecipe = candidate
                    }
                }
            }
            if (roundRecipe == null || roundScore - bestScore < settings.minGain) break
            best = roundRecipe
            bestScore = roundScore
            acceptedRounds++
            onProgress(evaluations, bestScore)
        }

        val changes = Param.entries.mapNotNull { param ->
            val from = base[param]
            val to = best[param]
            if (kotlin.math.abs(to - from) < 1e-4f) null else NimaChange(param, from, to)
        }.sortedByDescending { kotlin.math.abs(it.delta) }

        return NimaAdjustment(
            recipe = if (changes.isEmpty()) base else best,
            before = start,
            after = if (changes.isEmpty()) start else bestScore,
            changes = changes,
            evaluations = evaluations,
            acceptedRounds = acceptedRounds,
        )
    }

    private val DIRECTION = floatArrayOf(1f, -1f)
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
        return kotlin.math.abs(value - base) <= limit + 1e-4f
    }
}
