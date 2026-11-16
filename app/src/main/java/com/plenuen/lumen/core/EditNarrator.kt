package com.plenuen.lumen.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the real difference between two recipes into the sentence the user reads after a request
 * ("채도를 18% 올리고, 하이라이트를 20% 낮추고, 색온도를 따뜻하게 조절했어요.").
 *
 * The text comes from the actual before/after values, not from the sentence the user typed and not
 * from a language model's guess, so it always matches what the preview is showing. Pure Kotlin, so
 * it is covered by unit tests.
 */
object EditNarrator {

    /** One clause of the sentence: "<text> <present>" in the middle, "<text> <past>" at the end. */
    data class Move(val text: String, val present: String, val past: String)

    /** Changes smaller than this are noise (a slider tap, float drift) and are not announced. */
    private const val EPSILON = 0.004f

    /** Degrees of straightening worth mentioning. */
    private const val DEGREE_EPSILON = 0.05f

    /** How many parameters the sentence names before it says "외 N가지". */
    private const val MAX_ITEMS = 4

    /**
     * Describes [after] relative to [before].
     *
     * @param lutName display name of the film look that was selected, when the step picked one.
     * @return a Korean sentence, or null when nothing that is visible changed.
     */
    fun describe(before: EditRecipe, after: EditRecipe, lutName: String? = null): String? {
        if (before == after) return null
        val lutChanged = before.lutIndex() != after.lutIndex()
        val moves = mutableListOf<Pair<Float, Move>>()

        for (param in Param.entries) {
            // The auto-straighten flag is a switch for the geometry block, not a visible amount.
            if (param == Param.AUTO_STRAIGHTEN) continue
            // A look change already implies its strength; naming both reads like a machine.
            if (param == Param.LUT_INTENSITY && lutChanged) continue
            val delta = after[param] - before[param]
            if (param == Param.LUT_INDEX) {
                if (lutChanged) moves += 10f to lutMove(after.lutIndex(), lutName)
                continue
            }
            val threshold = if (param == Param.STRAIGHTEN_DEG) DEGREE_EPSILON else EPSILON
            if (abs(delta) < threshold) continue
            val move = moveFor(param, delta) ?: continue
            moves += abs(delta) to move
        }

        if (moves.isEmpty()) return null
        val ordered = moves.sortedByDescending { it.first }.map { it.second }
        val shown = ordered.take(MAX_ITEMS)
        val rest = ordered.size - shown.size
        val sentence = buildString {
            shown.forEachIndexed { index, move ->
                append(move.text)
                append(' ')
                append(if (index == shown.lastIndex) move.past else move.present)
                if (index != shown.lastIndex) append(", ")
            }
        }
        return if (rest > 0) "$sentence (외 ${rest}가지)" else sentence
    }

    /** One parameter's amount and direction, or null for values that have no useful wording. */
    private fun moveFor(param: Param, delta: Float): Move? {
        val up = delta > 0f
        val pct = (abs(delta) * 100f).roundToInt().coerceAtLeast(1)
        return when (param) {
            Param.STRAIGHTEN_DEG -> Move(
                "수평을 ${one(abs(delta))}° ${if (up) "오른쪽으로" else "왼쪽으로"}",
                "돌리고",
                "돌렸어요",
            )
            Param.VERTICAL_TILT -> Move(
                "수직 원근을 $pct% " + if (up) "위쪽으로" else "아래쪽으로",
                "잡고",
                "잡았어요",
            )
            Param.EXPOSURE -> Move(
                "노출을 ${two(abs(delta))} EV " + if (up) "밝게" else "어둡게",
                "하고",
                "했어요",
            )
            Param.CONTRAST -> amount("대비를", pct, up, "높이고", "높였어요", "낮추고", "낮췄어요")
            Param.HIGHLIGHTS -> amount("하이라이트를", pct, up, UP_P, UP_PAST, DOWN_P, DOWN_PAST)
            Param.SHADOWS -> amount("섀도우를", pct, up, "들어올리고", "들어올렸어요", "내리고", "내렸어요")
            Param.WHITES -> amount("화이트를", pct, up, UP_P, UP_PAST, DOWN_P, DOWN_PAST)
            Param.BLACKS -> amount("블랙을", pct, up, UP_P, UP_PAST, "내리고", "내렸어요")
            Param.FADE -> amount("페이드를", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.TEMPERATURE -> Move(
                "색온도를 " + if (up) "따뜻하게($pct%p)" else "차갑게($pct%p)",
                "하고",
                "했어요",
            )
            Param.TINT -> Move(
                "틴트를 " + if (up) "마젠타 쪽으로($pct%p)" else "그린 쪽으로($pct%p)",
                "하고",
                "했어요",
            )
            Param.VIBRANCE -> amount("활기를", pct, up, UP_P, UP_PAST, DOWN_P, DOWN_PAST)
            Param.SATURATION -> amount("채도를", pct, up, UP_P, UP_PAST, DOWN_P, DOWN_PAST)
            Param.COLOR_HARMONY -> amount("색 정돈을", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.CROSS_PROCESS -> amount("색 교차를", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.SPLIT_TONE -> amount("색 분리를", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.SPLIT_BALANCE -> amount("색 분리 균형을", pct, up, UP_P, UP_PAST, "내리고", "내렸어요")
            Param.SKIN_CLEAN -> amount("피부톤 정리를", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.MONOCHROME -> if (up) {
                Move("흑백으로", "바꾸고", "바꿨어요")
            } else {
                Move("흑백을", "풀고", "풀었어요")
            }
            Param.CLARITY -> amount("명료도를", pct, up, UP_P, UP_PAST, DOWN_P, DOWN_PAST)
            Param.TEXTURE -> amount("텍스처를", pct, up, UP_P, UP_PAST, DOWN_P, DOWN_PAST)
            Param.DEHAZE -> amount("안개 제거를", pct, up, UP_P, UP_PAST, DOWN_P, DOWN_PAST)
            Param.SHARPNESS -> amount("샤프닝을", pct, up, UP_P, UP_PAST, DOWN_P, DOWN_PAST)
            Param.GLOW -> amount("글로우를", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.HALATION -> amount("할레이션을", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.GRAIN -> amount("그레인을", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.VIGNETTE -> amount("비네팅을", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.SUBJECT_FOCUS -> amount("피사체 강조를", pct, up, "넣고", "넣었어요", "빼고", "뺐어요")
            Param.LUT_INTENSITY -> amount("룩 강도를", pct, up, UP_P, UP_PAST, "내리고", "내렸어요")
            Param.LUT_INDEX, Param.AUTO_STRAIGHTEN,
            Param.SPLIT_SHADOW_HUE, Param.SPLIT_HIGHLIGHT_HUE -> null
        }
    }

    private fun lutMove(index: Int, lutName: String?): Move = if (index < 0) {
        Move("필름 룩을", "빼고", "뺐어요")
    } else {
        Move("필름 룩을 「${lutName ?: "${index + 1}번"}」로", "바꾸고", "바꿨어요")
    }

    private fun amount(
        subject: String,
        pct: Int,
        up: Boolean,
        upPresent: String,
        upPast: String,
        downPresent: String,
        downPast: String,
    ): Move = if (up) {
        Move("$subject $pct%", upPresent, upPast)
    } else {
        Move("$subject $pct%", downPresent, downPast)
    }

    private fun one(value: Float): String = String.format(java.util.Locale.US, "%.1f", value)

    private fun two(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)

    private const val UP_P = "올리고"
    private const val UP_PAST = "올렸어요"
    private const val DOWN_P = "낮추고"
    private const val DOWN_PAST = "낮췄어요"
}
