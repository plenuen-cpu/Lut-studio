package com.plenuen.lumen.geom

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Non-destructive straighten geometry, shared by the preview and the full-resolution export.
 *
 * Everything works in *image space*: x to the right, y down, the image centred on the origin with
 * height 1 and width [aspect]. A pixel of the output frame is mapped to the source by
 *
 *     source = R(straighten) * frame / cropScale
 *
 * so a photo whose content is tilted by +t comes out level when the recipe holds t. Because the
 * frame is magnified by [cropScale] the corners never leave the source, so no black border appears.
 * The GLSL shader repeats these exact lines (`ShaderSource.GEOMETRY`), and
 * `GeometryMathTest` renders a synthetic tilted frame through this function to prove it.
 */
object GeometryMath {

    const val MAX_STRAIGHTEN_DEG = 15f

    /**
     * Smallest magnification that keeps all four corners of the output frame inside the source
     * after rotating by [straightenDeg].
     */
    fun cropScale(straightenDeg: Float, aspect: Float): Float {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val rad = Math.toRadians(straightenDeg.toDouble().coerceIn(-MAX_STRAIGHTEN_DEG.toDouble(), MAX_STRAIGHTEN_DEG.toDouble()))
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val forWidth = c + s / a
        val forHeight = c + a * s
        return maxOf(1f, forWidth, forHeight)
    }

    /**
     * Maps an output uv (0..1, y down) to the source uv for the given recipe geometry.
     *
     * [verticalTilt] corrects converging verticals: the top or bottom of the frame widens.
     */
    fun sourceUv(
        u: Float,
        v: Float,
        straightenDeg: Float,
        cropScale: Float,
        aspect: Float,
        verticalTilt: Float = 0f,
    ): FloatArray {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val scale = if (cropScale.isFinite() && cropScale > 0f) cropScale else 1f
        // frame coordinates, y down, centred
        var x = (u - 0.5f) * a
        var y = v - 0.5f
        // scale first: magnifying the frame shrinks the sampled region
        x /= scale
        y /= scale
        // rotate the sampling position (positive = corrects a clockwise tilt)
        val rad = Math.toRadians(straightenDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val rx = c * x - s * y
        val ry = s * x + c * y
        // vertical keystone: widen one end of the frame
        val tilt = verticalTilt.coerceIn(-1f, 1f)
        val kx = 1f + tilt * ry * 0.45f
        val fx = rx * kx
        return floatArrayOf(fx / a + 0.5f, ry + 0.5f)
    }
}
