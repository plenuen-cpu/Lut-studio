package com.plenuen.lumen.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder
import kotlin.math.max

/**
 * On-device region masks: R = subject (person / subject), G = sky.
 *
 * The primary route is MediaPipe's ImageSegmenter running `selfie_multiclass_256x256.tflite` from
 * the app's own assets (16 MB, bundled, no network). That model predicts six classes — background,
 * hair, body skin, face skin, clothes, accessories — so the subject is everything except class 0.
 *
 * The model knows people, not skies, so the sky mask is always derived from the picture itself
 * ([MaskMath.skyMask]) and clipped by the subject. If the model is missing, fails to initialise or
 * returns something unreadable, a colour/position saliency fallback ([MaskMath.heuristicSubject])
 * keeps the region sliders usable instead of failing the edit.
 */
class MaskGenerator(private val context: Context) {

    data class Output(
        val bitmap: Bitmap,
        val method: String,
        val skyCoverage: Float,
        val subjectCoverage: Float,
    ) {
        val hasSky: Boolean get() = skyCoverage > 0.004f
    }

    private var segmenter: ImageSegmenter? = null
    private var segmenterBroken = false
    private var lastError: String? = null

    /**
     * Builds the mask texture for [source]. Always returns something usable; [Output.method] says
     * which route produced it. Synchronized because the preview and an export can ask at once, and
     * the underlying MediaPipe task is not meant to be run from two threads.
     */
    @Synchronized
    fun generate(source: Bitmap): Output {
        val width = max(1, minOf(source.width, MAX_SIDE))
        val height = max(1, (source.height.toFloat() * width / source.width).toInt())
        val scaled = if (width == source.width && height == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)

            var subject = runSegmenter(scaled, width, height)
            var method = MODEL
            if (subject == null) {
                method = FALLBACK
                subject = MaskMath.heuristicSubject(pixels, width, height)
            } else {
                subject = MaskMath.feather(subject, width, height, MODEL_FEATHER)
            }
            val sky = MaskMath.skyMask(pixels, width, height, subject)
            val packed = MaskMath.pack(subject, sky, width, height)
            val bitmap = Bitmap.createBitmap(packed, width, height, Bitmap.Config.ARGB_8888)
            return Output(
                bitmap = bitmap,
                method = method,
                skyCoverage = MaskMath.coverage(sky),
                subjectCoverage = MaskMath.coverage(subject),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "mask packing failed", t)
            // 마지막 안전망: 빈 마스크라도 돌려줘 프리뷰가 멈추지 않게 한다.
            val empty = IntArray(width * height) { (0xFF shl 24) }
            return Output(
                bitmap = Bitmap.createBitmap(empty, width, height, Bitmap.Config.ARGB_8888),
                method = "없음",
                skyCoverage = 0f,
                subjectCoverage = 0f,
            )
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    /** Human-readable status for the panel ("MediaPipe · 인물 분리" 등). */
    fun describe(method: String): String = when (method) {
        MODEL -> "MediaPipe selfie_multiclass · 인물 분리"
        FALLBACK -> "색·위치 기반 폴백"
        else -> "마스크 없음"
    }

    private fun runSegmenter(bitmap: Bitmap, width: Int, height: Int): FloatArray? {
        val engine = ensureSegmenter() ?: return null
        var mpImage: MPImage? = null
        var result: com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult? = null
        return try {
            mpImage = BitmapImageBuilder(bitmap).build()
            result = engine.segment(mpImage)
            val masks = result.confidenceMasks().orElse(null) ?: return null
            val background = masks.getOrNull(0) ?: return null
            val confidence = readMask(background, width, height) ?: return null
            FloatArray(confidence.size) { (1f - confidence[it]).coerceIn(0f, 1f) }
        } catch (t: Throwable) {
            Log.w(TAG, "segmentation failed", t)
            lastError = t.message ?: t.javaClass.simpleName
            segmenterBroken = true
            null
        } finally {
            runCatching { result?.confidenceMasks()?.orElse(null)?.forEach { it.close() } }
            runCatching { mpImage?.close() }
        }
    }

    /** Reads one confidence mask as 0..1 floats, whatever container MediaPipe handed back. */
    private fun readMask(image: MPImage, width: Int, height: Int): FloatArray? {
        val imageWidth = image.width
        val imageHeight = image.height
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val format = image.containedImageProperties.firstOrNull()?.imageFormat ?: -1
        return try {
            if (format == MPImage.IMAGE_FORMAT_VEC32F1 || format == MPImage.IMAGE_FORMAT_VEC32F2) {
                val buffer = ByteBufferExtractor.extract(image).duplicate().order(ByteOrder.nativeOrder())
                val floats = buffer.asFloatBuffer()
                val raw = FloatArray(imageWidth * imageHeight)
                val read = minOf(raw.size, floats.remaining())
                floats.get(raw, 0, read)
                resample(raw, imageWidth, imageHeight, width, height)
            } else {
                val maskBitmap = BitmapExtractor.extract(image) ?: return null
                val pixels = IntArray(maskBitmap.width * maskBitmap.height)
                maskBitmap.getPixels(pixels, 0, maskBitmap.width, 0, 0, maskBitmap.width, maskBitmap.height)
                // 카테고리 마스크는 클래스 번호가 R,G,B에 실려 온다. 배경(0)이면 0, 아니면 1.
                val raw = FloatArray(pixels.size) { index ->
                    val argb = pixels[index]
                    val r = argb shr 16 and 0xFF
                    val g = argb shr 8 and 0xFF
                    val b = argb and 0xFF
                    if (r == 0 && g == 0 && b == 0) 0f else 1f
                }
                resample(raw, maskBitmap.width, maskBitmap.height, width, height)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mask read failed", t)
            null
        }
    }

    /** Model output is 256×256 while the photo is not; nearest-neighbour is enough before feathering. */
    private fun resample(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        if (srcW == dstW && srcH == dstH) return src
        val out = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = (y.toLong() * srcH / dstH).toInt().coerceIn(0, srcH - 1)
            for (x in 0 until dstW) {
                val sx = (x.toLong() * srcW / dstW).toInt().coerceIn(0, srcW - 1)
                out[y * dstW + x] = src[sy * srcW + sx]
            }
        }
        return out
    }

    private fun ensureSegmenter(): ImageSegmenter? {
        if (segmenterBroken) return null
        segmenter?.let { return it }
        return try {
            val base = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
            ImageSegmenter.createFromOptions(context, options).also { segmenter = it }
        } catch (t: Throwable) {
            Log.w(TAG, "segmentation model unavailable", t)
            lastError = t.message ?: t.javaClass.simpleName
            segmenterBroken = true
            null
        }
    }

    @Synchronized
    fun close() {
        runCatching { segmenter?.close() }
        segmenter = null
    }

    companion object {
        private const val TAG = "LumenMask"
        const val MODEL = "모델"
        const val FALLBACK = "폴백"
        private const val MODEL_ASSET = "models/selfie_multiclass_256x256.tflite"

        /** Long edge the mask is computed at: enough for a feathered texture, cheap on a phone. */
        private const val MAX_SIDE = 1024

        /** Feathering after the 256 px model output, in mask pixels. */
        private const val MODEL_FEATHER = 3
    }
}
