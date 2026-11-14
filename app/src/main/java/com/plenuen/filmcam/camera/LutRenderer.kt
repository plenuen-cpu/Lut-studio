package com.plenuen.filmcam.camera

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the camera preview through a 3D LUT.
 *
 * The camera writes into an external OES texture; the fragment shader samples the 17^3 LUT with
 * hardware trilinear filtering — the same interpolation Tonefinder does on the CPU — and mixes
 * it with the original frame by the intensity slider.
 */
class LutRenderer : GLSurfaceView.Renderer {

    @Volatile
    var intensity: Float = 1f

    /** Clockwise rotation needed to make the camera buffer upright (sensor orientation). */
    @Volatile
    var rotationDegrees: Int = 90

    @Volatile
    var mirrorHorizontally: Boolean = false

    @Volatile
    var sourceWidth: Int = 0

    @Volatile
    var sourceHeight: Int = 0

    var onSurfaceTextureCreated: ((SurfaceTexture) -> Unit)? = null
    var requestFrame: (() -> Unit)? = null
    var onGlError: ((String) -> Unit)? = null

    private val pendingLut = AtomicReference<ByteArray?>(null)

    private var program = 0
    private var oesTexture = 0
    private var lutTexture = 0
    private var vertexBuffer = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)
    private var viewWidth = 1
    private var viewHeight = 1
    private var lutUploaded = false

    private var uTexMatrix = -1
    private var uTexture = -1
    private var uLut = -1
    private var uMix = -1

    fun setLut(lut: ByteArray) {
        pendingLut.set(lut)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            onGlError?.invoke("LUT 셰이더를 만들 수 없습니다.")
            return
        }
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTexture = GLES30.glGetUniformLocation(program, "uTexture")
        uLut = GLES30.glGetUniformLocation(program, "uLut")
        uMix = GLES30.glGetUniformLocation(program, "uMix")

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oesTexture = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
        )

        @Suppress("DEPRECATION")
        val texture = SurfaceTexture(oesTexture)
        surfaceTexture = texture
        lutUploaded = false
        onSurfaceTextureCreated?.invoke(texture)

        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        val buffer: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vertexBuffer = buffers[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val texture = surfaceTexture ?: return
        if (program == 0) return

        texture.updateTexImage()
        texture.getTransformMatrix(stMatrix)

        val pending = pendingLut.getAndSet(null)
        if (pending != null) {
            uploadLut(pending)
        }
        if (!lutUploaded) return

        val matrix = Affine2.forPreview(
            textureMatrix = stMatrix,
            rotationDegrees = rotationDegrees,
            mirror = mirrorHorizontally,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glUniform1i(uTexture, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(uLut, 1)
        GLES30.glUniformMatrix3fv(uTexMatrix, 1, false, matrix.values, 0)
        GLES30.glUniform1f(uMix, intensity.coerceIn(0f, 1f))

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun uploadLut(lut: ByteArray) {
        if (lut.size != LUT_SIZE * LUT_SIZE * LUT_SIZE * 3) {
            onGlError?.invoke("LUT 데이터 크기가 올바르지 않습니다: ${lut.size}")
            return
        }
        if (lutTexture == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            lutTexture = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(lut.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(lut)
                position(0)
            }
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
            LUT_SIZE, LUT_SIZE, LUT_SIZE, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buffer,
        )
        lutUploaded = true
        requestFrame?.invoke()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource) ?: return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetProgramInfoLog(program)
            Log.e(TAG, "link failed: $log")
            onGlError?.invoke("LUT 셰이더 링크 실패: $log")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) return null
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            Log.e(TAG, "compile failed: $log\n$source")
            onGlError?.invoke("LUT 셰이더 컴파일 실패: $log")
            GLES30.glDeleteShader(shader)
            return null
        }
        return shader
    }

    companion object {
        private const val TAG = "FilmCamGl"
        private const val LUT_SIZE = 17

        private const val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            uniform mat3 uTexMatrix;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = (uTexMatrix * vec3(aTexCoord, 1.0)).xy;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform sampler3D uLut;
            uniform float uMix;
            in vec2 vTexCoord;
            out vec4 fragColor;
            void main() {
                vec3 source = texture(uTexture, vTexCoord).rgb;
                vec3 graded = texture(uLut, clamp(source, 0.0, 1.0)).rgb;
                fragColor = vec4(mix(source, graded, uMix), 1.0);
            }
        """
    }
}
