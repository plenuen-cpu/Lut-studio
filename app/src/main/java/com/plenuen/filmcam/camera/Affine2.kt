package com.plenuen.filmcam.camera

/**
 * 2D affine transform stored column-major for a GLSL `mat3`:
 *
 *     u = a*x + c*y + e
 *     v = b*x + d*y + f
 *     values = [a, b, 0, c, d, 0, e, f, 1]
 */
class Affine2 private constructor(val values: FloatArray) {

    init {
        require(values.size == 9) { "mat3 needs 9 floats" }
    }

    fun mapU(x: Float, y: Float): Float = values[0] * x + values[3] * y + values[6]

    fun mapV(x: Float, y: Float): Float = values[1] * x + values[4] * y + values[7]

    /** this ∘ other — [other] is applied first. */
    operator fun times(other: Affine2): Affine2 {
        val a = values[0]
        val b = values[1]
        val c = values[3]
        val d = values[4]
        val e = values[6]
        val f = values[7]
        val oa = other.values[0]
        val ob = other.values[1]
        val oc = other.values[3]
        val od = other.values[4]
        val oe = other.values[6]
        val of = other.values[7]
        return Affine2(
            floatArrayOf(
                a * oa + c * ob, b * oa + d * ob, 0f,
                a * oc + c * od, b * oc + d * od, 0f,
                a * oe + c * of + e, b * oe + d * of + f, 1f,
            )
        )
    }

    companion object {

        fun identity(): Affine2 = of(1f, 0f, 0f, 1f, 0f, 0f)

        fun of(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float): Affine2 =
            Affine2(floatArrayOf(a, b, 0f, c, d, 0f, e, f, 1f))

        /** The xy part of the 4x4 matrix returned by SurfaceTexture#getTransformMatrix. */
        fun fromTextureMatrix(matrix: FloatArray): Affine2 {
            if (matrix.size < 16) return identity()
            return of(matrix[0], matrix[1], matrix[4], matrix[5], matrix[12], matrix[13])
        }

        /** Zooms the sampled area around the centre: (0.5,0.5) stays fixed. */
        fun scaleAboutCenter(scaleX: Float, scaleY: Float): Affine2 = of(
            scaleX, 0f, 0f, scaleY,
            0.5f - 0.5f * scaleX,
            0.5f - 0.5f * scaleY,
        )

        /**
         * Maps destination uv to source uv for content that must be rotated clockwise by
         * [degrees] (and mirrored horizontally for the selfie camera) to appear upright.
         */
        fun contentRotation(degrees: Int, mirror: Boolean): Affine2 {
            val rotation = when (((degrees % 360) + 360) % 360) {
                90 -> of(0f, 1f, -1f, 0f, 1f, 0f)   // u = 1 - v, v = u
                180 -> of(-1f, 0f, 0f, -1f, 1f, 1f) // u = 1 - u, v = 1 - v
                270 -> of(0f, -1f, 1f, 0f, 0f, 1f)  // u = v, v = 1 - u
                else -> identity()
            }
            if (!mirror) return rotation
            val flip = of(-1f, 0f, 0f, 1f, 1f, 0f) // u = 1 - u
            return flip * rotation
        }

        /**
         * Full preview transform: camera buffer (via [textureMatrix]) -> upright -> cropped to
         * the viewport aspect ratio.
         */
        fun forPreview(
            textureMatrix: FloatArray,
            rotationDegrees: Int,
            mirror: Boolean,
            sourceWidth: Int,
            sourceHeight: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): Affine2 {
            var scaleX = 1f
            var scaleY = 1f
            val quarterTurn = ((rotationDegrees % 360) + 360) % 360 == 90 ||
                ((rotationDegrees % 360) + 360) % 360 == 270
            val displayWidth = if (quarterTurn) sourceHeight else sourceWidth
            val displayHeight = if (quarterTurn) sourceWidth else sourceHeight
            if (displayWidth > 0 && displayHeight > 0 && viewWidth > 0 && viewHeight > 0) {
                val sourceAspect = displayWidth.toFloat() / displayHeight.toFloat()
                val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
                if (sourceAspect > viewAspect) {
                    scaleX = viewAspect / sourceAspect
                } else if (sourceAspect < viewAspect) {
                    scaleY = sourceAspect / viewAspect
                }
            }
            return scaleAboutCenter(scaleX, scaleY) *
                contentRotation(rotationDegrees, mirror) *
                fromTextureMatrix(textureMatrix)
        }
    }
}
