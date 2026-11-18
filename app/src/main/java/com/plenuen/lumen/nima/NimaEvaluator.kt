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

    /** The mask the offscreen pass draws with, or null when the photo has none (yet). */
    val maskBitmap: Bitmap? get() = mask

    /**
     * Hands the renderer a region mask that was built after [prepare].
     *
     * Segmentation runs in the background and can finish after the first score request, so the
     * evaluator may have been opened before there was a mask. Uploading it here (rather than
     * re-opening the session) is what makes a region candidate scored the same way the preview
     * draws it. Returns false when the session is closed, so the caller can drop and rebuild it.
     */
    fun updateMask(mask: Bitmap?): Boolean {
        if (this.mask === mask) return true
        val renderSession = session ?: return false
        val uploaded = renderSession.updateMask(mask)
        if (uploaded) this.mask = mask
        return uploaded
    }

    /** NIMA 미적 점수(1~10). 준비가 안 됐거나 추론이 실패하면 null. */
    fun score(recipe: com.plenuen.lumen.core.EditRecipe): Float? {
        val pixels = renderPixels(recipe) ?: return null
        return nimaOf(pixels)
    }

    /**
     * Renders [recipe] at 224×224 through the preview's own shader and returns the pixels.
     *
     * v2.3 needs the pixels as well as the score: the quality evaluator measures clipping, colour,
     * skin and subject separation on exactly the frame the model judged.
     *
     * The array belongs to the render session and the next [renderPixels] overwrites it in place.
     * Keep it only through an owner that copies ([com.plenuen.lumen.engine.PixelSample] does), never
     * by holding the reference — the baseline of a search is read after seventy more renders.
     */
    fun renderPixels(recipe: com.plenuen.lumen.core.EditRecipe): IntArray? {
        val renderSession = session ?: return null
        if (source == null) return null
        return renderSession.render(recipe, lutBytes, sourceWidth, sourceHeight)
    }

    /** Scores an already rendered 224×224 frame. */
    fun nimaOf(pixels: IntArray): Float? {
        val model = interpreter ?: return null
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
