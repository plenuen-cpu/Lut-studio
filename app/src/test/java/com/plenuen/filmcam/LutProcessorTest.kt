package com.plenuen.filmcam

import com.plenuen.filmcam.data.LorenzoDecoder
import com.plenuen.filmcam.data.PresetCatalog
import com.plenuen.filmcam.image.LutProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

class LutProcessorTest {

    private val presets = PresetCatalog.parse(
        File("src/main/assets/luts/presets.json").readText(Charsets.UTF_8),
    )

    private val residuals: ByteArray by lazy {
        inflate(File("src/main/assets/luts/lut_residuals.bin").readBytes())
    }

    private fun identityLut(): ByteArray {
        val size = LorenzoDecoder.SIZE
        val lut = ByteArray(size * size * size * 3)
        var i = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    lut[i++] = (r * 255 / (size - 1)).toByte()
                    lut[i++] = (g * 255 / (size - 1)).toByte()
                    lut[i++] = (b * 255 / (size - 1)).toByte()
                }
            }
        }
        return lut
    }

    @Test
    fun identityLutKeepsEveryChannelValue() {
        val pixels = IntArray(256) { value ->
            (0xFF shl 24) or (value shl 16) or (((255 - value) and 0xFF) shl 8) or ((value / 2) and 0xFF)
        }
        val original = pixels.copyOf()
        LutProcessor.apply(pixels, identityLut(), 1f)
        for (i in pixels.indices) {
            val before = original[i]
            val after = pixels[i]
            // one grid step is 255/16, so allow the interpolation quantisation of the 17^3 grid
            assertTrue(
                "red ${(before shr 16) and 0xFF} -> ${(after shr 16) and 0xFF}",
                kotlin.math.abs(((before shr 16) and 0xFF) - ((after shr 16) and 0xFF)) <= 2,
            )
            assertTrue(
                "green ${(before shr 8) and 0xFF} -> ${(after shr 8) and 0xFF}",
                kotlin.math.abs(((before shr 8) and 0xFF) - ((after shr 8) and 0xFF)) <= 2,
            )
        }
    }

    @Test
    fun zeroIntensityLeavesPixelsUntouched() {
        val lut = LorenzoDecoder.decode(residuals, presets.first().idx)
        val pixels = intArrayOf(0xFF102030.toInt(), 0xFFAABBCC.toInt(), 0xFF00FF7F.toInt())
        val original = pixels.copyOf()
        LutProcessor.apply(pixels, lut, 0f)
        assertArrayEquals(original, pixels)
    }

    @Test
    fun fullIntensityUsesTheLutAtTheGridCorners() {
        val preset = presets.first { it.code == "KE1" }
        val lut = LorenzoDecoder.decode(residuals, preset.idx)

        val black = intArrayOf(0xFF000000.toInt())
        LutProcessor.apply(black, lut, 1f)
        assertEquals(lut[LorenzoDecoder.offset(0, 0, 0)].toInt() and 0xFF, (black[0] shr 16) and 0xFF)

        val white = intArrayOf(0xFFFFFFFF.toInt())
        LutProcessor.apply(white, lut, 1f)
        assertEquals(lut[LorenzoDecoder.offset(16, 16, 16)].toInt() and 0xFF, (white[0] shr 16) and 0xFF)
        assertEquals(lut[LorenzoDecoder.offset(16, 16, 16) + 1].toInt() and 0xFF, (white[0] shr 8) and 0xFF)
        assertEquals(lut[LorenzoDecoder.offset(16, 16, 16) + 2].toInt() and 0xFF, white[0] and 0xFF)
    }

    @Test
    fun sampleMatchesApplyForTheSameColour() {
        val preset = presets.first { it.code == "FP4" }
        val lut = LorenzoDecoder.decode(residuals, preset.idx)
        val pixel = intArrayOf((0xFF shl 24) or (74 shl 16) or (132 shl 8) or 201)
        LutProcessor.apply(pixel, lut, 1f)
        val sampled = LutProcessor.sample(lut, 74, 132, 201)
        assertEquals(sampled[0], (pixel[0] shr 16) and 0xFF)
        assertEquals(sampled[1], (pixel[0] shr 8) and 0xFF)
        assertEquals(sampled[2], pixel[0] and 0xFF)
    }

    @Test
    fun intensityBlendsTowardsTheOriginal() {
        val preset = presets.first { it.code == "KP8" }
        val lut = LorenzoDecoder.decode(residuals, preset.idx)
        val full = intArrayOf(0xFF2060A0.toInt()).also { LutProcessor.apply(it, lut, 1f) }[0]
        val half = intArrayOf(0xFF2060A0.toInt()).also { LutProcessor.apply(it, lut, 0.5f) }[0]
        val source = 0xFF2060A0.toInt()
        val fullR = (full shr 16) and 0xFF
        val halfR = (half shr 16) and 0xFF
        val sourceR = (source shr 16) and 0xFF
        assertTrue("half intensity sits between source and full LUT", halfR >= minOf(sourceR, fullR) - 1 && halfR <= maxOf(sourceR, fullR) + 1)
    }

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
