package com.plenuen.filmcam.image

import com.plenuen.filmcam.data.LorenzoDecoder

/**
 * Applies a Tonefinder 3D LUT to ARGB pixels.
 *
 * The maths mirrors Tonefinder's `processImageBuffer` exactly: the LUT is sampled with trilinear
 * interpolation over the 0..255 domain and the result is mixed with the original pixel by
 * `mix` (the intensity slider), then truncated to an integer channel like the JavaScript original.
 */
object LutProcessor {

    fun apply(
        pixels: IntArray,
        lut: ByteArray,
        mix: Float = 1f,
        size: Int = LorenzoDecoder.SIZE,
    ): IntArray {
        val maxIdx = size - 1
        val scale = maxIdx / 255f
        val sliceStride = size * size * 3
        val rowStride = size * 3
        val m = mix.coerceIn(0f, 1f)
        val invMix = 1f - m

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (m <= 0f) continue

            val rf = r * scale
            val gf = g * scale
            val bf = b * scale
            val r0 = rf.toInt()
            val g0 = gf.toInt()
            val b0 = bf.toInt()
            val r1 = if (r0 < maxIdx) r0 + 1 else maxIdx
            val g1 = if (g0 < maxIdx) g0 + 1 else maxIdx
            val b1 = if (b0 < maxIdx) b0 + 1 else maxIdx

            val rd = rf - r0
            val gd = gf - g0
            val bd = bf - b0
            val omRd = 1f - rd
            val omGd = 1f - gd
            val omBd = 1f - bd

            val b0Off = b0 * sliceStride
            val b1Off = b1 * sliceStride
            val g0Off = g0 * rowStride
            val g1Off = g1 * rowStride
            val r0Off = r0 * 3
            val r1Off = r1 * 3

            val i000 = b0Off + g0Off + r0Off
            val i100 = b0Off + g0Off + r1Off
            val i010 = b0Off + g1Off + r0Off
            val i110 = b0Off + g1Off + r1Off
            val i001 = b1Off + g0Off + r0Off
            val i101 = b1Off + g0Off + r1Off
            val i011 = b1Off + g1Off + r0Off
            val i111 = b1Off + g1Off + r1Off

            var c00 = byte(lut, i000) * omRd + byte(lut, i100) * rd
            var c01 = byte(lut, i001) * omRd + byte(lut, i101) * rd
            var c10 = byte(lut, i010) * omRd + byte(lut, i110) * rd
            var c11 = byte(lut, i011) * omRd + byte(lut, i111) * rd
            val mappedR = (c00 * omGd + c10 * gd) * omBd + (c01 * omGd + c11 * gd) * bd

            c00 = byte(lut, i000 + 1) * omRd + byte(lut, i100 + 1) * rd
            c01 = byte(lut, i001 + 1) * omRd + byte(lut, i101 + 1) * rd
            c10 = byte(lut, i010 + 1) * omRd + byte(lut, i110 + 1) * rd
            c11 = byte(lut, i011 + 1) * omRd + byte(lut, i111 + 1) * rd
            val mappedG = (c00 * omGd + c10 * gd) * omBd + (c01 * omGd + c11 * gd) * bd

            c00 = byte(lut, i000 + 2) * omRd + byte(lut, i100 + 2) * rd
            c01 = byte(lut, i001 + 2) * omRd + byte(lut, i101 + 2) * rd
            c10 = byte(lut, i010 + 2) * omRd + byte(lut, i110 + 2) * rd
            c11 = byte(lut, i011 + 2) * omRd + byte(lut, i111 + 2) * rd
            val mappedB = (c00 * omGd + c10 * gd) * omBd + (c01 * omGd + c11 * gd) * bd

            val outR = clamp(r * invMix + mappedR * m)
            val outG = clamp(g * invMix + mappedG * m)
            val outB = clamp(b * invMix + mappedB * m)
            pixels[i] = (p and 0xFF000000.toInt()) or (outR shl 16) or (outG shl 8) or outB
        }
        return pixels
    }

    /** Trilinear sample of one 8-bit colour, for tests and previews. */
    fun sample(
        lut: ByteArray,
        r: Int,
        g: Int,
        b: Int,
        size: Int = LorenzoDecoder.SIZE,
    ): IntArray {
        val maxIdx = size - 1
        val scale = maxIdx / 255f
        val sliceStride = size * size * 3
        val rowStride = size * 3

        val rf = r.coerceIn(0, 255) * scale
        val gf = g.coerceIn(0, 255) * scale
        val bf = b.coerceIn(0, 255) * scale
        val r0 = rf.toInt()
        val g0 = gf.toInt()
        val b0 = bf.toInt()
        val r1 = if (r0 < maxIdx) r0 + 1 else maxIdx
        val g1 = if (g0 < maxIdx) g0 + 1 else maxIdx
        val b1 = if (b0 < maxIdx) b0 + 1 else maxIdx
        val rd = rf - r0
        val gd = gf - g0
        val bd = bf - b0
        val omRd = 1f - rd
        val omGd = 1f - gd
        val omBd = 1f - bd

        val i000 = b0 * sliceStride + g0 * rowStride + r0 * 3
        val i100 = b0 * sliceStride + g0 * rowStride + r1 * 3
        val i010 = b0 * sliceStride + g1 * rowStride + r0 * 3
        val i110 = b0 * sliceStride + g1 * rowStride + r1 * 3
        val i001 = b1 * sliceStride + g0 * rowStride + r0 * 3
        val i101 = b1 * sliceStride + g0 * rowStride + r1 * 3
        val i011 = b1 * sliceStride + g1 * rowStride + r0 * 3
        val i111 = b1 * sliceStride + g1 * rowStride + r1 * 3

        val out = IntArray(3)
        for (ch in 0 until 3) {
            val c00 = byte(lut, i000 + ch) * omRd + byte(lut, i100 + ch) * rd
            val c01 = byte(lut, i001 + ch) * omRd + byte(lut, i101 + ch) * rd
            val c10 = byte(lut, i010 + ch) * omRd + byte(lut, i110 + ch) * rd
            val c11 = byte(lut, i011 + ch) * omRd + byte(lut, i111 + ch) * rd
            val mapped = (c00 * omGd + c10 * gd) * omBd + (c01 * omGd + c11 * gd) * bd
            out[ch] = clamp(mapped)
        }
        return out
    }

    private fun byte(lut: ByteArray, index: Int): Int = lut[index].toInt() and 0xFF

    private fun clamp(value: Float): Int = when {
        value <= 0f -> 0
        value >= 255f -> 255
        else -> value.toInt()
    }
}
