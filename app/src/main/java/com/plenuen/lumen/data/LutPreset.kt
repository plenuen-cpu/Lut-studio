package com.plenuen.lumen.data

/**
 * One selectable look: a Tonefinder 2.0.1 built-in 3D LUT plus the art used for its film strip
 * slot (a film canister for the film brands, a brand badge for Leica and Ricoh GR).
 */
data class LutPreset(
    /** 1-based position in the strip, shown as "1  Kodak Ektar 100". */
    val slot: Int,
    /** Index of the 17^3 LUT inside the residual blob (0..56). */
    val idx: Int,
    val brand: String,
    val category: String,
    /** Tonefinder preset code, e.g. "KE1". */
    val code: String,
    /** Full preset name, e.g. "KE1 · Kodak Ektar 100". */
    val name: String,
    /** Name without the code prefix, used for the label line. */
    val display: String,
    /** Short film code printed on the canister, e.g. "E100". */
    val label: String,
    /** "canister" or "badge". */
    val art: String,
    val bodyColor: Int,
    val bodyColor2: Int,
    val bandColor: Int,
    val inkColor: Int,
) {
    val isBadge: Boolean get() = art == "badge"

    /** Text used inside a badge-style slot. */
    val badgeText: String
        get() = when (brand) {
            "Leica" -> "LEICA"
            "Ricoh GR" -> "RICOH GR"
            else -> brand.uppercase()
        }

    companion object {
        /** Parses "#RRGGBB" into an opaque ARGB int. */
        fun parseColor(hex: String, fallback: Int = 0xFF000000.toInt()): Int {
            val cleaned = hex.trim().removePrefix("#")
            if (cleaned.length != 6 && cleaned.length != 8) return fallback
            val value = cleaned.toLongOrNull(16) ?: return fallback
            return if (cleaned.length == 6) {
                (0xFF000000L or value).toInt()
            } else {
                value.toInt()
            }
        }
    }
}
