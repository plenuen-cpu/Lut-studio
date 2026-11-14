package com.plenuen.filmcam.camera

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/** GLSurfaceView that renders the LUT camera preview. */
class GlPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    val lutRenderer = LutRenderer()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(lutRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
