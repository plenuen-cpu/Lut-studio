package com.plenuen.lumen.engine

import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.nl.AestheticPlanner
import com.plenuen.lumen.nl.AestheticProfile
import com.plenuen.lumen.nl.SceneStats
import kotlin.math.abs

/**
 * Makes a spoken request mean something different on different photographs.
 *
 * "고급스럽게" on a backlit portrait may not mean more contrast — the face is already dark and the sky
 * is already white; it means lifting the face off the background and holding the skin tone. On a flat
 * indoor food shot the same word means warmth and mid-tone separation. And "선명하게" on a night
 * street must not sharpen the noise in the shadows.
 *
 * Two entry points:
 *  - [plan] for the aesthetic profiles ("예쁘게", "작품처럼", "SNS 감성" …), which have no amounts of
 *    their own until the picture is measured, and
 *  - [adjust] for an ordinary intent's delta ("선명하게", "따뜻하게"), which is modulated — never
 *    replaced — by what the diagnosis found.
 *
 * Every rule is a multiplier or a small addition; nothing here can turn a request into its opposite
 * except the one case where the request would damage the photo (more saturation on an already
 * blown-out frame), and even then the amount is only reduced to a third, not inverted.
 */
object ScenePlanner {

    fun plan(
        profile: AestheticProfile,
        diagnosis: PhotoDiagnosis,
        stats: SceneStats = SceneStats.UNKNOWN,
    ): RecipeDelta {
        // 사진을 읽기 전의 기본값(프로필 자체의 양)에서 시작해 진단으로 조정한다.
        val base = AestheticPlanner.plan(profile, stats)
        if (!diagnosis.measured) return base
        val adjusted = adjust(base, diagnosis)
        val extras = sceneExtras(profile, diagnosis)
        if (extras.isEmpty()) return adjusted
        val ops = adjusted.ops.toMutableList()
        val present = ops.mapNotNull { op ->
            when (op) {
                is RecipeDelta.Op.Add -> op.param
                is RecipeDelta.Op.Set -> op.param
            }
        }.toSet()
        extras.forEach { (param, amount) -> if (param !in present) ops += RecipeDelta.Op.Add(param, amount) }
        return RecipeDelta(label = base.label, ops = ops, summary = describe(profile, diagnosis, adjusted))
    }

    /** "예쁘게 — 역광 인물이라 피부톤을 지키고 피사체만 밝혔어요." */
    private fun describe(profile: AestheticProfile, diagnosis: PhotoDiagnosis, delta: RecipeDelta): String =
        "${profile.label} — ${diagnosis.scene.label}로 읽고 " +
            delta.ops.take(3).joinToString(", ") { op ->
                when (op) {
                    is RecipeDelta.Op.Add -> "${op.param.label} ${(abs(op.amount) * 100).toInt()}%"
                    is RecipeDelta.Op.Set -> "${op.param.label} 설정"
                }
            } + " 방향으로 잡았어요."

    /**
     * Modulates a delta to suit the frame.
     *
     * Only the size of a move changes, plus the small additions a scene needs (skin protection on a
     * face, sky detail under a blown sky). An unmeasured frame is returned untouched, which is what
     * keeps the unit tests and the no-mask path identical to v2.2.5 behaviour.
     */
    fun adjust(delta: RecipeDelta, diagnosis: PhotoDiagnosis): RecipeDelta {
        if (!diagnosis.measured || delta.ops.isEmpty()) return delta
        val scene = diagnosis.scene
        val issues = diagnosis.issues.toSet()
        val night = scene == SceneKind.NIGHT
        val portrait = diagnosis.hasSubject
        val flat = Issue.FLAT_CONTRAST in issues
        val oversaturated = Issue.OVERSATURATED in issues
        val clipped = diagnosis.global.highlightClip > 0.02f
        val crushed = diagnosis.global.shadowClip > 0.035f
        val warmCast = diagnosis.whiteBalance.redBias - diagnosis.whiteBalance.blueBias > 0.035f
        val coolCast = diagnosis.whiteBalance.blueBias - diagnosis.whiteBalance.redBias > 0.035f
        val harsh = diagnosis.global.contrast > 0.92f
        val dark = diagnosis.global.meanLuma < 0.34f
        val bright = diagnosis.global.meanLuma > 0.70f

        val ops = delta.ops.map { op ->
            if (op !is RecipeDelta.Op.Add) return@map op
            val param = op.param
            var amount = op.amount
            if (amount == 0f) return@map op
            val up = amount > 0f
            when (param) {
                Param.SATURATION, Param.VIBRANCE -> {
                    if (up) {
                        amount *= when {
                            oversaturated -> 0.30f
                            diagnosis.color.meanSaturation > 0.46f -> 0.65f
                            diagnosis.color.meanSaturation < 0.14f -> 1.15f
                            else -> 1f
                        }
                    } else if (diagnosis.color.meanSaturation < 0.14f) {
                        amount *= 0.4f
                    }
                }
                Param.CLARITY, Param.TEXTURE, Param.SHARPNESS -> {
                    if (up) {
                        amount *= when {
                            night && crushed -> 0.55f
                            night -> 0.75f
                            flat -> 1.15f
                            else -> 1f
                        }
                    }
                }
                Param.CONTRAST -> {
                    if (up) {
                        amount *= if (harsh) 0.45f else if (flat) 1.20f else 1f
                    } else if (flat) {
                        amount *= 0.5f
                    }
                }
                Param.HIGHLIGHTS, Param.WHITES -> {
                    if (!up && clipped) amount *= 1.35f
                    if (up && clipped) amount *= 0.4f
                    if (night && !up) amount *= 1.10f
                }
                Param.SHADOWS, Param.BLACKS -> {
                    if (up) {
                        amount *= when {
                            night -> 0.55f
                            crushed -> 0.75f
                            dark -> 1.10f
                            else -> 1f
                        }
                    } else if (crushed) {
                        amount *= 0.4f
                    }
                }
                Param.EXPOSURE -> {
                    if (up) amount *= if (bright) 0.35f else if (dark) 1.10f else 1f
                    if (!up) amount *= if (dark) 0.35f else 1f
                }
                Param.TEMPERATURE -> {
                    // 이미 따뜻한 사진을 더 따뜻하게 하면 살색이 주황으로 간다.
                    if (up && warmCast) amount *= 0.45f
                    if (!up && coolCast) amount *= 0.45f
                }
                Param.TINT -> {
                    if (up && diagnosis.whiteBalance.tintFix < -0.03f) amount *= 0.5f
                }
                Param.VIGNETTE -> {
                    if (!night && diagnosis.global.meanLuma < 0.4f && up) amount *= 0.7f
                }
                else -> Unit
            }
            if (amount != op.amount) RecipeDelta.Op.Add(param, amount) else op
        }

        val extras = LinkedHashMap<Param, Float>()
        if (portrait) {
            val skin = diagnosis.skin
            val protect = when {
                skin == null -> 0.10f
                skin.deviation > 0.16f -> 0.22f
                else -> 0.12f
            }
            extras[Param.SUBJECT_SKIN_PROTECT] = protect
            if (diagnosis.separation != null && diagnosis.separation.score < 0.5f) {
                extras[Param.SUBJECT_STRUCTURE] = 0.10f
            }
        }
        if (diagnosis.hasSky && clipped) extras[Param.SKY_DETAIL] = 0.10f
        if (scene == SceneKind.FLAT) extras[Param.TONE_MID_CURVE] = 0.08f
        if (night) extras[Param.TONE_HIGH_CURVE] = -0.06f
        val have = ops.mapNotNull { op ->
            when (op) {
                is RecipeDelta.Op.Add -> op.param
                is RecipeDelta.Op.Set -> op.param
            }
        }.toSet()
        val merged = ops.toMutableList()
        extras.forEach { (param, amount) -> if (param !in have) merged += RecipeDelta.Op.Add(param, amount) }
        return delta.copy(ops = merged)
    }

    /** Profile-specific additions the scene asks for on top of its words. */
    private fun sceneExtras(profile: AestheticProfile, diagnosis: PhotoDiagnosis): Map<Param, Float> {
        val extras = LinkedHashMap<Param, Float>()
        val portrait = diagnosis.hasSubject
        when (diagnosis.scene) {
            SceneKind.PORTRAIT, SceneKind.BACKLIT_PORTRAIT -> {
                if (profile == AestheticProfile.PRO || profile == AestheticProfile.ARTWORK) {
                    extras[Param.SUBJECT_STRUCTURE] = 0.14f
                    extras[Param.SUBJECT_CLARITY] = 0.10f
                }
                if (profile == AestheticProfile.PRETTY || profile == AestheticProfile.SOCIAL) {
                    extras[Param.SUBJECT_SKIN_PROTECT] = 0.16f
                    extras[Param.SUBJECT_EXPOSURE] = 0.06f
                }
                if (diagnosis.scene == SceneKind.BACKLIT_PORTRAIT) {
                    extras[Param.BACKGROUND_EXPOSURE] = -0.10f
                    extras[Param.SUBJECT_EXPOSURE] = 0.10f
                }
            }
            SceneKind.LANDSCAPE, SceneKind.OVEREXPOSED_LANDSCAPE -> {
                extras[Param.SKY_DETAIL] = 0.12f
                if (profile == AestheticProfile.PRO) extras[Param.DEHAZE] = 0.08f
            }
            SceneKind.NIGHT -> {
                extras[Param.TONE_HIGH_CURVE] = -0.06f
                if (profile == AestheticProfile.PRO) extras[Param.TEXTURE] = -0.04f
            }
            SceneKind.FOOD -> {
                extras[Param.TEXTURE] = 0.06f
                extras[Param.TEMPERATURE] = 0.04f
            }
            SceneKind.FLAT -> {
                extras[Param.TONE_MID_CURVE] = 0.10f
                extras[Param.BLACKS] = -0.06f
            }
            else -> Unit
        }
        if (portrait && diagnosis.skin != null && diagnosis.skin.deviation > 0.2f) {
            extras[Param.SKIN_CLEAN] = 0.12f
        }
        return extras
    }
}
