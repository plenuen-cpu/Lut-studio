package com.plenuen.lumen.data

/**
 * The 57 FilmCam looks, in strip order: 46 films (Kodak 24, Fujifilm 14, Agfa 7, Ilford 1) and
 * 11 camera looks (Leica 6, Ricoh GR 5).
 */
object PresetCatalog {

    const val LUT_SIZE = 17
    const val LUT_BYTES = LUT_SIZE * LUT_SIZE * LUT_SIZE * 3
    const val PRESET_COUNT = 57

    const val BRAND_ALL = "All"
    val BRANDS = listOf("Kodak", "Fujifilm", "Agfa", "Ilford", "Leica", "Ricoh GR")
    val FILTERS = listOf(BRAND_ALL) + BRANDS

    /** Brand counts that the catalog must match exactly. */
    val EXPECTED_COUNTS = mapOf(
        "Kodak" to 24,
        "Fujifilm" to 14,
        "Agfa" to 7,
        "Ilford" to 1,
        "Leica" to 6,
        "Ricoh GR" to 5,
    )

    fun parse(catalogJson: String): List<LutPreset> {
        val root = Json.parse(catalogJson)
        val presets = Json.asList(Json.mapOf(root, "presets")).map { entry ->
            val colors = Json.mapOf(entry, "colors")
            LutPreset(
                slot = Json.asInt(Json.mapOf(entry, "slot")),
                idx = Json.asInt(Json.mapOf(entry, "idx")),
                brand = Json.asString(Json.mapOf(entry, "brand")),
                category = Json.asString(Json.mapOf(entry, "cat")),
                code = Json.asString(Json.mapOf(entry, "code")),
                name = Json.asString(Json.mapOf(entry, "name")),
                display = Json.asString(Json.mapOf(entry, "display")),
                label = Json.asString(Json.mapOf(entry, "label")),
                art = Json.asString(Json.mapOf(entry, "art")),
                bodyColor = LutPreset.parseColor(Json.asString(Json.mapOf(colors, "body"))),
                bodyColor2 = LutPreset.parseColor(Json.asString(Json.mapOf(colors, "body2"))),
                bandColor = LutPreset.parseColor(Json.asString(Json.mapOf(colors, "band"))),
                inkColor = LutPreset.parseColor(Json.asString(Json.mapOf(colors, "ink"))),
            )
        }
        require(presets.isNotEmpty()) { "empty preset catalog" }
        return presets
    }

    fun countByBrand(presets: List<LutPreset>): Map<String, Int> =
        presets.groupingBy { it.brand }.eachCount()
}
