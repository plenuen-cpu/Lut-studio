package com.plenuen.lumen.engine

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.nima.NimaGuardrails
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A frame as the quality pass sees it: the pixels plus the region masks that belong to them.
 *
 * [subject] and [sky] are the app's mask channels (R = subject, G = sky) flattened to 0..1 weights,
 * or null when the photo has no mask. Everything the evaluator needs is measured once, on
 * construction, because a search compares dozens of candidates against the same two frames.
 */
class PixelSample(
    argb: IntArray,
    val width: Int,
    val height: Int,
    /** Read-only region weights; the caller keeps ownership and never rewrites them. */
    val subject: FloatArray? = null,
    val sky: FloatArray? = null,
) {
    /**
     * An owned copy of the frame, never the caller's buffer.
     *
     * The renderer that produced these pixels hands back the same array on the next draw — that is
     * what makes sixty candidates cheap — so a sample that kept the reference would silently become
     * the *last* candidate: the baseline's own clipping, colour and pixel-change readings would
     * drift with every render. Copying here makes every sample an immutable snapshot, whichever
     * caller builds it.
     */
    val argb: IntArray = argb.copyOf()

    init {
        require(argb.size >= width * height || argb.isEmpty()) {
            "pixel buffer too small: ${argb.size} < ${width * height}"
        }
    }

    val pixels: Int get() = argb.size

    /** Mean luminance 0..1. */
    val meanLuma: Float by lazy { measureTone().mean }
    val p05: Float by lazy { measureTone().p05 }
    val p95: Float by lazy { measureTone().p95 }
    val meanSaturation: Float by lazy { measureColour().saturation }
    val oversaturated: Float by lazy { measureColour().oversaturated }

    /** Fraction of pixels at or above 98% luminance — detail the file no longer holds. */
    val highlightClip: Float by lazy { measureTone().highlight }
    /** Fraction of pixels at or below 2% luminance. */
    val shadowClip: Float by lazy { measureTone().shadow }

    /** Mean chroma (max−min of RGB, 0..1). */
    val meanChroma: Float by lazy { measureColour().chroma }

    /** Skin reading inside the subject mask, or null when the frame has no face. */
    val skin: SkinReading? by lazy { measureSkin() }

    /** Average luminance inside the subject mask, or null without a mask. */
    val subjectLuma: Float? by lazy { maskedMean(subject) }
    val backgroundLuma: Float? by lazy { maskedMean(backgroundWeights()) }
    val skyLuma: Float? by lazy { maskedMean(sky) }

    /** Contrast (p95−p05) inside the subject mask, or null without a mask. */
    val subjectContrast: Float? by lazy { maskedContrast(subject) }

    data class SkinReading(val coverage: Float, val hueDeg: Float, val chroma: Float, val luma: Float)

    private data class Tone(val mean: Float, val p05: Float, val p95: Float, val highlight: Float, val shadow: Float)

    private fun measureTone(): Tone {
        val histogram = IntArray(256)
        var sum = 0.0
        var count = 0
        var step = max(1, kotlin.math.sqrt((width.toLong() * height / 160_000.0).toDouble()).toInt())
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val px = argb[y * width + x]
                val luma = 0.2126f * ((px shr 16) and 0xFF) / 255f +
                    0.7152f * ((px shr 8) and 0xFF) / 255f +
                    0.0722f * (px and 0xFF) / 255f
                histogram[(luma * 255f).toInt().coerceIn(0, 255)]++
                sum += luma
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return Tone(0f, 0f, 1f, 0f, 0f)
        val total = count.toFloat()
        return Tone(
            mean = (sum / count).toFloat(),
            p05 = percentile(histogram, 0.05f),
            p95 = percentile(histogram, 0.95f),
            highlight = histogram.copyOfRange(250, 256).sum() / total,
            shadow = histogram.copyOfRange(0, 6).sum() / total,
        )
    }

    private data class Colour(val saturation: Float, val oversaturated: Float, val chroma: Float)

    private fun measureColour(): Colour {
        var satSum = 0.0
        var chromaSum = 0.0
        var over = 0
        var count = 0
        val step = max(1, kotlin.math.sqrt((width.toLong() * height / 160_000.0).toDouble()).toInt())
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
                chromaSum += hi - lo
                if (sat > 0.80f) over++
                count++
                x += step
            }
            y += step
        }
        val total = count.toFloat().coerceAtLeast(1f)
        return Colour((satSum / total).toFloat(), over / total, (chromaSum / total).toFloat())
    }

    private fun measureSkin(): SkinReading? {
        val mask = subject ?: return null
        var count = 0
        var inside = 0
        var hueX = 0f
        var hueY = 0f
        var chroma = 0f
        var lumaSum = 0f
        val step = max(1, kotlin.math.sqrt((width.toLong() * height / 160_000.0).toDouble()).toInt())
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * width + x
                if ((mask.getOrNull(index) ?: 0f) >= 0.5f) {
                    inside++
                    val px = argb[index]
                    val r = ((px shr 16) and 0xFF) / 255f
                    val g = ((px shr 8) and 0xFF) / 255f
                    val b = (px and 0xFF) / 255f
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val hue = hueOf(r, g, b)
                    val c = max(r, max(g, b)) - min(r, min(g, b))
                    if (hue in 8f..52f && c in 0.09f..0.62f && luma in 0.16f..0.97f) {
                        val rad = Math.toRadians(hue.toDouble()).toFloat()
                        hueX += kotlin.math.cos(rad)
                        hueY += kotlin.math.sin(rad)
                        chroma += c
                        lumaSum += luma
                        count++
                    }
                }
                x += step
            }
            y += step
        }
        if (count < 12 || inside == 0) return null
        val hue = ((Math.toDegrees(kotlin.math.atan2(hueY, hueX).toDouble()).toFloat() + 360f) % 360f)
        return SkinReading(count.toFloat() / inside, hue, chroma / count, lumaSum / count)
    }

    private fun backgroundWeights(): FloatArray? {
        val subject = subject ?: return null
        return FloatArray(subject.size) { 1f - subject[it].coerceIn(0f, 1f) }
    }

    private fun maskedMean(mask: FloatArray?): Float? {
        if (mask == null) return null
        var sum = 0.0
        var count = 0
        val step = max(1, kotlin.math.sqrt((width.toLong() * height / 160_000.0).toDouble()).toInt())
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * width + x
                if ((mask.getOrNull(index) ?: 0f) >= 0.5f) {
                    val px = argb[index]
                    sum += 0.2126f * ((px shr 16) and 0xFF) / 255f +
                        0.7152f * ((px shr 8) and 0xFF) / 255f +
                        0.0722f * (px and 0xFF) / 255f
                    count++
                }
                x += step
            }
            y += step
        }
        return if (count < 12) null else (sum / count).toFloat()
    }

    private fun maskedContrast(mask: FloatArray?): Float? {
        if (mask == null) return null
        val histogram = IntArray(256)
        var count = 0
        val step = max(1, kotlin.math.sqrt((width.toLong() * height / 160_000.0).toDouble()).toInt())
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * width + x
                if ((mask.getOrNull(index) ?: 0f) >= 0.5f) {
                    val px = argb[index]
                    val luma = 0.2126f * ((px shr 16) and 0xFF) / 255f +
                        0.7152f * ((px shr 8) and 0xFF) / 255f +
                        0.0722f * (px and 0xFF) / 255f
                    histogram[(luma * 255f).toInt().coerceIn(0, 255)]++
                    count++
                }
                x += step
            }
            y += step
        }
        if (count < 12) return null
        return (percentile(histogram, 0.95f) - percentile(histogram, 0.05f)).coerceIn(0f, 1f)
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

    private fun hueOf(r: Float, g: Float, b: Float): Float {
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        if (d <= 1e-5f) return -1f
        val h = when (hi) {
            r -> ((g - b) / d + if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return (h * 60f + 360f) % 360f
    }

    companion object {
        /** A frame with no pixels at all; the evaluator answers "unknown" for it. */
        val EMPTY = PixelSample(IntArray(0), 0, 0)
    }
}

/** Which hard rule threw a candidate away. Shown in the diagnostics, never to the user. */
enum class Rejection(val label: String) {
    HIGHLIGHT_CLIP("하이라이트가 더 날아감"),
    SHADOW_CRUSH("암부가 더 뭉개짐"),
    SKIN_DRIFT("피부톤이 어긋남"),
    SATURATION_BLOWOUT("색이 터짐"),
    PIXEL_OVERWORK("사진이 과하게 바뀜"),
    COLOR_IMBALANCE("색 균형이 무너짐"),
    BUDGET("예산 초과"),
}

/** The score of one candidate, split into the parts that made it. */
data class QualityReport(
    val nima: Float?,
    /** −1 … +1: NIMA relative to the frame's own neutral render (0.5 = same). */
    val nimaPart: Float,
    val clipPart: Float,
    val colorPart: Float,
    val skinPart: Float,
    val separationPart: Float,
    val scenePart: Float,
    /** 0..1, 1 = the recipe barely moved anything. */
    val restraintPart: Float,
    /** Weighted total 0..1 used to rank candidates. */
    val total: Float,
    val rejected: Rejection?,
    val skinDrift: Float,
    val meanChange: Float,
    val highlightClip: Float,
    val shadowClip: Float,
    val meanSaturation: Float,
    val separation: Float?,
) {
    val accepted: Boolean get() = rejected == null

    fun summary(): String = buildString {
        append("품질 ")
        append(String.format(java.util.Locale.US, "%.3f", total))
        nima?.let { append(" · NIMA ").append(String.format(java.util.Locale.US, "%.2f", it)) }
        append(" · 클리핑 ").append((highlightClip * 100).toInt()).append('/').append((shadowClip * 100).toInt()).append('%')
        if (rejected != null) append(" · 탈락: ").append(rejected.label)
    }
}

/**
 * Judges a corrected frame on more than the model's taste.
 *
 * NIMA was trained on what people vote for; it rewards contrast, warmth and saturation wherever
 * they appear, so a sky can go white and a face orange while the score climbs. This evaluator keeps
 * the model's opinion as one voice among seven, and lets the hard gates overrule it: a candidate
 * that clips more highlights, crushes more shadow, drifts the skin or blows the colour is thrown
 * away *before* the score is compared at all. A slightly lower NIMA with an intact sky and a
 * believable face wins.
 *
 * All numbers are measured on the same 224×224 render the model scores, so what is judged here is
 * what the user sees — the preview and the export run the identical shader.
 */
object QualityEvaluator {

    /**
     * Weights of the seven parts. NIMA still leads, but the photo's integrity (clip + colour + skin)
     * together outweighs it, which is the whole point of the change.
     */
    private const val NIMA_WEIGHT = 0.34f
    private const val CLIP_WEIGHT = 0.18f
    private const val COLOR_WEIGHT = 0.10f
    private const val SKIN_WEIGHT = 0.12f
    private const val SEPARATION_WEIGHT = 0.10f
    private const val SCENE_WEIGHT = 0.10f
    private const val RESTRAINT_WEIGHT = 0.06f

    /** Hard ceilings, measured against the frame's own neutral render. */
    private const val MAX_HIGHLIGHT_GROWTH = 0.020f   // +2%p of the frame
    private const val MAX_HIGHLIGHT_RATIO = 1.6f
    private const val MAX_SHADOW_GROWTH = 0.030f
    private const val MAX_SHADOW_RATIO = 1.8f
    private const val MAX_SKIN_HUE_DRIFT = 11f        // degrees
    private const val MAX_SKIN_CHROMA_GROWTH = 0.16f
    private const val MAX_SATURATION_GROWTH = 1.32f   // × the original mean saturation
    private const val MAX_OVERSATURATED = 0.22f
    private const val MAX_MEAN_CHANGE = 0.20f         // mean |Δluma| over the frame
    private const val MAX_CAST_GROWTH = 0.10f         // |R−B| mean change

    /**
     * Scores [candidate] against the frame it came from.
     *
     * @param base the frame rendered with the user's own recipe (neutral for quality purposes)
     * @param diagnosis the reading of that frame, used for the scene-fit part
     * @param nima the model's score for the candidate, if the model answered
     */
    fun evaluate(
        base: PixelSample,
        candidate: PixelSample,
        baseRecipe: EditRecipe,
        candidateRecipe: EditRecipe,
        diagnosis: PhotoDiagnosis,
        nima: Float?,
        nimaBase: Float?,
    ): QualityReport {
        if (base.pixels == 0 || candidate.pixels == 0) {
            return QualityReport(
                nima = nima, nimaPart = 0.5f, clipPart = 0.5f, colorPart = 0.5f, skinPart = 0.5f,
                separationPart = 0.5f, scenePart = 0.5f, restraintPart = 1f, total = 0.5f,
                rejected = Rejection.BUDGET, skinDrift = 0f, meanChange = 0f,
                highlightClip = 0f, shadowClip = 0f, meanSaturation = 0f, separation = null,
            )
        }
        return score(base, candidate, baseRecipe, candidateRecipe, diagnosis, nima, nimaBase, reference = false)
    }

    /**
     * Scores [candidate] against [base] with the seven weighted parts and the hard gates.
     *
     * [reference] marks the frame's own report — the "nothing changed" baseline. It goes through
     * exactly the same arithmetic as a candidate (only the comparison side is itself), so the two
     * totals are on one scale; the gates are left out because they describe a *proposal* that made
     * the picture worse and the baseline is not a proposal. A frame that already clips or is already
     * oversaturated is measured, not thrown away: that is what the search has to beat.
     */
    private fun score(
        base: PixelSample,
        candidate: PixelSample,
        baseRecipe: EditRecipe,
        candidateRecipe: EditRecipe,
        diagnosis: PhotoDiagnosis,
        nima: Float?,
        nimaBase: Float?,
        reference: Boolean,
    ): QualityReport {
        val highlightClip = candidate.highlightClip
        val shadowClip = candidate.shadowClip
        val meanChange = meanLumaChange(base.argb, candidate.argb)
        val skinDrift = skinDriftOf(base.skin, candidate.skin)
        val saturationGrowth = if (base.meanSaturation > 1e-3f) {
            candidate.meanSaturation / base.meanSaturation
        } else {
            1f
        }
        val castGrowth = castOf(candidate) - castOf(base)

        // ── 하드 탈락 게이트: 점수를 보기 전에 사진이 망가졌는지 먼저 본다 ──────────
        val rejection = if (reference) null else when {
            highlightClip - base.highlightClip > MAX_HIGHLIGHT_GROWTH &&
                highlightClip > base.highlightClip * MAX_HIGHLIGHT_RATIO -> Rejection.HIGHLIGHT_CLIP
            shadowClip - base.shadowClip > MAX_SHADOW_GROWTH &&
                shadowClip > base.shadowClip * MAX_SHADOW_RATIO -> Rejection.SHADOW_CRUSH
            skinDrift > 1f -> Rejection.SKIN_DRIFT
            saturationGrowth > MAX_SATURATION_GROWTH || candidate.oversaturated > MAX_OVERSATURATED -> Rejection.SATURATION_BLOWOUT
            castGrowth > MAX_CAST_GROWTH -> Rejection.COLOR_IMBALANCE
            meanChange > MAX_MEAN_CHANGE -> Rejection.PIXEL_OVERWORK
            else -> null
        }

        // ── 부분 점수 ────────────────────────────────────────────────────────
        val nimaPart = when {
            nima == null || nimaBase == null || !nima.isFinite() || !nimaBase.isFinite() -> 0.5f
            else -> (0.5f + (nima - nimaBase) * 0.75f).coerceIn(0f, 1f)
        }
        // 클리핑: 지금 상태 + 늘어난 양 둘 다 벌점. 0%면 1, 10%면 0 근처.
        val clipPart = (1f -
            2.2f * (highlightClip + shadowClip) -
            3.0f * max(0f, highlightClip - base.highlightClip) -
            3.0f * max(0f, shadowClip - base.shadowClip)).coerceIn(0f, 1f)
        val colorPart = (1f -
            0.9f * max(0f, saturationGrowth - 1.02f) -
            1.4f * max(0f, 1f - saturationGrowth - 0.02f) -
            1.2f * max(0f, castGrowth)).coerceIn(0f, 1f)
        val baseSkinReading = base.skin
        // 작은 이동(노출을 올리며 살색이 조금 밝아지는 정도)은 벌하지 않고,
        // 자연스러운 대역을 벗어나기 시작할 때부터 세게 깎는다.
        val skinPart = when {
            baseSkinReading == null || baseSkinReading.coverage < 0.02f -> 0.6f
            else -> (1f - max(0f, skinDrift - 0.18f) * 0.9f).coerceIn(0f, 1f)
        }
        val separationPart = separationScore(base, candidate, diagnosis)
        val scenePart = sceneFit(diagnosis, base, candidate, baseRecipe, candidateRecipe)
        val restraintPart = (1f - recipeDistance(baseRecipe, candidateRecipe) * 0.8f -
            meanChange * 1.2f).coerceIn(0f, 1f)

        val total = (
            NIMA_WEIGHT * nimaPart +
                CLIP_WEIGHT * clipPart +
                COLOR_WEIGHT * colorPart +
                SKIN_WEIGHT * skinPart +
                SEPARATION_WEIGHT * separationPart +
                SCENE_WEIGHT * scenePart +
                RESTRAINT_WEIGHT * restraintPart
            ).coerceIn(0f, 1f)

        return QualityReport(
            nima = nima,
            nimaPart = nimaPart,
            clipPart = clipPart,
            colorPart = colorPart,
            skinPart = skinPart,
            separationPart = separationPart,
            scenePart = scenePart,
            restraintPart = restraintPart,
            total = if (rejection != null) min(total, 0.35f) else total,
            rejected = rejection,
            skinDrift = skinDrift,
            meanChange = meanChange,
            highlightClip = highlightClip,
            shadowClip = shadowClip,
            meanSaturation = candidate.meanSaturation,
            separation = separationOf(candidate, diagnosis),
        )
    }

    /**
     * The frame's own report, used as the "nothing changed" baseline.
     *
     * Same weights and same component formulas as a candidate, so "this candidate beats the photo"
     * means what it says. A fixed placeholder here (0.5) let the search accept any correction that
     * merely looked better than a number nobody had measured.
     */
    fun baseline(base: PixelSample, diagnosis: PhotoDiagnosis, nima: Float?): QualityReport =
        score(base, base, EditRecipe.NEUTRAL, EditRecipe.NEUTRAL, diagnosis, nima, nima, reference = true)

    // ── 부분 점수 계산 ────────────────────────────────────────────────────

    /**
     * 피부 이탈: 색조가 11° 넘게 돌거나 채도가 0.16 넘게 오르면 1을 넘겨 하드 탈락.
     * 얼굴이 없는 사진은 0.
     */
    private fun skinDriftOf(base: PixelSample.SkinReading?, candidate: PixelSample.SkinReading?): Float {
        if (base == null || candidate == null) return 0f
        if (base.coverage < 0.02f) return 0f
        var hue = abs(candidate.hueDeg - base.hueDeg)
        if (hue > 180f) hue = 360f - hue
        val huePart = (hue - 4f).coerceAtLeast(0f) / MAX_SKIN_HUE_DRIFT
        val chromaPart = ((candidate.chroma - base.chroma - 0.03f).coerceAtLeast(0f)) / MAX_SKIN_CHROMA_GROWTH
        val lumaPart = (abs(candidate.luma - base.luma) - 0.16f).coerceAtLeast(0f) / 0.30f
        return (huePart + chromaPart + lumaPart).coerceIn(0f, 2f)
    }

    /** |mean(R−B)|: a cast is a shift of the frame's own colour balance. */
    private fun castOf(sample: PixelSample): Float {
        var sum = 0.0
        var count = 0
        val step = max(1, kotlin.math.sqrt((sample.width.toLong() * sample.height / 40_000.0).toDouble()).toInt())
        var y = 0
        while (y < sample.height) {
            var x = 0
            while (x < sample.width) {
                val px = sample.argb[y * sample.width + x]
                sum += (((px shr 16) and 0xFF) - (px and 0xFF)) / 255.0
                count++
                x += step
            }
            y += step
        }
        return if (count == 0) 0f else kotlin.math.abs((sum / count).toFloat())
    }

    /** 피사체-배경 분리: 밝기 차와 대비 차가 원본보다 벌어졌는지. */
    private fun separationScore(base: PixelSample, candidate: PixelSample, diagnosis: PhotoDiagnosis): Float {
        val baseSep = separationOf(base, diagnosis)
        val candSep = separationOf(candidate, diagnosis)
        if (baseSep == null || candSep == null) return 0.5f
        // 0.6 = 그대로, 1 = 뚜렷이 좋아짐, 0 = 오히려 붙음.
        return (0.6f + (candSep - baseSep) * 2.6f).coerceIn(0f, 1f)
    }

    /** −1 … +1: subject brighter than background and crisper than it. */
    fun separationOf(sample: PixelSample, diagnosis: PhotoDiagnosis): Float? {
        val subjectLuma = sample.subjectLuma ?: return null
        val backgroundLuma = sample.backgroundLuma ?: return null
        if (diagnosis.subject == null || diagnosis.subject.coverage < 0.02f) return null
        val lumaGap = (subjectLuma - backgroundLuma).coerceIn(-1f, 1f)
        val contrastGap = ((sample.subjectContrast ?: 0.5f) - 0.35f).coerceIn(-1f, 1f)
        return (0.70f * lumaGap + 0.30f * contrastGap).coerceIn(-1f, 1f)
    }

    /**
     * 장면 적합성: 그 장면에서 기대하는 방향으로 움직였는지.
     *
     * Checks are deliberately coarse — each is a direction, not a target — because the search still
     * has to beat the baseline on the total; this only breaks ties between plausible candidates.
     */
    private fun sceneFit(
        diagnosis: PhotoDiagnosis,
        base: PixelSample,
        candidate: PixelSample,
        baseRecipe: EditRecipe,
        candidateRecipe: EditRecipe,
    ): Float {
        if (!diagnosis.measured) return 0.5f
        var score = 0.5f
        val highlights = diagnosis.global.highlightClip
        val shadows = diagnosis.global.shadowClip
        when (diagnosis.scene) {
            SceneKind.PORTRAIT, SceneKind.BACKLIT_PORTRAIT -> {
                // 얼굴이 밝아지고 배경이 눌리면 +, 피부 채도가 터지면 −
                val subj = candidateRecipe[Param.SUBJECT_EXPOSURE] - baseRecipe[Param.SUBJECT_EXPOSURE]
                val bkg = candidateRecipe[Param.BACKGROUND_EXPOSURE] - baseRecipe[Param.BACKGROUND_EXPOSURE]
                score += (subj * 0.9f - bkg * 0.5f).coerceIn(-0.25f, 0.25f)
                val candSkin = candidate.skin
                val baseSkin = base.skin
                if (candSkin != null && baseSkin != null) {
                    score -= (candSkin.chroma - baseSkin.chroma).coerceAtLeast(0f) * 0.8f
                }
                if (diagnosis.separation != null && diagnosis.separation.score < 0.45f) {
                    score += (candidateRecipe[Param.SUBJECT_SKIN_PROTECT] * 0.05f)
                }
            }
            SceneKind.LANDSCAPE, SceneKind.OVEREXPOSED_LANDSCAPE -> {
                val skyDrop = (base.skyLuma ?: 0.5f) - (candidate.skyLuma ?: 0.5f)
                score += (skyDrop * 0.8f).coerceIn(-0.2f, 0.2f)
                if (highlights > 0.03f && candidate.highlightClip < base.highlightClip) score += 0.10f
                score += (candidateRecipe[Param.SKY_DETAIL] * 0.10f).coerceIn(0f, 0.08f)
            }
            SceneKind.NIGHT -> {
                // 암부를 열되 하이라이트가 더 날아가면 −
                val lift = candidate.meanLuma - base.meanLuma
                score += (lift * 0.9f).coerceIn(-0.1f, 0.16f)
                if (candidate.highlightClip > base.highlightClip + 0.01f) score -= 0.12f
                if (shadows > 0.08f && candidate.shadowClip > base.shadowClip + 0.01f) score -= 0.08f
            }
            SceneKind.FLAT -> {
                val contrast = (candidate.p95 - candidate.p05) - (base.p95 - base.p05)
                score += (contrast * 1.2f).coerceIn(-0.15f, 0.25f)
            }
            SceneKind.COLOR_CAST -> {
                val cast = castOf(candidate) - castOf(base)
                score -= (cast * 1.5f).coerceIn(-0.2f, 0.2f)
            }
            SceneKind.FOOD -> {
                val warm = candidateRecipe[Param.TEMPERATURE] - baseRecipe[Param.TEMPERATURE]
                score += (warm.coerceIn(-0.1f, 0.12f) * 0.6f)
                val sat = candidate.meanSaturation - base.meanSaturation
                score -= (sat - 0.06f).coerceAtLeast(0f) * 0.8f
            }
            SceneKind.INDOOR -> {
                val cast = castOf(candidate) - castOf(base)
                score -= (cast * 1.2f).coerceIn(-0.15f, 0.2f)
                score += ((candidate.meanLuma - base.meanLuma) * 0.5f).coerceIn(-0.05f, 0.10f)
            }
            SceneKind.GENERAL -> {
                score += ((candidate.p95 - candidate.p05) - (base.p95 - base.p05)).coerceIn(-0.1f, 0.1f)
            }
        }
        return score.coerceIn(0f, 1f)
    }

    /** How far a candidate moved the recipe, 0..1, measured against each parameter's guardrail. */
    fun recipeDistance(base: EditRecipe, candidate: EditRecipe): Float {
        var total = 0f
        var count = 0
        for (param in Param.entries) {
            val from = base[param]
            val to = candidate[param]
            if (abs(to - from) < 1e-4f) continue
            val limit = NimaGuardrails.limitOf(param).takeIf { it > 0f }
                ?: RegionLimits.limitOf(param)
                ?: 1f
            total += (abs(to - from) / max(limit, 1e-3f)).coerceAtMost(2f)
            count++
        }
        if (count == 0) return 0f
        return (total / max(count, 1)).coerceIn(0f, 1f)
    }

    /** Mean |Δluma| between two frames of the same size, 0..1. */
    fun meanLumaChange(before: IntArray, after: IntArray): Float {
        val size = min(before.size, after.size)
        if (size == 0) return 0f
        val step = max(1, size / 40_000)
        var sum = 0.0
        var count = 0
        var i = 0
        while (i < size) {
            val a = before[i]
            val b = after[i]
            val la = 0.2126f * ((a shr 16) and 0xFF) / 255f + 0.7152f * ((a shr 8) and 0xFF) / 255f + 0.0722f * (a and 0xFF) / 255f
            val lb = 0.2126f * ((b shr 16) and 0xFF) / 255f + 0.7152f * ((b shr 8) and 0xFF) / 255f + 0.0722f * (b and 0xFF) / 255f
            sum += abs(lb - la)
            count++
            i += step
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }
}

/**
 * Search radii for the region parameters, which [NimaGuardrails] deliberately does not list.
 *
 * Region values act only inside a mask, so a whole slider's worth of them is a smaller change to the
 * picture than the same amount on a global parameter. These are the ceilings the search may use;
 * the scene strategy narrows them further.
 */
object RegionLimits {

    private val limits: Map<Param, Float> = mapOf(
        Param.SUBJECT_EXPOSURE to 0.35f,
        Param.SUBJECT_SATURATION to 0.20f,
        Param.SUBJECT_WARMTH to 0.20f,
        Param.SUBJECT_CLARITY to 0.24f,
        Param.SUBJECT_CONTRAST to 0.24f,
        Param.SUBJECT_STRUCTURE to 0.28f,
        Param.SUBJECT_SKIN_PROTECT to 0.60f,
        Param.BACKGROUND_EXPOSURE to 0.35f,
        Param.BACKGROUND_SATURATION to 0.20f,
        Param.BACKGROUND_WARMTH to 0.20f,
        Param.BACKGROUND_CLARITY to 0.24f,
        Param.BACKGROUND_SOFT to 0.28f,
        Param.BACKGROUND_HIGHLIGHTS to 0.30f,
        Param.SKY_EXPOSURE to 0.30f,
        Param.SKY_SATURATION to 0.26f,
        Param.SKY_WARMTH to 0.18f,
        Param.SKY_BLUE to 0.28f,
        Param.SKY_DETAIL to 0.32f,
        Param.TONE_SHADOW_CURVE to 0.24f,
        Param.TONE_MID_CURVE to 0.24f,
        Param.TONE_HIGH_CURVE to 0.24f,
    )

    fun limitOf(param: Param): Float? = limits[param]

    fun isSearchable(param: Param): Boolean = limits.containsKey(param)

    /** True when [value] stays inside this parameter's region ceiling. */
    fun allows(param: Param, base: Float, value: Float): Boolean {
        val limit = limits[param] ?: return false
        return abs(value - base) <= limit + 1e-4f
    }
}
