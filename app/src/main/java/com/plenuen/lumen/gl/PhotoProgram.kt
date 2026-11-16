package com.plenuen.lumen.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.geom.GeometryMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Everything the render thread needs to draw one frame. Immutable, swapped by reference. */
data class RenderSpec(
    val recipe: EditRecipe,
    val imageWidth: Int,
    val imageHeight: Int,
    val compare: Boolean = false,
)

/**
 * Compiled copy of [ShaderSource] plus its uniform table. Used by the preview renderer and by the
 * full-resolution exporter, so both paths run byte-for-byte the same code.
 */
class PhotoProgram {

    private var program = 0
    private val uniforms = HashMap<String, Int>()

    val isCompiled: Boolean get() = program != 0

    fun compile(): Boolean {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, ShaderSource.VERTEX)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, ShaderSource.FRAGMENT)
        if (vertex == 0 || fragment == 0) return false
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            program = 0
            lastError = "셰이더 링크 실패: $log"
            return false
        }
        for (name in UNIFORM_NAMES) {
            uniforms[name] = GLES30.glGetUniformLocation(program, name)
        }
        return true
    }

    var lastError: String? = null
        private set

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            lastError = "셰이더 컴파일 실패: " + GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    fun use() {
        GLES30.glUseProgram(program)
    }

    fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        program = 0
    }

    /** Binds every recipe value; the geometry block mirrors [GeometryMath]. */
    fun bind(spec: RenderSpec, sourceTexture: Int, lutTexture: Int, lutEnabled: Boolean) {
        val recipe = spec.recipe
        val width = spec.imageWidth.coerceAtLeast(1)
        val height = spec.imageHeight.coerceAtLeast(1)
        val aspect = width.toFloat() / height.toFloat()
        val angleDeg = recipe[Param.STRAIGHTEN_DEG]
        val cropScale = GeometryMath.cropScale(angleDeg, aspect)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
        GLES30.glUniform1i(loc("uSrc"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(loc("uLut"), 1)

        GLES30.glUniform2f(loc("uTexel"), 1f / width, 1f / height)
        GLES30.glUniform1f(loc("uAspect"), aspect)
        GLES30.glUniform1f(loc("uStraighten"), Math.toRadians(angleDeg.toDouble()).toFloat())
        GLES30.glUniform1f(loc("uCropScale"), cropScale)
        GLES30.glUniform1f(loc("uVerticalTilt"), recipe[Param.VERTICAL_TILT])

        GLES30.glUniform1f(loc("uExposure"), recipe[Param.EXPOSURE])
        GLES30.glUniform1f(loc("uContrast"), recipe[Param.CONTRAST])
        GLES30.glUniform1f(loc("uHighlights"), recipe[Param.HIGHLIGHTS])
        GLES30.glUniform1f(loc("uShadows"), recipe[Param.SHADOWS])
        GLES30.glUniform1f(loc("uWhites"), recipe[Param.WHITES])
        GLES30.glUniform1f(loc("uBlacks"), recipe[Param.BLACKS])
        GLES30.glUniform1f(loc("uFade"), recipe[Param.FADE])

        GLES30.glUniform1f(loc("uTemperature"), recipe[Param.TEMPERATURE])
        GLES30.glUniform1f(loc("uTint"), recipe[Param.TINT])
        GLES30.glUniform1f(loc("uVibrance"), recipe[Param.VIBRANCE])
        GLES30.glUniform1f(loc("uSaturation"), recipe[Param.SATURATION])
        GLES30.glUniform1f(loc("uHarmony"), recipe[Param.COLOR_HARMONY])
        GLES30.glUniform1f(loc("uCross"), recipe[Param.CROSS_PROCESS])
        GLES30.glUniform1f(loc("uSplitTone"), recipe[Param.SPLIT_TONE])
        GLES30.glUniform1f(loc("uSplitBalance"), recipe[Param.SPLIT_BALANCE])
        GLES30.glUniform1f(loc("uShadowHue"), recipe[Param.SPLIT_SHADOW_HUE] / 360f)
        GLES30.glUniform1f(loc("uHighlightHue"), recipe[Param.SPLIT_HIGHLIGHT_HUE] / 360f)
        GLES30.glUniform1f(loc("uSkin"), recipe[Param.SKIN_CLEAN])
        GLES30.glUniform1f(loc("uMono"), recipe[Param.MONOCHROME])

        GLES30.glUniform1f(loc("uClarity"), recipe[Param.CLARITY])
        GLES30.glUniform1f(loc("uTextureAmt"), recipe[Param.TEXTURE])
        GLES30.glUniform1f(loc("uDehaze"), recipe[Param.DEHAZE])
        GLES30.glUniform1f(loc("uSharpnessAmt"), recipe[Param.SHARPNESS])
        GLES30.glUniform1f(loc("uGlow"), recipe[Param.GLOW])
        GLES30.glUniform1f(loc("uHalation"), recipe[Param.HALATION])
        GLES30.glUniform1f(loc("uGrain"), recipe[Param.GRAIN])
        GLES30.glUniform1f(loc("uVignette"), recipe[Param.VIGNETTE])
        GLES30.glUniform1f(loc("uFocus"), recipe[Param.SUBJECT_FOCUS])

        GLES30.glUniform1f(loc("uLutIntensity"), recipe[Param.LUT_INTENSITY])
        GLES30.glUniform1f(loc("uLutEnabled"), if (lutEnabled) 1f else 0f)
        GLES30.glUniform1f(loc("uCompare"), if (spec.compare) 1f else 0f)
    }

    private fun loc(name: String): Int = uniforms[name] ?: -1

    companion object {
        val UNIFORM_NAMES = listOf(
            "uSrc", "uLut", "uTexel", "uAspect", "uStraighten", "uCropScale", "uVerticalTilt",
            "uExposure", "uContrast", "uHighlights", "uShadows", "uWhites", "uBlacks", "uFade",
            "uTemperature", "uTint", "uVibrance", "uSaturation", "uHarmony", "uCross",
            "uSplitTone", "uSplitBalance", "uShadowHue", "uHighlightHue", "uSkin", "uMono",
            "uClarity", "uTextureAmt", "uDehaze", "uSharpnessAmt", "uGlow", "uHalation",
            "uGrain", "uVignette", "uFocus", "uLutIntensity", "uLutEnabled", "uCompare",
        )

        /** Quad as a triangle strip: positions then uv (uv.y = 0 at the top of the image). */
        val VERTICES: FloatArray = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

        /**
         * Same quad with the image drawn upside down, used by the exporter: glReadPixels starts at
         * the bottom row, so drawing flipped makes the read-back buffer already top-down and it can
         * be copied straight into a Bitmap (no second full-size pixel array).
         */
        val FLIPPED_VERTICES: FloatArray = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        fun vertexBuffer(vertices: FloatArray = VERTICES): FloatBuffer = ByteBuffer
            .allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

        /** Uploads a bitmap as an RGBA texture; the top row of the image is t = 0. */
        fun uploadBitmap(bitmap: Bitmap, textureId: Int) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        }

        /** Uploads a decoded 17^3 LUT as a trilinear 3D texture. */
        fun uploadLut(lut: ByteArray, textureId: Int) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            val buffer = ByteBuffer.allocateDirect(lut.size)
                .order(ByteOrder.nativeOrder())
                .apply {
                    put(lut)
                    position(0)
                }
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
                17, 17, 17, 0,
                GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buffer,
            )
        }
    }
}
