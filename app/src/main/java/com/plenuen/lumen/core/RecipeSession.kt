package com.plenuen.lumen.core

/**
 * One layer of the edit history.
 *
 * The photo itself is the bottom line; every step above it keeps the change *it* made, not just the
 * picture that came out of it. A spoken request keeps its delta ("따뜻하게" = +0.25 colour
 * temperature), a slider move or a look keeps the absolute targets it set. That is what lets a step
 * in the middle be lifted out: the steps above and below it still add up, because each one can be
 * replayed on whatever the layers below it now produce.
 *
 * [recipe] is the fold of every step up to and including this one, kept for display and saving.
 */
data class EditStep(
    val id: Long,
    /** What the user said or did to get here, e.g. "부드럽게" or "✨ 자동". */
    val title: String,
    val recipe: EditRecipe,
    /** Relative change for a spoken/derived request; null when the step set absolute values. */
    val delta: RecipeDelta? = null,
    val strength: Float = 1f,
    /** Absolute values this layer set (only for steps without a delta). */
    val targets: Map<Param, Float> = emptyMap(),
    /** The bottom line ("원본", "불러옴"). It stays; everything above it can be removed. */
    val isOrigin: Boolean = false,
) {
    /** Kept for call sites that still speak of a chip's label. */
    val label: String get() = title
}

/**
 * The recipe as an ordered stack of removable layers.
 *
 * Replaces the older linear snapshot stack: instead of "recipe n" it stores "what step n changed",
 * so [remove] can take one step out and recompute the rest from [EditRecipe.NEUTRAL] (or from the
 * loaded recipe) without touching any other step. Undo/redo snapshots the whole layer list, which is
 * what makes a wrong deletion recoverable.
 */
class RecipeSession(initial: EditRecipe = EditRecipe.NEUTRAL) {

    /** One applied change; [delta] and [targets] are mutually exclusive. */
    private data class Layer(
        val id: Long,
        val title: String,
        val delta: RecipeDelta?,
        val strength: Float,
        val targets: Map<Param, Float>,
    )

    private data class Snapshot(
        val origin: EditRecipe,
        val originTitle: String,
        val layers: List<Layer>,
    )

    private var origin: EditRecipe = initial
    private var originTitle: String = "원본"
    private var layers: MutableList<Layer> = mutableListOf()
    private var nextId: Long = 1L
    private var originId: Long = 0L

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private var folded: EditRecipe = initial

    val current: EditRecipe get() = folded

    /** The top-most step (the last thing that happened). */
    val currentStep: EditStep get() = steps().last()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Every step from the bottom line to the top; [EditStep.isOrigin] marks the bottom one. */
    val history: List<EditStep> get() = steps()

    /** Titles in order, for older call sites. */
    val titleList: List<String> get() = steps().map { it.title }

    fun steps(): List<EditStep> {
        val out = ArrayList<EditStep>(layers.size + 1)
        var recipe = origin
        out += EditStep(originId, originTitle, recipe, isOrigin = true)
        for (layer in layers) {
            recipe = if (layer.delta != null) {
                recipe.apply(layer.delta, layer.strength)
            } else {
                recipe.withAll(layer.targets)
            }
            out += EditStep(layer.id, layer.title, recipe, layer.delta, layer.strength, layer.targets)
        }
        return out
    }

    // ── 보정 추가 ──────────────────────────────────────────────────────────

    fun apply(label: String, delta: RecipeDelta, strength: Float = 1f): EditRecipe {
        if (delta.isEmpty) return current
        val candidate = current.apply(delta, strength)
        if (candidate == current) return current
        commit(Layer(nextId++, label, delta, strength, emptyMap()))
        return current
    }

    /** Adds a step that sets absolute values (a look, a slider move, an automatic result). */
    fun replace(recipe: EditRecipe, label: String): EditRecipe {
        if (recipe == current) return current
        val targets = diff(current, recipe)
        if (targets.isEmpty()) return current
        commit(Layer(nextId++, label, null, 1f, targets))
        return current
    }

    /** Re-applies the top step in the same direction ("더 따뜻하게"). */
    fun repeatLast(direction: Float = 1f): EditRecipe {
        val layer = layers.lastOrNull() ?: return current
        return if (layer.delta != null) {
            apply("더 ${layer.title}", scaleDelta(layer.delta, direction), 1f)
        } else {
            val below = foldBelow()
            val ops = layer.targets.mapNotNull { (param, target) ->
                val amount = (target - below[param]) * direction
                if (kotlin.math.abs(amount) < 1e-4f) null else RecipeDelta.Op.Add(param, amount)
            }
            if (ops.isEmpty()) current
            else apply("더 ${layer.title}", RecipeDelta("더 ${layer.title}", ops), 1f)
        }
    }

    /**
     * Recomputes the top step at half strength. The step stays in place, so undoing afterwards still
     * goes back to what the photo looked like before it.
     */
    fun halveLast(): EditRecipe {
        val layer = layers.lastOrNull() ?: return current
        pushUndo()
        val index = layers.lastIndex
        layers[index] = if (layer.delta != null) {
            layer.copy(strength = layer.strength * 0.5f)
        } else {
            val below = foldUpTo(index)
            val half = layer.targets.mapValues { (param, target) ->
                below[param] + (target - below[param]) * 0.5f
            }
            layer.copy(targets = half)
        }
        refold()
        return current
    }

    // ── 개별 스텝 삭제 ──────────────────────────────────────────────────────

    /**
     * Lifts one step out of the stack and re-adds everything else from the bottom line.
     *
     * "따뜻하게" as the second of three steps disappears while the first and third stay exactly as
     * they were. The removed step is remembered by undo, so a mistaken tap can be taken back.
     */
    fun remove(stepId: Long): EditRecipe {
        val index = layers.indexOfFirst { it.id == stepId }
        if (index < 0) return current
        pushUndo()
        layers.removeAt(index)
        refold()
        return current
    }

    fun canRemove(stepId: Long): Boolean = layers.any { it.id == stepId }

    // ── 되돌리기 ────────────────────────────────────────────────────────────

    fun undo(): EditRecipe {
        val previous = undoStack.removeLastOrNull() ?: return current
        redoStack.addLast(snapshot())
        restore(previous)
        return current
    }

    fun redo(): EditRecipe {
        val next = redoStack.removeLastOrNull() ?: return current
        undoStack.addLast(snapshot())
        restore(next)
        return current
    }

    fun reset(): EditRecipe {
        if (current.isNeutral && layers.isEmpty()) return current
        pushUndo()
        origin = EditRecipe.NEUTRAL
        originTitle = "원본"
        layers.clear()
        refold()
        return current
    }

    /** Loads a photo's saved recipe as the bottom line of a fresh stack. */
    fun start(recipe: EditRecipe, label: String = "불러옴") {
        origin = recipe
        originTitle = label
        layers.clear()
        undoStack.clear()
        redoStack.clear()
        originId = nextId++
        refold()
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    private fun commit(layer: Layer) {
        pushUndo()
        layers.add(layer)
        if (layers.size > MAX_STEPS) {
            repeat(layers.size - MAX_STEPS) { layers.removeAt(0) }
        }
        refold()
    }

    private fun pushUndo() {
        undoStack.addLast(snapshot())
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun snapshot() = Snapshot(origin, originTitle, layers.toList())

    private fun restore(snapshot: Snapshot) {
        origin = snapshot.origin
        originTitle = snapshot.originTitle
        layers = snapshot.layers.toMutableList()
        refold()
    }

    private fun refold() {
        folded = foldUpTo(layers.size)
    }

    /** Folds the bottom line and the first [count] layers. */
    private fun foldUpTo(count: Int): EditRecipe {
        var recipe = origin
        for (index in 0 until count) {
            val layer = layers[index]
            recipe = if (layer.delta != null) {
                recipe.apply(layer.delta, layer.strength)
            } else {
                recipe.withAll(layer.targets)
            }
        }
        return recipe
    }

    private fun foldBelow(): EditRecipe = foldUpTo(layers.lastIndex.coerceAtLeast(0))

    private fun diff(from: EditRecipe, to: EditRecipe): Map<Param, Float> {
        val out = mutableMapOf<Param, Float>()
        for (param in Param.entries) {
            val value = to[param]
            if (kotlin.math.abs(value - from[param]) >= 1e-4f) out[param] = value
        }
        return out
    }

    private fun scaleDelta(delta: RecipeDelta, factor: Float): RecipeDelta = delta.copy(
        ops = delta.ops.map { op ->
            when (op) {
                is RecipeDelta.Op.Add -> RecipeDelta.Op.Add(op.param, op.amount * factor)
                is RecipeDelta.Op.Set -> op
            }
        },
    )

    companion object {
        const val MAX_STEPS = 200
        const val MAX_UNDO = 100
    }
}
