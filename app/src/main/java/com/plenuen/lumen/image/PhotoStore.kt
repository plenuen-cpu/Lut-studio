package com.plenuen.lumen.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Reads photos and writes new files. The source image is opened read-only and never written back:
 * every export is a new file in Pictures/Lumen.
 */
class PhotoStore(private val context: Context) {

    data class Loaded(val bitmap: Bitmap, val width: Int, val height: Int)

    /** Decodes a preview-sized bitmap (long edge <= [maxEdge]) with EXIF orientation applied. */
    fun loadPreview(uri: Uri, maxEdge: Int = 1600): Loaded? = load(uri, maxEdge)

    /**
     * Decodes at the photo's own resolution for export. Very large photos are capped by the caller
     * through the GL texture limit; this only guards against a decode that cannot fit in memory.
     */
    fun loadFull(uri: Uri, maxEdge: Int = MAX_EXPORT_EDGE): Loaded? = load(uri, maxEdge)

    private fun load(uri: Uri, maxEdge: Int): Loaded? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                if (width <= 0 || height <= 0) return@decodeBitmap
                val longEdge = maxOf(width, height)
                if (longEdge > maxEdge) {
                    val scale = maxEdge.toFloat() / longEdge.toFloat()
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
            val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
            }
            Loaded(argb, argb.width, argb.height)
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed for $uri", t)
            null
        }
    }

    /** Writes [bitmap] as a new JPEG in Pictures/Lumen and returns its uri. */
    fun saveExport(bitmap: Bitmap, quality: Int = 96): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "Lumen_$stamp"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumen")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
        }
        return try {
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    throw IllegalStateException("JPEG 인코딩 실패")
                }
            } ?: throw IllegalStateException("출력 스트림을 열 수 없습니다.")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "save failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "LumenPhoto"
        const val MAX_EXPORT_EDGE = 7000

        /** Downscale helper used when the device GL texture limit is smaller than the photo. */
        fun capForGl(bitmap: Bitmap, maxTexture: Int): Bitmap {
            val longEdge = maxOf(bitmap.width, bitmap.height)
            if (longEdge <= maxTexture) return bitmap
            val scale = maxTexture.toFloat() / longEdge.toFloat()
            val width = min(maxTexture, (bitmap.width * scale).toInt().coerceAtLeast(1))
            val height = min(maxTexture, (bitmap.height * scale).toInt().coerceAtLeast(1))
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
    }
}
