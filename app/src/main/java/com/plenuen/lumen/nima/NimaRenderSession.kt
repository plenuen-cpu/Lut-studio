package com.plenuen.lumen.nima

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.gl.PhotoProgram
import com.plenuen.lumen.gl.RenderSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws the recipe at 224×224 into an offscreen buffer so the NIMA model can judge it.
 *
 * The same shader, uniforms and geometry as the preview: a candidate is scored as the photo would
 * look, not as a rough approximation of it. One EGL context, one source texture and one framebuffer
 * live for the whole pass — only the uniforms change between candidates, which is what makes sixty
 * evaluations possible in a couple of seconds.
 *
 * Open, render and close all have to happen on the same thread.
 */
class NimaRenderSession {

    var error: String? = null
        private set

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program: PhotoProgram? = null
    private var framebuffer = 0
    private val textures = IntArray(4)
    private var lutBytes: ByteArray? = null
    private var width = 0
    private var height = 0
    private var pixels = IntArray(0)
    private var pixelBuffer: ByteBuffer? = null

    val isOpen: Boolean get() = framebuffer != 0 && program?.isCompiled == true

    /** Uploads the photo (and its region mask, when it has one) and builds the render target. */
    fun open(source: Bitmap, mask: Bitmap?): Boolean {
        close()
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return fail("EGL 디스플레이를 열 수 없습니다.")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return fail("EGL을 초기화할 수 없습니다.")
            }
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) ||
                count[0] == 0 || configs[0] == null
            ) {
                return fail("EGL 설정을 찾을 수 없습니다 (GLES 3.0 필요).")
            }
            context = EGL14.eglCreateContext(
                display, configs[0]!!, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return fail("GLES 3.0 컨텍스트를 만들 수 없습니다.")
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0]!!,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return fail("EGL 서피스를 만들 수 없습니다.")
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return fail("EGL 컨텍스트를 활성화할 수 없습니다.")
            }

            val compiled = PhotoProgram().also { it.compile() }
            if (!compiled.isCompiled) return fail(compiled.lastError ?: "셰이더를 준비하지 못했습니다.")
            program = compiled

            width = NimaScores.INPUT_SIZE
            height = NimaScores.INPUT_SIZE
            GLES30.glGenTextures(4, textures, 0)
            PhotoProgram.uploadBitmap(source, textures[0])
            if (mask != null) {
                PhotoProgram.uploadBitmap(mask, textures[3])
            } else {
                PhotoProgram.uploadBitmap(
                    Bitmap.createBitmap(intArrayOf(0), 1, 1, Bitmap.Config.ARGB_8888),
                    textures[3],
                )
            }

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[1])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )

            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, textures[1], 0,
            )
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                return fail("프레임버퍼를 만들 수 없습니다.")
            }
            pixels = IntArray(width * height)
            pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            // 사진 기하는 프리뷰와 같게 본다: 후보는 같은 프레임을 224px로 줄여 그린 것이다.
            GLES30.glViewport(0, 0, width, height)
            return true
        } catch (t: Throwable) {
            Log.e("LumenNima", "render session failed", t)
            close()
            return fail(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Renders [recipe] and returns its 224×224 ARGB pixels, or null when the draw failed. */
    fun render(recipe: EditRecipe, lut: ByteArray?, sourceWidth: Int, sourceHeight: Int): IntArray? {
        val compiled = program ?: return null
        if (framebuffer == 0) return null
        return runCatching {
            if (!lut.contentEqualsNullable(lutBytes) && lut != null && lut.size == 17 * 17 * 17 * 3) {
                PhotoProgram.uploadLut(lut, textures[2])
                lutBytes = lut
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            compiled.use()
            compiled.bind(
                spec = RenderSpec(
                    recipe = recipe,
                    imageWidth = sourceWidth.coerceAtLeast(1),
                    imageHeight = sourceHeight.coerceAtLeast(1),
                    compare = false,
                    splitEnabled = false,
                    maskReady = false,
                    maskPreview = false,
                ),
                sourceTexture = textures[0],
                lutTexture = textures[2],
                lutEnabled = lut != null && recipe.lutIndex() >= 0,
                maskTexture = textures[3],
            )
            val vertices = PhotoProgram.vertexBuffer(PhotoProgram.FLIPPED_VERTICES)
            val buffers = IntArray(1)
            GLES30.glGenBuffers(1, buffers, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                PhotoProgram.FLIPPED_VERTICES.size * 4,
                vertices,
                GLES30.GL_STATIC_DRAW,
            )
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDeleteBuffers(1, buffers, 0)
            GLES30.glFinish()

            val buffer = pixelBuffer!!.also { it.rewind() }
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()
            val rowBytes = width * 4
            val row = ByteArray(rowBytes)
            for (y in 0 until height) {
                buffer.get(row, 0, rowBytes)
                var out = y * width
                var i = 0
                while (i < rowBytes) {
                    val r = row[i].toInt() and 0xFF
                    val g = row[i + 1].toInt() and 0xFF
                    val b = row[i + 2].toInt() and 0xFF
                    val a = row[i + 3].toInt() and 0xFF
                    pixels[out] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    out++
                    i += 4
                }
            }
            pixels
        }.getOrElse { error ->
            Log.w("LumenNima", "render failed: ${error.message}")
            null
        }
    }

    fun close() {
        runCatching { program?.release() }
        program = null
        if (framebuffer != 0) {
            runCatching { GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0) }
            framebuffer = 0
        }
        if (textures[0] != 0) {
            runCatching { GLES30.glDeleteTextures(4, textures, 0) }
            textures.fill(0)
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (surface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, surface) }
            if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        lutBytes = null
        pixels = IntArray(0)
        pixelBuffer = null
    }

    private fun fail(message: String): Boolean {
        error = message
        close()
        return false
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        this === other || (this != null && other != null && this.contentEquals(other))

    private companion object {
        const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}
