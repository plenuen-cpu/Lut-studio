package com.plenuen.lumen.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Preview renderer. The photo is uploaded once and every frame draws it through [PhotoProgram]
 * with the current recipe, letterboxed inside the view so the whole frame stays visible.
 */
class PhotoRenderer : GLSurfaceView.Renderer {

    private val pendingImage = AtomicReference<Bitmap?>(null)
    private val pendingLut = AtomicReference<ByteArray?>(null)
    private val pendingMask = AtomicReference<MaskUpdate?>(null)
    private val specRef = AtomicReference<RenderSpec?>(null)

    /** Null bitmap means "drop the mask" (a new photo is loading). */
    private data class MaskUpdate(val bitmap: Bitmap?)

    @Volatile
    private var imageGeneration = 0

    var onError: ((String) -> Unit)? = null
    var onFrameRendered: (() -> Unit)? = null

    private var program: PhotoProgram? = null
    private var sourceTexture = 0
    private var lutTexture = 0
    private var maskTexture = 0
    private var maskUploaded = false
    private var vertexBuffer = 0
    private var imageWidth = 0
    private var imageHeight = 0
    private var lutUploaded = false
    private var viewWidth = 1
    private var viewHeight = 1

    fun setImage(bitmap: Bitmap) {
        pendingImage.set(bitmap)
        imageGeneration++
    }

    fun setLut(lutBytes: ByteArray?) {
        pendingLut.set(lutBytes)
    }

    fun setMask(bitmap: Bitmap?) {
        pendingMask.set(MaskUpdate(bitmap))
    }

    fun updateSpec(spec: RenderSpec) {
        specRef.set(spec)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        val compiled = PhotoProgram().also { it.compile() }
        if (!compiled.isCompiled) {
            onError?.invoke(compiled.lastError ?: "셰이더를 준비하지 못했습니다.")
            return
        }
        program = compiled

        val ids = IntArray(3)
        GLES30.glGenTextures(3, ids, 0)
        sourceTexture = ids[0]
        lutTexture = ids[1]
        maskTexture = ids[2]
        // 마스크가 없을 때도 세이더가 항상 유효한 텍스처를 보도록 1×1 검사 텍스처를 올린다.
        PhotoProgram.uploadBitmap(
            Bitmap.createBitmap(intArrayOf(0), 1, 1, Bitmap.Config.ARGB_8888),
            maskTexture,
        )

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vertexBuffer = buffers[0]
        val buffer = PhotoProgram.vertexBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            PhotoProgram.VERTICES.size * 4,
            buffer,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        lutUploaded = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val active = program ?: return

        pendingImage.getAndSet(null)?.let { bitmap ->
            imageWidth = bitmap.width
            imageHeight = bitmap.height
            PhotoProgram.uploadBitmap(bitmap, sourceTexture)
        }
        pendingLut.getAndSet(null)?.let { lut ->
            PhotoProgram.uploadLut(lut, lutTexture)
            lutUploaded = true
        }
        pendingMask.getAndSet(null)?.let { update ->
            val bitmap = update.bitmap
            if (bitmap == null) {
                PhotoProgram.uploadBitmap(
                    Bitmap.createBitmap(intArrayOf(0), 1, 1, Bitmap.Config.ARGB_8888),
                    maskTexture,
                )
                maskUploaded = false
            } else {
                PhotoProgram.uploadBitmap(bitmap, maskTexture)
                maskUploaded = true
            }
        }
        if (imageWidth == 0 || imageHeight == 0) return

        val spec = specRef.get() ?: RenderSpec(com.plenuen.lumen.core.EditRecipe.NEUTRAL, imageWidth, imageHeight)
        val fitted = fit(imageWidth, imageHeight, viewWidth, viewHeight)
        GLES30.glViewport(fitted.x, fitted.y, fitted.width, fitted.height)

        active.use()
        active.bind(
            spec = spec.copy(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                maskReady = spec.maskReady && maskUploaded,
            ),
            sourceTexture = sourceTexture,
            lutTexture = lutTexture,
            lutEnabled = lutUploaded && spec.recipe.lutIndex() >= 0,
            maskTexture = maskTexture,
        )

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            Log.w("LumenGl", "GL error $error")
        }
        onFrameRendered?.invoke()
    }

    /** Aspect-fit rectangle inside the view (letterbox). */
    private fun fit(imageW: Int, imageH: Int, viewW: Int, viewH: Int): Rect {
        val imageAspect = imageW.toFloat() / imageH.toFloat()
        val viewAspect = viewW.toFloat() / viewH.toFloat()
        return if (imageAspect > viewAspect) {
            val height = (viewW / imageAspect).toInt()
            Rect(0, (viewH - height) / 2, viewW, height)
        } else {
            val width = (viewH * imageAspect).toInt()
            Rect((viewW - width) / 2, 0, width, viewH)
        }
    }

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
}
