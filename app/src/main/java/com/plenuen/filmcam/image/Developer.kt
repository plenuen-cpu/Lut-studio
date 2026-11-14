package com.plenuen.filmcam.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix

/**
 * Turns a captured JPEG into the finished photograph: orientation, aspect crop, then the LUT.
 */
object Developer {

    private const val MAX_PIXELS = 12_000_000

    class DevelopException(message: String) : Exception(message)

    fun develop(
        jpeg: ByteArray,
        rotationDegrees: Int,
        mirror: Boolean,
        aspectRatio: Float,
        lut: ByteArray,
        mix: Float,
    ): Bitmap {
        var working = decode(jpeg)
        try {
            val matrix = Matrix()
            if (rotationDegrees != 0) matrix.postRotate(rotationDegrees.toFloat())
            if (mirror) matrix.postScale(-1f, 1f)
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(working, 0, 0, working.width, working.height, matrix, true)
                if (rotated != working) {
                    working.recycle()
                    working = rotated
                }
            }
            working = cropToAspect(working, aspectRatio)

            val width = working.width
            val height = working.height
            val pixels = IntArray(width * height)
            working.getPixels(pixels, 0, width, 0, 0, width, height)
            LutProcessor.apply(pixels, lut, mix)
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, width, 0, 0, width, height)
            return out
        } finally {
            working.recycle()
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw DevelopException("촬영한 사진을 읽을 수 없습니다.")
        }
        var sampleSize = 1
        while ((bounds.outWidth / sampleSize).toLong() * (bounds.outHeight / sampleSize) > MAX_PIXELS) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw DevelopException("촬영한 사진을 읽을 수 없습니다.")
    }

    private fun cropToAspect(source: Bitmap, aspectRatio: Float): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source
        val current = width.toFloat() / height.toFloat()
        var targetWidth = width
        var targetHeight = height
        if (current > aspectRatio) {
            targetWidth = Math.round(height * aspectRatio)
        } else if (current < aspectRatio) {
            targetHeight = Math.round(width / aspectRatio)
        }
        targetWidth = targetWidth.coerceIn(1, width)
        targetHeight = targetHeight.coerceIn(1, height)
        if (targetWidth == width && targetHeight == height) return source
        val cropped = Bitmap.createBitmap(
            source,
            (width - targetWidth) / 2,
            (height - targetHeight) / 2,
            targetWidth,
            targetHeight,
        )
        if (cropped != source) source.recycle()
        return cropped
    }
}
