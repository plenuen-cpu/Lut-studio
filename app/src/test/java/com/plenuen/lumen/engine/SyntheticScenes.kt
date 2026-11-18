package com.plenuen.lumen.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic synthetic photographs for the v2.2.5 ↔ v2.3 comparison.
 *
 * Real photos cannot be committed to the repository and the build machine has no camera, so the
 * benchmark draws its own frames: nine scene families, four variants each (36 frames), every one of
 * them a case the diagnosis is supposed to recognise — a portrait, a backlit portrait, a landscape,
 * a blown-out landscape, a night street, an indoor room, a plate of food, a flat low-contrast wall
 * and a colour-cast frame. Each frame also carries the region masks a real segmentation would give
 * (subject ellipse, sky region), so the region-aware parts of the engine are exercised too.
 *
 * What this is *not*: a stand-in for a real photograph. Noise, lens character, real faces, texture
 * and mixed light all differ, which is exactly why the report keeps synthetic numbers apart from the
 * on-device checks. The point is a controlled comparison where the truth about each frame is known.
 */
data class SyntheticScene(
    val name: String,
    val category: String,
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val subject: FloatArray?,
    val sky: FloatArray?,
) {
    val hasSubject: Boolean get() = subject != null
    val hasSky: Boolean get() = sky != null
}

object SyntheticScenes {

    const val SIZE = 224

    fun all(): List<SyntheticScene> {
        val scenes = mutableListOf<SyntheticScene>()
        for (variant in 0 until 4) {
            scenes += portrait("인물", variant, backlit = false)
            scenes += portrait("역광 인물", variant, backlit = true)
            scenes += landscape("풍경", variant, overexposed = false)
            scenes += landscape("과노출 풍경", variant, overexposed = true)
            scenes += night("야경", variant)
            scenes += indoor("실내", variant)
            scenes += food("음식", variant)
            scenes += flat("저대비", variant)
            scenes += colorCast("색편향", variant)
        }
        return scenes
    }

    // ── 인물 ─────────────────────────────────────────────────────────────

    private fun portrait(category: String, variant: Int, backlit: Boolean): SyntheticScene {
        val w = SIZE
        val h = SIZE
        val subject = FloatArray(w * h)
        val sky = FloatArray(w * h)
        val skinTone = 0.92f - variant * 0.05f
        val pixels = IntArray(w * h)
        val cx = 0.5f + (variant - 1.5f) * 0.03f
        val cy = 0.57f
        val rx = 0.20f
        val ry = 0.29f
        for (y in 0 until h) {
            val fy = y.toFloat() / h
            for (x in 0 until w) {
                val fx = x.toFloat() / w
                var r: Float
                var g: Float
                var b: Float
                if (backlit) {
                    // 밝은 하늘 배경 + 어두운 인물: 역광
                    val glow = exp(-((fx - 0.5f) * (fx - 0.5f) + (fy - 0.22f) * (fy - 0.22f)) * 9f)
                    r = 0.86f + 0.14f * glow - variant * 0.02f
                    g = 0.88f + 0.12f * glow - variant * 0.02f
                    b = 0.84f + 0.10f * glow - variant * 0.03f
                } else {
                    val shade = 0.62f - 0.20f * fy + 0.03f * sin(fx * 12f)
                    r = shade * 0.92f - variant * 0.02f
                    g = shade * 0.96f - variant * 0.02f
                    b = shade * 1.05f - variant * 0.02f
                }
                val dx = (fx - cx) / rx
                val dy = (fy - cy) / ry
                val inside = dx * dx + dy * dy
                if (inside <= 1f) {
                    val edge = smoothstep(1.0f, 0.92f, inside)
                    val light = 0.78f + 0.30f * (1f - fx) * (0.6f + 0.4f * (1f - fy))
                    val skin = if (backlit) 0.42f + variant * 0.03f else 1f
                    val sr = skinTone * light * skin
                    val sg = skinTone * 0.78f * light * skin
                    val sb = skinTone * 0.63f * light * skin
                    r = r + (sr - r) * edge
                    g = g + (sg - g) * edge
                    b = b + (sb - b) * edge
                    subject[y * w + x] = edge
                    // 머리카락
                    if (fy < cy - ry * 0.62f) {
                        val hair = smoothstep(cy - ry * 0.95f, cy - ry * 0.55f, fy)
                        r *= 1f - 0.75f * hair
                        g *= 1f - 0.75f * hair
                        b *= 1f - 0.72f * hair
                    }
                }
                if (backlit && fy < 0.30f && inside > 1f) sky[y * w + x] = 1f
                pixels[y * w + x] = pack(r, g, b)
            }
        }
        val name = "$category-${variant + 1}"
        return SyntheticScene(
            name = name,
            category = category,
            width = w,
            height = h,
            argb = pixels,
            subject = subject,
            sky = if (backlit) sky else null,
        )
    }

    // ── 풍경 ─────────────────────────────────────────────────────────────

    private fun landscape(category: String, variant: Int, overexposed: Boolean): SyntheticScene {
        val w = SIZE
        val h = SIZE
        val pixels = IntArray(w * h)
        val sky = FloatArray(w * h)
        val horizon = 0.46f + variant * 0.02f
        for (y in 0 until h) {
            val fy = y.toFloat() / h
            for (x in 0 until w) {
                val fx = x.toFloat() / w
                val noise = 0.02f * sin(fx * 41f + variant) * cos(fy * 29f)
                val r: Float
                val g: Float
                val bl: Float
                if (fy < horizon) {
                    val t = fy / horizon
                    val haze = if (overexposed) 0.30f else 0.06f
                    r = (0.42f + 0.40f * t + haze + noise).coerceIn(0f, 1f)
                    g = (0.62f + 0.30f * t + haze + noise).coerceIn(0f, 1f)
                    bl = (0.88f + 0.10f * t + haze * 0.6f).coerceIn(0f, 1f)
                    sky[y * w + x] = 1f
                } else {
                    val t = (fy - horizon) / (1f - horizon)
                    val green = 0.42f - 0.14f * t
                    r = (green * 0.82f + noise + if (overexposed) 0.22f else 0f).coerceIn(0f, 1f)
                    g = (green + noise + if (overexposed) 0.18f else 0f).coerceIn(0f, 1f)
                    bl = (green * 0.45f + noise).coerceIn(0f, 1f)
                }
                val boosted = if (overexposed) 1.10f + variant * 0.02f else 1f
                pixels[y * w + x] = pack(r * boosted, g * boosted, bl * boosted)
            }
        }
        return SyntheticScene(
            name = "$category-${variant + 1}",
            category = category,
            width = w,
            height = h,
            argb = pixels,
            subject = null,
            sky = sky,
        )
    }

    // ── 야경 ─────────────────────────────────────────────────────────────

    private fun night(category: String, variant: Int): SyntheticScene {
        val w = SIZE
        val h = SIZE
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val fy = y.toFloat() / h
            for (x in 0 until w) {
                val fx = x.toFloat() / w
                var r = 0.020f + 0.012f * fy
                var g = 0.024f + 0.012f * fy
                var b = 0.048f + 0.020f * fy
                // 건물 창문
                val building = fx in 0.08f..0.92f && fy > 0.25f
                if (building) {
                    val windowX = ((fx * 14f) % 1f)
                    val windowY = ((fy * 18f) % 1f)
                    val lit = windowX in 0.25f..0.65f && windowY in 0.2f..0.6f
                    val on = lit && ((x * 7 + y * 13 + variant * 5) % 23 == 0)
                    if (on) {
                        r = 0.86f
                        g = 0.72f
                        b = 0.44f
                    } else {
                        r = 0.05f
                        g = 0.05f
                        b = 0.08f
                    }
                }
                // 가로등
                val lamp = abs(fx - 0.22f) < 0.012f && fy in 0.72f..0.86f
                if (lamp) {
                    r = 1f
                    g = 0.95f
                    b = 0.8f
                }
                val lift = variant * 0.008f
                pixels[y * w + x] = pack(r + lift, g + lift, b + lift)
            }
        }
        return SyntheticScene(
            name = "$category-${variant + 1}",
            category = category,
            width = w,
            height = h,
            argb = pixels,
            subject = null,
            sky = null,
        )
    }

    // ── 실내 ─────────────────────────────────────────────────────────────

    private fun indoor(category: String, variant: Int): SyntheticScene {
        val w = SIZE
        val h = SIZE
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val fy = y.toFloat() / h
            for (x in 0 until w) {
                val fx = x.toFloat() / w
                var r = 0.50f - 0.10f * fy - variant * 0.02f
                var g = 0.43f - 0.09f * fy - variant * 0.02f
                var b = 0.37f - 0.08f * fy - variant * 0.02f
                // 창문: 하이라이트가 날아가는 밝은 사각형
                if (fx in 0.62f..0.92f && fy in 0.10f..0.48f) {
                    r = 1.0f
                    g = 0.99f
                    b = 0.94f
                }
                // 테이블과 의자(피사체로 취급)
                if (fy > 0.70f) {
                    r = 0.34f
                    g = 0.26f
                    b = 0.21f
                }
                if (fx in 0.18f..0.38f && fy in 0.46f..0.72f) {
                    r = 0.30f
                    g = 0.24f
                    b = 0.20f
                }
                pixels[y * w + x] = pack(r, g, b)
            }
        }
        // 방 안에는 사람이 없다 → 세그멘테이션이 인물을 못 찾는 경우와 같다.
        return SyntheticScene("$category-${variant + 1}", category, w, h, pixels, null, null)
    }

    // ── 음식 ─────────────────────────────────────────────────────────────

    private fun food(category: String, variant: Int): SyntheticScene {
        val w = SIZE
        val h = SIZE
        val pixels = IntArray(w * h)
        val subject = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = y.toFloat() / h
            for (x in 0 until w) {
                val fx = x.toFloat() / w
                var r = 0.58f - 0.10f * fy
                var g = 0.47f - 0.09f * fy
                var b = 0.37f - 0.07f * fy
                val dx = (fx - 0.5f) / 0.30f
                val dy = (fy - 0.55f) / 0.24f
                val plate = dx * dx + dy * dy
                if (plate <= 1f) {
                    r = 0.94f
                    g = 0.92f
                    b = 0.88f
                    subject[y * w + x] = 1f
                    val inner = ((fx - 0.5f) / 0.20f).let { it * it } + ((fy - 0.55f) / 0.15f).let { it * it }
                    if (inner <= 1f) {
                        val warm = 0.90f - variant * 0.04f
                        r = warm
                        g = 0.46f + 0.06f * fx
                        b = 0.24f
                    }
                }
                pixels[y * w + x] = pack(r, g, b)
            }
        }
        return SyntheticScene("$category-${variant + 1}", category, w, h, pixels, subject, null)
    }

    // ── 저대비 ───────────────────────────────────────────────────────────

    private fun flat(category: String, variant: Int): SyntheticScene {
        val w = SIZE
        val h = SIZE
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val fy = y.toFloat() / h
            for (x in 0 until w) {
                val fx = x.toFloat() / w
                val v = 0.52f + 0.05f * fy + 0.02f * sin(fx * 7f + variant)
                val r = v + 0.01f
                val g = v
                val b = v - 0.01f
                pixels[y * w + x] = pack(r, g, b)
            }
        }
        return SyntheticScene("$category-${variant + 1}", category, w, h, pixels, null, null)
    }

    // ── 색편향 ───────────────────────────────────────────────────────────

    private fun colorCast(category: String, variant: Int): SyntheticScene {
        val w = SIZE
        val h = SIZE
        val pixels = IntArray(w * h)
        val warm = variant % 2 == 0
        for (y in 0 until h) {
            val fy = y.toFloat() / h
            for (x in 0 until w) {
                val fx = x.toFloat() / w
                var r = 0.58f + 0.10f * (1f - fy)
                var g = 0.56f + 0.08f * (1f - fy)
                var b = 0.54f + 0.06f * (1f - fy)
                if (fy > 0.55f) {
                    r = 0.44f
                    g = 0.42f
                    b = 0.40f
                }
                if (warm) {
                    r *= 1.22f
                    b *= 0.72f
                } else {
                    r *= 0.74f
                    b *= 1.24f
                    g *= 1.06f
                }
                val grain = 0.01f * sin(fx * 33f + variant)
                pixels[y * w + x] = pack(r + grain, g + grain, b + grain)
            }
        }
        return SyntheticScene("$category-${variant + 1}", category, w, h, pixels, null, null)
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** Mean luminance, used by the benchmark to describe a frame. */
    fun meanLuma(argb: IntArray): Float {
        var sum = 0.0
        for (px in argb) {
            sum += 0.2126 * ((px shr 16) and 0xFF) / 255.0 +
                0.7152 * ((px shr 8) and 0xFF) / 255.0 +
                0.0722 * (px and 0xFF) / 255.0
        }
        return if (argb.isEmpty()) 0f else (sum / argb.size).toFloat()
    }

    fun rmsContrast(argb: IntArray): Float {
        val mean = meanLuma(argb)
        var variance = 0.0
        for (px in argb) {
            val l = 0.2126 * ((px shr 16) and 0xFF) / 255.0 +
                0.7152 * ((px shr 8) and 0xFF) / 255.0 +
                0.0722 * (px and 0xFF) / 255.0
            variance += (l - mean) * (l - mean)
        }
        return if (argb.isEmpty()) 0f else sqrt(variance / argb.size).toFloat()
    }

    /** True when the frame is dark enough that "it would be a night shot". */
    fun isNight(argb: IntArray): Boolean = meanLuma(argb) < 0.26f

    fun describe(scene: SyntheticScene): String =
        String.format(
            java.util.Locale.US,
            "%s luma=%.2f contrast=%.2f",
            scene.category,
            meanLuma(scene.argb),
            rmsContrast(scene.argb),
        )

    /** Luminance of one pixel, 0..1. */
    fun lumaAt(argb: IntArray, index: Int): Float {
        val px = argb[index]
        return 0.2126f * ((px shr 16) and 0xFF) / 255f +
            0.7152f * ((px shr 8) and 0xFF) / 255f +
            0.0722f * (px and 0xFF) / 255f
    }

    fun percentile(argb: IntArray, fraction: Float): Float {
        val histogram = IntArray(256)
        for (px in argb) {
            val l = 0.2126f * ((px shr 16) and 0xFF) / 255f +
                0.7152f * ((px shr 8) and 0xFF) / 255f +
                0.0722f * (px and 0xFF) / 255f
            histogram[(l * 255f).toInt().coerceIn(0, 255)]++
        }
        val total = histogram.sum()
        if (total == 0) return 0f
        val target = max(1, min(total, (total * fraction).toInt()))
        var running = 0
        for (i in histogram.indices) {
            running += histogram[i]
            if (running >= target) return i / 255f
        }
        return 1f
    }

    /** A label for the report: how far this frame is from a believable exposure. */
    fun exposureIssue(argb: IntArray): String {
        val mean = meanLuma(argb)
        return when {
            mean < 0.30f -> "어두움"
            mean > 0.72f -> "밝음"
            else -> "보통"
        }
    }
}
