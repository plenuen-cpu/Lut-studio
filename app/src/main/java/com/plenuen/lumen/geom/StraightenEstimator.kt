package com.plenuen.lumen.geom

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device tilt estimation: how far the photo has to be rotated so its dominant horizontal and
 * vertical edges lie level again.
 *
 * Method — structure-tensor voting:
 *  1. the image is box-downsampled to at most [MAX_SIDE] px and turned into luma,
 *  2. a Sobel operator gives a gradient per pixel,
 *  3. per 12 px block the gradient second-moment matrix is summed, which averages out the pixel
 *     staircase of a slightly tilted edge and gives one stable orientation per block,
 *  4. each block's orientation is folded into ±45° around multiples of 90°, so horizontal and
 *     vertical edges vote on the same tilt axis,
 *  5. votes are weighted by gradient energy × coherence into 0.1° bins across ±[SEARCH_DEG],
 *  6. the smoothed histogram peak is refined with a parabola.
 *
 * The returned degrees go straight into [com.plenuen.lumen.core.Param.STRAIGHTEN_DEG]; positive
 * means the content was tilted clockwise and is rotated back counter-clockwise.
 */
object StraightenEstimator {

    const val MAX_SIDE = 640
    const val SEARCH_DEG = 12f
    private const val BIN = 0.1f
    private const val BLOCK = 12
    private const val STRIDE = 6
    private const val MIN_BLOCK_ENERGY = 40f

    data class Result(
        /** Degrees to put into the recipe; 0 when no dominant edge exists. */
        val degrees: Float,
        /** 0..1 share of the voting energy that agrees with the peak. */
        val confidence: Float,
        /** Share of voting blocks that were near-horizontal before correction. */
        val horizontalShare: Float,
        /** Share of voting blocks that were near-vertical before correction. */
        val verticalShare: Float,
        /** Number of blocks that voted. */
        val edgeCount: Int,
    ) {
        /** True when the estimate is strong enough to apply on its own. */
        val isReliable: Boolean
            get() = edgeCount >= 60 && confidence >= 0.05f && abs(degrees) >= 0.3f

        companion object {
            val NONE = Result(0f, 0f, 0f, 0f, 0)
        }
    }

    fun estimate(argb: IntArray, width: Int, height: Int): Result {
        if (width < 16 || height < 16 || argb.size < width * height) return Result.NONE

        val step = maxOf(1, (maxOf(width, height) + MAX_SIDE - 1) / MAX_SIDE)
        val w = width / step
        val h = height / step
        if (w < 24 || h < 24) return Result.NONE

        val luma = FloatArray(w * h)
        for (y in 0 until h) {
            val row = (y * step) * width
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (dy in 0 until step) {
                    val srcRow = row + dy * width
                    for (dx in 0 until step) {
                        val px = argb[srcRow + x * step + dx]
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        sum += 0.299f * r + 0.587f * g + 0.114f * b
                        count++
                    }
                }
                luma[y * w + x] = sum / count
            }
        }

        // 3x3 가우시안으로 살짝 부드럽게 한 뒤 중심 차분 기울기를 구한다.
        // (경계 근처의 픽셀 계단 현상이 방향 추정을 흔들지 않게 한다.)
        val blurred = blur3(luma, w, h)
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        var maxMag = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val dx = blurred[i + 1] - blurred[i - 1]
                val dy = blurred[i + w] - blurred[i - w]
                gx[i] = dx
                gy[i] = dy
                val m = dx * dx + dy * dy
                if (m > maxMag) maxMag = m
            }
        }
        if (maxMag < 400f) return Result.NONE // 완전히 평평한 사진

        val bins = ((2 * SEARCH_DEG) / BIN).roundToInt() + 1
        val histogram = FloatArray(bins)
        var horizontal = 0f
        var vertical = 0f
        var blocks = 0

        var blockY = 1
        while (blockY + BLOCK < h - 1) {
            var blockX = 1
            while (blockX + BLOCK < w - 1) {
                var jxx = 0f
                var jyy = 0f
                var jxy = 0f
                for (y in blockY until blockY + BLOCK) {
                    var i = y * w + blockX
                    for (x in blockX until blockX + BLOCK) {
                        val dx = gx[i]
                        val dy = gy[i]
                        jxx += dx * dx
                        jyy += dy * dy
                        jxy += dx * dy
                        i++
                    }
                }
                val energy = jxx + jyy
                if (energy > MIN_BLOCK_ENERGY) {
                    val coherence = (sqrt((jxx - jyy) * (jxx - jyy) + 4f * jxy * jxy) / energy)
                        .coerceIn(0f, 1f)
                    // gradient orientation (mod 180°): 0° = vertical edge, 90° = horizontal edge
                    val angle = Math.toDegrees(0.5 * atan2((2 * jxy).toDouble(), (jxx - jyy).toDouble()))
                    val folded = foldToAxis(angle.toFloat())
                    if (coherence > 0.05f && abs(folded) <= SEARCH_DEG) {
                        val weight = sqrt(energy) * coherence
                        val bin = ((folded + SEARCH_DEG) / BIN).roundToInt().coerceIn(0, bins - 1)
                        histogram[bin] += weight
                        if (abs(abs(angle.toFloat()) - 90f) < 25f) horizontal += weight else vertical += weight
                        blocks++
                    }
                }
                blockX += STRIDE
            }
            blockY += STRIDE
        }
        if (blocks < 40) return Result.NONE

        val smoothed = smooth(histogram, sigmaBins = 1.1f)
        var bestBin = 0
        for (i in smoothed.indices) if (smoothed[i] > smoothed[bestBin]) bestBin = i

        val refined: Float = if (bestBin in 1 until bins - 1) {
            val left = smoothed[bestBin - 1]
            val centre = smoothed[bestBin]
            val right = smoothed[bestBin + 1]
            val denom = left - 2f * centre + right
            val offset = if (abs(denom) > 1e-6f) 0.5f * (left - right) / denom else 0f
            bestBin.toFloat() + offset.coerceIn(-0.5f, 0.5f)
        } else {
            bestBin.toFloat()
        }
        val degrees = -SEARCH_DEG + refined * BIN

        val total = smoothed.sum()
        val confidence = if (total > 0f) (smoothed[bestBin] / total).coerceIn(0f, 1f) else 0f
        val edgeTotal = horizontal + vertical

        return Result(
            degrees = (degrees * 10f).roundToInt() / 10f,
            confidence = confidence,
            horizontalShare = if (edgeTotal > 0f) horizontal / edgeTotal else 0f,
            verticalShare = if (edgeTotal > 0f) vertical / edgeTotal else 0f,
            edgeCount = blocks,
        )
    }

    /** Folds an orientation in degrees into the ±45° tilt axis around 0°/90°. */
    private fun foldToAxis(angle: Float): Float {
        var folded = angle % 90f
        if (folded > 45f) folded -= 90f
        if (folded < -45f) folded += 90f
        return folded
    }

    /** Separable 3x3 binomial smoothing (1 2 1) / 4. */
    private fun blur3(source: FloatArray, w: Int, h: Int): FloatArray {
        val temp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val left = source[if (x > 0) i - 1 else i]
                val right = source[if (x < w - 1) i + 1 else i]
                temp[i] = 0.25f * left + 0.5f * source[i] + 0.25f * right
            }
        }
        for (y in 0 until h) {
            val up = if (y > 0) (y - 1) * w else 0
            val down = if (y < h - 1) (y + 1) * w else (h - 1) * w
            val row = y * w
            for (x in 0 until w) {
                out[row + x] = 0.25f * temp[up + x] + 0.5f * temp[row + x] + 0.25f * temp[down + x]
            }
        }
        return out
    }

    private fun smooth(values: FloatArray, sigmaBins: Float): FloatArray {
        val radius = (sigmaBins * 3).roundToInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * radius + 1) { i ->
            val d = (i - radius).toFloat()
            (kotlin.math.exp((-0.5 * d * d) / (sigmaBins * sigmaBins))).toFloat()
        }
        val norm = kernel.sum()
        val out = FloatArray(values.size)
        for (i in values.indices) {
            var sum = 0f
            for (k in kernel.indices) {
                val j = i + k - radius
                if (j in values.indices) sum += values[j] * kernel[k]
            }
            out[i] = sum / norm
        }
        return out
    }
}
