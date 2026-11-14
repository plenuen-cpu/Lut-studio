package com.plenuen.filmcam.camera

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.camera.core.Preview
import java.util.concurrent.Executor

/**
 * Bridges CameraX's preview surface request and the GL texture owned by the renderer.
 *
 * Either side may arrive first (the GL thread creates the SurfaceTexture asynchronously), so the
 * request is held until a texture exists.
 */
class PreviewSurfaceGateway(private val executor: Executor) : Preview.SurfaceProvider {

    private var pendingRequest: Preview.SurfaceProvider.Request? = null
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null

    var onBufferSize: ((Int, Int) -> Unit)? = null

    /** Called on the main thread once the renderer created its SurfaceTexture. */
    fun onTextureReady(surfaceTexture: SurfaceTexture) {
        texture = surfaceTexture
        val request = pendingRequest
        if (request != null) {
            attach(request)
        }
    }

    override fun onSurfaceRequested(request: Preview.SurfaceProvider.Request) {
        if (texture == null) {
            pendingRequest = request
        } else {
            attach(request)
        }
    }

    fun release() {
        pendingRequest = null
        surface?.release()
        surface = null
    }

    private fun attach(request: Preview.SurfaceProvider.Request) {
        val surfaceTexture = texture ?: return
        pendingRequest = null
        val resolution = request.resolution
        surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
        val newSurface = Surface(surfaceTexture)
        surface?.release()
        surface = newSurface
        request.provideSurface(newSurface, executor) { }
        onBufferSize?.invoke(resolution.width, resolution.height)
    }
}
