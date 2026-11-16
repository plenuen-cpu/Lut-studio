package com.plenuen.lumen.nl

import com.plenuen.lumen.core.Param as P
import com.plenuen.lumen.core.RecipeDelta
import com.plenuen.lumen.core.RecipeDelta.Op.Add
import com.plenuen.lumen.core.RecipeDelta.Op.Set

/** Scene-dependent looks: the exact amounts come from the photo's own histogram. */
enum class AestheticProfile(val label: String) {
    PRETTY("예쁘게"),
    ARTWORK("작품처럼"),
    EXPENSIVE("있어 보이게"),
    PRO("전문가처럼"),
    SOCIAL("SNS 감성"),
}

/**
 * One phrase the parser understands.
 *
 * [delta] is the look in parameter space. Aesthetic intents carry a [aesthetic] profile instead,
 * because their amounts are decided from the picture that is open.
 */
data class Intent(
    val key: String,
    val label: String,
    val phrases: List<String>,
    val delta: RecipeDelta,
    val aesthetic: AestheticProfile? = null,
    /** Film look to load from the 57-LUT library when the phrase is used. */
    val lutQuery: String? = null,
    val lutIntensity: Float? = null,
    val category: Boolean = false,
    /** Korean sentence describing the effect, shown as the applied-edit note. */
    val summary: String = "",
)

private fun d(label: String, vararg ops: RecipeDelta.Op): RecipeDelta =
    RecipeDelta(label, ops.toList())

private fun add(param: P, amount: Float) = Add(param, amount)

/** "자연스럽게" returns every look parameter to neutral, LUT selection included. */
private val NATURAL_DELTA: RecipeDelta = RecipeDelta(
    "자연스럽게",
    listOf(
        P.CONTRAST, P.SATURATION, P.VIBRANCE, P.CLARITY, P.TEXTURE, P.SHARPNESS,
        P.TEMPERATURE, P.TINT, P.DEHAZE, P.FADE, P.SPLIT_TONE, P.VIGNETTE, P.GLOW,
        P.GRAIN, P.HALATION, P.CROSS_PROCESS, P.COLOR_HARMONY, P.SKIN_CLEAN,
        P.MONOCHROME, P.SUBJECT_FOCUS, P.EXPOSURE, P.HIGHLIGHTS, P.SHADOWS, P.WHITES,
        P.BLACKS, P.SPLIT_BALANCE, P.STRAIGHTEN_DEG, P.LUT_INTENSITY,
    ).map { Set(it, it.default) } + Set(P.LUT_INDEX, -1f),
)

/**
 * The Korean vocabulary the instant parser matches against: the 11 slider categories and the 17
 * precise mappings the user asked for, plus the representative looks, the time/season, air,
 * memory, media and aesthetic groups.
 */
object IntentCatalog {

    const val NATURAL_KEY = "natural"

    val intents: List<Intent> = listOf(

        // ── 17개 정밀 매핑 ────────────────────────────────────────────────
        Intent(
            key = "soft", label = "부드럽게", category = true,
            phrases = listOf("부드럽게", "부드럽", "소프트하게", "소프트", "물러지게", "부드러운 느낌"),
            delta = d(
                "부드럽게",
                add(P.CONTRAST, -0.20f), add(P.TEXTURE, -0.32f), add(P.CLARITY, -0.14f),
                add(P.HIGHLIGHTS, -0.14f), add(P.SHADOWS, 0.06f), add(P.GLOW, 0.12f),
                add(P.SHARPNESS, -0.10f),
            ),
            summary = "대비와 텍스처를 낮추고 하이라이트를 부드럽게 눌렀어요.",
        ),
        Intent(
            key = "crisp", label = "선명하게", category = true,
            phrases = listOf("선명하게", "선명", "쨍하게", "쨍하", "또렷하게", "또렷", "샤프하게", "디테일 살"),
            delta = d(
                "선명하게",
                add(P.SHARPNESS, 0.38f), add(P.CLARITY, 0.26f), add(P.TEXTURE, 0.20f),
                add(P.DEHAZE, 0.10f), add(P.CONTRAST, 0.08f),
            ),
            summary = "샤프닝과 로컬 대비를 올려 윤곽을 세웠어요.",
        ),
        Intent(
            key = "heavy", label = "묵직하게", category = true,
            phrases = listOf("묵직하게", "묵직", "무겁게", "무거운", "진중하게", "차분하게"),
            delta = d(
                "묵직하게",
                add(P.SATURATION, -0.20f), add(P.BLACKS, -0.22f), add(P.SHADOWS, -0.12f),
                add(P.CONTRAST, 0.14f), add(P.VIGNETTE, 0.16f), add(P.EXPOSURE, -0.06f),
            ),
            summary = "채도를 낮추고 블랙을 깊게 내려 무게를 실었어요.",
        ),
        Intent(
            key = "bright", label = "화사하게", category = true,
            phrases = listOf("화사하게", "화사", "환하게", "밝게", "밝고", "화사한"),
            delta = d(
                "화사하게",
                add(P.EXPOSURE, 0.16f), add(P.SHADOWS, 0.16f), add(P.WHITES, 0.10f),
                add(P.SKIN_CLEAN, 0.25f), add(P.VIBRANCE, 0.08f), add(P.FADE, 0.04f),
            ),
            summary = "노출과 중간톤을 올리고 피부톤을 정리했어요.",
        ),
        Intent(
            key = "luxe", label = "고급스럽게", category = true,
            phrases = listOf("고급스럽게", "고급스러", "고급지게", "럭셔리", "고급"),
            delta = d(
                "고급스럽게",
                add(P.SATURATION, -0.10f), add(P.COLOR_HARMONY, 0.36f), add(P.HIGHLIGHTS, -0.16f),
                add(P.CONTRAST, 0.10f), add(P.BLACKS, -0.12f), add(P.SPLIT_TONE, 0.16f),
                Set(P.SPLIT_SHADOW_HUE, 200f), Set(P.SPLIT_HIGHLIGHT_HUE, 35f),
            ),
            summary = "채도를 절제하고 색을 정돈해 하이라이트를 차분하게 잡았어요.",
        ),
        Intent(
            key = NATURAL_KEY, label = "자연스럽게", category = true,
            phrases = listOf(
                "자연스럽게", "자연스러", "과하지 않게", "과하게 하지", "보정 티 안",
                "있는 그대로", "원래 느낌",
            ),
            delta = NATURAL_DELTA,
            summary = "과한 보정을 덜어내고 색과 톤을 원본 쪽으로 되돌렸어요.",
        ),
        Intent(
            key = "emotional", label = "감성적으로", category = true,
            phrases = listOf("감성적으로", "감성적", "감성 있게", "분위기 있게", "무드 있게", "감성"),
            delta = d(
                "감성적으로",
                add(P.TEMPERATURE, 0.06f), add(P.FADE, 0.10f), add(P.CONTRAST, -0.05f),
                add(P.SHADOWS, 0.10f), add(P.SPLIT_TONE, 0.12f), add(P.GRAIN, 0.12f),
                add(P.GLOW, 0.08f),
            ),
            summary = "색온도와 명암 곡선을 부드럽게 틀어 필름 같은 분위기를 얹었어요.",
        ),
        Intent(
            key = "fresh", label = "청량하게", category = true,
            phrases = listOf("청량하게", "청량", "시원하게", "시원한", "상쾌하게", "싱그럽게", "싱그러운"),
            delta = d(
                "청량하게",
                add(P.TEMPERATURE, -0.10f), add(P.TINT, -0.06f), add(P.VIBRANCE, 0.13f),
                add(P.HIGHLIGHTS, -0.08f), add(P.WHITES, 0.10f), add(P.SHADOWS, 0.12f),
                add(P.CLARITY, 0.08f), add(P.COLOR_HARMONY, 0.15f),
            ),
            summary = "시안·블루 쪽으로 기울이고 투명한 명암을 만들었어요.",
        ),
        Intent(
            key = "warm", label = "따스하게", category = true,
            phrases = listOf("따스하게", "따뜻하게", "따뜻", "따스", "온화하게", "포근하게", "포근"),
            delta = d(
                "따스하게",
                add(P.TEMPERATURE, 0.22f), add(P.TINT, 0.04f), add(P.SHADOWS, 0.08f),
                add(P.HIGHLIGHTS, -0.06f), add(P.SATURATION, 0.05f),
            ),
            summary = "색온도를 올리고 하이라이트를 부드럽게 감쌌어요.",
        ),
        Intent(
            key = "lonely", label = "쓸쓸하게", category = true,
            phrases = listOf("쓸쓸하게", "쓸쓸", "적적하게", "스산하게", "스산"),
            delta = d(
                "쓸쓸하게",
                add(P.SATURATION, -0.28f), add(P.TEMPERATURE, -0.14f), add(P.EXPOSURE, -0.10f),
                add(P.CONTRAST, -0.06f), add(P.FADE, 0.08f), add(P.VIGNETTE, 0.12f),
            ),
            summary = "채도를 빼고 차가운 쪽으로 기울여 명도를 낮췄어요.",
        ),
        Intent(
            key = "cinematic", label = "영화처럼", category = true,
            phrases = listOf("영화처럼", "영화 같", "영화같", "시네마틱", "영화 느낌", "시네마"),
            delta = d(
                "영화처럼",
                add(P.CONTRAST, 0.18f), add(P.SPLIT_TONE, 0.35f), Set(P.SPLIT_SHADOW_HUE, 195f),
                Set(P.SPLIT_HIGHLIGHT_HUE, 30f), add(P.SATURATION, -0.06f), add(P.FADE, 0.06f),
                add(P.GRAIN, 0.10f), add(P.HALATION, 0.12f), add(P.BLACKS, -0.10f),
                add(P.COLOR_HARMONY, 0.20f),
            ),
            summary = "섀도우와 하이라이트를 다른 색으로 갈라 컬러 그레이딩을 넣었어요.",
        ),
        Intent(
            key = "nostalgic", label = "아련하게", category = true,
            phrases = listOf("아련하게", "아련", "몽글하게", "몽환적으로", "몽환", "뿌옇게"),
            delta = d(
                "아련하게",
                add(P.FADE, 0.22f), add(P.GLOW, 0.22f), add(P.CONTRAST, -0.12f),
                add(P.TEXTURE, -0.15f), add(P.SATURATION, -0.10f), add(P.TEMPERATURE, -0.04f),
                add(P.HIGHLIGHTS, -0.10f), add(P.CLARITY, -0.08f),
            ),
            summary = "페이드와 글로우로 미세 대비를 눅여 아련한 느낌을 만들었어요.",
        ),
        Intent(
            key = "vivid", label = "생동감 있게", category = true,
            phrases = listOf("생동감 있게", "생동감", "활기 있게", "생생하게", "생생", "쌩쌩하게", "에너지 있게"),
            delta = d(
                "생동감 있게",
                add(P.VIBRANCE, 0.30f), add(P.SATURATION, 0.10f), add(P.CONTRAST, 0.12f),
                add(P.CLARITY, 0.15f), add(P.SHARPNESS, 0.12f),
            ),
            summary = "활기 채도와 국부 대비를 올려 색을 분리했어요.",
        ),
        Intent(
            key = "neat", label = "정갈하게", category = true,
            phrases = listOf("정갈하게", "정갈", "깔끔하게", "깔끔", "단정하게", "정돈"),
            delta = d(
                "정갈하게",
                add(P.COLOR_HARMONY, 0.30f), add(P.CONTRAST, 0.06f), add(P.SATURATION, -0.06f),
                add(P.HIGHLIGHTS, -0.06f), add(P.SHADOWS, 0.05f), add(P.CLARITY, 0.10f),
            ),
            summary = "색 수를 줄이고 톤을 균형 있게 정리했어요.",
        ),
        Intent(
            key = "dimensional", label = "입체적으로", category = true,
            phrases = listOf("입체적으로", "입체", "볼륨감 있게", "볼륨", "깊이감 있게", "깊이 있게"),
            delta = d(
                "입체적으로",
                add(P.CLARITY, 0.28f), add(P.CONTRAST, 0.10f), add(P.SUBJECT_FOCUS, 0.30f),
                add(P.VIGNETTE, 0.12f), add(P.SHADOWS, -0.05f), add(P.HIGHLIGHTS, -0.05f),
                add(P.TEXTURE, 0.12f),
            ),
            summary = "부분 명암과 피사체 분리를 살려 앞뒤를 나눴어요.",
        ),
        Intent(
            key = "transparent", label = "투명하게", category = true,
            phrases = listOf("투명하게", "투명", "맑게", "맑은", "깨끗하게", "깨끗"),
            delta = d(
                "투명하게",
                add(P.TINT, -0.05f), add(P.TEMPERATURE, -0.04f), add(P.DEHAZE, 0.12f),
                add(P.CONTRAST, 0.05f), add(P.HIGHLIGHTS, -0.12f), add(P.SHADOWS, 0.12f),
                add(P.VIBRANCE, 0.06f), add(P.COLOR_HARMONY, 0.08f),
            ),
            summary = "색 편향을 줄이고 중간톤을 명료하게 세웠어요.",
        ),
        Intent(
            key = "film", label = "필름처럼", category = true,
            phrases = listOf("필름처럼", "필름 같", "필름같", "필름 느낌", "필름톤", "아날로그 느낌", "아날로그"),
            delta = d(
                "필름처럼",
                add(P.CONTRAST, -0.04f), add(P.FADE, 0.10f), add(P.GRAIN, 0.20f),
                add(P.HALATION, 0.15f), add(P.CROSS_PROCESS, 0.12f), add(P.SATURATION, -0.08f),
                add(P.TEMPERATURE, 0.04f),
            ),
            lutQuery = "Portra 400",
            lutIntensity = 0.70f,
            summary = "필름 특성곡선과 색 교차, 그레인·할레이션을 함께 얹었어요.",
        ),

        // ── 대표 룩 ─────────────────────────────────────────────────────
        Intent(
            key = "refined", label = "세련되게",
            phrases = listOf("세련되게", "세련", "시크하게", "시크", "모던하게", "모던"),
            delta = d(
                "세련되게",
                add(P.CONTRAST, 0.08f), add(P.COLOR_HARMONY, 0.26f), add(P.SATURATION, -0.06f),
                add(P.CLARITY, 0.12f), add(P.HIGHLIGHTS, -0.08f), add(P.BLACKS, -0.06f),
                add(P.SPLIT_TONE, 0.10f),
            ),
            summary = "색을 정돈하고 대비를 또렷하게 잡았어요.",
        ),
        Intent(
            key = "dramatic", label = "드라마틱하게",
            phrases = listOf("드라마틱", "극적으로", "강렬하게", "강렬", "대비 강하게"),
            delta = d(
                "드라마틱하게",
                add(P.CONTRAST, 0.30f), add(P.CLARITY, 0.25f), add(P.SHADOWS, -0.20f),
                add(P.HIGHLIGHTS, -0.20f), add(P.VIGNETTE, 0.22f), add(P.SATURATION, -0.05f),
                add(P.BLACKS, -0.15f),
            ),
            summary = "명암을 크게 벌리고 주변을 눌러 보는 곳을 모았어요.",
        ),
        Intent(
            key = "editorial", label = "화보처럼",
            phrases = listOf("화보처럼", "화보", "에디토리얼", "잡지 화보"),
            delta = d(
                "화보처럼",
                add(P.CLARITY, 0.15f), add(P.CONTRAST, 0.15f), add(P.SATURATION, -0.10f),
                add(P.COLOR_HARMONY, 0.30f), add(P.SKIN_CLEAN, 0.35f), add(P.HIGHLIGHTS, -0.10f),
                add(P.SHARPNESS, 0.25f),
            ),
            summary = "피부톤을 정리하고 색을 절제해 화보 톤으로 맞췄어요.",
        ),
        Intent(
            key = "vintage", label = "빈티지하게",
            phrases = listOf("빈티지하게", "빈티지", "레트로", "복고"),
            delta = d(
                "빈티지하게",
                add(P.FADE, 0.25f), add(P.GRAIN, 0.22f), add(P.SATURATION, -0.18f),
                add(P.TEMPERATURE, 0.10f), add(P.CONTRAST, -0.10f), add(P.VIGNETTE, 0.18f),
                add(P.CROSS_PROCESS, 0.15f),
            ),
            lutQuery = "Gold 200",
            lutIntensity = 0.60f,
            summary = "바랜 듯한 색과 그레인으로 오래된 사진의 질감을 냈어요.",
        ),
        Intent(
            key = "teal_orange", label = "틸앤오렌지",
            phrases = listOf("틸앤오렌지", "틸 앤 오렌지", "청록 오렌지", "teal and orange", "오렌지 앤 틸"),
            delta = d(
                "틸앤오렌지",
                add(P.SPLIT_TONE, 0.45f), Set(P.SPLIT_SHADOW_HUE, 195f),
                Set(P.SPLIT_HIGHLIGHT_HUE, 30f), add(P.SATURATION, -0.05f),
                add(P.CONTRAST, 0.15f), add(P.COLOR_HARMONY, 0.25f), add(P.SKIN_CLEAN, 0.20f),
            ),
            summary = "섀도우는 청록, 하이라이트는 오렌지로 갈랐어요.",
        ),
        Intent(
            key = "japanese_snap", label = "일본 스냅",
            phrases = listOf("일본 스냅", "일본 감성", "일본 사진", "재팬 스냅", "일본 필름", "일본 느낌"),
            delta = d(
                "일본 스냅",
                add(P.TEMPERATURE, -0.08f), add(P.TINT, 0.02f), add(P.EXPOSURE, 0.12f),
                add(P.HIGHLIGHTS, -0.15f), add(P.SHADOWS, 0.18f), add(P.VIBRANCE, 0.10f),
                add(P.FADE, 0.06f), add(P.COLOR_HARMONY, 0.20f), add(P.SATURATION, -0.02f),
            ),
            lutQuery = "Pro 400H",
            lutIntensity = 0.65f,
            summary = "차분한 하이라이트와 옅은 청록으로 담백한 스냅 톤을 냈어요.",
        ),
        Intent(
            key = "mono", label = "흑백",
            phrases = listOf("흑백으로", "흑백", "모노크롬", "모노톤", "라이카 모노", "monochrome", "b&w", "비앤더블유"),
            delta = d(
                "흑백",
                Set(P.MONOCHROME, 1f), add(P.CONTRAST, 0.15f), add(P.CLARITY, 0.15f),
                add(P.GRAIN, 0.12f), add(P.COLOR_HARMONY, 0f),
            ),
            summary = "색을 걷어내고 명암만 남겼어요.",
        ),
        Intent(
            key = "night_neon", label = "야경·네온",
            phrases = listOf("야경", "네온", "밤 사진", "나이트", "시티팝", "밤거리"),
            delta = d(
                "야경·네온",
                add(P.EXPOSURE, 0.10f), add(P.SHADOWS, 0.25f), add(P.BLACKS, 0.05f),
                add(P.SATURATION, 0.15f), add(P.VIBRANCE, 0.25f), add(P.CONTRAST, 0.10f),
                add(P.GRAIN, 0.10f), add(P.HALATION, 0.25f), add(P.GLOW, 0.15f),
                add(P.TEMPERATURE, -0.08f), add(P.TINT, 0.05f),
            ),
            summary = "어두운 부분을 살리고 네온의 번짐을 키웠어요.",
        ),
        Intent(
            key = "portrait", label = "인물·뽀얗게",
            phrases = listOf("뽀얗게", "뽀얀", "인물 사진", "인물 보정", "얼굴 보정", "피부 보정", "셀카"),
            delta = d(
                "인물·뽀얗게",
                add(P.SKIN_CLEAN, 0.50f), add(P.EXPOSURE, 0.10f), add(P.SHADOWS, 0.12f),
                add(P.HIGHLIGHTS, -0.10f), add(P.TEXTURE, -0.20f), add(P.CLARITY, -0.05f),
                add(P.SATURATION, -0.05f), add(P.TEMPERATURE, 0.04f), add(P.GLOW, 0.08f),
            ),
            summary = "피부톤을 정리하고 텍스처를 눅여 부드럽게 살렸어요.",
        ),

        // ── 시간·계절 ───────────────────────────────────────────────────
        Intent(
            key = "dawn", label = "새벽처럼",
            phrases = listOf("새벽처럼", "새벽", "여명", "동트", "동이 트"),
            delta = d(
                "새벽처럼",
                add(P.TEMPERATURE, -0.15f), add(P.TINT, 0.05f), add(P.EXPOSURE, -0.05f),
                add(P.SATURATION, -0.10f), add(P.SPLIT_TONE, 0.25f), Set(P.SPLIT_SHADOW_HUE, 220f),
                Set(P.SPLIT_HIGHLIGHT_HUE, 320f), add(P.FADE, 0.08f), add(P.BLACKS, 0.05f),
            ),
            summary = "차가운 새벽 공기와 옅은 분홍 하이라이트를 넣었어요.",
        ),
        Intent(
            key = "dusk", label = "황혼처럼",
            phrases = listOf("황혼", "노을", "석양", "일몰", "해질녘", "저녁 노을"),
            delta = d(
                "황혼처럼",
                add(P.TEMPERATURE, 0.15f), add(P.SPLIT_TONE, 0.30f), Set(P.SPLIT_SHADOW_HUE, 210f),
                Set(P.SPLIT_HIGHLIGHT_HUE, 40f), add(P.SATURATION, 0.05f), add(P.CONTRAST, 0.10f),
                add(P.VIGNETTE, 0.10f),
            ),
            summary = "따뜻한 하이라이트와 푸른 섀도우로 노을의 명암을 나눴어요.",
        ),
        Intent(
            key = "summer", label = "여름처럼",
            phrases = listOf("여름처럼", "여름", "한여름", "여름날"),
            delta = d(
                "여름처럼",
                add(P.VIBRANCE, 0.20f), add(P.TEMPERATURE, -0.05f), add(P.HIGHLIGHTS, -0.10f),
                add(P.WHITES, 0.10f), add(P.CLARITY, 0.10f), add(P.SATURATION, 0.05f),
            ),
            summary = "활기 채도를 올리고 하이라이트를 눌러 여름 빛을 살렸어요.",
        ),
        Intent(
            key = "winter", label = "겨울처럼",
            phrases = listOf("겨울처럼", "겨울", "한겨울", "설경", "눈 오는"),
            delta = d(
                "겨울처럼",
                add(P.TEMPERATURE, -0.18f), add(P.SATURATION, -0.12f), add(P.HIGHLIGHTS, -0.05f),
                add(P.SHADOWS, 0.08f), add(P.CONTRAST, 0.05f), add(P.DEHAZE, 0.05f),
            ),
            summary = "푸른 기운을 더하고 채도를 낮춰 겨울 공기를 냈어요.",
        ),
        Intent(
            key = "autumn", label = "가을 오후",
            phrases = listOf("가을 오후", "가을처럼", "가을", "늦가을", "초가을"),
            delta = d(
                "가을 오후",
                add(P.TEMPERATURE, 0.18f), add(P.SATURATION, 0.10f), add(P.SPLIT_TONE, 0.20f),
                Set(P.SPLIT_SHADOW_HUE, 40f), Set(P.SPLIT_HIGHLIGHT_HUE, 25f),
                add(P.CONTRAST, 0.08f), add(P.VIBRANCE, 0.10f),
            ),
            summary = "따뜻한 색온도와 골드 톤으로 가을 오후를 만들었어요.",
        ),

        // ── 공간·공기 ───────────────────────────────────────────────────
        Intent(
            key = "misty", label = "안개 낀 듯",
            phrases = listOf("안개 낀", "안개낀", "안갯속", "안개처럼", "안개"),
            delta = d(
                "안개 낀 듯",
                add(P.FADE, 0.18f), add(P.CONTRAST, -0.10f), add(P.DEHAZE, -0.10f),
                add(P.GLOW, 0.15f), add(P.SATURATION, -0.05f), add(P.WHITES, 0.05f),
            ),
            summary = "검정을 들어 올리고 안개를 더해 공기를 두껍게 했어요.",
        ),
        Intent(
            key = "humid", label = "습하게",
            phrases = listOf("습하게", "습한", "장마", "비 오는 날", "비 온 뒤", "후덥"),
            delta = d(
                "습하게",
                add(P.DEHAZE, -0.15f), add(P.GLOW, 0.12f), add(P.SATURATION, -0.05f),
                add(P.TEMPERATURE, 0.05f), add(P.FADE, 0.10f), add(P.CONTRAST, -0.05f),
            ),
            summary = "대비를 눅이고 번짐을 올려 습한 공기를 만들었어요.",
        ),
        Intent(
            key = "clear_air", label = "공기가 맑게",
            phrases = listOf("공기가 맑", "공기 맑", "맑은 공기", "숨이 트이", "안개 걷"),
            delta = d(
                "공기가 맑게",
                add(P.DEHAZE, 0.30f), add(P.CLARITY, 0.15f), add(P.CONTRAST, 0.10f),
                add(P.VIBRANCE, 0.05f), add(P.TEMPERATURE, -0.03f),
            ),
            summary = "안개를 걷어내고 로컬 대비를 세워 공기를 맑게 했어요.",
        ),

        // ── 기억·향수 ───────────────────────────────────────────────────
        Intent(
            key = "old_photo", label = "옛날 사진",
            phrases = listOf("옛날 사진", "옛날 느낌", "오래된 사진", "추억", "빛바랜", "필름 한 롤"),
            delta = d(
                "옛날 사진",
                add(P.FADE, 0.30f), add(P.SATURATION, -0.30f), add(P.GRAIN, 0.25f),
                add(P.TEMPERATURE, 0.12f), add(P.CONTRAST, -0.08f), add(P.VIGNETTE, 0.20f),
                add(P.CROSS_PROCESS, 0.10f),
            ),
            summary = "바랜 색과 그레인으로 오래된 사진의 기억을 얹었어요.",
        ),
        Intent(
            key = "dream", label = "꿈속처럼",
            phrases = listOf("꿈속", "꿈같", "꿈결", "환상적", "비현실적"),
            delta = d(
                "꿈속처럼",
                add(P.GLOW, 0.35f), add(P.FADE, 0.15f), add(P.CLARITY, -0.15f),
                add(P.CONTRAST, -0.12f), add(P.TEXTURE, -0.15f), add(P.HIGHLIGHTS, -0.05f),
                add(P.SATURATION, 0.05f),
            ),
            summary = "번짐을 크게 올리고 윤곽을 풀어 몽환적으로 만들었어요.",
        ),

        // ── 매체·장르 ───────────────────────────────────────────────────
        Intent(
            key = "magazine", label = "잡지처럼",
            phrases = listOf("잡지처럼", "잡지", "매거진"),
            delta = d(
                "잡지처럼",
                add(P.CLARITY, 0.12f), add(P.CONTRAST, 0.12f), add(P.COLOR_HARMONY, 0.28f),
                add(P.SATURATION, -0.08f), add(P.SKIN_CLEAN, 0.30f), add(P.HIGHLIGHTS, -0.08f),
            ),
            summary = "색을 정돈하고 피부톤을 다듬어 지면 같은 톤을 만들었어요.",
        ),
        Intent(
            key = "commercial", label = "광고처럼",
            phrases = listOf("광고처럼", "광고", "커머셜", "제품 사진", "상품 사진"),
            delta = d(
                "광고처럼",
                add(P.EXPOSURE, 0.10f), add(P.WHITES, 0.12f), add(P.SHARPNESS, 0.30f),
                add(P.CLARITY, 0.18f), add(P.VIBRANCE, 0.15f), add(P.CONTRAST, 0.10f),
            ),
            summary = "하이라이트를 밀어 올리고 윤곽을 세워 제품을 선명하게 했어요.",
        ),
        Intent(
            key = "documentary", label = "다큐멘터리처럼",
            phrases = listOf("다큐멘터리", "다큐", "기록 사진", "리포트"),
            delta = d(
                "다큐멘터리처럼",
                add(P.SATURATION, -0.15f), add(P.CONTRAST, 0.05f), add(P.GRAIN, 0.15f),
                add(P.TEXTURE, 0.10f), add(P.HIGHLIGHTS, -0.08f),
            ),
            summary = "색을 덜어내고 질감을 남겨 기록 사진처럼 만들었어요.",
        ),

        // ── 미적 평가 (사진을 분석해 자동 복합 보정) ──────────────────────
        Intent(
            key = "pretty", label = "예쁘게",
            phrases = listOf("예쁘게", "예쁘", "이쁘게", "이쁘", "예쁜"),
            delta = RecipeDelta("예쁘게", emptyList()),
            aesthetic = AestheticProfile.PRETTY,
            summary = "사진의 밝기와 색을 분석해 어울리는 보정을 골랐어요.",
        ),
        Intent(
            key = "artwork", label = "작품처럼",
            phrases = listOf("작품처럼", "작품 같", "작품같", "작품 사진", "예술적으로", "예술"),
            delta = RecipeDelta("작품처럼", emptyList()),
            aesthetic = AestheticProfile.ARTWORK,
            summary = "색을 정돈하고 여백을 만들어 작품 같은 인상을 잡았어요.",
        ),
        Intent(
            key = "expensive", label = "있어 보이게",
            phrases = listOf("있어 보이게", "있어보이게", "비싸 보이게", "고급져 보이게", "간지"),
            delta = RecipeDelta("있어 보이게", emptyList()),
            aesthetic = AestheticProfile.EXPENSIVE,
            summary = "채도를 절제하고 명암을 깊게 해 값비싼 인상을 만들었어요.",
        ),
        Intent(
            key = "pro", label = "전문가처럼",
            phrases = listOf("전문가처럼", "전문가 같", "프로처럼", "프로 같", "전문적으로"),
            delta = RecipeDelta("전문가처럼", emptyList()),
            aesthetic = AestheticProfile.PRO,
            summary = "하이라이트를 지키고 디테일을 세워 정확한 톤으로 맞췄어요.",
        ),
        Intent(
            key = "social", label = "SNS 감성",
            phrases = listOf("sns", "인스타", "인스타그램", "감성 사진", "피드"),
            delta = RecipeDelta("SNS 감성", emptyList()),
            aesthetic = AestheticProfile.SOCIAL,
            summary = "휴대폰 화면에서 잘 보이도록 밝고 산뜻하게 조정했어요.",
        ),
    )

    val byKey: Map<String, Intent> = intents.associateBy { it.key }

    /** Every phrase with its intent, longest first so "고급스럽게" wins over "고급". */
    val phraseIndex: List<Pair<String, Intent>> =
        intents.flatMap { intent -> intent.phrases.map { it to intent } }
            .sortedByDescending { it.first.length }

    fun get(key: String): Intent? = byKey[key]
}
