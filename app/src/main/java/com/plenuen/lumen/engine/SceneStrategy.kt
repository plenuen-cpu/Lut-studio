package com.plenuen.lumen.engine

import com.plenuen.lumen.core.Param
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The first candidates the automatic pass tries, built from the diagnosis rather than from a fixed
 * ladder, plus the radius each parameter may be searched within.
 *
 * [seeds] are offsets from the recipe the user already has, so an edit the user made by hand is kept
 * and only added to. [limits] are symmetric search radii: the search may combine seeds and refine,
 * but never wanders further from the user's own values than the scene justifies. A portrait is
 * allowed to lift its subject and protect skin; a night shot is not allowed to open the shadows far
 * enough to turn into noise.
 */
data class CorrectionStrategy(
    val label: String,
    val seeds: List<Map<Param, Float>>,
    val limits: Map<Param, Float>,
    /** Parameters this scene usually answers to; the fine stage refines these first. */
    val emphasis: List<Param>,
    val notes: List<String> = emptyList(),
) {
    companion object {
        /** Used before a photo is measured; the search falls back to its generic pool. */
        val NEUTRAL = CorrectionStrategy(
            label = "일반",
            seeds = emptyList(),
            limits = emptyMap(),
            emphasis = emptyList(),
        )
    }
}

object SceneStrategy {

    /** Guardrail the search may widen to when the photo is measurable. */
    private fun radius(base: Float, want: Float, hardLimit: Float): Float =
        min(hardLimit, max(0.02f, abs(want - base)))

    fun forScene(
        scene: SceneKind,
        issues: List<Issue>,
        global: RegionTone,
        subject: RegionTone?,
        sky: RegionTone?,
        background: RegionTone?,
        wb: WhiteBalance,
        skin: SkinReport?,
        separation: Separation?,
    ): CorrectionStrategy {
        val has = issues.toSet()
        val wbFix = wbTemperatureSeed(wb)
        val wbTint = (wb.tintFix * 0.5f).coerceIn(-0.10f, 0.10f)
        val exposureSeed = exposureTarget(global.meanLuma)
        val highlightSeed = (-min(0.45f, global.highlightClip * 6f)).coerceIn(-0.45f, 0f)
        val shadowSeed = min(0.45f, global.shadowClip * 5f).coerceIn(0f, 0.45f)
        val vibranceSeed = when {
            Issue.OVERSATURATED in has -> -0.12f
            Issue.DULL_COLOR in has -> 0.16f
            else -> 0.06f
        }

        return when (scene) {
            SceneKind.PORTRAIT -> portrait(
                global, subject, background, skin, separation, exposureSeed, highlightSeed, shadowSeed,
                vibranceSeed, wbFix, wbTint, has,
            )
            SceneKind.BACKLIT_PORTRAIT -> backlitPortrait(
                global, subject, background, skin, exposureSeed, highlightSeed, shadowSeed, wbFix, wbTint,
            )
            SceneKind.LANDSCAPE -> landscape(global, sky, exposureSeed, highlightSeed, shadowSeed, wbFix, wbTint, has)
            SceneKind.OVEREXPOSED_LANDSCAPE -> overexposedLandscape(global, sky, exposureSeed, wbFix)
            SceneKind.NIGHT -> night(global, exposureSeed, highlightSeed, shadowSeed, wbFix, wbTint)
            SceneKind.INDOOR -> indoor(global, exposureSeed, shadowSeed, wbFix, wbTint, skin)
            SceneKind.FOOD -> food(global, exposureSeed, highlightSeed, wbFix, wbTint)
            SceneKind.FLAT -> flat(global, exposureSeed, vibranceSeed)
            SceneKind.COLOR_CAST -> colorCast(global, wbFix, wbTint, exposureSeed)
            SceneKind.GENERAL -> general(global, exposureSeed, highlightSeed, shadowSeed, vibranceSeed, wbFix)
        }
    }

    // ── 장면별 ───────────────────────────────────────────────────────────

    private fun portrait(
        global: RegionTone,
        subject: RegionTone?,
        background: RegionTone?,
        skin: SkinReport?,
        separation: Separation?,
        exposure: Float,
        highlight: Float,
        shadow: Float,
        vibrance: Float,
        wbFix: Float,
        wbTint: Float,
        issues: Set<Issue>,
    ): CorrectionStrategy {
        val skinHard = skin != null && skin.coverage > 0.02f
        val protect = when {
            Issue.SKIN_UNNATURAL in issues -> -0.18f
            skin != null && skin.meanChroma > 0.34f -> -0.10f
            else -> 0.08f
        }
        val subjectLift = if (separation != null && separation.lumaGap < -0.05f) 0.14f else 0.04f
        val bgDrop = if (background != null && background.meanLuma > 0.66f) -0.14f else -0.05f
        val seeds = listOf(
            mapOf(
                Param.EXPOSURE to exposure * 0.6f,
                Param.HIGHLIGHTS to highlight * 0.7f,
                Param.SHADOWS to shadow * 0.6f,
                Param.VIBRANCE to vibrance * 0.5f,
                Param.SATURATION to protect * 0.4f,
                Param.TEMPERATURE to wbFix,
                Param.TINT to wbTint,
                Param.SUBJECT_EXPOSURE to subjectLift,
                Param.SUBJECT_SKIN_PROTECT to if (skinHard) 0.35f else 0.15f,
                Param.SUBJECT_CONTRAST to 0.06f,
                Param.BACKGROUND_EXPOSURE to bgDrop,
                Param.BACKGROUND_SOFT to 0.10f,
            ),
            mapOf(
                Param.EXPOSURE to exposure * 0.3f,
                Param.SKIN_CLEAN to if (skinHard) 0.22f else 0.08f,
                Param.SUBJECT_SKIN_PROTECT to if (skinHard) 0.45f else 0.2f,
                Param.SUBJECT_STRUCTURE to 0.16f,
                Param.SUBJECT_CLARITY to 0.10f,
                Param.BACKGROUND_HIGHLIGHTS to -0.16f,
                Param.VIBRANCE to vibrance * 0.7f,
                Param.TONE_MID_CURVE to 0.06f,
            ),
            mapOf(
                Param.CONTRAST to 0.08f,
                Param.SUBJECT_EXPOSURE to subjectLift * 0.6f,
                Param.BACKGROUND_EXPOSURE to bgDrop * 0.5f,
                Param.SUBJECT_SATURATION to 0.04f,
                Param.TEMPERATURE to wbFix * 0.6f,
                Param.HIGHLIGHTS to highlight * 0.5f,
            ),
        )
        return CorrectionStrategy(
            label = "인물 보정 — 피부톤 보호와 피사체 분리",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.40f,
                Param.CONTRAST to 0.22f,
                Param.HIGHLIGHTS to 0.35f,
                Param.SHADOWS to 0.35f,
                Param.VIBRANCE to 0.20f,
                Param.SATURATION to 0.10f,
                Param.CLARITY to 0.18f,
                Param.TEXTURE to 0.12f,
                Param.TEMPERATURE to 0.10f,
                Param.TINT to 0.08f,
                Param.SKIN_CLEAN to 0.30f,
                Param.SUBJECT_EXPOSURE to 0.30f,
                Param.SUBJECT_SATURATION to 0.16f,
                Param.SUBJECT_CONTRAST to 0.20f,
                Param.SUBJECT_STRUCTURE to 0.24f,
                Param.SUBJECT_CLARITY to 0.18f,
                Param.SUBJECT_SKIN_PROTECT to 0.50f,
                Param.BACKGROUND_EXPOSURE to 0.30f,
                Param.BACKGROUND_HIGHLIGHTS to 0.24f,
                Param.BACKGROUND_SOFT to 0.24f,
                Param.BACKGROUND_CLARITY to 0.12f,
                Param.TONE_SHADOW_CURVE to 0.18f,
                Param.TONE_MID_CURVE to 0.18f,
                Param.TONE_HIGH_CURVE to 0.18f,
                Param.VIGNETTE to 0.12f,
            ),
            emphasis = listOf(
                Param.SUBJECT_EXPOSURE, Param.BACKGROUND_EXPOSURE, Param.SUBJECT_SKIN_PROTECT,
                Param.SUBJECT_STRUCTURE, Param.EXPOSURE, Param.HIGHLIGHTS, Param.TEMPERATURE,
            ),
        )
    }

    private fun backlitPortrait(
        global: RegionTone,
        subject: RegionTone?,
        background: RegionTone?,
        skin: SkinReport?,
        exposure: Float,
        highlight: Float,
        shadow: Float,
        wbFix: Float,
        wbTint: Float,
    ): CorrectionStrategy {
        val skinHard = skin != null && skin.coverage > 0.02f
        val subjectSeed = if (subject != null) {
            (0.30f * (0.52f - subject.meanLuma)).coerceIn(0.02f, 0.26f)
        } else {
            0.16f
        }
        val seeds = listOf(
            mapOf(
                Param.SHADOWS to min(0.42f, shadow + 0.18f),
                Param.SUBJECT_EXPOSURE to subjectSeed + 0.06f,
                Param.BACKGROUND_EXPOSURE to (-min(0.24f, ((background?.meanLuma ?: 0.7f) - 0.55f) * 0.8f)),
                Param.HIGHLIGHTS to highlight,
                Param.WHITES to (highlight * 0.5f),
                Param.SUBJECT_SKIN_PROTECT to if (skinHard) 0.40f else 0.2f,
                Param.SUBJECT_CONTRAST to 0.08f,
                Param.TEMPERATURE to wbFix,
                Param.TINT to wbTint,
            ),
            mapOf(
                Param.EXPOSURE to max(0.06f, exposure * 0.8f),
                Param.SHADOWS to min(0.40f, shadow + 0.10f),
                Param.SUBJECT_EXPOSURE to subjectSeed,
                Param.BACKGROUND_HIGHLIGHTS to -0.20f,
                Param.SUBJECT_STRUCTURE to 0.20f,
                Param.SUBJECT_SKIN_PROTECT to if (skinHard) 0.45f else 0.2f,
                Param.TONE_SHADOW_CURVE to 0.12f,
                Param.VIBRANCE to 0.06f,
            ),
            mapOf(
                Param.HIGHLIGHTS to highlight * 1.2f,
                Param.SHADOWS to 0.30f,
                Param.SUBJECT_EXPOSURE to subjectSeed * 0.7f,
                Param.CONTRAST to 0.06f,
                Param.SUBJECT_SATURATION to 0.05f,
            ),
        )
        return CorrectionStrategy(
            label = "역광 인물 — 피사체만 밝히고 배경은 눌러서",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.45f,
                Param.CONTRAST to 0.22f,
                Param.HIGHLIGHTS to 0.45f,
                Param.SHADOWS to 0.40f,
                Param.WHITES to 0.25f,
                Param.VIBRANCE to 0.18f,
                Param.SATURATION to 0.08f,
                Param.TEMPERATURE to 0.10f,
                Param.TINT to 0.08f,
                Param.SKIN_CLEAN to 0.24f,
                Param.SUBJECT_EXPOSURE to 0.34f,
                Param.SUBJECT_CONTRAST to 0.20f,
                Param.SUBJECT_STRUCTURE to 0.24f,
                Param.SUBJECT_SKIN_PROTECT to 0.50f,
                Param.BACKGROUND_EXPOSURE to 0.30f,
                Param.BACKGROUND_HIGHLIGHTS to 0.26f,
                Param.BACKGROUND_SOFT to 0.20f,
                Param.TONE_SHADOW_CURVE to 0.20f,
                Param.TONE_MID_CURVE to 0.16f,
            ),
            emphasis = listOf(
                Param.SUBJECT_EXPOSURE, Param.BACKGROUND_EXPOSURE, Param.HIGHLIGHTS,
                Param.SHADOWS, Param.SUBJECT_SKIN_PROTECT, Param.EXPOSURE,
            ),
        )
    }

    private fun landscape(
        global: RegionTone,
        sky: RegionTone?,
        exposure: Float,
        highlight: Float,
        shadow: Float,
        wbFix: Float,
        wbTint: Float,
        issues: Set<Issue>,
    ): CorrectionStrategy {
        val skySeed = if (sky != null && sky.meanLuma > 0.62f) -0.12f else 0.04f
        val dehaze = when {
            Issue.BLOWN_SKY in issues -> 0.16f
            global.contrast < 0.45f -> 0.12f
            else -> 0.06f
        }
        val seeds = listOf(
            mapOf(
                Param.EXPOSURE to exposure * 0.5f,
                Param.CONTRAST to 0.10f,
                Param.HIGHLIGHTS to highlight * 0.8f,
                Param.SHADOWS to shadow * 0.6f,
                Param.DEHAZE to dehaze,
                Param.CLARITY to 0.12f,
                Param.VIBRANCE to 0.10f,
                Param.SKY_EXPOSURE to skySeed,
                Param.SKY_SATURATION to 0.10f,
                Param.SKY_DETAIL to 0.18f,
                Param.TEMPERATURE to wbFix,
                Param.TINT to wbTint,
            ),
            mapOf(
                Param.HIGHLIGHTS to highlight,
                Param.SKY_EXPOSURE to skySeed * 1.4f,
                Param.SKY_BLUE to 0.12f,
                Param.SKY_DETAIL to 0.24f,
                Param.CONTRAST to 0.08f,
                Param.TONE_HIGH_CURVE to -0.08f,
                Param.TONE_SHADOW_CURVE to 0.08f,
                Param.TEXTURE to 0.08f,
            ),
            mapOf(
                Param.EXPOSURE to exposure * 0.8f,
                Param.VIBRANCE to 0.08f,
                Param.CLARITY to 0.14f,
                Param.DEHAZE to dehaze * 1.2f,
                Param.SATURATION to 0.04f,
                Param.SKY_SATURATION to 0.06f,
            ),
        )
        return CorrectionStrategy(
            label = "풍경 — 하늘과 지면의 명암·색 균형",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.45f,
                Param.CONTRAST to 0.30f,
                Param.HIGHLIGHTS to 0.45f,
                Param.SHADOWS to 0.40f,
                Param.WHITES to 0.25f,
                Param.BLACKS to 0.22f,
                Param.VIBRANCE to 0.28f,
                Param.SATURATION to 0.14f,
                Param.CLARITY to 0.28f,
                Param.TEXTURE to 0.18f,
                Param.DEHAZE to 0.22f,
                Param.TEMPERATURE to 0.12f,
                Param.TINT to 0.08f,
                Param.SKY_EXPOSURE to 0.26f,
                Param.SKY_SATURATION to 0.24f,
                Param.SKY_BLUE to 0.24f,
                Param.SKY_DETAIL to 0.30f,
                Param.SKY_WARMTH to 0.16f,
                Param.TONE_HIGH_CURVE to 0.20f,
                Param.TONE_SHADOW_CURVE to 0.20f,
                Param.TONE_MID_CURVE to 0.16f,
                Param.VIGNETTE to 0.12f,
            ),
            emphasis = listOf(
                Param.HIGHLIGHTS, Param.SKY_EXPOSURE, Param.SKY_DETAIL, Param.DEHAZE,
                Param.CONTRAST, Param.VIBRANCE, Param.EXPOSURE,
            ),
        )
    }

    private fun overexposedLandscape(
        global: RegionTone,
        sky: RegionTone?,
        exposure: Float,
        wbFix: Float,
    ): CorrectionStrategy {
        val seeds = listOf(
            mapOf(
                Param.EXPOSURE to min(-0.10f, exposure),
                Param.HIGHLIGHTS to -0.40f,
                Param.WHITES to -0.22f,
                Param.SKY_EXPOSURE to -0.26f,
                Param.SKY_SATURATION to 0.14f,
                Param.SKY_DETAIL to 0.22f,
                Param.DEHAZE to 0.14f,
                Param.CONTRAST to 0.10f,
                Param.TEMPERATURE to wbFix,
            ),
            mapOf(
                Param.HIGHLIGHTS to -0.32f,
                Param.TONE_HIGH_CURVE to -0.16f,
                Param.SKY_EXPOSURE to -0.18f,
                Param.SKY_BLUE to 0.16f,
                Param.CLARITY to 0.12f,
                Param.SHADOWS to 0.10f,
            ),
            mapOf(
                Param.EXPOSURE to min(-0.06f, exposure * 0.6f),
                Param.WHITES to -0.16f,
                Param.HIGHLIGHTS to -0.45f,
                Param.SKY_DETAIL to 0.16f,
                Param.VIBRANCE to 0.06f,
            ),
        )
        return CorrectionStrategy(
            label = "과노출 풍경 — 하늘 되살리고 하이라이트 회수",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.36f,
                Param.CONTRAST to 0.26f,
                Param.HIGHLIGHTS to 0.50f,
                Param.SHADOWS to 0.28f,
                Param.WHITES to 0.34f,
                Param.BLACKS to 0.20f,
                Param.VIBRANCE to 0.22f,
                Param.SATURATION to 0.12f,
                Param.CLARITY to 0.24f,
                Param.DEHAZE to 0.24f,
                Param.SKY_EXPOSURE to 0.34f,
                Param.SKY_SATURATION to 0.24f,
                Param.SKY_BLUE to 0.22f,
                Param.SKY_DETAIL to 0.28f,
                Param.TONE_HIGH_CURVE to 0.24f,
                Param.TONE_MID_CURVE to 0.16f,
                Param.SKY_WARMTH to 0.14f,
            ),
            emphasis = listOf(
                Param.HIGHLIGHTS, Param.WHITES, Param.SKY_EXPOSURE, Param.TONE_HIGH_CURVE,
                Param.SKY_DETAIL, Param.DEHAZE,
            ),
        )
    }

    private fun night(
        global: RegionTone,
        exposure: Float,
        highlight: Float,
        shadow: Float,
        wbFix: Float,
        wbTint: Float,
    ): CorrectionStrategy {
        val shadowSafe = min(0.22f, shadow * 0.8f)
        val seeds = listOf(
            mapOf(
                Param.EXPOSURE to (0.18f * (0.38f - global.meanLuma)).coerceIn(0.02f, 0.34f),
                Param.SHADOWS to shadowSafe,
                Param.BLACKS to -0.06f,
                Param.HIGHLIGHTS to highlight,
                Param.CONTRAST to 0.10f,
                Param.VIBRANCE to -0.04f,
                Param.SATURATION to -0.04f,
                Param.CLARITY to 0.08f,
                Param.TEMPERATURE to wbFix * 0.8f,
                Param.TINT to wbTint * 0.8f,
                Param.TONE_MID_CURVE to 0.08f,
            ),
            mapOf(
                Param.EXPOSURE to 0.06f,
                Param.SHADOWS to min(0.18f, shadowSafe * 0.6f),
                Param.HIGHLIGHTS to highlight * 0.8f,
                Param.CONTRAST to 0.06f,
                Param.CLARITY to 0.06f,
                Param.TEMPERATURE to wbFix * 0.5f,
                Param.TONE_SHADOW_CURVE to -0.06f,
            ),
            mapOf(
                Param.HIGHLIGHTS to -0.28f,
                Param.WHITES to -0.12f,
                Param.EXPOSURE to 0.02f,
                Param.CONTRAST to 0.14f,
                Param.VIBRANCE to 0.04f,
            ),
        )
        return CorrectionStrategy(
            label = "야경 — 암부 노이즈와 하이라이트 제어",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.40f,
                Param.CONTRAST to 0.26f,
                Param.HIGHLIGHTS to 0.42f,
                // 야경에서 섀도우를 크게 열면 노이즈만 남는다 — 상한을 낮게 둔다.
                Param.SHADOWS to 0.24f,
                Param.WHITES to 0.20f,
                Param.BLACKS to 0.14f,
                Param.VIBRANCE to 0.14f,
                Param.SATURATION to 0.08f,
                Param.CLARITY to 0.16f,
                Param.TEXTURE to 0.10f,
                Param.TEMPERATURE to 0.12f,
                Param.TINT to 0.08f,
                Param.TONE_MID_CURVE to 0.16f,
                Param.TONE_SHADOW_CURVE to 0.12f,
                Param.TONE_HIGH_CURVE to 0.16f,
            ),
            emphasis = listOf(
                Param.EXPOSURE, Param.HIGHLIGHTS, Param.SHADOWS, Param.CONTRAST, Param.TONE_MID_CURVE,
            ),
        )
    }

    private fun indoor(
        global: RegionTone,
        exposure: Float,
        shadow: Float,
        wbFix: Float,
        wbTint: Float,
        skin: SkinReport?,
    ): CorrectionStrategy {
        val seeds = listOf(
            mapOf(
                Param.EXPOSURE to max(0.08f, exposure * 0.8f),
                Param.SHADOWS to min(0.30f, shadow * 0.7f),
                Param.HIGHLIGHTS to -0.10f,
                Param.TEMPERATURE to wbFix,
                Param.TINT to wbTint,
                Param.VIBRANCE to 0.08f,
                Param.CONTRAST to 0.04f,
                Param.SKIN_CLEAN to if (skin != null) 0.14f else 0f,
            ),
            mapOf(
                Param.EXPOSURE to 0.10f,
                Param.WHITES to -0.08f,
                Param.TEMPERATURE to wbFix * 0.6f,
                Param.CLARITY to 0.08f,
                Param.VIBRANCE to 0.10f,
                Param.TONE_MID_CURVE to 0.08f,
            ),
            mapOf(
                Param.SHADOWS to 0.20f,
                Param.BLACKS to 0.06f,
                Param.CONTRAST to 0.06f,
                Param.SATURATION to 0.04f,
                Param.TEMPERATURE to wbFix * 0.8f,
            ),
        )
        return CorrectionStrategy(
            label = "실내 — 색온도 교정과 암부 정리",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.40f,
                Param.CONTRAST to 0.22f,
                Param.HIGHLIGHTS to 0.32f,
                Param.SHADOWS to 0.34f,
                Param.WHITES to 0.22f,
                Param.BLACKS to 0.20f,
                Param.VIBRANCE to 0.22f,
                Param.SATURATION to 0.12f,
                Param.CLARITY to 0.18f,
                Param.TEXTURE to 0.12f,
                Param.TEMPERATURE to 0.16f,
                Param.TINT to 0.10f,
                Param.SKIN_CLEAN to 0.22f,
                Param.TONE_MID_CURVE to 0.16f,
                Param.TONE_SHADOW_CURVE to 0.16f,
            ),
            emphasis = listOf(
                Param.TEMPERATURE, Param.TINT, Param.EXPOSURE, Param.SHADOWS, Param.VIBRANCE,
            ),
        )
    }

    private fun food(
        global: RegionTone,
        exposure: Float,
        highlight: Float,
        wbFix: Float,
        wbTint: Float,
    ): CorrectionStrategy {
        val seeds = listOf(
            mapOf(
                Param.EXPOSURE to (exposure * 0.6f + 0.02f).coerceIn(-0.1f, 0.2f),
                Param.TEMPERATURE to (wbFix + 0.05f).coerceIn(-0.12f, 0.16f),
                Param.VIBRANCE to 0.12f,
                Param.SATURATION to 0.05f,
                Param.CLARITY to 0.10f,
                Param.TEXTURE to 0.06f,
                Param.HIGHLIGHTS to highlight * 0.6f,
                Param.TINT to wbTint,
            ),
            mapOf(
                Param.EXPOSURE to 0.04f,
                Param.CONTRAST to 0.08f,
                Param.TEXTURE to 0.10f,
                Param.CLARITY to 0.08f,
                Param.VIBRANCE to 0.08f,
                Param.TEMPERATURE to wbFix * 0.5f,
                Param.TONE_MID_CURVE to 0.06f,
                Param.VIGNETTE to 0.06f,
            ),
            mapOf(
                Param.SATURATION to 0.04f,
                Param.SHADOWS to 0.08f,
                Param.HIGHLIGHTS to -0.10f,
                Param.TEMPERATURE to 0.06f,
                Param.CLARITY to 0.12f,
            ),
        )
        return CorrectionStrategy(
            label = "음식 — 온기와 질감, 채도는 절제",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.32f,
                Param.CONTRAST to 0.22f,
                Param.HIGHLIGHTS to 0.34f,
                Param.SHADOWS to 0.24f,
                Param.VIBRANCE to 0.24f,
                Param.SATURATION to 0.14f,
                Param.CLARITY to 0.22f,
                Param.TEXTURE to 0.18f,
                Param.TEMPERATURE to 0.14f,
                Param.TINT to 0.08f,
                Param.TONE_MID_CURVE to 0.16f,
                Param.VIGNETTE to 0.10f,
            ),
            emphasis = listOf(
                Param.TEMPERATURE, Param.VIBRANCE, Param.CLARITY, Param.TEXTURE, Param.EXPOSURE,
            ),
        )
    }

    private fun flat(global: RegionTone, exposure: Float, vibrance: Float): CorrectionStrategy {
        val seeds = listOf(
            mapOf(
                Param.CONTRAST to 0.20f,
                Param.CLARITY to 0.16f,
                Param.BLACKS to -0.10f,
                Param.WHITES to 0.06f,
                Param.VIBRANCE to max(0.10f, vibrance),
                Param.TONE_MID_CURVE to 0.12f,
                Param.TONE_SHADOW_CURVE to -0.06f,
                Param.EXPOSURE to exposure * 0.5f,
            ),
            mapOf(
                Param.CONTRAST to 0.12f,
                Param.TEXTURE to 0.12f,
                Param.DEHAZE to 0.10f,
                Param.SATURATION to 0.05f,
                Param.TONE_HIGH_CURVE to 0.08f,
            ),
            mapOf(
                Param.CONTRAST to 0.26f,
                Param.CLARITY to 0.12f,
                Param.BLACKS to -0.14f,
                Param.VIBRANCE to max(0.06f, vibrance * 0.6f),
            ),
        )
        return CorrectionStrategy(
            label = "저대비 — 명암 범위 넓히기",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.30f,
                Param.CONTRAST to 0.34f,
                Param.HIGHLIGHTS to 0.28f,
                Param.SHADOWS to 0.30f,
                Param.WHITES to 0.22f,
                Param.BLACKS to 0.24f,
                Param.VIBRANCE to 0.26f,
                Param.SATURATION to 0.14f,
                Param.CLARITY to 0.26f,
                Param.TEXTURE to 0.18f,
                Param.DEHAZE to 0.18f,
                Param.TONE_MID_CURVE to 0.20f,
                Param.TONE_SHADOW_CURVE to 0.16f,
                Param.TONE_HIGH_CURVE to 0.16f,
            ),
            emphasis = listOf(
                Param.CONTRAST, Param.CLARITY, Param.TONE_MID_CURVE, Param.BLACKS, Param.VIBRANCE,
            ),
        )
    }

    private fun colorCast(global: RegionTone, wbFix: Float, wbTint: Float, exposure: Float): CorrectionStrategy {
        val seeds = listOf(
            mapOf(
                Param.TEMPERATURE to (wbFix * 0.9f).coerceIn(-0.16f, 0.16f),
                Param.TINT to (wbTint * 0.8f).coerceIn(-0.10f, 0.10f),
                Param.VIBRANCE to 0.04f,
                Param.CONTRAST to 0.04f,
                Param.EXPOSURE to exposure * 0.4f,
            ),
            mapOf(
                Param.TEMPERATURE to (wbFix * 0.6f).coerceIn(-0.16f, 0.16f),
                Param.COLOR_HARMONY to 0.14f,
                Param.SATURATION to -0.04f,
                Param.TINT to wbTint * 0.5f,
            ),
            mapOf(
                Param.TEMPERATURE to (wbFix * 1.2f).coerceIn(-0.16f, 0.16f),
                Param.WHITES to -0.06f,
                Param.HIGHLIGHTS to -0.06f,
            ),
        )
        return CorrectionStrategy(
            label = "색편향 — 화이트밸런스 교정",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.26f,
                Param.CONTRAST to 0.18f,
                Param.HIGHLIGHTS to 0.24f,
                Param.SHADOWS to 0.20f,
                Param.WHITES to 0.18f,
                Param.VIBRANCE to 0.16f,
                Param.SATURATION to 0.10f,
                Param.TEMPERATURE to 0.16f,
                Param.TINT to 0.10f,
                Param.COLOR_HARMONY to 0.20f,
            ),
            emphasis = listOf(Param.TEMPERATURE, Param.TINT, Param.COLOR_HARMONY),
        )
    }

    private fun general(
        global: RegionTone,
        exposure: Float,
        highlight: Float,
        shadow: Float,
        vibrance: Float,
        wbFix: Float,
    ): CorrectionStrategy {
        val seeds = listOf(
            mapOf(
                Param.EXPOSURE to exposure * 0.6f,
                Param.CONTRAST to 0.08f,
                Param.HIGHLIGHTS to highlight * 0.7f,
                Param.SHADOWS to shadow * 0.6f,
                Param.VIBRANCE to vibrance,
                Param.TEMPERATURE to wbFix,
                Param.CLARITY to 0.06f,
            ),
            mapOf(
                Param.EXPOSURE to exposure * 0.3f,
                Param.VIBRANCE to vibrance * 0.8f,
                Param.TONE_MID_CURVE to 0.08f,
                Param.TEXTURE to 0.06f,
                Param.HIGHLIGHTS to highlight * 0.4f,
            ),
            mapOf(
                Param.CONTRAST to 0.12f,
                Param.SHADOWS to shadow * 0.5f,
                Param.EXPOSURE to exposure * 0.4f,
                Param.SATURATION to 0.03f,
            ),
        )
        return CorrectionStrategy(
            label = "일반 — 노출·색 균형부터",
            seeds = seeds,
            limits = mapOf(
                Param.EXPOSURE to 0.42f,
                Param.CONTRAST to 0.28f,
                Param.HIGHLIGHTS to 0.40f,
                Param.SHADOWS to 0.36f,
                Param.WHITES to 0.24f,
                Param.BLACKS to 0.22f,
                Param.VIBRANCE to 0.24f,
                Param.SATURATION to 0.12f,
                Param.CLARITY to 0.20f,
                Param.TEXTURE to 0.14f,
                Param.TEMPERATURE to 0.12f,
                Param.TINT to 0.08f,
                Param.TONE_MID_CURVE to 0.14f,
            ),
            emphasis = listOf(Param.EXPOSURE, Param.CONTRAST, Param.HIGHLIGHTS, Param.SHADOWS, Param.VIBRANCE),
        )
    }

    // ── 측정값 → 양 ──────────────────────────────────────────────────────

    /** 중간 밝기 0.46을 목표로 한 EV 델타(±0.5 안). */
    fun exposureTarget(meanLuma: Float): Float {
        val mean = meanLuma.coerceIn(0.03f, 0.97f)
        val ev = (ln(0.46f / mean) / ln(2f)).toFloat()
        return (ev * 0.55f).coerceIn(-0.45f, 0.55f)
    }

    /** 화이트밸런스 슬라이더 시드. 진단이 이미 ±1 눈금으로 계산해 둔 값을 0.8배로 쓴다. */
    fun wbTemperatureSeed(wb: WhiteBalance): Float = (wb.temperatureFix * 0.8f).coerceIn(-0.16f, 0.16f)
}
