package com.plenuen.lumen.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The eight colour bands of the HSL panel, and the maths that feathers them.
 *
 * The shader in [com.plenuen.lumen.gl.ShaderSource] keeps its own copy of this arithmetic (GLSL
 * cannot call Kotlin), so the two are written to match term for term: same centres, same cosine
 * window, same ±30° hue travel, and the same neutral state (every band at zero leaves the pixel
 * untouched). This object is the reference implementation the unit tests hold the shader to.
 */
enum class HslBand(
    val label: String,
    val centerDeg: Float,
    val hue: Param,
    val saturation: Param,
    val luminance: Param,
    /** Chip colour in the panel. */
    val swatch: Int,
) {
    RED("빨강", 0f, Param.RED_HUE, Param.RED_SAT, Param.RED_LUM, 0xFFE53935.toInt()),
    ORANGE("주황", 30f, Param.ORANGE_HUE, Param.ORANGE_SAT, Param.ORANGE_LUM, 0xFFFB8C00.toInt()),
    YELLOW("노랑", 60f, Param.YELLOW_HUE, Param.YELLOW_SAT, Param.YELLOW_LUM, 0xFFFDD835.toInt()),
    GREEN("초록", 120f, Param.GREEN_HUE, Param.GREEN_SAT, Param.GREEN_LUM, 0xFF43A047.toInt()),
    CYAN("청록", 180f, Param.CYAN_HUE, Param.CYAN_SAT, Param.CYAN_LUM, 0xFF00ACC1.toInt()),
    BLUE("파랑", 240f, Param.BLUE_HUE, Param.BLUE_SAT, Param.BLUE_LUM, 0xFF1E88E5.toInt()),
    PURPLE("보라", 285f, Param.PURPLE_HUE, Param.PURPLE_SAT, Param.PURPLE_LUM, 0xFF8E24AA.toInt()),
    MAGENTA("자홍", 330f, Param.MAGENTA_HUE, Param.MAGENTA_SAT, Param.MAGENTA_LUM, 0xFFD81B60.toInt()),
    ;

    val params: List<Param> get() = listOf(hue, saturation, luminance)

    companion object {

        /** Hue a fully turned band knob moves a pixel, in degrees. */
        const val HUE_TRAVEL_DEG = 30f

        /** Saturation multiplier at +1 / removed at -1. */
        const val SAT_GAIN = 0.85f

        /** Luminance travel at +1, weighted toward the midtones. */
        const val LUM_GAIN = 0.16f

        /** Below this saturation a pixel is grey and no band may touch it. */
        const val GREY_FLOOR = 0.03f
        const val GREY_CEIL = 0.16f

        /** The band whose knobs are [param], or null for parameters outside the eight bands. */
        fun bandOf(param: Param): HslBand? = entries.firstOrNull { param in it.params }

        /**
         * Cosine window per band, matching the shader's `bandWeights`.
         *
         * Each band is full strength at its centre and fades to zero at the neighbouring centre on
         * each side; because neighbouring windows are complementary, the weights add up to 1 across
         * the whole hue circle and an edge between two bands never steps.
         */
        fun weights(hueDeg: Float): FloatArray {
            val h = normalize(hueDeg)
            val out = FloatArray(entries.size)
            for (i in entries.indices) {
                val center = entries[i].centerDeg
                val prev = entries[(i + entries.size - 1) % entries.size].centerDeg
                val next = entries[(i + 1) % entries.size].centerDeg
                val gapPrev = forwardGap(prev, center)
                val gapNext = forwardGap(center, next)
                val delta = signedDelta(center, h)
                val gap = if (delta < 0f) gapPrev else gapNext
                out[i] = if (gap <= 1e-3f || abs(delta) >= gap) 0f
                else 0.5f * (1f + cos(PI.toFloat() * abs(delta) / gap))
            }
            return out
        }

        /** Reference implementation of the shader's HSL block. Values are read through [value]. */
        fun apply(rgb: FloatArray, value: (Param) -> Float): FloatArray {
            val hsl = rgbToHsl(rgb)
            val weights = weights(hsl[0] * 360f)
            var dh = 0f
            var ds = 0f
            var dl = 0f
            entries.forEachIndexed { index, band ->
                dh += weights[index] * value(band.hue)
                ds += weights[index] * value(band.saturation)
                dl += weights[index] * value(band.luminance)
            }
            val grey = smoothstep(GREY_FLOOR, GREY_CEIL, hsl[1])
            val hue = normalize01(hsl[0] + dh * (HUE_TRAVEL_DEG / 360f) * grey)
            val sat = (hsl[1] * (1f + ds * SAT_GAIN * grey)).coerceIn(0f, 1f)
            val midWeight = 1f - abs(2f * hsl[2] - 1f) * 0.5f
            val lum = (hsl[2] + dl * LUM_GAIN * grey * midWeight).coerceIn(0f, 1f)
            return hslToRgb(floatArrayOf(hue, sat, lum))
        }

        /** hue 0..1, saturation 0..1, lightness 0..1. */
        fun rgbToHsl(rgb: FloatArray): FloatArray {
            val r = rgb[0]
            val g = rgb[1]
            val b = rgb[2]
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val l = (maxC + minC) * 0.5f
            val d = maxC - minC
            if (d <= 1e-6f) return floatArrayOf(0f, 0f, l)
            val s = if (l > 0.5f) d / max(2f - maxC - minC, 1e-6f) else d / max(maxC + minC, 1e-6f)
            val h = when (maxC) {
                r -> ((g - b) / d + if (g < b) 6f else 0f)
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            }
            return floatArrayOf(normalize01(h / 6f), s.coerceIn(0f, 1f), l)
        }

        fun hslToRgb(hsl: FloatArray): FloatArray {
            val h = normalize01(hsl[0])
            val s = hsl[1].coerceIn(0f, 1f)
            val l = hsl[2].coerceIn(0f, 1f)
            if (s <= 1e-6f) return floatArrayOf(l, l, l)
            val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
            val p = 2f * l - q
            return floatArrayOf(
                hueToRgb(p, q, h + 1f / 3f),
                hueToRgb(p, q, h),
                hueToRgb(p, q, h - 1f / 3f),
            ).map { it.coerceIn(0f, 1f) }.toFloatArray()
        }

        private fun hueToRgb(p: Float, q: Float, t: Float): Float {
            var value = t
            if (value < 0f) value += 1f
            if (value > 1f) value -= 1f
            return when {
                value < 1f / 6f -> p + (q - p) * 6f * value
                value < 0.5f -> q
                value < 2f / 3f -> p + (q - p) * (2f / 3f - value) * 6f
                else -> p
            }
        }

        private fun normalize(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        private fun normalize01(value: Float): Float = ((value % 1f) + 1f) % 1f

        /** Signed shortest way from [from] to [to], in -180..180 degrees. */
        private fun signedDelta(from: Float, to: Float): Float {
            var d = normalize(to - from)
            if (d > 180f) d -= 360f
            return d
        }

        private fun forwardGap(from: Float, to: Float): Float = normalize(to - from)

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
