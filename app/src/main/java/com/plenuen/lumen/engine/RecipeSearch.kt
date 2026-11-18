package com.plenuen.lumen.engine

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.nima.NimaGuardrails
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How far each parameter may move, and which ones the scene wants refined first.
 *
 * Three ceilings are folded together: the model guardrails (what NIMA may touch at all), the region
 * ceilings (mask-only parameters act on a smaller part of the frame, so they may travel further) and
 * the diagnosis strategy (a night frame opens its shadows less than a flat indoor wall does).
 */
data class SearchSpace(
    val base: EditRecipe,
    val limits: Map<Param, Float>,
    val emphasis: List<Param>,
) {
    fun limitOf(param: Param): Float = limits[param] ?: 0f

    fun allows(param: Param, value: Float): Boolean {
        val limit = limits[param] ?: return false
        return abs(value - base[param]) <= limit + 1e-4f
    }

    /** Clamps a wanted value into this space, or returns null when the parameter is not searched. */
    fun clamp(param: Param, want: Float): Float? {
        val limit = limits[param] ?: return null
        val from = base[param]
        return param.clamp(want.coerceIn(from - limit, from + limit))
    }

    companion object {
        /** Parameters that define the look or the frame, not the light: never searched. */
        private val STRUCTURAL = setOf(
            Param.AUTO_STRAIGHTEN, Param.STRAIGHTEN_DEG, Param.VERTICAL_TILT,
            Param.LUT_INDEX, Param.LUT_INTENSITY, Param.MASK_ON, Param.MONOCHROME,
        )

        fun of(base: EditRecipe, diagnosis: PhotoDiagnosis): SearchSpace {
            val strategy = diagnosis.strategy
            val limits = LinkedHashMap<Param, Float>()
            for (param in Param.entries) {
                if (param in STRUCTURAL) continue
                if (param.group == com.plenuen.lumen.core.ParamGroup.HSL) continue
                val model = NimaGuardrails.limitOf(param).takeIf { it > 0f }
                val region = RegionLimits.limitOf(param)
                val hard = min(model ?: Float.MAX_VALUE, region ?: Float.MAX_VALUE)
                if (hard == Float.MAX_VALUE) continue
                val scene = strategy.limits[param] ?: hard
                limits[param] = min(hard, scene)
            }
            val emphasis = (strategy.emphasis + listOf(
                Param.EXPOSURE, Param.HIGHLIGHTS, Param.SHADOWS, Param.CONTRAST, Param.VIBRANCE,
            )).distinct().filter { limits.containsKey(it) }
            return SearchSpace(base, limits, emphasis)
        }

        /** The space used when no diagnosis is available: the model guardrails alone. */
        fun unguided(base: EditRecipe): SearchSpace {
            val limits = LinkedHashMap<Param, Float>()
            for (param in Param.entries) {
                if (param in STRUCTURAL) continue
                val limit = NimaGuardrails.limitOf(param).takeIf { it > 0f } ?: continue
                limits[param] = limit
            }
            return SearchSpace(
                base,
                limits,
                listOf(
                    Param.EXPOSURE, Param.CONTRAST, Param.HIGHLIGHTS, Param.SHADOWS,
                    Param.VIBRANCE, Param.SATURATION, Param.CLARITY, Param.TEMPERATURE,
                ).filter { limits.containsKey(it) },
            )
        }
    }
}

/** One evaluated candidate, with the reasons it scored what it scored. */
data class ScoredCandidate(
    val recipe: EditRecipe,
    val score: Float,
    val nima: Float?,
    val quality: QualityReport?,
    val stage: String,
) {
    val accepted: Boolean get() = quality?.accepted ?: true
}

/** The full record of a search, including what it threw away and why. */
data class SearchOutcome(
    val recipe: EditRecipe,
    val baseScore: Float?,
    val bestScore: Float?,
    val baseNima: Float?,
    val bestNima: Float?,
    val baseQuality: QualityReport?,
    val bestQuality: QualityReport?,
    val evaluations: Int,
    val rejected: Int,
    val elapsedMs: Long,
    val stageCounts: Map<String, Int>,
    val diagnosis: PhotoDiagnosis,
) {
    val gain: Float get() = if (baseScore != null && bestScore != null) bestScore - baseScore else 0f
    val nimaGain: Float get() = if (baseNima != null && bestNima != null) bestNima - baseNima else 0f
}

/**
 * The v2.3 automatic pass: a two-stage search over *combinations* of parameters that is told what
 * the photo needs before it starts.
 *
 * v2.2.5 walked one slider at a time (exposure, then contrast, then …), kept the single best move of
 * each round, and let NIMA alone decide. That cannot find the move that matters — a backlit face
 * needs exposure *and* highlight recovery *and* a subject mask at once — and it cannot know that a
 * sky is about to go white. This version:
 *
 *  1. builds a pool of 12–18 complete recipes, each seeded by [SceneStrategy] from the diagnosis,
 *  2. scores every one on [QualityEvaluator] (NIMA + clipping + colour + skin + separation + scene fit
 *     − over-correction) and drops the ones a hard gate rejects,
 *  3. takes the best K as finalists and refines each by coordinate moves at two step sizes, plus one
 *     blend of the two best ideas,
 *  4. stops on a call budget *and* a wall-clock budget, so an S25 Ultra never waits more than the
 *     time it was given.
 *
 * Everything is deterministic: no randomness, no clock-dependent choices except the budget check, so
 * unit tests see the same search the phone runs.
 */
object RecipeSearch {

    data class Settings(
        /** Hard ceiling on renders + model calls. */
        val maxEvaluations: Int = 72,
        /** Wall-clock ceiling; a slow device stops early instead of blocking the UI. */
        val timeBudgetMs: Long = 6000,
        /** How many candidates enter the coarse stage. */
        val coarsePool: Int = 16,
        /** How many of those are refined. */
        val finalists: Int = 3,
        /** A candidate must beat the baseline by this much (quality units, or NIMA points). */
        val minGain: Float = 0.004f,
        /** Coordinate passes in the fine stage. */
        val refinePasses: Int = 2,
    )

    /** What the search can do to the frame. Renders are 224×224, the same buffer NIMA scores. */
    class Renderer(
        val render: (EditRecipe) -> IntArray?,
        val nima: (IntArray) -> Float? = { null },
        val baseSample: PixelSample = PixelSample.EMPTY,
        val baseNima: Float? = null,
        /** Called after every evaluation so the screen can say how far the pass has got. */
        val onProgress: (evaluations: Int, best: Float?) -> Unit = { _, _ -> },
    )

    fun search(
        base: EditRecipe,
        diagnosis: PhotoDiagnosis,
        renderer: Renderer,
        settings: Settings = Settings(),
    ): SearchOutcome {
        val startedAt = System.nanoTime()
        val space = SearchSpace.of(base, diagnosis)
        require(renderer.baseSample.pixels > 0) { "guided search needs the neutral render of the frame" }

        var evaluations = 0
        var rejected = 0
        val stageCounts = LinkedHashMap<String, Int>()

        fun overBudget(): Boolean {
            if (evaluations >= settings.maxEvaluations) return true
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            return elapsedMs >= settings.timeBudgetMs
        }

        var baseNima = renderer.baseNima
        var baseQuality: QualityReport? = null

        fun evaluate(recipe: EditRecipe, stage: String): ScoredCandidate? {
            if (overBudget()) return null
            val pixels = renderer.render(recipe) ?: return null
            evaluations++
            stageCounts[stage] = (stageCounts[stage] ?: 0) + 1
            val nima = renderer.nima(pixels)
            renderer.onProgress(evaluations, nima)
            val sample = PixelSample(
                pixels,
                NIMA_SIDE,
                NIMA_SIDE,
                renderer.baseSample.subject,
                renderer.baseSample.sky,
            )
            val quality = QualityEvaluator.evaluate(
                base = renderer.baseSample,
                candidate = sample,
                baseRecipe = base,
                candidateRecipe = recipe,
                diagnosis = diagnosis,
                nima = nima,
                nimaBase = baseNima,
            )
            if (!quality.accepted) rejected++
            return ScoredCandidate(recipe, quality.total, nima, quality, stage)
        }

        // ── 0) 기준선 ───────────────────────────────────────────────────────
        if (baseNima == null) baseNima = renderer.nima(renderer.baseSample.argb)
        baseQuality = QualityEvaluator.baseline(renderer.baseSample, diagnosis, baseNima)
        val baseline = baseQuality?.total ?: baseNima ?: return emptyOutcome(
            base, diagnosis, startedAt, evaluations, rejected, stageCounts, baseNima, null,
        )

        // ── 1) 거친 후보 풀 ─────────────────────────────────────────────────
        val pool = CandidateGenerator.build(base, diagnosis, space, settings.coarsePool)
        val coarse = mutableListOf<ScoredCandidate>()
        for (recipe in pool) {
            if (overBudget()) break
            val scored = evaluate(recipe, "coarse") ?: continue
            if (scored.accepted) coarse += scored
        }
        val finalists = coarse
            .filter { it.score > baseline + settings.minGain * 0.5f }
            .sortedByDescending { it.score }
            .take(settings.finalists)

        // ── 2) 곱게 다듬기 ─────────────────────────────────────────────────
        val refined = mutableListOf<ScoredCandidate>()
        for (seed in finalists) {
            var current = seed
            for (pass in 0 until settings.refinePasses) {
                if (overBudget()) break
                val scale = if (pass == 0) 0.5f else 0.25f
                val order = refineOrder(space, current.recipe)
                for (param in order) {
                    if (overBudget()) break
                    val step = space.limitOf(param) * scale * 0.5f
                    if (step <= 1e-4f) continue
                    for (direction in floatArrayOf(1f, -1f)) {
                        if (overBudget()) break
                        val next = space.clamp(param, current.recipe[param] + step * direction) ?: continue
                        if (abs(next - current.recipe[param]) < 1e-4f) continue
                        val candidate = evaluate(current.recipe.with(param, next), "fine")
                            ?: continue
                        if (candidate.accepted && candidate.score > current.score + 1e-4f) {
                            current = candidate
                        }
                    }
                }
            }
            refined += current
        }

        // 두 유망 후보의 절충안 — 조합형 탐색이 놓친 사이 지점을 한 번 더 본다.
        if (finalists.size >= 2 && !overBudget()) {
            val a = finalists[0].recipe
            val b = finalists[1].recipe
            val blended = EditRecipe.fromMap(
                Param.entries.associateWith { param ->
                    val va = a[param]
                    val vb = b[param]
                    if (abs(va - vb) < 1e-4f) va else (va + vb) * 0.5f
                },
            )
            val candidate = evaluate(blended, "blend")
            if (candidate != null && candidate.accepted) refined += candidate
        }

        val best = (refined + finalists).filter { it.accepted }.maxByOrNull { it.score }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val chosen = if (best != null && best.score > baseline + settings.minGain) best else null

        return SearchOutcome(
            recipe = chosen?.recipe ?: base,
            baseScore = baseline,
            bestScore = chosen?.score ?: baseline,
            baseNima = baseNima,
            bestNima = chosen?.nima ?: baseNima,
            baseQuality = baseQuality,
            bestQuality = chosen?.quality,
            evaluations = evaluations,
            rejected = rejected,
            elapsedMs = elapsedMs,
            stageCounts = stageCounts,
            diagnosis = diagnosis,
        )
    }

    /** Score-only search: identical structure, but the score is whatever [scorer] says. */
    fun searchScores(
        base: EditRecipe,
        baseScore: Float?,
        scorer: (EditRecipe) -> Float?,
        settings: Settings = Settings(),
        space: SearchSpace = SearchSpace.unguided(base),
    ): SearchOutcome {
        val startedAt = System.nanoTime()
        var evaluations = 0
        val stageCounts = LinkedHashMap<String, Int>()

        fun overBudget(): Boolean = evaluations >= settings.maxEvaluations ||
            (System.nanoTime() - startedAt) / 1_000_000 >= settings.timeBudgetMs

        var baseline = baseScore
        if (baseline == null) {
            baseline = scorer(base) ?: return emptyOutcome(
                base, PhotoDiagnosis.UNKNOWN, startedAt, 0, 0, stageCounts, null, null,
            )
            evaluations++
        }

        fun evaluate(recipe: EditRecipe, stage: String): ScoredCandidate? {
            if (overBudget()) return null
            val score = scorer(recipe) ?: return null
            evaluations++
            stageCounts[stage] = (stageCounts[stage] ?: 0) + 1
            return ScoredCandidate(recipe, score, score, null, stage)
        }

        val pool = CandidateGenerator.build(base, PhotoDiagnosis.UNKNOWN, space, settings.coarsePool)
        val coarse = mutableListOf<ScoredCandidate>()
        for (recipe in pool) {
            if (overBudget()) break
            evaluate(recipe, "coarse")?.let { if (it.score > baseline + settings.minGain * 0.5f) coarse += it }
        }
        val finalists = coarse.sortedByDescending { it.score }.take(settings.finalists)

        val refined = mutableListOf<ScoredCandidate>()
        for (seed in finalists) {
            var current = seed
            for (pass in 0 until settings.refinePasses) {
                if (overBudget()) break
                val scale = if (pass == 0) 0.5f else 0.25f
                for (param in refineOrder(space, current.recipe)) {
                    if (overBudget()) break
                    val step = space.limitOf(param) * scale * 0.5f
                    if (step <= 1e-4f) continue
                    for (direction in floatArrayOf(1f, -1f)) {
                        if (overBudget()) break
                        val next = space.clamp(param, current.recipe[param] + step * direction) ?: continue
                        if (abs(next - current.recipe[param]) < 1e-4f) continue
                        val candidate = evaluate(current.recipe.with(param, next), "fine") ?: continue
                        if (candidate.score > current.score + 1e-4f) current = candidate
                    }
                }
            }
            refined += current
        }
        if (finalists.size >= 2 && !overBudget()) {
            val a = finalists[0].recipe
            val b = finalists[1].recipe
            val blended = EditRecipe.fromMap(
                Param.entries.associateWith { if (abs(a[it] - b[it]) < 1e-4f) a[it] else (a[it] + b[it]) * 0.5f },
            )
            evaluate(blended, "blend")?.let { refined += it }
        }

        val best = (refined + finalists).maxByOrNull { it.score }
        val chosen = if (best != null && best.score > baseline + settings.minGain) best else null
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        return SearchOutcome(
            recipe = chosen?.recipe ?: base,
            baseScore = baseline,
            bestScore = chosen?.score ?: baseline,
            baseNima = baseline,
            bestNima = chosen?.score ?: baseline,
            baseQuality = null,
            bestQuality = null,
            evaluations = evaluations,
            rejected = 0,
            elapsedMs = elapsedMs,
            stageCounts = stageCounts,
            diagnosis = PhotoDiagnosis.UNKNOWN,
        )
    }

    /** Which parameters the fine stage touches first: the ones the recipe leans on hardest. */
    private fun refineOrder(space: SearchSpace, recipe: EditRecipe): List<Param> {
        val moved = space.emphasis.filter { abs(recipe[it] - space.base[it]) > 1e-3f }
        val rest = space.emphasis - moved.toSet()
        return (moved.sortedByDescending { abs(recipe[it] - space.base[it]) } + rest).take(7)
    }

    private fun emptyOutcome(
        base: EditRecipe,
        diagnosis: PhotoDiagnosis,
        startedAt: Long,
        evaluations: Int,
        rejected: Int,
        stageCounts: Map<String, Int>,
        baseScore: Float?,
        bestScore: Float?,
    ) = SearchOutcome(
        recipe = base,
        baseScore = baseScore,
        bestScore = bestScore,
        baseNima = baseScore,
        bestNima = bestScore,
        baseQuality = null,
        bestQuality = null,
        evaluations = evaluations,
        rejected = rejected,
        elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
        stageCounts = stageCounts,
        diagnosis = diagnosis,
    )

    /** Side of the square the NIMA model scores and the quality pass measures. */
    const val NIMA_SIDE = 224
}

/**
 * Builds the first candidates.
 *
 * Guided (a diagnosis was measured): every seed comes from [SceneStrategy] and carries the amounts
 * that frame asked for — a portrait lifts its subject and protects skin, a night shot opens shadows
 * only a little, a blown-out landscape pulls the sky down. Each seed is also tried at half strength,
 * because the diagnosis is a guess about a whole frame and a gentler version often scores better.
 *
 * Unguided (no pixels, e.g. the fallback path): a broad, scene-neutral table of combinations, so the
 * search still tries *joint* moves rather than one slider at a time.
 */
object CandidateGenerator {

    fun build(
        base: EditRecipe,
        diagnosis: PhotoDiagnosis,
        space: SearchSpace,
        limit: Int,
    ): List<EditRecipe> {
        val recipes = LinkedHashSet<EditRecipe>()
        if (diagnosis.measured && diagnosis.strategy.seeds.isNotEmpty()) {
            for ((index, seed) in diagnosis.strategy.seeds.withIndex()) {
                recipes += apply(base, seed, space, 1f)
                if (index < 2) recipes += apply(base, seed, space, 0.55f)
            }
            // 8색 HSL·룩은 그대로 두고, 진단이 지적한 문제만 따로 모은 "진단 단일 수정" 후보.
            diagnosisFix(base, diagnosis, space)?.let { recipes += it }
        }
        if (recipes.size < limit) {
            for (template in GENERIC) {
                recipes += apply(base, template, space, 1f)
                if (recipes.size >= limit) break
            }
        }
        // 기준선과 같은 후보는 뺀다(평가 예산 낭비).
        return recipes.filter { changes(it, base) }.take(limit)
    }

    private fun changes(candidate: EditRecipe, base: EditRecipe): Boolean =
        Param.entries.any { abs(candidate[it] - base[it]) > 1e-3f }

    private fun apply(
        base: EditRecipe,
        seed: Map<Param, Float>,
        space: SearchSpace,
        scale: Float,
    ): EditRecipe {
        var recipe = base
        for ((param, amount) in seed) {
            val wanted = base[param] + amount * scale
            val value = space.clamp(param, wanted) ?: continue
            recipe = recipe.with(param, value)
        }
        return recipe
    }

    /** The one or two direct answers to what the diagnosis found, without a scene template. */
    private fun diagnosisFix(base: EditRecipe, diagnosis: PhotoDiagnosis, space: SearchSpace): EditRecipe? {
        if (!diagnosis.measured) return null
        val fix = LinkedHashMap<Param, Float>()
        val wb = diagnosis.whiteBalance
        if (wb.magnitude > 0.045f) {
            fix[Param.TEMPERATURE] = (wb.temperatureFix * 0.8f).coerceIn(-0.16f, 0.16f)
            fix[Param.TINT] = (wb.tintFix * 0.5f).coerceIn(-0.10f, 0.10f)
        }
        val highlight = diagnosis.global.highlightClip
        if (highlight > 0.02f) {
            fix[Param.HIGHLIGHTS] = (-min(0.45f, highlight * 5f))
            if (diagnosis.hasSky) fix[Param.SKY_EXPOSURE] = -0.12f
        }
        val shadow = diagnosis.global.shadowClip
        if (shadow > 0.03f) {
            fix[Param.SHADOWS] = min(0.35f, shadow * 4f)
        }
        if (diagnosis.global.meanLuma < 0.34f) {
            fix[Param.EXPOSURE] = SceneStrategy.exposureTarget(diagnosis.global.meanLuma) * 0.8f
        }
        if (diagnosis.global.contrast < 0.38f) {
            fix[Param.CONTRAST] = 0.14f
            fix[Param.TONE_MID_CURVE] = 0.08f
        }
        if (diagnosis.color.meanSaturation > 0.52f) fix[Param.SATURATION] = -0.08f
        if (diagnosis.skin != null && diagnosis.skin.deviation > 0.16f) {
            fix[Param.SUBJECT_SKIN_PROTECT] = 0.30f
            fix[Param.SKIN_CLEAN] = 0.14f
        }
        if (fix.isEmpty()) return null
        var recipe = base
        for ((param, amount) in fix) {
            val value = space.clamp(param, base[param] + amount) ?: continue
            recipe = recipe.with(param, value)
        }
        return recipe.takeIf { changes(it, base) }
    }

    /** Scene-neutral combinations: joints of two or three sliders that photographs usually answer to. */
    private val GENERIC: List<Map<Param, Float>> = listOf(
        mapOf(Param.EXPOSURE to 0.25f, Param.CONTRAST to 0.12f),
        mapOf(Param.EXPOSURE to 0.45f, Param.CONTRAST to 0.20f),
        mapOf(Param.EXPOSURE to -0.25f, Param.CONTRAST to 0.10f),
        mapOf(Param.EXPOSURE to -0.45f, Param.HIGHLIGHTS to -0.25f),
        mapOf(Param.CONTRAST to 0.30f, Param.CLARITY to 0.20f),
        mapOf(Param.CONTRAST to -0.30f, Param.TEXTURE to -0.20f),
        mapOf(Param.HIGHLIGHTS to -0.35f, Param.WHITES to -0.25f),
        mapOf(Param.HIGHLIGHTS to 0.30f, Param.WHITES to 0.20f),
        mapOf(Param.SHADOWS to 0.35f, Param.BLACKS to 0.20f),
        mapOf(Param.SHADOWS to -0.30f, Param.BLACKS to -0.25f),
        mapOf(Param.VIBRANCE to 0.30f, Param.SATURATION to 0.15f),
        mapOf(Param.VIBRANCE to -0.30f, Param.SATURATION to -0.20f),
        mapOf(Param.SATURATION to 0.22f),
        mapOf(Param.TEMPERATURE to 0.15f, Param.TINT to 0.08f),
        mapOf(Param.TEMPERATURE to -0.15f, Param.TINT to -0.08f),
        mapOf(Param.CLARITY to 0.30f, Param.TEXTURE to 0.15f),
        mapOf(Param.VIGNETTE to 0.20f, Param.CLARITY to 0.15f),
        mapOf(Param.EXPOSURE to 0.50f, Param.SHADOWS to 0.30f, Param.VIBRANCE to 0.20f),
        mapOf(Param.HIGHLIGHTS to -0.40f, Param.SHADOWS to 0.40f, Param.CONTRAST to 0.25f),
        mapOf(Param.EXPOSURE to 0.10f, Param.VIBRANCE to 0.15f, Param.CLARITY to 0.10f),
    )
}
