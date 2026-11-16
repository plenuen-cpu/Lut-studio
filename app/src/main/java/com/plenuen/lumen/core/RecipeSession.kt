package com.plenuen.lumen.core

/** One applied step in the edit history. */
data class EditStep(
    val recipe: EditRecipe,
    /** What the user said to get here, e.g. "부드럽게" or "자동 수평". */
    val label: String,
    val delta: RecipeDelta? = null,
    val strength: Float = 1f,
)

/**
 * Undo/redo over recipes. The original photo is never part of this stack — only how it is seen.
 */
class RecipeSession(initial: EditRecipe = EditRecipe.NEUTRAL) {

    private val steps = mutableListOf(EditStep(initial, "원본"))
    private var index = 0

    val current: EditRecipe get() = steps[index].recipe
    val currentStep: EditStep get() = steps[index]
    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < steps.size - 1
    val history: List<EditStep> get() = steps

    fun apply(label: String, delta: RecipeDelta, strength: Float = 1f): EditRecipe {
        if (delta.isEmpty) return current
        val next = current.apply(delta, strength)
        if (next == current) return current
        return push(EditStep(next, label, delta, strength))
    }

    fun replace(recipe: EditRecipe, label: String): EditRecipe {
        if (recipe == current) return current
        return push(EditStep(recipe, label))
    }

    /** Re-applies the last delta in the same direction ("더 따뜻하게"). */
    fun repeatLast(direction: Float = 1f): EditRecipe {
        val step = steps[index]
        val delta = step.delta ?: return current
        val scaled = delta.copy(
            ops = delta.ops.map { op ->
                when (op) {
                    is RecipeDelta.Op.Add -> RecipeDelta.Op.Add(op.param, op.amount * direction)
                    is RecipeDelta.Op.Set -> op
                }
            },
        )
        return apply("더 ${step.label}", scaled, 1f)
    }

    /**
     * Recomputes the last step at half strength ("절반만"). The step stays in place, so a following
     * undo still goes back to what the photo looked like before it.
     */
    fun halveLast(): EditRecipe {
        if (index == 0) return current
        val step = steps[index]
        val delta = step.delta ?: return current
        val base = steps[index - 1].recipe
        val target = base.apply(delta, step.strength * 0.5f)
        steps[index] = EditStep(target, "${step.label} 절반", delta, step.strength * 0.5f)
        return current
    }

    fun undo(): EditRecipe {
        if (canUndo) index--
        return current
    }

    fun redo(): EditRecipe {
        if (canRedo) index++
        return current
    }

    fun reset(): EditRecipe {
        val neutral = EditRecipe.NEUTRAL
        return if (current == neutral) current else push(EditStep(neutral, "초기화"))
    }

    /** Loads a photo's saved recipe at the bottom of a fresh stack. */
    fun start(recipe: EditRecipe, label: String = "불러옴") {
        steps.clear()
        steps.add(EditStep(recipe, label))
        index = 0
    }

    private fun push(step: EditStep): EditRecipe {
        if (index < steps.size - 1) steps.subList(index + 1, steps.size).clear()
        steps.add(step)
        index = steps.size - 1
        // History is bounded so a long session cannot grow without limit.
        if (steps.size > MAX_STEPS) {
            val drop = steps.size - MAX_STEPS
            repeat(drop) { steps.removeAt(0) }
            index = steps.size - 1
        }
        return current
    }

    companion object {
        const val MAX_STEPS = 200
    }
}
