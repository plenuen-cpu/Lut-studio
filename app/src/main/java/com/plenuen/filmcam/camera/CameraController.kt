package com.plenuen.filmcam.camera

import android.content.Context
import android.media.Image
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Owns the CameraX Preview + ImageCapture use cases for FilmCam.
 */
class CameraController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var opening = false

    var onCameraReady: ((Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val currentLensFacing: Int get() = lensFacing

    fun open(surfaceProvider: Preview.SurfaceProvider, lens: Int) {
        lensFacing = lens
        val existing = provider
        if (existing != null) {
            bind(existing, surfaceProvider, lens)
            return
        }
        if (opening) return
        opening = true
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            opening = false
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                bind(cameraProvider, surfaceProvider, lensFacing)
            } catch (t: Throwable) {
                onError?.invoke("카메라를 열 수 없습니다: ${t.message}")
            }
        }, mainExecutor)
    }

    private fun bind(
        cameraProvider: ProcessCameraProvider,
        surfaceProvider: Preview.SurfaceProvider,
        lens: Int,
    ) {
        val selector = try {
            CameraSelector.Builder().requireLensFacing(lens).build()
        } catch (t: Throwable) {
            onError?.invoke("이 기기에서는 해당 카메라를 쓸 수 없습니다.")
            return
        }
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(mainExecutor, surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        try {
            cameraProvider.unbindAll()
            val bound = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            camera = bound
            imageCapture = capture
            lensFacing = lens
            onCameraReady?.invoke(bound.cameraInfo.sensorRotationDegrees)
        } catch (t: Throwable) {
            onError?.invoke("카메라를 시작할 수 없습니다: ${t.message}")
        }
    }

    fun setLens(lens: Int, surfaceProvider: Preview.SurfaceProvider) {
        if (lens == lensFacing) return
        val cameraProvider = provider ?: return
        lensFacing = lens
        bind(cameraProvider, surfaceProvider, lens)
    }

    fun close() {
        cameraProviderOrNull()?.unbindAll()
        camera = null
        imageCapture = null
    }

    private fun cameraProviderOrNull(): ProcessCameraProvider? = provider

    fun takePicture(
        onImage: (jpeg: ByteArray, rotationDegrees: Int, mirror: Boolean) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val capture = imageCapture
        if (capture == null) {
            onFailure("카메라가 아직 준비되지 않았습니다.")
            return
        }
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        try {
            capture.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val rotation = image.imageInfo.rotationDegrees
                            onImage(bytes, rotation, mirror)
                        } catch (t: Throwable) {
                            onFailure("촬영 결과를 읽을 수 없습니다.")
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        onFailure("촬영에 실패했습니다: ${exception.message}")
                    }
                },
            )
        } catch (t: Throwable) {
            onFailure("촬영에 실패했습니다: ${t.message}")
        }
    }
}
