package com.plenuen.lumen.engine

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A CPU stand-in for the GLSL shader, used only by the benchmark.
 *
 * The app's real render path is GLES 3.0 ([com.plenuen.lumen.gl.ShaderSource]) and cannot run on a
 * build machine without a GPU. This file reproduces the *arithmetic* of the tone, colour, region and
 * local-contrast blocks — the parts the benchmark measures — with the same coefficients, so a recipe
 * produces a frame whose clipping, colour, skin and separation respond the way they do on the phone.
 *
 * What it deliberately leaves out, and what that means for the numbers:
 *  - geometry (straighten, keystone, crop): the synthetic scenes are already level, so the block is
 *    the identity and leaving it out changes nothing;
 *  - the 3D LUT and the 8-band HSL pins: the benchmark never selects a look, so both are neutral;
 *  - grain and halation: they add noise the metrics would misread as detail.
 *
 * So it is a faithful model of the blocks the comparison depends on, not a pixel-exact clone of the
 * shader: results are labelled "CPU 파이프라인" in the report and the device-side numbers still have
 * to be confirmed on the S25 Ultra.
 */
object CpuPipeline {

    fun render(
        argb: IntArray,
        width: Int,
        height: Int,
        recipe: EditRecipe,
        subject: FloatArray? = null,
        sky: FloatArray? = null,
    ): IntArray {
        val n = width * height
        val exposure = recipe[Param.EXPOSURE]
        val contrast = recipe[Param.CONTRAST]
        val highlights = recipe[Param.HIGHLIGHTS]
        val shadows = recipe[Param.SHADOWS]
        val whites = recipe[Param.WHITES]
        val blacks = recipe[Param.BLACKS]
        val fade = recipe[Param.FADE]
        val temperature = recipe[Param.TEMPERATURE]
        val tint = recipe[Param.TINT]
        val vibrance = recipe[Param.VIBRANCE]
        val saturation = recipe[Param.SATURATION]
        val clarity = recipe[Param.CLARITY]
        val texture = recipe[Param.TEXTURE]
        val dehaze = recipe[Param.DEHAZE]

        // ── 톤·색 (기하·룩은 중립) ─────────────────────────────────────────
        val tone = FloatArray(n * 3)
        for (i in 0 until n) {
            val px = argb[i]
            var r = ((px shr 16) and 0xFF) / 255f
            var g = ((px shr 8) and 0xFF) / 255f
            var b = (px and 0xFF) / 255f

            // 1. 노출 (linear light)
            val lin = 2f.pow(exposure)
            r = toSrgb(toLinear(r) * lin)
            g = toSrgb(toLinear(g) * lin)
            b = toSrgb(toLinear(b) * lin)

            // 2. 기본 톤
            r = (r - 0.5f) * (1f + contrast * 1.15f) + 0.5f
            g = (g - 0.5f) * (1f + contrast * 1.15f) + 0.5f
            b = (b - 0.5f) * (1f + contrast * 1.15f) + 0.5f
            val l = luma(r, g, b)
            val highMask = smoothstep(0.45f, 1f, l)
            val lowMask = 1f - smoothstep(0f, 0.55f, l)
            val hl = highlights * 0.38f * highMask
            val sh = shadows * 0.42f * lowMask
            r += hl * (1f - r) + sh * r
            g += hl * (1f - g) + sh * g
            b += hl * (1f - b) + sh * b
            val wl = whites * 0.20f * smoothstep(0.62f, 1f, l)
            val bl = blacks * 0.20f * (1f - smoothstep(0f, 0.38f, l))
            r += wl + bl
            g += wl + bl
            b += wl + bl
            r = r * 0.80f + 0.10f + (r - (r * 0.80f + 0.10f)) * (1f - fade)
            g = g * 0.80f + 0.10f + (g - (g * 0.80f + 0.10f)) * (1f - fade)
            b = b * 0.80f + 0.10f + (b - (b * 0.80f + 0.10f)) * (1f - fade)

            // 2-1. 국소 톤 커브
            val ts = recipe[Param.TONE_SHADOW_CURVE]
            val tm = recipe[Param.TONE_MID_CURVE]
            val th = recipe[Param.TONE_HIGH_CURVE]
            if (ts != 0f || tm != 0f || th != 0f) {
                val tl = luma(r, g, b)
                val wShadow = 1f - smoothstep(0f, 0.58f, tl)
                val wMid = smoothstep(0.12f, 0.50f, tl) * (1f - smoothstep(0.50f, 0.88f, tl))
                val wHigh = smoothstep(0.52f, 1f, tl)
                val curve = ts * wShadow + tm * wMid + th * wHigh
                val gain = 1f + curve * 0.28f * (1f - 0.45f * tl)
                r *= gain
                g *= gain
                b *= gain
            }

            // 3. 색온도·틴트
            r *= 1f + temperature * 0.20f
            b *= 1f - temperature * 0.20f
            g *= 1f - tint * 0.14f
            r *= 1f + tint * 0.05f
            b *= 1f + tint * 0.05f

            // 4. 채도·활기
            val base = luma(r, g, b)
            r = base + (r - base) * (1f + saturation)
            g = base + (g - base) * (1f + saturation)
            b = base + (b - base) * (1f + saturation)
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            val vibWeight = 1f - smoothstep(0f, 0.65f, chroma)
            val l2 = luma(r, g, b)
            r = l2 + (r - l2) * (1f + vibrance * vibWeight)
            g = l2 + (g - l2) * (1f + vibrance * vibWeight)
            b = l2 + (b - l2) * (1f + vibrance * vibWeight)

            tone[i * 3] = r
            tone[i * 3 + 1] = g
            tone[i * 3 + 2] = b
        }

        // ── 9. 디테일: 근사 박스 블러 (clarity/texture) ────────────────────
        if (clarity != 0f || texture != 0f || dehaze != 0f) {
            val wide = boxBlur(tone, width, height, 7)
            val mid = boxBlur(tone, width, height, 2)
            for (i in 0 until n) {
                val c0 = luma(tone[i * 3], tone[i * 3 + 1], tone[i * 3 + 2])
                val clarityMask = (1f - kotlin.math.abs(c0 - 0.5f) * 1.3f).coerceIn(0f, 1f)
                for (ch in 0 until 3) {
                    val index = i * 3 + ch
                    val original = channel(argb[i], ch)
                    tone[index] += (original - wide[index]) * clarity * 0.85f * clarityMask
                    tone[index] += (original - mid[index]) * texture * 0.90f
                }
                if (dehaze != 0f) {
                    val veil = min(min(channel(argb[i], 0), channel(argb[i], 1)), channel(argb[i], 2))
                    val scale = max(1f - dehaze * 0.18f, 0.35f)
                    for (ch in 0 until 3) {
                        val index = i * 3 + ch
                        tone[index] = (tone[index] - veil * dehaze * 0.30f) / scale
                    }
                }
            }
        }

        // ── 14. 영역 보정 ─────────────────────────────────────────────────
        val subExposure = recipe[Param.SUBJECT_EXPOSURE]
        val subSaturation = recipe[Param.SUBJECT_SATURATION]
        val subWarmth = recipe[Param.SUBJECT_WARMTH]
        val subClarity = recipe[Param.SUBJECT_CLARITY]
        val subContrast = recipe[Param.SUBJECT_CONTRAST]
        val subStructure = recipe[Param.SUBJECT_STRUCTURE]
        val skinProtect = recipe[Param.SUBJECT_SKIN_PROTECT]
        val bkgExposure = recipe[Param.BACKGROUND_EXPOSURE]
        val bkgSaturation = recipe[Param.BACKGROUND_SATURATION]
        val bkgWarmth = recipe[Param.BACKGROUND_WARMTH]
        val bkgClarity = recipe[Param.BACKGROUND_CLARITY]
        val bkgSoft = recipe[Param.BACKGROUND_SOFT]
        val bkgHighlights = recipe[Param.BACKGROUND_HIGHLIGHTS]
        val skyExposure = recipe[Param.SKY_EXPOSURE]
        val skySaturation = recipe[Param.SKY_SATURATION]
        val skyWarmth = recipe[Param.SKY_WARMTH]
        val skyDetail = recipe[Param.SKY_DETAIL]
        val maskOn = recipe[Param.MASK_ON] >= 0.5f

        val out = IntArray(n)
        val midBlur = if (subStructure != 0f || skyDetail != 0f || subClarity != 0f || bkgClarity != 0f ||
            bkgSoft != 0f
        ) {
            boxBlur(tone, width, height, 2)
        } else {
            null
        }
        val wideBlur = if (bkgSoft != 0f || subStructure != 0f || skyDetail != 0f) {
            boxBlur(tone, width, height, 7)
        } else {
            null
        }

        for (i in 0 until n) {
            var r = tone[i * 3]
            var g = tone[i * 3 + 1]
            var b = tone[i * 3 + 2]
            if (maskOn && subject != null) {
                val sSub = (subject.getOrNull(i) ?: 0f).coerceIn(0f, 1f)
                val sSky = (sky?.getOrNull(i) ?: 0f).coerceIn(0f, 1f)
                val mSky = sSky * (1f - sSub)
                val mBkg = (1f - sSub - sSky).coerceIn(0f, 1f)
                val skin = skinWeight(r, g, b)
                val skinHold = skin * skinProtect
                val subW = sSub * (1f - skinHold * 0.80f)

                val ev = subExposure * subW + bkgExposure * mBkg + skyExposure * mSky
                if (ev != 0f) {
                    val gain = 2f.pow(ev * 0.9f)
                    r = toSrgb(toLinear(clamp01(r)) * gain)
                    g = toSrgb(toLinear(clamp01(g)) * gain)
                    b = toSrgb(toLinear(clamp01(b)) * gain)
                }
                val warm = subWarmth * subW + bkgWarmth * mBkg + skyWarmth * mSky
                if (warm != 0f) {
                    r *= 1f + warm * 0.20f
                    b *= 1f - warm * 0.20f
                }
                val satAmt = subSaturation * subW + bkgSaturation * mBkg + skySaturation * mSky
                if (satAmt != 0f) {
                    val l3 = luma(r, g, b)
                    r = l3 + (r - l3) * (1f + satAmt)
                    g = l3 + (g - l3) * (1f + satAmt)
                    b = l3 + (b - l3) * (1f + satAmt)
                }
                if (subContrast != 0f && sSub > 0f) {
                    val k = 1f + subContrast * 0.55f
                    r = r + ((r - 0.5f) * k + 0.5f - r) * subW
                    g = g + ((g - 0.5f) * k + 0.5f - g) * subW
                    b = b + ((b - 0.5f) * k + 0.5f - b) * subW
                }
                if (midBlur != null) {
                    if (subStructure != 0f && sSub > 0f) {
                        for (ch in 0 until 3) {
                            val index = i * 3 + ch
                            val original = channel(argb[i], ch)
                            val add = (original - midBlur[index]) * subStructure * 0.65f * subW
                            when (ch) {
                                0 -> r += add
                                1 -> g += add
                                else -> b += add
                            }
                        }
                    }
                    val clarity2 = subClarity * subW + bkgClarity * mBkg
                    if (clarity2 != 0f) {
                        for (ch in 0 until 3) {
                            val index = i * 3 + ch
                            val original = channel(argb[i], ch)
                            val add = (original - midBlur[index]) * clarity2 * 0.85f
                            when (ch) {
                                0 -> r += add
                                1 -> g += add
                                else -> b += add
                            }
                        }
                    }
                    if (bkgSoft != 0f && wideBlur != null) {
                        for (ch in 0 until 3) {
                            val index = i * 3 + ch
                            val original = channel(argb[i], ch)
                            val add = -(original - wideBlur[index]) * bkgSoft * 1.35f * mBkg
                            when (ch) {
                                0 -> r += add
                                1 -> g += add
                                else -> b += add
                            }
                        }
                    }
                    if (skyDetail != 0f && mSky > 0f && wideBlur != null) {
                        for (ch in 0 until 3) {
                            val index = i * 3 + ch
                            val original = channel(argb[i], ch)
                            val add = (original - midBlur[index]) * skyDetail * 0.85f * mSky +
                                (original - wideBlur[index]) * skyDetail * 0.30f * mSky
                            when (ch) {
                                0 -> r += add
                                1 -> g += add
                                else -> b += add
                            }
                        }
                    }
                }
                if (bkgHighlights != 0f && mBkg > 0f) {
                    val bl = luma(r, g, b)
                    val bright = smoothstep(0.58f, 1f, bl) * mBkg
                    if (bkgHighlights > 0f) {
                        val drop = bkgHighlights * 0.42f * bright * bl
                        r -= drop
                        g -= drop
                        b -= drop
                    } else {
                        val lift = -bkgHighlights * 0.22f * bright
                        r += (1f - r) * lift
                        g += (1f - g) * lift
                        b += (1f - b) * lift
                    }
                }
                if (skinProtect != 0f && sSub > 0f && skinHold > 0f) {
                    val hsl = rgbToHsl(clamp01(r), clamp01(g), clamp01(b))
                    val target = hsl[1].coerceIn(0.14f, 0.44f)
                    val mixed = hsl[1] + (target - hsl[1]) * skinHold * 0.55f
                    val back = hslToRgb(hsl[0], mixed, hsl[2])
                    val k = skinHold * 0.55f
                    r += (back[0] - r) * k
                    g += (back[1] - g) * k
                    b += (back[2] - b) * k
                }
            }
            // 15. 비네팅
            val vignette = recipe[Param.VIGNETTE]
            if (vignette != 0f) {
                val x = (i % width).toFloat() / width - 0.5f
                val y = (i / width).toFloat() / height - 0.5f
                val radius = kotlin.math.sqrt(x * x + y * y) * 2f
                val vig = smoothstep(0.40f, 1.15f, radius)
                val k = 1f - vignette * 0.60f * vig
                r *= k
                g *= k
                b *= k
            }
            out[i] = pack(r, g, b)
        }
        return out
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    fun skinWeight(r: Float, g: Float, b: Float): Float {
        val hsl = rgbToHsl(clamp01(r), clamp01(g), clamp01(b))
        val hueDeg = hsl[0] * 360f
        var w = smoothstep(8f, 22f, hueDeg) * (1f - smoothstep(42f, 62f, hueDeg))
        w *= smoothstep(0.06f, 0.16f, hsl[1]) * (1f - smoothstep(0.55f, 0.85f, hsl[1]))
        return w.coerceIn(0f, 1f)
    }

    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun toSrgb(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    private fun channel(px: Int, ch: Int): Float = when (ch) {
        0 -> ((px shr 16) and 0xFF) / 255f
        1 -> ((px shr 8) and 0xFF) / 255f
        else -> (px and 0xFF) / 255f
    }

    private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** Separable box blur over the float RGB buffer; radius in pixels. */
    private fun boxBlur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val window = radius * 2 + 1
        for (y in 0 until height) {
            for (ch in 0 until 3) {
                var sum = 0f
                for (dx in -radius..radius) {
                    val x = dx.coerceIn(0, width - 1)
                    sum += src[(y * width + x) * 3 + ch]
                }
                for (x in 0 until width) {
                    tmp[(y * width + x) * 3 + ch] = sum / window
                    val addX = (x + radius + 1).coerceAtMost(width - 1)
                    val subX = (x - radius).coerceAtLeast(0)
                    sum += src[(y * width + addX) * 3 + ch] - src[(y * width + subX) * 3 + ch]
                }
            }
        }
        for (x in 0 until width) {
            for (ch in 0 until 3) {
                var sum = 0f
                for (dy in -radius..radius) {
                    val y = dy.coerceIn(0, height - 1)
                    sum += tmp[(y * width + x) * 3 + ch]
                }
                for (y in 0 until height) {
                    out[(y * width + x) * 3 + ch] = sum / window
                    val addY = (y + radius + 1).coerceAtMost(height - 1)
                    val subY = (y - radius).coerceAtLeast(0)
                    sum += tmp[(addY * width + x) * 3 + ch] - tmp[(subY * width + x) * 3 + ch]
                }
            }
        }
        return out
    }

    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        val l = (maxc + minc) * 0.5f
        val d = maxc - minc
        if (d <= 1e-6f) return floatArrayOf(0f, 0f, l)
        val s = if (l > 0.5f) d / max(2f - maxc - minc, 1e-6f) else d / max(maxc + minc, 1e-6f)
        val h = when (maxc) {
            r -> ((g - b) / d + if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return floatArrayOf((h / 6f + 1f) % 1f, s.coerceIn(0f, 1f), l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s <= 1e-6f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 0.5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        return floatArrayOf(hue(h + 1f / 3f), hue(h), hue(h - 1f / 3f))
    }
}
