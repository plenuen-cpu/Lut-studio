package com.plenuen.lumen.core

import com.plenuen.lumen.data.Json

/**
 * One immutable, non-destructive edit state.
 *
 * The source photo is never modified; this recipe alone describes what the GLSL pipeline does to
 * it. Every recipe is cheap to copy, so undo/redo keeps a list of them.
 */
data class EditRecipe(private val values: Map<Param, Float> = emptyMap()) {

    operator fun get(param: Param): Float = values[param] ?: param.default

    fun with(param: Param, value: Float): EditRecipe =
        copy(values = values + (param to param.clamp(value)))

    fun withAll(changes: Map<Param, Float>): EditRecipe {
        if (changes.isEmpty()) return this
        val merged = values.toMutableMap()
        changes.forEach { (param, value) -> merged[param] = param.clamp(value) }
        return copy(values = merged)
    }

    /** Adds [delta] (already clamped amounts) to the current values. */
    fun plus(delta: Map<Param, Float>): EditRecipe {
        if (delta.isEmpty()) return this
        val merged = values.toMutableMap()
        delta.forEach { (param, amount) -> merged[param] = param.clamp(get(param) + amount) }
        return copy(values = merged)
    }

    /** Applies a delta with [strength] (1 = full, 0.5 = half, negative = undo direction). */
    fun apply(delta: RecipeDelta, strength: Float = 1f): EditRecipe {
        if (delta.ops.isEmpty() || strength == 0f) return this
        val merged = values.toMutableMap()
        delta.ops.forEach { op ->
            val current = merged[op.param] ?: op.param.default
            val next = when (op) {
                is RecipeDelta.Op.Add -> current + op.amount * strength
                is RecipeDelta.Op.Set -> current + (op.target - current) * strength
            }
            merged[op.param] = op.param.clamp(next)
        }
        return copy(values = merged)
    }

    /** True when nothing deviates from the neutral defaults (LUT selection included). */
    val isNeutral: Boolean
        get() = Param.entries.all { kotlin.math.abs(get(it) - it.default) < 1e-4f }

    /** Params that differ from neutral, for the "적용된 보정" summary. */
    fun changedParams(): List<Param> =
        Param.entries.filter { kotlin.math.abs(get(it) - it.default) >= 1e-4f }

    fun autoStraightenEnabled(): Boolean = get(Param.AUTO_STRAIGHTEN) >= 0.5f

    fun lutIndex(): Int = get(Param.LUT_INDEX).toInt()

    fun toMap(): Map<String, Float> = values.entries
        // 기본값과 같은 항목은 저장하지 않는다 (레시피 JSON을 짧게 유지).
        .filter { (param, value) -> kotlin.math.abs(value - param.default) >= 1e-4f }
        .associate { (param, value) -> param.key to value }

    fun toJson(): String {
        val map = toMap()
        val body = map.entries.joinToString(",") { (key, value) -> "\"$key\":$value" }
        return "{\"version\":1,\"params\":{$body}}"
    }

    companion object {
        val NEUTRAL = EditRecipe()

        fun of(vararg pairs: Pair<Param, Float>): EditRecipe = NEUTRAL.withAll(pairs.toMap())

        fun fromMap(map: Map<Param, Float>): EditRecipe =
            NEUTRAL.withAll(map.filterValues { it.isFinite() })

        /** Reads a recipe written by [toJson]; unknown keys and bad values are ignored. */
        fun fromJson(text: String): EditRecipe {
            val root = Json.parse(text) ?: return NEUTRAL
            val params = Json.mapOf(root, "params") ?: return NEUTRAL
            val out = mutableMapOf<Param, Float>()
            Json.asMap(params).forEach { (key, raw) ->
                val param = Param.byKey[key] ?: return@forEach
                val number = (raw as? Number)?.toFloat() ?: return@forEach
                if (number.isFinite()) out[param] = number
            }
            return fromMap(out)
        }
    }
}
