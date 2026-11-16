package com.plenuen.lumen.nima

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable

/**
 * Turns a photo into a NIMA score for any recipe, on the device.
 *
 * Opens the weights (from the APK or a one-time download), an EGL surface that draws candidates at
 * 224×224 through the preview's own shader, and a TFLite interpreter; then keeps all three for the
 * life of the open photo so the automatic pass costs only a draw and an inference per candidate.
 *
 * `prepare`, [score] and [close] must be called from the same background thread.
 */
class NimaEvaluator(private val context: Context) : Closeable {

    private val store = NimaModelStore(context)
    private var session: NimaRenderSession? = null
    private var interpreter: NimaInterpreter? = null
    private var source: Bitmap? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var mask: Bitmap? = null
    private var lutBytes: ByteArray? = null

    var status: String? = null
        private set

    val ready: Boolean get() = session?.isOpen == true && interpreter != null

    /**
     * Prepares everything for [bitmap]. Returns false with [status] set when the model cannot run,
     * in which case the caller falls back to the rule-based aesthetic pass.
     */
    fun prepare(
        bitmap: Bitmap,
        mask: Bitmap?,
        lutBytes: ByteArray?,
        onProgress: (Float) -> Unit = {},
    ): Boolean {
        close()
        val file = store.ensure(onProgress)
        if (file == null) {
            status = "NIMA 모델을 열지 못했어요."
            return false
        }
        val loaded = NimaInterpreter.open(file)
        if (loaded == null) {
            status = "NIMA 모델을 읽지 못했어요."
            return false
        }
        interpreter = loaded
        val renderSession = NimaRenderSession()
        if (!renderSession.open(bitmap, mask)) {
            status = renderSession.error ?: "NIMA 렌더러를 열지 못했어요."
            close()
            return false
        }
        session = renderSession
        source = bitmap
        sourceWidth = bitmap.width
        sourceHeight = bitmap.height
        this.mask = mask
        this.lutBytes = lutBytes
        status = null
        return true
    }

    /** Keeps the photo and mask while the film look changes underneath. */
    fun updateLook(lutBytes: ByteArray?) {
        this.lutBytes = lutBytes
    }

    /** NIMA 미적 점수(1~10). 준비가 안 됐거나 추론이 실패하면 null. */
    fun score(recipe: com.plenuen.lumen.core.EditRecipe): Float? {
        val renderSession = session ?: return null
        val model = interpreter ?: return null
        val bitmapSource = source ?: return null
        val pixels = renderSession.render(recipe, lutBytes, sourceWidth, sourceHeight) ?: return null
        val input = NimaScores.toInput(pixels, NimaScores.INPUT_SIZE, NimaScores.INPUT_SIZE)
        return model.score(input)
    }

    /** A scorer for [NimaOptimizer]; the same object, so the search renders what the user sees. */
    fun scorer(): RecipeScorer = RecipeScorer { recipe -> score(recipe) }

    override fun close() {
        runCatching { session?.close() }
        session = null
        runCatching { interpreter?.close() }
        interpreter = null
        source = null
        mask = null
        lutBytes = null
    }

    companion object {
        private const val TAG = "LumenNima"

        /** Logs once whether the model could be opened, so a device report can be acted on. */
        fun logUnavailable(reason: String?) {
            Log.w(TAG, "NIMA unavailable: $reason")
        }
    }
}
