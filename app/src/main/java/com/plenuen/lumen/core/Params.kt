package com.plenuen.lumen.core

/** Slider groups in the manual panel. */
enum class ParamGroup(val label: String) {
    GEOMETRY("수평·구도"),
    TONE("기본 톤"),
    COLOR("색감"),
    DETAIL("디테일·질감"),
    FOCUS("강조·마스크"),
    LUT("필름 룩"),
}

/**
 * Every non-destructive adjustment the pipeline understands.
 *
 * A recipe is a map of these to clamped floats. Nothing here touches the original pixels: the
 * source bitmap is read-only and the whole edit lives in the recipe (plus its history).
 */
enum class Param(
    val key: String,
    val label: String,
    val group: ParamGroup,
    val min: Float,
    val max: Float,
    val default: Float,
    val suffix: String = "",
    val step: Float = 0.01f,
    /** true when the neutral point sits in the middle of the range (shows a centre tick). */
    val bipolar: Boolean = false,
    /** value shown in the UI: the numeric value or an on/off word. */
    val toggle: Boolean = false,
) {
    // ---- 기하 (비파괴 회전·원근) ----
    STRAIGHTEN_DEG("straightenDeg", "수평 맞춤", ParamGroup.GEOMETRY, -15f, 15f, 0f, "°", 0.1f, true),
    VERTICAL_TILT("verticalTilt", "수직 원근", ParamGroup.GEOMETRY, -1f, 1f, 0f, "", 0.01f, true),
    AUTO_STRAIGHTEN("autoStraighten", "자동 수평·수직", ParamGroup.GEOMETRY, 0f, 1f, 1f, "", 1f, false, true),

    // ---- 기본 톤 ----
    EXPOSURE("exposure", "노출", ParamGroup.TONE, -2f, 2f, 0f, "EV", 0.05f, true),
    CONTRAST("contrast", "대비", ParamGroup.TONE, -1f, 1f, 0f, "", 0.01f, true),
    HIGHLIGHTS("highlights", "하이라이트", ParamGroup.TONE, -1f, 1f, 0f, "", 0.01f, true),
    SHADOWS("shadows", "섀도우", ParamGroup.TONE, -1f, 1f, 0f, "", 0.01f, true),
    WHITES("whites", "화이트", ParamGroup.TONE, -1f, 1f, 0f, "", 0.01f, true),
    BLACKS("blacks", "블랙", ParamGroup.TONE, -1f, 1f, 0f, "", 0.01f, true),
    FADE("fade", "페이드", ParamGroup.TONE, 0f, 1f, 0f, "", 0.01f),

    // ---- 색감 ----
    TEMPERATURE("temperature", "색온도", ParamGroup.COLOR, -1f, 1f, 0f, "", 0.01f, true),
    TINT("tint", "틴트", ParamGroup.COLOR, -1f, 1f, 0f, "", 0.01f, true),
    VIBRANCE("vibrance", "활기", ParamGroup.COLOR, -1f, 1f, 0f, "", 0.01f, true),
    SATURATION("saturation", "채도", ParamGroup.COLOR, -1f, 1f, 0f, "", 0.01f, true),
    COLOR_HARMONY("colorHarmony", "색 정돈", ParamGroup.COLOR, 0f, 1f, 0f, "", 0.01f),
    CROSS_PROCESS("crossProcess", "색 교차", ParamGroup.COLOR, -1f, 1f, 0f, "", 0.01f, true),
    SPLIT_TONE("splitTone", "색 분리", ParamGroup.COLOR, 0f, 1f, 0f, "", 0.01f),
    SPLIT_BALANCE("splitBalance", "색 분리 균형", ParamGroup.COLOR, -1f, 1f, 0f, "", 0.01f, true),
    SPLIT_SHADOW_HUE("splitShadowHue", "섀도우 색상", ParamGroup.COLOR, 0f, 360f, 200f, "°", 1f),
    SPLIT_HIGHLIGHT_HUE("splitHighlightHue", "하이라이트 색상", ParamGroup.COLOR, 0f, 360f, 35f, "°", 1f),
    SKIN_CLEAN("skinToneClean", "피부톤 정리", ParamGroup.COLOR, 0f, 1f, 0f, "", 0.01f),
    MONOCHROME("monochrome", "흑백", ParamGroup.COLOR, 0f, 1f, 0f, "", 0.01f),

    // ---- 디테일·질감 ----
    CLARITY("clarity", "명료도", ParamGroup.DETAIL, -1f, 1f, 0f, "", 0.01f, true),
    TEXTURE("texture", "텍스처", ParamGroup.DETAIL, -1f, 1f, 0f, "", 0.01f, true),
    DEHAZE("dehaze", "안개 제거", ParamGroup.DETAIL, -1f, 1f, 0f, "", 0.01f, true),
    SHARPNESS("sharpness", "샤프닝", ParamGroup.DETAIL, 0f, 1f, 0f, "", 0.01f),
    GLOW("glow", "글로우", ParamGroup.DETAIL, 0f, 1f, 0f, "", 0.01f),
    HALATION("halation", "할레이션", ParamGroup.DETAIL, 0f, 1f, 0f, "", 0.01f),
    GRAIN("grain", "그레인", ParamGroup.DETAIL, 0f, 1f, 0f, "", 0.01f),
    VIGNETTE("vignette", "비네팅", ParamGroup.DETAIL, -1f, 1f, 0f, "", 0.01f, true),

    // ---- 강조·마스크 ----
    SUBJECT_FOCUS("subjectFocus", "피사체 강조", ParamGroup.FOCUS, 0f, 1f, 0f, "", 0.01f),

    // ---- 3D LUT ----
    LUT_INDEX("lutIndex", "룩", ParamGroup.LUT, -1f, 56f, -1f, "", 1f),
    LUT_INTENSITY("lutIntensity", "룩 강도", ParamGroup.LUT, 0f, 1f, 0.85f, "", 0.01f);

    fun clamp(value: Float): Float = value.coerceIn(min, max)

    /** 0..1 position of [value] inside the slider range. */
    fun fraction(value: Float): Float = ((value - min) / (max - min)).coerceIn(0f, 1f)

    fun fromFraction(fraction: Float): Float {
        val raw = min + (max - min) * fraction.coerceIn(0f, 1f)
        return if (step >= 1f) Math.round(raw).toFloat() else Math.round(raw / step) * step
    }

    fun display(value: Float): String = when {
        toggle -> if (value >= 0.5f) "켬" else "끔"
        key == "lutIndex" -> if (value < 0f) "없음" else "${value.toInt() + 1}번"
        suffix == "°" -> "${format(value, 1)}°"
        suffix == "EV" -> (if (value > 0f) "+" else "") + format(value, 2) + " EV"
        bipolar -> (if (value > 0f) "+" else "") + format(value, 2) + suffix
        else -> format(value * 100f, 0) + "%"
    }

    private fun format(value: Float, decimals: Int): String {
        val rounded = if (decimals <= 0) Math.round(value).toString()
        else String.format(java.util.Locale.US, "%.${decimals}f", value)
        return rounded
    }

    companion object {
        val byKey: Map<String, Param> = entries.associateBy { it.key }

        /** Sliders shown in the manual panel, in display order. */
        val sliders: List<Param> = entries.filter {
            it != LUT_INDEX && it != AUTO_STRAIGHTEN && it != SPLIT_SHADOW_HUE && it != SPLIT_HIGHLIGHT_HUE
        }

        fun groupOf(param: Param): ParamGroup = param.group
    }
}
