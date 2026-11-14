package com.plenuen.filmcam.camera

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor

/**
 * Bridges CameraX's preview surface request and the GL texture owned by the renderer.
 *
 * Either side may arrive first (the GL thread creates the SurfaceTexture asynchronously), so the
 * request is held until a texture exists.
 */
class PreviewSurfaceGateway(private val executor: Executor) : Preview.SurfaceProvider {

    private var pendingRequest: SurfaceRequest? = null
    private var texture: SurfaceTexture? = null
    private val surfaces = mutableListOf<Surface>()

    var onBufferSize: ((Int, Int) -> Unit)? = null

    /** Called on the main thread once the renderer created its SurfaceTexture. */
    fun onTextureReady(surfaceTexture: SurfaceTexture) {
        texture = surfaceTexture
        val request = pendingRequest
        if (request != null) {
            attach(request)
        }
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        if (texture == null) {
            pendingRequest = request
        } else {
            attach(request)
        }
    }

    fun release() {
        pendingRequest = null
        surfaces.forEach { surface -> runCatching { surface.release() } }
        surfaces.clear()
    }

    private fun attach(request: SurfaceRequest) {
        val surfaceTexture = texture ?: return
        pendingRequest = null
        val resolution = request.resolution
        surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
        val newSurface = Surface(surfaceTexture)
        surfaces += newSurface
        request.provideSurface(newSurface, executor) { }
        onBufferSize?.invoke(resolution.width, resolution.height)
    }
}
