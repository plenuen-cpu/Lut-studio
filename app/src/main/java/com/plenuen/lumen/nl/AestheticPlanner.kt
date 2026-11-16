package com.plenuen.lumen.nl

import com.plenuen.lumen.core.Param as P
import com.plenuen.lumen.core.RecipeDelta
import kotlin.math.min

/**
 * Turns a subjective request ("예쁘게", "작품처럼", "SNS 감성") into concrete amounts that depend on
 * the photo's own histogram. A dark, flat frame is lifted more than a bright, contrasty one.
 */
object AestheticPlanner {

    fun plan(profile: AestheticProfile, stats: SceneStats): RecipeDelta {
        val ops = mutableListOf<RecipeDelta.Op>()
        fun add(param: P, amount: Float) {
            if (kotlin.math.abs(amount) >= 1e-3f) ops += RecipeDelta.Op.Add(param, amount)
        }

        when (profile) {
            AestheticProfile.PRETTY -> {
                add(P.VIBRANCE, 0.14f)
                add(P.SKIN_CLEAN, 0.30f)
                add(P.CLARITY, 0.08f)
                add(P.FADE, 0.05f)
                add(P.SPLIT_TONE, 0.14f)
                add(P.SHADOWS, 0.08f)
                add(P.HIGHLIGHTS, -0.06f)
            }
            AestheticProfile.ARTWORK -> {
                add(P.COLOR_HARMONY, 0.34f)
                add(P.CONTRAST, 0.12f)
                add(P.SATURATION, -0.08f)
                add(P.CLARITY, 0.14f)
                add(P.VIGNETTE, 0.12f)
                add(P.GRAIN, 0.06f)
                add(P.BLACKS, -0.10f)
            }
            AestheticProfile.EXPENSIVE -> {
                add(P.COLOR_HARMONY, 0.30f)
                add(P.BLACKS, -0.12f)
                add(P.CONTRAST, 0.14f)
                add(P.SATURATION, -0.10f)
                add(P.VIGNETTE, 0.14f)
                add(P.SPLIT_TONE, 0.12f)
            }
            AestheticProfile.PRO -> {
                add(P.CLARITY, 0.18f)
                add(P.SHARPNESS, 0.22f)
                add(P.CONTRAST, 0.08f)
                add(P.HIGHLIGHTS, -0.12f)
                add(P.SHADOWS, 0.08f)
                add(P.COLOR_HARMONY, 0.18f)
            }
            AestheticProfile.SOCIAL -> {
                add(P.VIBRANCE, 0.22f)
                add(P.EXPOSURE, 0.08f)
                add(P.FADE, 0.06f)
                add(P.GLOW, 0.10f)
                add(P.TEMPERATURE, 0.05f)
                add(P.SPLIT_TONE, 0.12f)
            }
        }

        // 사진에 맞춘 보정 — 측정값이 없으면(0 샘플) 건너뛴다.
        if (stats.samples > 0) {
            if (stats.isDark) {
                add(P.EXPOSURE, 0.14f)
                add(P.SHADOWS, 0.14f)
                add(P.BLACKS, 0.06f)
            }
            if (stats.isBright) {
                add(P.EXPOSURE, -0.10f)
                add(P.HIGHLIGHTS, -0.12f)
                add(P.WHITES, -0.06f)
            }
            if (stats.isFlat) {
                add(P.CONTRAST, 0.12f)
                add(P.CLARITY, 0.08f)
            }
            if (stats.isLowSaturation) add(P.VIBRANCE, 0.12f)
            if (stats.isHighSaturation) add(P.VIBRANCE, -0.10f)
            if (stats.isWarm) add(P.TEMPERATURE, -0.08f)
            if (stats.isCool) add(P.TEMPERATURE, 0.08f)
            if (stats.highlightClip > 0.02f) {
                add(P.HIGHLIGHTS, -0.16f)
                add(P.WHITES, -0.08f)
            }
            if (stats.shadowClip > 0.02f) {
                add(P.SHADOWS, 0.16f)
                add(P.BLACKS, 0.10f)
            }
            if (!stats.isDetailed) add(P.TEXTURE, 0.10f)
            if (stats.detailEnergy > 0.2f) add(P.TEXTURE, -0.06f)
        }

        val summary = buildString {
            append(profile.label)
            append(" — 사진의 밝기 ")
            append(describeLuma(stats))
            append(", 채도 ")
            append(describeSaturation(stats))
            if (stats.samples > 0) {
                append(", 색온도 ")
                append(describeWarmth(stats))
            }
            append("를 읽고 보정을 정했어요.")
        }
        return RecipeDelta(label = profile.label, ops = ops, summary = summary)
    }

    private fun describeLuma(stats: SceneStats): String = when {
        stats.samples == 0 -> "측정 전"
        stats.isDark -> "어두움"
        stats.isBright -> "밝음"
        else -> "보통"
    }

    private fun describeSaturation(stats: SceneStats): String = when {
        stats.samples == 0 -> "측정 전"
        stats.isLowSaturation -> "낮음"
        stats.isHighSaturation -> "높음"
        else -> "보통"
    }

    private fun describeWarmth(stats: SceneStats): String = when {
        stats.isWarm -> "따뜻함"
        stats.isCool -> "차가움"
        else -> "중립"
    }

    /** Clamps a strength so a scene-aware plan never pushes a slider past its range by accident. */
    fun safeStrength(strength: Float): Float = min(1.6f, kotlin.math.max(0.2f, strength))
}
