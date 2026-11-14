package com.plenuen.filmcam

import com.plenuen.filmcam.data.Json
import com.plenuen.filmcam.data.LorenzoDecoder
import com.plenuen.filmcam.data.PresetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Decodes the packaged LUT blob and compares every preset with the values extracted from the
 * Tonefinder 2.0.1 build, so the app reproduces the original colour maths bit for bit.
 */
class LorenzoDecoderTest {

    private val presets = PresetCatalog.parse(
        File("src/main/assets/luts/presets.json").readText(Charsets.UTF_8),
    )

    private val residuals: ByteArray by lazy {
        inflate(File("src/main/assets/luts/lut_residuals.bin").readBytes())
    }

    private val golden: Map<String, Any?> by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/lut_golden.json")) {
            "lut_golden.json fixture missing"
        }.readBytes().toString(Charsets.UTF_8)
        Json.asMap(Json.parse(text))
    }

    @Test
    fun residualBlobCoversAll57Luts() {
        assertEquals(57 * PresetCatalog.LUT_BYTES, residuals.size)
    }

    @Test
    fun everyDecodedLutMatchesTheTonefinderReference() {
        val goldenEntries = Json.asList(Json.mapOf(golden, "presets"))
        assertEquals(57, goldenEntries.size)
        goldenEntries.forEach { raw ->
            val entry = Json.asMap(raw)
            val idx = Json.asInt(entry["idx"])
            val expectedHash = Json.asString(entry["sha256"])
            val lut = LorenzoDecoder.decode(residuals, idx)
            assertEquals("LUT $idx length", PresetCatalog.LUT_BYTES, lut.size)
            assertEquals("LUT $idx sha256", expectedHash, sha256(lut))
            Json.asList(entry["samples"]).forEach { sampleRaw ->
                val sample = Json.asMap(sampleRaw)
                val input = Json.asList(sample["input"]).map { Json.asInt(it) }
                val output = Json.asList(sample["output"]).map { Json.asInt(it) }
                val offset = LorenzoDecoder.offset(input[0], input[1], input[2])
                assertEquals(
                    "LUT $idx node ${input[0]},${input[1]},${input[2]}",
                    output,
                    listOf(
                        lut[offset].toInt() and 0xFF,
                        lut[offset + 1].toInt() and 0xFF,
                        lut[offset + 2].toInt() and 0xFF,
                    ),
                )
            }
        }
    }

    @Test
    fun blackAndWhiteStocksAreNeutral() {
        val ilford = presets.first { it.code == "IH5" }
        val lut = LorenzoDecoder.decode(residuals, ilford.idx)
        for (v in 0..16) {
            val offset = LorenzoDecoder.offset(v, v, v)
            val r = lut[offset].toInt() and 0xFF
            val g = lut[offset + 1].toInt() and 0xFF
            val b = lut[offset + 2].toInt() and 0xFF
            assertTrue("Ilford grey $v should stay neutral: $r/$g/$b", r == g && g == b)
        }
    }

    @Test
    fun decodedLutsAreOrderedBlackToWhite() {
        presets.filter { !it.category.contains("B&W") }.forEach { preset ->
            val lut = LorenzoDecoder.decode(residuals, preset.idx)
            val black = LorenzoDecoder.offset(0, 0, 0)
            val white = LorenzoDecoder.offset(16, 16, 16)
            val blackSum = (0 until 3).sumOf { lut[black + it].toInt() and 0xFF }
            val whiteSum = (0 until 3).sumOf { lut[white + it].toInt() and 0xFF }
            assertTrue("${preset.name}: black $blackSum should be darker than white $whiteSum", blackSum < whiteSum)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun inflate(packed: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(packed)
        val out = ByteArrayOutputStream(packed.size * 5)
        val buffer = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val read = inflater.inflate(buffer)
            if (read == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(buffer, 0, read)
            }
        }
        inflater.end()
        return out.toByteArray()
    }
}
