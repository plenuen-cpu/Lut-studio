package com.plenuen.lumen.core

/**
 * A description of change, not a state.
 *
 * The natural language parser and the local LLM both produce deltas: "따뜻하게" adds to the colour
 * temperature, "필름처럼" moves several parameters toward film values. Applying a delta at a given
 * strength lets the same intent work at "살짝" and at "확".
 */
data class RecipeDelta(
    /** Korean label shown as a chip, e.g. "부드럽게". */
    val label: String,
    val ops: List<Op>,
    /** Short Korean sentence describing what the delta does. */
    val summary: String = "",
    /** Resting spot the user can fine-tune afterwards; null keeps the recipe as is. */
    val look: String? = null,
) {
    sealed interface Op {
        val param: Param

        /** Relative change, in the param's own units. */
        data class Add(override val param: Param, val amount: Float) : Op

        /** Absolute target; the applied value is blended toward it by the delta's strength. */
        data class Set(override val param: Param, val target: Float) : Op
    }

    val isEmpty: Boolean get() = ops.isEmpty()

    /** Scales relative changes and blends targets toward the current value. */
    fun scaled(strength: Float): RecipeDelta = copy(
        ops = ops.map { op ->
            when (op) {
                is Op.Add -> Op.Add(op.param, op.amount * strength)
                is Op.Set -> Op.Set(op.param, op.target)
            }
        },
    )

    /** The inverse of this delta, used by "취소" style follow-ups. */
    fun negated(): RecipeDelta = copy(
        label = "$label 취소",
        ops = ops.mapNotNull { op ->
            when (op) {
                is Op.Add -> Op.Add(op.param, -op.amount)
                // A Set op has no inverse without the previous state; the history stack handles it.
                is Op.Set -> null
            }
        },
    )

    fun mergedWith(other: RecipeDelta): RecipeDelta = RecipeDelta(
        label = if (label == other.label) label else "$label · ${other.label}",
        ops = ops + other.ops,
        summary = listOf(summary, other.summary).filter { it.isNotBlank() }.joinToString(" "),
        look = other.look ?: look,
    )

    companion object {
        fun add(label: String, param: Param, amount: Float, summary: String = ""): RecipeDelta =
            RecipeDelta(label, listOf(Op.Add(param, amount)), summary)

        fun set(label: String, param: Param, target: Float, summary: String = ""): RecipeDelta =
            RecipeDelta(label, listOf(Op.Set(param, target)), summary)

        fun of(label: String, vararg ops: Op): RecipeDelta = RecipeDelta(label, ops.toList())
    }
}
