package com.plenuen.lumen.image

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin mask arithmetic for the region tools (subject / background / sky).
 *
 * The MediaPipe segmenter lives in [MaskGenerator] because it needs Android and a native model; the
 * measurable parts — feathering, the sky heuristic, the saliency fallback used when the model is
 * unavailable, and packing the two masks into the texture the shader reads — are kept here so unit
 * tests can pin their behaviour on synthetic photos.
 *
 * Packing: R = subject confidence (1 = person/subject), G = sky confidence, B = 0. The shader
 * derives the background weight as (1 - R - G).
 */
object MaskMath {

    /** Pixels darker than this are not sky, unless they are strongly blue. */
    private const val SKY_MIN_LUMA = 0.40f

    /** A row counts as sky only when this much of it looks like sky. */
    private const val SKY_ROW_RATIO = 0.28f

    /** A sky earlier than this fraction of the frame height is not believed (indoors, walls). */
    private const val SKY_MAX_ROW_FRACTION = 0.78f

    /** Three passes of a box blur approximate a Gaussian closely enough for a mask edge. */
    fun feather(mask: FloatArray, width: Int, height: Int, radius: Int, passes: Int = 3): FloatArray {
        if (radius <= 0 || width <= 0 || height <= 0) return mask.copyOf()
        var current = mask.copyOf()
        repeat(passes.coerceAtLeast(1)) {
            current = blurHorizontal(current, width, height, radius)
            current = blurVertical(current, width, height, radius)
        }
        return current
    }

    private fun blurHorizontal(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val out = FloatArray(src.size)
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (x in -radius..radius) sum += src[row + x.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                out[row + x] = sum / window
                val leaving = src[row + (x - radius).coerceIn(0, width - 1)]
                val entering = src[row + (x + radius + 1).coerceIn(0, width - 1)]
                sum += entering - leaving
            }
        }
        return out
    }

    private fun blurVertical(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val out = FloatArray(src.size)
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var sum = 0f
            for (y in -radius..radius) sum += src[y.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = sum / window
                val leaving = src[(y - radius).coerceIn(0, height - 1) * width + x]
                val entering = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sum += entering - leaving
            }
        }
        return out
    }

    /**
     * Sky confidence from colour, brightness, position and edges.
     *
     * Sky is blue-to-neutral, bright, in the upper part of the frame and flat; a row only counts
     * when a good share of it looks like sky, which keeps a blue shirt or a painted wall from being
     * lifted into the sky mask. Anything the subject mask claims is removed afterwards.
     */
    fun skyMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        subject: FloatArray? = null,
        radius: Int = 4,
    ): FloatArray {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return FloatArray(0)
        val raw = FloatArray(width * height)
        val rowHits = IntArray(height)
        for (y in 0 until height) {
            val vertical = 1f - (y.toFloat() / height) / SKY_MAX_ROW_FRACTION
            if (vertical <= 0f) continue
            var hits = 0
            for (x in 0 until width) {
                val index = y * width + x
                val argb = pixels[index]
                val r = (argb shr 16 and 0xFF) / 255f
                val g = (argb shr 8 and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                val blueish = b >= r - 0.02f && b >= g - 0.06f
                val bright = luma >= SKY_MIN_LUMA || (b > r + 0.10f && b > 0.35f)
                val flat = chroma <= 0.60f
                if (blueish && bright && flat) {
                    raw[index] = vertical
                    hits++
                }
            }
            rowHits[y] = hits
        }
        for (y in 0 until height) {
            val ratio = rowHits[y].toFloat() / width
            if (ratio < SKY_ROW_RATIO) {
                for (x in 0 until width) raw[y * width + x] = 0f
            } else {
                val scale = min(1f, ratio / 0.55f)
                if (scale < 1f) {
                    for (x in 0 until width) raw[y * width + x] *= scale
                }
            }
        }
        if (subject != null && subject.size == raw.size) {
            for (i in raw.indices) raw[i] *= (1f - subject[i].coerceIn(0f, 1f))
        }
        return feather(raw, width, height, radius)
    }

    /**
     * Saliency fallback used when the segmentation model cannot run: how far a pixel is from the
     * colour at the frame's border, weighted toward the middle of the picture. Enough to give the
     * region sliders something sane to act on; not a person cut-out.
     */
    fun heuristicSubject(pixels: IntArray, width: Int, height: Int, radius: Int = 3): FloatArray {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return FloatArray(0)
        val ring = max(1, min(width, height) / 25)
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val onBorder = x < ring || y < ring || x >= width - ring || y >= height - ring
                if (!onBorder) continue
                val argb = pixels[y * width + x]
                rSum += (argb shr 16 and 0xFF) / 255.0
                gSum += (argb shr 8 and 0xFF) / 255.0
                bSum += (argb and 0xFF) / 255.0
                count++
            }
        }
        if (count == 0) return FloatArray(width * height)
        val br = (rSum / count).toFloat()
        val bg = (gSum / count).toFloat()
        val bb = (bSum / count).toFloat()

        val raw = FloatArray(width * height)
        val cx = width * 0.5f
        val cy = height * 0.46f
        val sx = width * 0.33f
        val sy = height * 0.36f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = pixels[y * width + x]
                val r = (argb shr 16 and 0xFF) / 255f
                val g = (argb shr 8 and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                val dr = r - br
                val dg = g - bg
                val db = b - bb
                val distance = sqrt(dr * dr + dg * dg + db * db) / 1.732f
                val dx = (x - cx) / sx
                val dy = (y - cy) / sy
                val centre = exp(-(dx * dx + dy * dy) * 0.5f)
                raw[y * width + x] = (distance * 2.6f * (0.30f + 0.80f * centre)).coerceIn(0f, 1f)
            }
        }
        return feather(raw, width, height, radius)
    }

    /** ARGB pixels with R = subject, G = sky, ready for `uploadBitmap`. */
    fun pack(subject: FloatArray, sky: FloatArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val s = (subject.getOrElse(i) { 0f }.coerceIn(0f, 1f) * 255f).toInt()
            val k = (sky.getOrElse(i) { 0f }.coerceIn(0f, 1f) * 255f).toInt()
            out[i] = (0xFF shl 24) or (s shl 16) or (k shl 8)
        }
        return out
    }

    /** Mean sky confidence, used to tell "there is a sky here" from "there is not". */
    fun coverage(mask: FloatArray): Float {
        if (mask.isEmpty()) return 0f
        var sum = 0f
        for (value in mask) sum += value
        return sum / mask.size
    }

    /** Mean confidence above [threshold], for a quick sanity check in tests and logs. */
    fun fractionAbove(mask: FloatArray, threshold: Float = 0.5f): Float {
        if (mask.isEmpty()) return 0f
        var count = 0
        for (value in mask) if (abs(value) >= threshold) count++
        return count.toFloat() / mask.size
    }
}
