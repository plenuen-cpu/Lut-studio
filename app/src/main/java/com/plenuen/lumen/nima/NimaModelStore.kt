package com.plenuen.lumen.nima

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where the NIMA weights live on the device.
 *
 * The model is small enough to ship inside the APK, and when it is there the first use simply copies
 * it out of `assets/models`. If a build has to leave it out (the release host refuses an APK over
 * 66MB), the same file is fetched once from the project's own release page and kept in the app's
 * files directory, so the feature still works after the first download.
 *
 * Nothing about the photo leaves the phone either way: only the weights are downloaded.
 */
class NimaModelStore(private val context: Context) {

    fun modelFile(): File = File(directory(), FILE_NAME)

    fun isReady(): Boolean = modelFile().let { it.exists() && it.length() >= MIN_BYTES }

    /**
     * Makes sure the weights are on disk, copying them from the APK or downloading them once.
     *
     * Blocking (file copy or a 6.4MB download); call it off the main thread. Returns null when the
     * model cannot be obtained, and the caller then falls back to the rule-based auto pass.
     */
    fun ensure(onProgress: (Float) -> Unit = {}): File? {
        val target = modelFile()
        if (target.exists() && target.length() >= MIN_BYTES) return target
        target.parentFile?.mkdirs()
        if (copyFromAssets(target)) return target
        return download(target, onProgress)
    }

    private fun directory(): File = File(context.filesDir, "nima")

    private fun copyFromAssets(target: File): Boolean {
        val asset = runCatching { context.assets.open(ASSET_PATH) }.getOrNull() ?: return false
        val temp = File(target.parentFile, "$FILE_NAME.copy")
        return runCatching {
            asset.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            if (temp.length() < MIN_BYTES) {
                temp.delete()
                false
            } else {
                temp.renameTo(target)
                true
            }
        }.getOrDefault(false)
    }

    private fun download(target: File, onProgress: (Float) -> Unit): File? {
        val temp = File(target.parentFile, "$FILE_NAME.part")
        temp.delete()
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = connection!!.responseCode
            if (code !in 200..299) return@runCatching null
            val total = connection!!.contentLength
            connection!!.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (temp.length() < MIN_BYTES) {
                temp.delete()
                null
            } else {
                temp.renameTo(target)
                onProgress(1f)
                target
            }
        }.getOrNull().also { connection?.disconnect() }
    }

    companion object {
        const val ASSET_PATH = "models/nima_aesthetic.tflite"
        const val FILE_NAME = "nima_aesthetic.tflite"
        /** 6.4MB 모델 — 이보다 작으면 잘린 파일이다. */
        const val MIN_BYTES = 5_000_000L

        /** 빌드에 모델을 넣지 못했을 때 받아 오는 곳(이 앱의 릴리스 자산). */
        const val MODEL_URL =
            "https://github.com/plenuen-cpu/Lut-studio/releases/download/v2.2.0/nima_aesthetic.tflite"
    }
}
