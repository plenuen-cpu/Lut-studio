package com.plenuen.lumen.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the downloaded model files. The APK never contains them; the first request that needs the
 * model offers a download with progress, and the file lives in app-private storage afterwards.
 */
class LlmModelStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "llm").apply { if (!exists()) mkdirs() }

    fun fileFor(model: LlmModel): File = File(dir, model.fileName)

    fun downloadedBytes(model: LlmModel): Long {
        val file = fileFor(model)
        return if (file.exists()) file.length() else 0L
    }

    fun isDownloaded(model: LlmModel): Boolean {
        val expected = model.sizeMb.toLong() * 1024L * 1024L
        val actual = downloadedBytes(model)
        // 모델 번들 크기는 반올림되어 있으니 92% 이상이면 완료로 본다.
        return actual > 0 && actual >= (expected * 92) / 100
    }

    fun downloadedModels(): List<LlmModel> = LlmModels.all.filter { isDownloaded(it) }

    suspend fun download(model: LlmModel, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = fileFor(model)
            if (isDownloaded(model)) {
                onProgress(1f)
                return@withContext target
            }
            val part = File(dir, model.fileName + ".part")
            if (part.exists()) part.delete()
            val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LumenAndroid/2.0")
            }
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    error("모델 내려받기 실패 (HTTP ${connection.responseCode})")
                }
                val total = connection.contentLengthLong.takeIf { it > 0 }
                    ?: (model.sizeMb.toLong() * 1024L * 1024L)
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (written - lastReport > 4L * 1024 * 1024) {
                                lastReport = written
                                onProgress((written.toFloat() / total).coerceIn(0f, 0.99f))
                            }
                        }
                        output.flush()
                    }
                }
                if (part.length() < (model.sizeMb.toLong() * 1024L * 1024L * 80) / 100) {
                    part.delete()
                    error("내려받은 파일이 너무 작습니다 (${part.length()} bytes)")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                onProgress(1f)
                target
            } finally {
                connection.disconnect()
            }
        }

    fun delete(model: LlmModel) {
        fileFor(model).delete()
        File(dir, model.fileName + ".part").delete()
    }

    fun totalBytes(): Long = LlmModels.all.sumOf { downloadedBytes(it) }
}
