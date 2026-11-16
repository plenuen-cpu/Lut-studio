package com.plenuen.lumen.data

/**
 * Inverse "Lorenzo" predictor used by Tonefinder 2.0.1 for its built-in 17^3 LUTs.
 *
 * The blob holds, for every preset, three channel planes (plane = channel * 17^3) of residuals.
 * A residual is added to a 3D Lorenzo predictor built from already reconstructed neighbours, so
 * decoding must walk r -> g -> b in increasing order.
 *
 * Value layout of the decoded LUT (identical to the WebGL/Canvas layout used by Tonefinder):
 *      index = ((b * 17 + g) * 17 + r) * 3 + channel      (red varies fastest, then green, then blue)
 */
object LorenzoDecoder {

    const val SIZE = 17
    private const val PLANE = SIZE * SIZE * SIZE

    fun decode(residuals: ByteArray, index: Int): ByteArray {
        val lutBytes = SIZE * SIZE * SIZE * 3
        val base = index * lutBytes
        require(base >= 0 && base + lutBytes <= residuals.size) {
            "LUT index $index outside residual blob (${residuals.size} bytes)"
        }
        val out = ByteArray(lutBytes)
        for (c in 0 until 3) {
            val channelBase = base + c * PLANE
            for (b in 0 until SIZE) {
                for (g in 0 until SIZE) {
                    for (r in 0 until SIZE) {
                        val p = b * 289 + g * 17 + r
                        val res = residuals[channelBase + p].toInt() and 0xFF
                        val vr = if (r > 0) out[((b * 289 + g * 17 + (r - 1)) * 3) + c].toInt() and 0xFF else 0
                        val vg = if (g > 0) out[((b * 289 + (g - 1) * 17 + r) * 3) + c].toInt() and 0xFF else 0
                        val vb = if (b > 0) out[(((b - 1) * 289 + g * 17 + r) * 3) + c].toInt() and 0xFF else 0
                        val vrg = if (r > 0 && g > 0) {
                            out[((b * 289 + (g - 1) * 17 + (r - 1)) * 3) + c].toInt() and 0xFF
                        } else 0
                        val vrb = if (r > 0 && b > 0) {
                            out[(((b - 1) * 289 + g * 17 + (r - 1)) * 3) + c].toInt() and 0xFF
                        } else 0
                        val vgb = if (g > 0 && b > 0) {
                            out[(((b - 1) * 289 + (g - 1) * 17 + r) * 3) + c].toInt() and 0xFF
                        } else 0
                        val vrgb = if (r > 0 && g > 0 && b > 0) {
                            out[(((b - 1) * 289 + (g - 1) * 17 + (r - 1)) * 3) + c].toInt() and 0xFF
                        } else 0

                        val pred = when {
                            r > 0 && g > 0 && b > 0 -> vr + vg + vb - vrg - vrb - vgb + vrgb
                            r > 0 && g > 0 -> vr + vg - vrg
                            r > 0 && b > 0 -> vr + vb - vrb
                            g > 0 && b > 0 -> vg + vb - vgb
                            r > 0 -> vr
                            g > 0 -> vg
                            b > 0 -> vb
                            else -> 0
                        }
                        out[p * 3 + c] = ((res + pred) and 0xFF).toByte()
                    }
                }
            }
        }
        return out
    }

    /** Byte offset of one node inside a decoded LUT. */
    fun offset(r: Int, g: Int, b: Int): Int = ((b * SIZE + g) * SIZE + r) * 3
}
