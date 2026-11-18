package com.plenuen.lumen.engine

import com.plenuen.lumen.nl.SceneAnalyzer
import com.plenuen.lumen.nl.SceneStats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What is wrong with this photo, measured before anything is corrected.
 *
 * The automatic pass used to walk one slider at a time and let the NIMA model say yes or no. That
 * never looks at the photo itself, so a backlit portrait, a blown-out sky and a flat indoor wall all
 * got the same ladder — and NIMA, which likes contrast and saturation, happily pushed a face into
 * orange. This file reads the frame first: where the light is, what is clipped, which way the white
 * balance leans, whether there is a face to protect, how the subject sits against its background and
 * what kind of scene this is. The search (and the spoken-request planner) then starts from a
 * different set of candidates for a night street than for a portrait.
 *
 * Everything here is plain arithmetic on an ARGB buffer plus the optional region masks, so it runs
 * on the JVM in tests exactly as it does on the phone.
 */
enum class SceneKind(val label: String) {
    PORTRAIT("인물"),
    BACKLIT_PORTRAIT("역광 인물"),
    LANDSCAPE("풍경"),
    OVEREXPOSED_LANDSCAPE("과노출 풍경"),
    NIGHT("야경"),
    INDOOR("실내"),
    FOOD("음식"),
    FLAT("저대비"),
    COLOR_CAST("색편향"),
    GENERAL("일반"),
}

/** One concrete problem found in the frame, with the scene-specific strategy that answers it. */
enum class Issue(val label: String) {
    HIGHLIGHT_CLIP("하이라이트 날아감"),
    SHADOW_CRUSH("암부 뭉개짐"),
    UNDER_EXPOSED("전체 어두움"),
    OVER_EXPOSED("전체 밝음"),
    WARM_CAST("따뜻한 색편향"),
    COOL_CAST("차가운 색편향"),
    GREEN_CAST("초록/마젠타 편향"),
    FLAT_CONTRAST("납작한 명암"),
    HARSH_CONTRAST("과한 명암"),
    OVERSATURATED("과포화"),
    DULL_COLOR("칙칙한 색"),
    SKIN_UNNATURAL("피부톤 어긋남"),
    SKIN_DULL("피부 칙칙함"),
    LOW_SEPARATION("피사체가 배경에 묻힘"),
    DISTRACTING_BACKGROUND("배경 밝은 영역이 시선을 뺏음"),
    BLOWN_SKY("하늘 날아감"),
    NOISY_SHADOW("암부 노이즈 위험"),
}

/** Tone statistics of the whole frame or of one region (subject, background, sky). */
data class RegionTone(
    /** Fraction of the frame this region covers (0..1). */
    val coverage: Float,
    val meanLuma: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val highlightClip: Float,
    val shadowClip: Float,
    val meanSaturation: Float,
    /** mean(R − B); positive is warm. */
    val warmth: Float,
) {
    val contrast: Float get() = (p95 - p05).coerceIn(0f, 1f)

    companion object {
        val NONE = RegionTone(0f, 0.5f, 0f, 0.5f, 1f, 0f, 0f, 0f, 0f)
    }
}

/** Gray-world white balance, expressed both as channel bias and as the two sliders that fix it. */
data class WhiteBalance(
    /** log(meanG / meanR): positive means the picture is too red. */
    val redBias: Float,
    /** log(meanG / meanB): positive means the picture is too blue. */
    val blueBias: Float,
    /** |redBias| + |blueBias|, the size of the cast. */
    val magnitude: Float,
    /** 색온도 슬라이더 방향(−1..1): 따뜻한 사진이면 음수(차갑게). */
    val temperatureFix: Float,
    /** 틴트 슬라이더 방향(−1..1): 초록이면 양수(마젠타로). */
    val tintFix: Float,
)

/** The face/skin reading, measured only inside the subject mask. */
data class SkinReport(
    val coverage: Float,
    val meanHueDeg: Float,
    val meanChroma: Float,
    val meanLuma: Float,
    /** How far the skin sits from the natural band (hue 18°~38°, chroma 0.18~0.42). */
    val deviation: Float,
)

/** How clearly the subject stands apart from its background. */
data class Separation(
    val subjectLuma: Float,
    val backgroundLuma: Float,
    val subjectContrast: Float,
    val backgroundContrast: Float,
) {
    /** −1 (subject darker than background) … +1 (subject brighter), scaled by 0.5. */
    val lumaGap: Float get() = (subjectLuma - backgroundLuma).coerceIn(-1f, 1f)
    val contrastGap: Float get() = (subjectContrast - backgroundContrast).coerceIn(-1f, 1f)

    /** 0..1: brighter-than-background and crisper-than-background both raise it. */
    val score: Float
        get() = (0.5f + 0.30f * lumaGap + 0.20f * contrastGap).coerceIn(0f, 1f)
}

/**
 * The shape of the subject mask: where it sits and whether it looks like a person.
 *
 * A segmentation model answers "these pixels are not background", which is true for a plate of food,
 * a chair and a face alike. A person is tall relative to its width, fills its own bounding box and
 * sits near the middle of the frame; a plate is wide and flat. This is what stops a food shot from
 * being treated as a portrait and getting skin protection it does not need.
 */
data class SubjectShape(
    val coverage: Float,
    val aspect: Float,
    val fill: Float,
    val centreY: Float,
) {
    val personLike: Boolean
        get() = aspect >= 1.10f && fill >= 0.40f && coverage in 0.02f..0.70f

    companion object {
        val NONE = SubjectShape(0f, 0f, 0f, 0.5f)
    }
}

/** Colour balance of the frame: how much colour, and how much of it is too much. */
data class ColorReport(
    val meanSaturation: Float,
    /** Fraction of pixels with chroma above 0.80 — the "형광" pixels. */
    val oversaturated: Float,
    /** Fraction of pixels with chroma below 0.06 — a frame that is nearly grey. */
    val desaturated: Float,
)

/**
 * The full reading of one frame.
 *
 * [strategy] is what the engines start from: a scene label plus a set of candidate recipes whose
 * amounts come from the measurements above, never from a fixed preset.
 */
data class PhotoDiagnosis(
    val scene: SceneKind,
    val sceneConfidence: Float,
    val global: RegionTone,
    val subject: RegionTone?,
    val background: RegionTone?,
    val sky: RegionTone?,
    val whiteBalance: WhiteBalance,
    val skin: SkinReport?,
    val separation: Separation?,
    /** 피사체 마스크의 모양(사람 같은지 판단). 마스크가 없으면 NONE. */
    val subjectShape: SubjectShape,
    val color: ColorReport,
    val issues: List<Issue>,
    val strategy: CorrectionStrategy,
    val samples: Int,
) {
    val hasSubject: Boolean
        get() = subject != null && subject.coverage >= 0.02f && subjectShape.personLike
    val hasSky: Boolean get() = sky != null && sky.coverage >= 0.02f

    /** True when this reading came from real pixels; an empty diagnosis must not steer anything. */
    val measured: Boolean get() = samples > 0

    /** "역광 인물 · 하이라이트 날아감, 피사체가 배경에 묻힘" — the card's first line. */
    fun summary(): String {
        if (!measured) return "사진을 읽지 못했어요."
        val head = scene.label
        val tail = issues.take(3).joinToString(", ") { it.label }
        return if (tail.isEmpty()) head + " · 눈에 띄는 문제 없음" else "$head · $tail"
    }

    /**
     * One compact line for the local model's prompt.
     *
     * A 0.5B model is asked for two or three parameter moves; a paragraph of numbers makes it worse,
     * so the hint carries only what changes the answer: the scene, the light, the cast and the face.
     */
    fun promptHint(): String {
        if (!measured) return ""
        val parts = mutableListOf(scene.label)
        parts += when {
            global.meanLuma < 0.30f -> "어둡다"
            global.meanLuma > 0.70f -> "밝다"
            else -> "노출 보통"
        }
        if (global.highlightClip > 0.02f) parts += "하이라이트 ${percent(global.highlightClip)} 날아감"
        if (global.shadowClip > 0.03f) parts += "암부 ${percent(global.shadowClip)} 뭉개짐"
        if (whiteBalance.magnitude > 0.045f) {
            parts += if (whiteBalance.redBias > abs(whiteBalance.blueBias)) "따뜻한 색편향" else "차가운 색편향"
        }
        if (global.contrast < 0.38f) parts += "납작한 명암"
        if (skin != null && skin.coverage > 0.02f) {
            parts += if (skin.deviation > 0.12f) "피부톤 어긋남" else "피부톤 정상"
        }
        if (separation != null && separation.score < 0.45f) parts += "피사체가 배경에 묻힘"
        if (hasSky && sky != null && sky.highlightClip > 0.15f) parts += "하늘 날아감"
        return parts.joinToString(", ") + "."
    }

    /** Strategy label for the note card. */
    fun strategyLine(): String = strategy.label

    private fun percent(value: Float): String = "${(value * 100f).toInt()}%"

    companion object {
        /** No pixels measured yet — used before a photo is open. */
        val UNKNOWN = PhotoDiagnosis(
            scene = SceneKind.GENERAL,
            sceneConfidence = 0f,
            global = RegionTone.NONE,
            subject = null,
            background = null,
            sky = null,
            whiteBalance = WhiteBalance(0f, 0f, 0f, 0f, 0f),
            skin = null,
            separation = null,
            subjectShape = SubjectShape.NONE,
            color = ColorReport(0f, 0f, 1f),
            issues = emptyList(),
            strategy = CorrectionStrategy.NEUTRAL,
            samples = 0,
        )
    }
}

/**
 * Reads a frame and turns the measurements into a correction strategy.
 *
 * Thresholds are deliberately wide: they only decide which family of candidates to try first, and
 * the search that follows still has to earn every point it moves. A wrong label therefore costs one
 * wasted candidate, not a wrong photo.
 */
object PhotoDiagnosisEngine {

    /** Pixels with luma at or above this count as clipped highlights. */
    private const val HIGHLIGHT_LEVEL = 0.980f

    /** Pixels with luma at or below this count as lost shadows. */
    private const val SHADOW_LEVEL = 0.020f

    private const val MAX_SAMPLES = 160_000

    /**
     * @param subjectMask per-pixel weight 0..1 of the subject (R channel of the app's mask), or null.
     * @param skyMask per-pixel weight 0..1 of the sky (G channel of the app's mask), or null.
     * @param sceneStats already measured global statistics, reused when the caller has them.
     */
    fun diagnose(
        argb: IntArray,
        width: Int,
        height: Int,
        subjectMask: FloatArray? = null,
        skyMask: FloatArray? = null,
        sceneStats: SceneStats? = null,
    ): PhotoDiagnosis {
        if (argb.isEmpty() || width <= 0 || height <= 0) return PhotoDiagnosis.UNKNOWN
        val stats = sceneStats ?: SceneAnalyzer.analyze(argb, width, height)
        val global = toneOf(argb, width, height, null, stats)
        if (global.coverage <= 0f) return PhotoDiagnosis.UNKNOWN

        val subject = subjectMask?.let { toneOf(argb, width, height, it, null) }?.takeIf { it.coverage > 0f }
        val sky = skyMask?.let { toneOf(argb, width, height, it, null) }?.takeIf { it.coverage > 0f }
        val background = if (subjectMask != null) {
            val inverted = FloatArray(subjectMask.size) { 1f - subjectMask[it].coerceIn(0f, 1f) }
            toneOf(argb, width, height, inverted, null).takeIf { it.coverage > 0.02f }
        } else {
            null
        }
        val whiteBalance = whiteBalanceOf(argb, width, height)
        val skin = if (subjectMask != null && subject != null && subject.coverage >= 0.02f) {
            skinOf(argb, width, height, subjectMask)
        } else {
            null
        }
        val separation = if (subject != null && background != null &&
            subject.coverage >= 0.02f && background.coverage >= 0.05f
        ) {
            Separation(
                subjectLuma = subject.meanLuma,
                backgroundLuma = background.meanLuma,
                subjectContrast = subject.contrast,
                backgroundContrast = background.contrast,
            )
        } else {
            null
        }
        val color = colorOf(argb, width, height)

        val shape = subjectMask?.let { shapeOf(it, width, height) } ?: SubjectShape.NONE
        val scene = classify(global, subject, sky, background, whiteBalance, color, skin, separation, shape)
        val issues = issuesOf(global, subject, sky, background, whiteBalance, color, skin, separation, scene)
        return PhotoDiagnosis(
            scene = scene.first,
            sceneConfidence = scene.second,
            global = global,
            subject = subject,
            background = background,
            sky = sky,
            whiteBalance = whiteBalance,
            skin = skin,
            separation = separation,
            subjectShape = shape,
            color = color,
            issues = issues,
            strategy = SceneStrategy.forScene(scene.first, issues, global, subject, sky, background, whiteBalance, skin, separation),
            samples = stats.samples,
        )
    }

    // ── 영역 통계 ─────────────────────────────────────────────────────────

    private fun toneOf(
        argb: IntArray,
        width: Int,
        height: Int,
        weight: FloatArray?,
        stats: SceneStats?,
    ): RegionTone {
        // 가중치가 없고 전체 통계를 이미 재 두었으면 그대로 쓴다(같은 프레임을 두 번 세지 않는다).
        if (weight == null && stats != null && stats.samples > 0) {
            return RegionTone(
                coverage = 1f,
                meanLuma = stats.meanLuma,
                p05 = stats.p05,
                p50 = stats.p50,
                p95 = stats.p95,
                highlightClip = stats.highlightClip,
                shadowClip = stats.shadowClip,
                meanSaturation = stats.saturationMean,
                warmth = stats.warmth,
            )
        }
        val step = max(1, sqrt((width.toLong() * height / MAX_SAMPLES).toDouble()).toInt())
        val histogram = IntArray(256)
        var count = 0
        var satSum = 0f
        var warmSum = 0f
        var lumaSum = 0f
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * width + x
                val w = weight?.getOrNull(index) ?: 1f
                if (w >= 0.5f) {
                    val px = argb[index]
                    val r = ((px shr 16) and 0xFF) / 255f
                    val g = ((px shr 8) and 0xFF) / 255f
                    val b = (px and 0xFF) / 255f
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    histogram[(luma * 255f).toInt().coerceIn(0, 255)]++
                    val hi = max(r, max(g, b))
                    val lo = min(r, min(g, b))
                    satSum += if (hi > 1e-4f) (hi - lo) / hi else 0f
                    warmSum += r - b
                    lumaSum += luma
                    count++
                }
                x += step
            }
            y += step
        }
        val total = count.toFloat()
        if (count == 0) return RegionTone.NONE
        val covered = if (weight == null) 1f else {
            // 표본이 아니라 전체 픽셀 대비 비율이 필요하다: 같은 격자에서 가중치만 다시 센다.
            coverageOf(weight, width, height, step)
        }
        return RegionTone(
            coverage = covered,
            meanLuma = lumaSum / total,
            p05 = percentile(histogram, 0.05f),
            p50 = percentile(histogram, 0.50f),
            p95 = percentile(histogram, 0.95f),
            highlightClip = histogram.copyOfRange(250, 256).sum() / total,
            shadowClip = histogram.copyOfRange(0, 6).sum() / total,
            meanSaturation = satSum / total,
            warmth = warmSum / total,
        )
    }

    private fun coverageOf(weight: FloatArray, width: Int, height: Int, step: Int): Float {
        var inside = 0
        var total = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if ((weight.getOrNull(y * width + x) ?: 0f) >= 0.5f) inside++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else inside.toFloat() / total
    }

    private fun percentile(histogram: IntArray, fraction: Float): Float {
        val total = histogram.sum()
        if (total == 0) return 0f
        val target = (total * fraction).toInt().coerceAtLeast(1)
        var running = 0
        for (i in histogram.indices) {
            running += histogram[i]
            if (running >= target) return i / 255f
        }
        return 1f
    }

    // ── 색·피부 ──────────────────────────────────────────────────────────

    private fun whiteBalanceOf(argb: IntArray, width: Int, height: Int): WhiteBalance {
        val step = sampleStep(width, height)
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val px = argb[y * width + x]
                val r = ((px shr 16) and 0xFF) / 255.0
                val g = ((px shr 8) and 0xFF) / 255.0
                val b = (px and 0xFF) / 255.0
                // 하이라이트와 완전한 검정은 gray-world 추정을 망친다.
                val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
                if (luma in 0.05..0.96) {
                    rSum += r
                    gSum += g
                    bSum += b
                    count++
                }
                x += step
            }
            y += step
        }
        if (count == 0) return WhiteBalance(0f, 0f, 0f, 0f, 0f)
        val r = max(rSum / count, 1e-3)
        val g = max(gSum / count, 1e-3)
        val b = max(bSum / count, 1e-3)
        val redBias = (kotlin.math.ln(g / r)).toFloat()
        val blueBias = (kotlin.math.ln(g / b)).toFloat()
        // 슬라이더 눈금: 셰이더가 색온도 ±1에 R/B를 ±20% 움직인다 → 편향을 그 폭에 맞춘다.
        val temperature = (-(redBias - blueBias) * 2.2f).coerceIn(-1f, 1f)
        // 초록이 도드라지면(g가 상대적으로 높으면) 틴트를 마젠타 쪽으로.
        val green = (kotlin.math.ln((r + b) * 0.5 / g)).toFloat()
        val tint = (green * -1.6f).coerceIn(-1f, 1f)
        return WhiteBalance(
            redBias = redBias,
            blueBias = blueBias,
            magnitude = abs(redBias) + abs(blueBias),
            temperatureFix = temperature,
            tintFix = tint,
        )
    }

    private fun skinOf(argb: IntArray, width: Int, height: Int, subjectMask: FloatArray): SkinReport? {
        val step = sampleStep(width, height)
        var count = 0
        var hueX = 0f
        var hueY = 0f
        var chromaSum = 0f
        var lumaSum = 0f
        var inside = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * width + x
                if ((subjectMask.getOrNull(index) ?: 0f) >= 0.5f) {
                    inside++
                    val px = argb[index]
                    val r = ((px shr 16) and 0xFF) / 255f
                    val g = ((px shr 8) and 0xFF) / 255f
                    val b = (px and 0xFF) / 255f
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val hue = hueDegrees(r, g, b)
                    val chroma = max(r, max(g, b)) - min(r, min(g, b))
                    if (isSkin(hue, chroma, luma)) {
                        val radians = Math.toRadians(hue.toDouble()).toFloat()
                        hueX += kotlin.math.cos(radians)
                        hueY += kotlin.math.sin(radians)
                        chromaSum += chroma
                        lumaSum += luma
                        count++
                    }
                }
                x += step
            }
            y += step
        }
        if (count < 12 || inside == 0) return null
        val meanHue = ((Math.toDegrees(kotlin.math.atan2(hueY, hueX).toDouble()).toFloat() + 360f) % 360f)
        val meanChroma = chromaSum / count
        val meanLuma = lumaSum / count
        val coverage = count.toFloat() / inside
        // 자연스러운 피부 대역: 색조 18~40°, 채도 0.16~0.42, 휘도 0.25~0.95.
        val hueMiss = when {
            meanHue < 18f -> (18f - meanHue) / 12f
            meanHue > 40f -> (meanHue - 40f) / 12f
            else -> 0f
        }
        val chromaMiss = when {
            meanChroma < 0.16f -> (0.16f - meanChroma) / 0.16f
            meanChroma > 0.42f -> (meanChroma - 0.42f) / 0.24f
            else -> 0f
        }
        val lumaMiss = when {
            meanLuma < 0.25f -> (0.25f - meanLuma) / 0.25f
            meanLuma > 0.95f -> (meanLuma - 0.95f) / 0.05f
            else -> 0f
        }
        return SkinReport(
            coverage = coverage,
            meanHueDeg = meanHue,
            meanChroma = meanChroma,
            meanLuma = meanLuma,
            deviation = (hueMiss * 0.6f + chromaMiss * 0.5f + lumaMiss * 0.4f).coerceIn(0f, 2f),
        )
    }

    private fun colorOf(argb: IntArray, width: Int, height: Int): ColorReport {
        val step = sampleStep(width, height)
        var count = 0
        var satSum = 0f
        var over = 0
        var desat = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val px = argb[y * width + x]
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                val sat = if (hi > 1e-4f) (hi - lo) / hi else 0f
                satSum += sat
                if (sat > 0.80f) over++
                if (sat < 0.06f) desat++
                count++
                x += step
            }
            y += step
        }
        val total = count.toFloat().coerceAtLeast(1f)
        return ColorReport(satSum / total, over / total, desat / total)
    }

    private fun sampleStep(width: Int, height: Int): Int =
        max(1, sqrt((width.toLong() * height / MAX_SAMPLES).toDouble()).toInt())

    /** 바운딩 박스와 채움률로 피사체 마스크의 모양을 잰다. */
    private fun shapeOf(weight: FloatArray, width: Int, height: Int): SubjectShape {
        var inside = 0
        var total = 0
        var x0 = width
        var x1 = -1
        var y0 = height
        var y1 = -1
        var sumY = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val w = weight.getOrNull(y * width + x) ?: 0f
                if (w >= 0.5f) {
                    inside++
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                    sumY += y
                }
                total++
            }
        }
        if (inside == 0 || x1 < x0 || y1 < y0) return SubjectShape.NONE
        val boxW = (x1 - x0 + 1).toFloat()
        val boxH = (y1 - y0 + 1).toFloat()
        val boxArea = boxW * boxH
        return SubjectShape(
            coverage = inside.toFloat() / total.coerceAtLeast(1),
            aspect = if (boxW <= 0f) 0f else boxH / boxW,
            fill = inside / boxArea,
            centreY = (sumY / inside / height).toFloat(),
        )
    }

    private fun isSkin(hue: Float, chroma: Float, luma: Float): Boolean =
        hue in 8f..52f && chroma in 0.09f..0.62f && luma in 0.16f..0.97f

    private fun hueDegrees(r: Float, g: Float, b: Float): Float {
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        if (d <= 1e-5f) return 0f
        val h = when (hi) {
            r -> ((g - b) / d + if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return (h * 60f + 360f) % 360f
    }

    // ── 장면 분류·문제 목록 ──────────────────────────────────────────────

    private fun classify(
        global: RegionTone,
        subject: RegionTone?,
        sky: RegionTone?,
        background: RegionTone?,
        wb: WhiteBalance,
        color: ColorReport,
        skin: SkinReport?,
        separation: Separation?,
        shape: SubjectShape,
    ): Pair<SceneKind, Float> {
        val subjectCoverage = subject?.coverage ?: 0f
        val skyCoverage = sky?.coverage ?: 0f
        // 사람으로 보려면 마스크 모양이 사람 같고(세로로 길고) 피부가 잡혀야 한다.
        // 접시나 가구는 마스크에 잡히지만 사람은 아니다.
        val hasPerson = subjectCoverage >= 0.02f && shape.personLike &&
            (skin == null || skin.coverage < 0.03f || skin.coverage > 0.06f)

        if (hasPerson) {
            if (skyCoverage >= 0.06f && background != null &&
                background.meanLuma - (subject?.meanLuma ?: global.meanLuma) > 0.16f
            ) {
                return SceneKind.BACKLIT_PORTRAIT to 0.85f
            }
            if (global.meanLuma < 0.26f) return SceneKind.INDOOR to 0.6f
            return SceneKind.PORTRAIT to 0.85f
        }
        if (skyCoverage >= 0.10f) {
            val skyClipped = (sky?.highlightClip ?: 0f) > 0.20f
            return if (skyClipped || global.highlightClip > 0.045f) {
                SceneKind.OVEREXPOSED_LANDSCAPE to 0.8f
            } else {
                SceneKind.LANDSCAPE to 0.8f
            }
        }
        // 장면 종류를 먼저, 결함(색편향·저대비)은 그 뒤에 본다. 따뜻한 실내나 음식 사진은
        // 색편향 수치가 높게 나오지만 결함이 아니라 장면의 성질이기 때문이다.
        if (global.meanLuma < 0.26f) return SceneKind.NIGHT to 0.8f
        if (global.meanLuma < 0.34f && global.highlightClip > 0.02f) return SceneKind.NIGHT to 0.6f
        if (global.meanLuma < 0.42f) return SceneKind.INDOOR to 0.55f
        if (subjectCoverage >= 0.04f && global.meanLuma in 0.2f..0.72f &&
            global.warmth > 0.04f && color.meanSaturation > 0.24f
        ) {
            return SceneKind.FOOD to 0.55f
        }
        if (wb.magnitude > 0.065f) return SceneKind.COLOR_CAST to 0.6f
        if (global.contrast < 0.34f) return SceneKind.FLAT to 0.75f
        if (global.meanLuma < 0.40f) return SceneKind.INDOOR to 0.5f
        return SceneKind.GENERAL to 0.4f
    }

    private fun issuesOf(
        global: RegionTone,
        subject: RegionTone?,
        sky: RegionTone?,
        background: RegionTone?,
        wb: WhiteBalance,
        color: ColorReport,
        skin: SkinReport?,
        separation: Separation?,
        scene: Pair<SceneKind, Float>,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (global.highlightClip > 0.02f) issues += Issue.HIGHLIGHT_CLIP
        if (global.shadowClip > 0.035f) issues += Issue.SHADOW_CRUSH
        if (global.meanLuma < 0.33f && global.highlightClip < 0.01f) issues += Issue.UNDER_EXPOSED
        if (global.meanLuma > 0.72f && global.shadowClip < 0.01f) issues += Issue.OVER_EXPOSED
        when {
            wb.redBias - wb.blueBias > 0.035f -> issues += Issue.WARM_CAST
            wb.blueBias - wb.redBias > 0.035f -> issues += Issue.COOL_CAST
        }
        if (abs(wb.redBias + wb.blueBias) > 0.06f) issues += Issue.GREEN_CAST
        if (global.contrast < 0.38f) issues += Issue.FLAT_CONTRAST
        if (global.contrast > 0.92f) issues += Issue.HARSH_CONTRAST
        if (color.oversaturated > 0.06f || color.meanSaturation > 0.55f) issues += Issue.OVERSATURATED
        if (color.meanSaturation < 0.14f && color.desaturated < 0.9f) issues += Issue.DULL_COLOR
        if (skin != null && skin.coverage > 0.02f) {
            if (skin.deviation > 0.16f) issues += Issue.SKIN_UNNATURAL
            else if (skin.meanLuma < 0.34f || skin.meanChroma < 0.17f) issues += Issue.SKIN_DULL
        }
        if (separation != null && separation.score < 0.42f) issues += Issue.LOW_SEPARATION
        if (background != null && subject != null && background.meanLuma > 0.72f &&
            background.meanLuma - subject.meanLuma > 0.2f
        ) {
            issues += Issue.DISTRACTING_BACKGROUND
        }
        if (sky != null && sky.highlightClip > 0.18f) issues += Issue.BLOWN_SKY
        if (scene.first == SceneKind.NIGHT && global.shadowClip > 0.06f) issues += Issue.NOISY_SHADOW
        return issues.distinct()
    }
}
