package com.plenuen.filmcam

import com.plenuen.filmcam.data.PresetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PresetCatalogTest {

    private val presets = PresetCatalog.parse(
        File("src/main/assets/luts/presets.json").readText(Charsets.UTF_8),
    )

    @Test
    fun catalogHasExactly57Presets() {
        assertEquals(57, presets.size)
        assertEquals(1, presets.first().slot)
        assertEquals(57, presets.last().slot)
    }

    @Test
    fun brandCountsMatchTheBrief() {
        val counts = PresetCatalog.countByBrand(presets)
        assertEquals(PresetCatalog.EXPECTED_COUNTS, counts)
        assertEquals(24, counts["Kodak"])
        assertEquals(14, counts["Fujifilm"])
        assertEquals(7, counts["Agfa"])
        assertEquals(1, counts["Ilford"])
        assertEquals(6, counts["Leica"])
        assertEquals(5, counts["Ricoh GR"])
        assertEquals(46, counts["Kodak"]!! + counts["Fujifilm"]!! + counts["Agfa"]!! + counts["Ilford"]!!)
        assertEquals(11, counts["Leica"]!! + counts["Ricoh GR"]!!)
    }

    @Test
    fun excludedBanksAreNotPresent() {
        val brands = presets.map { it.brand }.toSet()
        assertEquals(PresetCatalog.BRANDS.toSet(), brands)
        listOf("VSCO Classic", "VSCO Landscape", "VSCO B&W", "VSCO Editorial", "VSCO Tools",
            "Honor", "Xiaomi", "Vivo", "OnePlus", "Huawei & Nothing").forEach { excluded ->
            assertTrue("$excluded must not be in the catalog", excluded !in brands)
        }
    }

    @Test
    fun lutIndicesAreUniqueAndContiguous() {
        val indices = presets.map { it.idx }.sorted()
        assertEquals((0 until 57).toList(), indices)
    }

    @Test
    fun everyPresetCarriesArtAndLabels() {
        presets.forEach { preset ->
            assertTrue("display name", preset.display.isNotBlank())
            assertTrue("label", preset.label.isNotBlank())
            assertTrue("code", preset.code.isNotBlank())
            assertTrue("art kind", preset.art == "canister" || preset.art == "badge")
            if (preset.isBadge) {
                assertEquals(
                    "badge art belongs to camera looks",
                    true,
                    preset.brand == "Leica" || preset.brand == "Ricoh GR",
                )
            } else {
                assertTrue("canister art belongs to film brands", preset.brand != "Leica")
            }
        }
    }

    @Test
    fun knownFilmsAreMappedToTheExpectedSlots() {
        fun slotOf(code: String): Int = presets.first { it.code == code }.slot
        assertEquals(1, slotOf("KA1"))
        assertEquals(6, slotOf("KE1"))
        assertEquals(30, slotOf("FP4"))
        assertEquals(46, slotOf("IH5"))
        assertEquals(47, slotOf("L1"))
        assertEquals(53, slotOf("GR1"))
        assertEquals("Ektar 100", presets.first { it.code == "KE1" }.display.substringAfter("Kodak "))
        assertEquals("400H", presets.first { it.code == "FP4" }.label)
    }
}
