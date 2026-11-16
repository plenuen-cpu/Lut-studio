package com.plenuen.lumen.nl

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What the open photo looks like, measured from its own pixels.
 *
 * Only plain statistics are used (luminance distribution, saturation, colour balance, clipping,
 * local detail) — enough for "예쁘게" to choose an amount that suits this photo instead of a fixed
 * preset. No subject or emotion is inferred.
 */
data class SceneStats(
    val width: Int,
    val height: Int,
    val meanLuma: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val saturationMean: Float,
    val warmth: Float,
    val highlightClip: Float,
    val shadowClip: Float,
    val detailEnergy: Float,
    val contrastRange: Float,
    val samples: Int,
) {
    val isDark: Boolean get() = meanLuma < 0.36f
    val isBright: Boolean get() = meanLuma > 0.68f
    val isFlat: Boolean get() = contrastRange < 0.45f
    val isLowSaturation: Boolean get() = saturationMean < 0.16f
    val isHighSaturation: Boolean get() = saturationMean > 0.42f
    val isWarm: Boolean get() = warmth > 0.035f
    val isCool: Boolean get() = warmth < -0.035f
    val isDetailed: Boolean get() = detailEnergy > 0.11f

    companion object {
        val UNKNOWN = SceneStats(0, 0, 0.5f, 0f, 0.5f, 1f, 0.25f, 0f, 0f, 0f, 0.08f, 1f, 0)
    }
}

object SceneAnalyzer {

    private const val MAX_SAMPLES = 120_000

    fun analyze(argb: IntArray, width: Int, height: Int): SceneStats {
        if (width <= 0 || height <= 0 || argb.isEmpty()) return SceneStats.UNKNOWN
        val step = max(1, kotlin.math.sqrt((width.toLong() * height / MAX_SAMPLES).toDouble()).toInt())
        val histogram = IntArray(256)
        var count = 0
        var satSum = 0f
        var rSum = 0f
        var bSum = 0f
        var gSum = 0f
        var detailSum = 0f
        var detailCount = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val px = argb[y * width + x]
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                histogram[(luma * 255f).toInt().coerceIn(0, 255)]++
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                satSum += if (hi > 1e-4f) (hi - lo) / hi else 0f
                rSum += r
                gSum += g
                bSum += b
                count++

                // local detail: |laplacian| on the sampled grid
                if (x + step < width && y + step * 1 < height) {
                    val right = argb[y * width + (x + step)]
                    val down = argb[(y + step) * width + x]
                    val lr = 0.299f * ((right shr 16) and 0xFF) + 0.587f * ((right shr 8) and 0xFF) + 0.114f * (right and 0xFF)
                    val ld = 0.299f * ((down shr 16) and 0xFF) + 0.587f * ((down shr 8) and 0xFF) + 0.114f * (down and 0xFF)
                    detailSum += abs(lr + ld - 2f * luma * 255f) / 255f
                    detailCount++
                }
                x += step
            }
            y += step
        }
        if (count == 0) return SceneStats.UNKNOWN

        val total = count.toFloat()
        val meanLuma = (histogram.indices.sumOf { it * histogram[it].toLong() }.toFloat() / (255f * total))
        val p05 = percentile(histogram, 0.05f) / 255f
        val p50 = percentile(histogram, 0.50f) / 255f
        val p95 = percentile(histogram, 0.95f) / 255f
        val highlightClip = histogram.copyOfRange(250, 256).sum() / total
        val shadowClip = histogram.copyOfRange(0, 6).sum() / total
        val warmth = ((rSum - bSum) / total)
        val saturation = satSum / total
        val detail = if (detailCount > 0) detailSum / detailCount else 0f

        return SceneStats(
            width = width,
            height = height,
            meanLuma = meanLuma,
            p05 = p05,
            p50 = p50,
            p95 = p95,
            saturationMean = saturation,
            warmth = warmth,
            highlightClip = highlightClip,
            shadowClip = shadowClip,
            detailEnergy = detail,
            contrastRange = (p95 - p05).coerceIn(0f, 1f),
            samples = count,
        )
    }

    private fun percentile(histogram: IntArray, fraction: Float): Float {
        val total = histogram.sum()
        if (total == 0) return 0f
        val target = (total * fraction).toInt()
        var running = 0
        for (i in histogram.indices) {
            running += histogram[i]
            if (running >= target) return i.toFloat()
        }
        return 255f
    }
}
