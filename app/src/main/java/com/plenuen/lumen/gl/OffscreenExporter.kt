package com.plenuen.lumen.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import com.plenuen.lumen.core.EditRecipe
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the recipe at the photo's own resolution into an offscreen framebuffer and reads it back
 * as a bitmap. Same program, same uniforms, same geometry as the preview — only the viewport and
 * the source texture size differ, which is what makes the export match what was on screen.
 */
class OffscreenExporter {

    sealed interface Outcome {
        data class Success(val bitmap: Bitmap, val downscaled: Boolean) : Outcome
        data class Failure(val message: String) : Outcome
    }

    fun render(source: Bitmap, recipe: EditRecipe, lutBytes: ByteArray?): Outcome {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var program: PhotoProgram? = null
        val textures = IntArray(3)
        var framebuffer = 0

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return Outcome.Failure("EGL 디스플레이를 열 수 없습니다.")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return Outcome.Failure("EGL을 초기화할 수 없습니다.")
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
            val configCount = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, configCount, 0) ||
                configCount[0] == 0 || configs[0] == null
            ) {
                return Outcome.Failure("EGL 설정을 찾을 수 없습니다 (GLES 3.0 필요).")
            }
            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return Outcome.Failure("GLES 3.0 컨텍스트를 만들 수 없습니다.")
            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return Outcome.Failure("EGL 서피스를 만들 수 없습니다.")
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return Outcome.Failure("EGL 컨텍스트를 활성화할 수 없습니다.")
            }

            val maxTexture = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            val limit = if (maxTexture[0] > 0) maxTexture[0] else 4096
            var width = source.width
            var height = source.height
            var downscaled = false
            var input = source
            if (width > limit || height > limit) {
                val scale = limit.toFloat() / maxOf(width, height).toFloat()
                width = (width * scale).toInt().coerceAtLeast(1)
                height = (height * scale).toInt().coerceAtLeast(1)
                input = Bitmap.createScaledBitmap(source, width, height, true)
                downscaled = true
            }

            val compiled = PhotoProgram().also { it.compile() }
            if (!compiled.isCompiled) {
                return Outcome.Failure(compiled.lastError ?: "셰이더를 준비하지 못했습니다.")
            }
            program = compiled

            GLES30.glGenTextures(3, textures, 0)
            val sourceTexture = textures[0]
            val targetTexture = textures[1]
            val lutTexture = textures[2]
            PhotoProgram.uploadBitmap(input, sourceTexture)

            val lutReady = lutBytes != null && lutBytes.size == 17 * 17 * 17 * 3
            if (lutReady) PhotoProgram.uploadLut(lutBytes!!, lutTexture)

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, targetTexture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )

            val fboIds = IntArray(1)
            GLES30.glGenFramebuffers(1, fboIds, 0)
            framebuffer = fboIds[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, targetTexture, 0,
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                return Outcome.Failure("프레임버퍼를 만들 수 없습니다 (0x${Integer.toHexString(status)}).")
            }

            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            compiled.use()
            compiled.bind(
                spec = RenderSpec(recipe, width, height, compare = false),
                sourceTexture = sourceTexture,
                lutTexture = lutTexture,
                lutEnabled = lutReady && recipe.lutIndex() >= 0,
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
            GLES30.glFinish()

            val pixelCount = width * height
            val buffer = ByteBuffer.allocateDirect(pixelCount * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()

            // 사진을 뒤집어 그렸으므로 glReadPixels의 첫 행이 사진의 윗줄이다.
            // GL은 RGBA 바이트 순서로 주므로 int 픽셀(ARGB_8888)로 직접 조립한다.
            val pixels = IntArray(pixelCount)
            val rowBytes = width * 4
            val row = ByteArray(rowBytes)
            for (y in 0 until height) {
                buffer.get(row, 0, rowBytes)
                var outIndex = y * width
                var i = 0
                while (i < rowBytes) {
                    val r = row[i].toInt() and 0xFF
                    val g = row[i + 1].toInt() and 0xFF
                    val b = row[i + 2].toInt() and 0xFF
                    val a = row[i + 3].toInt() and 0xFF
                    pixels[outIndex] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    outIndex++
                    i += 4
                }
            }
            return Outcome.Success(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), downscaled)
        } catch (t: Throwable) {
            Log.e("LumenExport", "export failed", t)
            return Outcome.Failure(t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { program?.release() }
            if (framebuffer != 0) runCatching { GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0) }
            if (textures[0] != 0) runCatching { GLES30.glDeleteTextures(3, textures, 0) }
            if (display != EGL14.EGL_NO_DISPLAY) {
                runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                if (surface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, surface) }
                if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
                runCatching { EGL14.eglTerminate(display) }
            }
        }
    }

    private companion object {
        const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}
