package com.plenuen.lumen.core

/**
 * Slider groups in the manual panel.
 *
 * [chip] is the one-word name used by the panel's single-line category row (v2.2.5) — the long
 * [label] only fits a full-width header, which cost the photo too much vertical space.
 */
enum class ParamGroup(val label: String, val chip: String = label) {
    GEOMETRY("수평·구도", "구도"),
    TONE("기본 톤", "톤"),
    COLOR("색감"),
    DETAIL("디테일·질감", "디테일"),
    FOCUS("강조·마스크", "강조"),
    REGION("영역 보정 (인물·배경·하늘)", "영역"),
    HSL("색상별 HSL (8색)", "HSL"),
    LUT("필름 룩", "룩"),
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

    // 국소 톤 커브: 휘도 대역(섀도우·미드톤·하이라이트)별 가중치로만 밝기를 민다.
    // 기본 톤(노출·대비)이 전역인 반면, 이쪽은 이미 밝은 곳/중간/어두운 곳을 따로 만진다.
    TONE_SHADOW_CURVE("toneShadowCurve", "섀도우 톤 곡선", ParamGroup.TONE, -1f, 1f, 0f, "", 0.01f, true),
    TONE_MID_CURVE("toneMidCurve", "미드톤 톤 곡선", ParamGroup.TONE, -1f, 1f, 0f, "", 0.01f, true),
    TONE_HIGH_CURVE("toneHighCurve", "하이라이트 톤 곡선", ParamGroup.TONE, -1f, 1f, 0f, "", 0.01f, true),

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

    // ---- 영역 보정: 온디바이스 세그멘테이션 마스크로 인물·배경·하늘을 따로 조절 ----
    MASK_ON("maskOn", "영역 마스크", ParamGroup.REGION, 0f, 1f, 1f, "", 1f, false, true),
    SUBJECT_EXPOSURE("subjectExposure", "피사체 밝기", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SUBJECT_SATURATION("subjectSaturation", "피사체 채도", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SUBJECT_WARMTH("subjectWarmth", "피사체 색온도", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SUBJECT_CLARITY("subjectClarity", "피사체 선명도", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SUBJECT_CONTRAST("subjectContrast", "피사체 대비", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SUBJECT_STRUCTURE("subjectStructure", "피사체 입체감", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SUBJECT_SKIN_PROTECT("subjectSkinProtect", "피부톤 보호", ParamGroup.REGION, 0f, 1f, 0f, "", 0.01f),
    BACKGROUND_EXPOSURE("backgroundExposure", "배경 밝기", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    BACKGROUND_SATURATION("backgroundSaturation", "배경 채도", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    BACKGROUND_WARMTH("backgroundWarmth", "배경 색온도", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    BACKGROUND_CLARITY("backgroundClarity", "배경 선명도", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    BACKGROUND_HIGHLIGHTS("backgroundHighlights", "배경 하이라이트", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    BACKGROUND_SOFT("backgroundSoft", "배경 흐림", ParamGroup.REGION, 0f, 1f, 0f, "", 0.01f),
    SKY_EXPOSURE("skyExposure", "하늘 밝기", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SKY_SATURATION("skySaturation", "하늘 채도", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SKY_WARMTH("skyWarmth", "하늘 색온도", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SKY_BLUE("skyBlue", "하늘 파랑", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),
    SKY_DETAIL("skyDetail", "하늘 디테일", ParamGroup.REGION, -1f, 1f, 0f, "", 0.01f, true),

    // ---- 8색 HSL: 색 대역마다 색조·채도·밝기를 따로 ----
    RED_HUE("redHue", "빨강 색조", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    RED_SAT("redSaturation", "빨강 채도", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    RED_LUM("redLuminance", "빨강 밝기", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    ORANGE_HUE("orangeHue", "주황 색조", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    ORANGE_SAT("orangeSaturation", "주황 채도", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    ORANGE_LUM("orangeLuminance", "주황 밝기", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    YELLOW_HUE("yellowHue", "노랑 색조", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    YELLOW_SAT("yellowSaturation", "노랑 채도", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    YELLOW_LUM("yellowLuminance", "노랑 밝기", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    GREEN_HUE("greenHue", "초록 색조", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    GREEN_SAT("greenSaturation", "초록 채도", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    GREEN_LUM("greenLuminance", "초록 밝기", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    CYAN_HUE("cyanHue", "청록 색조", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    CYAN_SAT("cyanSaturation", "청록 채도", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    CYAN_LUM("cyanLuminance", "청록 밝기", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    BLUE_HUE("blueHue", "파랑 색조", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    BLUE_SAT("blueSaturation", "파랑 채도", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    BLUE_LUM("blueLuminance", "파랑 밝기", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    PURPLE_HUE("purpleHue", "보라 색조", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    PURPLE_SAT("purpleSaturation", "보라 채도", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    PURPLE_LUM("purpleLuminance", "보라 밝기", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    MAGENTA_HUE("magentaHue", "자홍 색조", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    MAGENTA_SAT("magentaSaturation", "자홍 채도", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),
    MAGENTA_LUM("magentaLuminance", "자홍 밝기", ParamGroup.HSL, -1f, 1f, 0f, "", 0.01f, true),

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
            it != LUT_INDEX && it != AUTO_STRAIGHTEN && it != MASK_ON &&
                it != SPLIT_SHADOW_HUE && it != SPLIT_HIGHLIGHT_HUE
        }

        fun groupOf(param: Param): ParamGroup = param.group
    }
}
