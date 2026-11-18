package com.plenuen.lumen.gl

import android.graphics.Bitmap
import android.util.Log
import android.opengl.GLES30
import android.opengl.GLUtils
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.HslBand
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
    /** Preview only: left of the line shows the untouched source, right shows the edit. */
    val splitEnabled: Boolean = false,
    /** 0..1 position of the split line measured from the left edge of the photo. */
    val splitX: Float = 0.5f,
    /** True when a segmentation mask texture is bound (R = subject, G = sky). */
    val maskReady: Boolean = false,
    /** Preview only: paint the mask over the photo so the user can see what is targeted. */
    val maskPreview: Boolean = false,
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
    fun bind(
        spec: RenderSpec,
        sourceTexture: Int,
        lutTexture: Int,
        lutEnabled: Boolean,
        maskTexture: Int = 0,
    ) {
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
        GLES30.glUniform1f(loc("uToneShadow"), recipe[Param.TONE_SHADOW_CURVE])
        GLES30.glUniform1f(loc("uToneMid"), recipe[Param.TONE_MID_CURVE])
        GLES30.glUniform1f(loc("uToneHigh"), recipe[Param.TONE_HIGH_CURVE])

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
        GLES30.glUniform1f(loc("uSplitEnabled"), if (spec.splitEnabled) 1f else 0f)
        GLES30.glUniform1f(loc("uSplitX"), spec.splitX.coerceIn(0f, 1f))

        // ── 영역 마스크 (텍스처 단위 2) ─────────────────────────────
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexture)
        GLES30.glUniform1i(loc("uMask"), 2)
        GLES30.glUniform1f(loc("uMaskOn"), if (spec.maskReady && recipe[Param.MASK_ON] >= 0.5f) 1f else 0f)
        GLES30.glUniform1f(loc("uMaskPreview"), if (spec.maskPreview) 1f else 0f)
        GLES30.glUniform1f(loc("uSubExposure"), recipe[Param.SUBJECT_EXPOSURE])
        GLES30.glUniform1f(loc("uSubSaturation"), recipe[Param.SUBJECT_SATURATION])
        GLES30.glUniform1f(loc("uSubWarmth"), recipe[Param.SUBJECT_WARMTH])
        GLES30.glUniform1f(loc("uSubClarity"), recipe[Param.SUBJECT_CLARITY])
        GLES30.glUniform1f(loc("uSubContrast"), recipe[Param.SUBJECT_CONTRAST])
        GLES30.glUniform1f(loc("uSubStructure"), recipe[Param.SUBJECT_STRUCTURE])
        GLES30.glUniform1f(loc("uSubSkinProtect"), recipe[Param.SUBJECT_SKIN_PROTECT])
        GLES30.glUniform1f(loc("uBkgExposure"), recipe[Param.BACKGROUND_EXPOSURE])
        GLES30.glUniform1f(loc("uBkgSaturation"), recipe[Param.BACKGROUND_SATURATION])
        GLES30.glUniform1f(loc("uBkgWarmth"), recipe[Param.BACKGROUND_WARMTH])
        GLES30.glUniform1f(loc("uBkgClarity"), recipe[Param.BACKGROUND_CLARITY])
        GLES30.glUniform1f(loc("uBkgSoft"), recipe[Param.BACKGROUND_SOFT])
        GLES30.glUniform1f(loc("uBkgHighlights"), recipe[Param.BACKGROUND_HIGHLIGHTS])
        GLES30.glUniform1f(loc("uSkyExposure"), recipe[Param.SKY_EXPOSURE])
        GLES30.glUniform1f(loc("uSkySaturation"), recipe[Param.SKY_SATURATION])
        GLES30.glUniform1f(loc("uSkyWarmth"), recipe[Param.SKY_WARMTH])
        GLES30.glUniform1f(loc("uSkyBlue"), recipe[Param.SKY_BLUE])
        GLES30.glUniform1f(loc("uSkyDetail"), recipe[Param.SKY_DETAIL])

        // ── 8색 HSL: vec3(색조, 채도, 밝기) × 8 ──────────────────
        val bands = FloatArray(HslBand.entries.size * 3)
        var active = false
        HslBand.entries.forEachIndexed { index, band ->
            val hue = recipe[band.hue]
            val sat = recipe[band.saturation]
            val lum = recipe[band.luminance]
            bands[index * 3] = hue
            bands[index * 3 + 1] = sat
            bands[index * 3 + 2] = lum
            if (hue != 0f || sat != 0f || lum != 0f) active = true
        }
        GLES30.glUniform1f(loc("uHslActive"), if (active) 1f else 0f)
        val bandBuffer = ByteBuffer.allocateDirect(bands.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(bands)
                position(0)
            }
        GLES30.glUniform3fv(loc("uBandHSL[0]"), HslBand.entries.size, bandBuffer)
    }

    private fun loc(name: String): Int = uniforms[name] ?: -1

    companion object {
        val UNIFORM_NAMES = listOf(
            "uSrc", "uLut", "uTexel", "uAspect", "uStraighten", "uCropScale", "uVerticalTilt",
            "uExposure", "uContrast", "uHighlights", "uShadows", "uWhites", "uBlacks", "uFade",
            "uToneShadow", "uToneMid", "uToneHigh",
            "uTemperature", "uTint", "uVibrance", "uSaturation", "uHarmony", "uCross",
            "uSplitTone", "uSplitBalance", "uShadowHue", "uHighlightHue", "uSkin", "uMono",
            "uClarity", "uTextureAmt", "uDehaze", "uSharpnessAmt", "uGlow", "uHalation",
            "uGrain", "uVignette", "uFocus", "uLutIntensity", "uLutEnabled", "uCompare",
            "uSplitEnabled", "uSplitX", "uMask", "uMaskOn", "uMaskPreview",
            "uSubExposure", "uSubSaturation", "uSubWarmth", "uSubClarity",
            "uSubContrast", "uSubStructure", "uSubSkinProtect",
            "uBkgExposure", "uBkgSaturation", "uBkgWarmth", "uBkgClarity", "uBkgSoft",
            "uBkgHighlights",
            "uSkyExposure", "uSkySaturation", "uSkyWarmth", "uSkyBlue", "uSkyDetail",
            "uHslActive", "uBandHSL[0]",
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
        /** Bytes a 17×17×17 RGB cube takes; anything shorter would be read past its end. */
        const val LUT_BYTES = 17 * 17 * 17 * 3

        fun uploadLut(lut: ByteArray, textureId: Int) {
            // 짧은 버퍼를 17³ 크기로 올리면 드라이버가 버퍼 밖을 읽고 프로세스가 그 자리에서 죽는다.
            if (lut.size < LUT_BYTES) {
                Log.w("LumenGl", "LUT too short: ${lut.size} < $LUT_BYTES, not uploaded")
                return
            }
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
