package com.plenuen.filmcam.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves developed photographs to the phone gallery under Pictures/FilmCam.
 */
object PhotoStore {

    const val ALBUM = "FilmCam"
    private const val JPEG_QUALITY = 95

    fun newFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "FilmCam_$stamp.jpg"
    }

    fun save(context: Context, bitmap: Bitmap): Uri {
        val name = newFileName()
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("갤러리에 사진을 저장할 수 없습니다.")
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                        throw IllegalStateException("사진을 저장할 수 없습니다.")
                    }
                } ?: throw IllegalStateException("사진을 저장할 수 없습니다.")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM,
            )
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("Pictures/FilmCam 폴더를 만들 수 없습니다.")
            }
            val file = File(dir, name)
            FileOutputStream(file).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    throw IllegalStateException("사진을 저장할 수 없습니다.")
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: Uri.fromFile(file)
        }
    }
}
