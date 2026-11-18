package com.plenuen.lumen.engine

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.nima.NimaGuardrails
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A stand-in for the NIMA aesthetic model, for the JVM benchmark.
 *
 * The real model is a TFLite graph that only runs on the device (and in the Python cross-check);
 * this function reproduces the property that matters for the comparison: it is an *aesthetic* prior
 * that likes mid-tone exposure, contrast and moderate saturation, and that forgives a little
 * clipping. It is the same judge for both engines, so neither side gets an easier objective.
 *
 * A high score here therefore means "looks punchy", not "is a good photograph" — which is exactly
 * the blind spot the v2.3 quality gates exist to cover.
 */
object ProxyAesthetic {

    fun score(argb: IntArray, width: Int, height: Int, night: Boolean = false): Float {
        if (argb.isEmpty()) return 5f
        var sum = 0.0
        val histogram = IntArray(256)
        var satSum = 0.0
        var detailSum = 0.0
        var detailCount = 0
        val step = max(1, kotlin.math.sqrt((width.toLong() * height / 40_000.0).toDouble()).toInt())
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val px = argb[y * width + x]
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                histogram[(l * 255f).toInt().coerceIn(0, 255)]++
                sum += l
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                satSum += if (hi > 1e-4f) (hi - lo) / hi else 0f
                if (x + step < width) {
                    val right = argb[y * width + x + step]
                    val lr = 0.2126f * ((right shr 16) and 0xFF) / 255f +
                        0.7152f * ((right shr 8) and 0xFF) / 255f +
                        0.0722f * (right and 0xFF) / 255f
                    detailSum += abs(lr - l)
                    detailCount++
                }
                x += step
            }
            y += step
        }
        val count = (0 until 256).sumOf { histogram[it] }.toFloat().coerceAtLeast(1f)
        val mean = (sum / count).toFloat()
        val p05 = percentile(histogram, 0.05f)
        val p95 = percentile(histogram, 0.95f)
        val contrast = (p95 - p05).coerceIn(0f, 1f)
        val saturation = (satSum / count).toFloat()
        val detail = if (detailCount == 0) 0f else (detailSum / detailCount).toFloat()
        val highlightClip = histogram.copyOfRange(250, 256).sum() / count
        val shadowClip = histogram.copyOfRange(0, 6).sum() / count

        var score = 4.0
        // 노출: 중간톤을 좋아한다
        score += 2.0 * (1.0 - abs(mean - 0.52) / 0.55).coerceIn(0.0, 1.0)
        // 대비와 채도: 조금 과해도 좋아한다(모델의 성향)
        score += 1.7 * min(contrast.toDouble(), 0.95)
        score += 1.4 * min(saturation.toDouble(), 0.58)
        score -= 1.0 * max(0.0, saturation - 0.66)
        // 디테일
        score += 1.2 * min(detail.toDouble() * 8.0, 0.5)
        // 클리핑은 벌점이지만 약하다 — 이게 v2.2.5가 하늘을 태우고도 점수를 올린 이유다
        score -= 3.0 * highlightClip
        score -= (if (night) 0.8 else 1.4) * shadowClip
        return score.coerceIn(1.0, 10.0).toFloat()
    }

    private fun percentile(histogram: IntArray, fraction: Float): Float {
        val total = histogram.sum()
        if (total == 0) return 0f
        val target = max(1, (total * fraction).toInt())
        var running = 0
        for (i in histogram.indices) {
            running += histogram[i]
            if (running >= target) return i / 255f
        }
        return 1f
    }
}

/**
 * The v2.2.5 automatic pass, kept verbatim for the benchmark.
 *
 * It walks a ladder one parameter at a time, keeps the single best move per round, and repeats from
 * there with smaller steps. This is the algorithm the v2.3 search replaces, so the benchmark runs it
 * against the same scenes and the same judge.
 */
object LegacyV225 {

    data class Result(val recipe: EditRecipe, val score: Float, val evaluations: Int)

    private val FIRST_SWEEP = listOf(
        Param.EXPOSURE to 0.12f, Param.CONTRAST to 0.10f, Param.HIGHLIGHTS to 0.12f,
        Param.SHADOWS to 0.12f, Param.WHITES to 0.09f, Param.BLACKS to 0.09f,
        Param.VIBRANCE to 0.12f, Param.SATURATION to 0.08f, Param.CLARITY to 0.10f,
        Param.TEMPERATURE to 0.06f, Param.TEXTURE to 0.08f, Param.VIGNETTE to 0.08f,
    )

    private val REFINEMENT = listOf(
        Param.EXPOSURE to 0.07f, Param.CONTRAST to 0.06f, Param.HIGHLIGHTS to 0.07f,
        Param.SHADOWS to 0.07f, Param.VIBRANCE to 0.07f, Param.SATURATION to 0.05f,
        Param.CLARITY to 0.06f, Param.TEMPERATURE to 0.04f,
    )

    private val FINAL_TOUCH = listOf(
        Param.EXPOSURE to 0.04f, Param.CONTRAST to 0.04f, Param.HIGHLIGHTS to 0.04f,
        Param.SHADOWS to 0.04f, Param.VIBRANCE to 0.04f, Param.SATURATION to 0.03f,
        Param.CLARITY to 0.04f, Param.TEMPERATURE to 0.03f,
    )

    private fun ladder(round: Int): List<Pair<Param, Float>> = when (round) {
        0 -> FIRST_SWEEP
        1 -> REFINEMENT
        else -> FINAL_TOUCH
    }

    fun optimize(
        base: EditRecipe,
        scorer: (EditRecipe) -> Float?,
        budget: Int = 60,
        rounds: Int = 3,
        minGain: Float = 0.02f,
    ): Result {
        var evaluations = 0
        var best = base
        var bestScore = scorer(base)?.also { evaluations++ } ?: return Result(base, 5f, 0)
        for (round in 0 until rounds) {
            if (evaluations >= budget) break
            var roundRecipe: EditRecipe? = null
            var roundScore = bestScore
            for ((param, amount) in ladder(round)) {
                for (direction in floatArrayOf(1f, -1f)) {
                    if (evaluations >= budget) break
                    val target = best[param] + amount * direction
                    val candidate = best.with(param, target)
                    if (candidate == best) continue
                    if (!NimaGuardrails.allows(param, base[param], candidate[param])) continue
                    val score = scorer(candidate) ?: continue
                    evaluations++
                    if (score > roundScore + 1e-4f) {
                        roundScore = score
                        roundRecipe = candidate
                    }
                }
            }
            if (roundRecipe == null || roundScore - bestScore < minGain) break
            best = roundRecipe
            bestScore = roundScore
        }
        return Result(best, bestScore, evaluations)
    }
}
