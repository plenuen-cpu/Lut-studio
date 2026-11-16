package com.plenuen.lumen

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort diary for anything that goes wrong at runtime.
 *
 * A hobby build has no crash reporting, so before this the only trace of a failure was the user
 * saying "앱이 꺼져요" — with no class, no line, no thread. Every guarded path and the process-wide
 * uncaught handler now append to one small text file, and the app shows the last entry on request,
 * so the next device report can name the exception instead of guessing at one.
 *
 * Nothing here may throw: it runs while something else is already broken.
 */
object LumenDiagnostics {

    private const val TAG = "LumenFailure"
    private const val FILE_NAME = "lumen-failure.log"
    private const val MAX_BYTES = 64 * 1024L
    private const val MAX_STACK_LINES = 24

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    /** Points the recorder at the app's private files directory (called once at start-up). */
    fun install(context: Context) {
        logFile = File(context.applicationContext.filesDir, FILE_NAME)
    }

    /** Appends one failure; the reason, thread and first frames are what a report needs. */
    fun record(where: String, error: Throwable) {
        val text = buildString {
            append(stamp.format(Date()))
            append(" [").append(Thread.currentThread().name).append("] ")
            append(where)
            append(": ").append(error.javaClass.name)
            error.message?.let { append(": ").append(it.take(300)) }
            append('\n')
            error.stackTrace.take(MAX_STACK_LINES).forEach { frame ->
                append("    at ").append(frame.toString()).append('\n')
            }
            var cause = error.cause
            var depth = 0
            while (cause != null && cause !== error && depth++ < 3) {
                append("  caused by ").append(cause.javaClass.name)
                cause.message?.let { append(": ").append(it.take(200)) }
                append('\n')
                cause = cause.cause
            }
            append('\n')
        }
        Log.e(TAG, "$where: ${error.javaClass.simpleName}: ${error.message}", error)
        runCatching {
            val file = logFile ?: return@runCatching
            if (file.length() > MAX_BYTES) file.writeText("")
            file.appendText(text)
        }
    }

    /** Everything recorded so far, or null when the app has not failed. */
    fun read(context: Context): String? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) null else file.readText()
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /**
     * Makes the next unexpected exception leave a trace and still crash (a silent zombie is worse
     * than a restart). Installed once from the activity.
     */
    fun installProcessHandler(context: Context) {
        install(context)
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(app, previous))
    }

    private class Recorder(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            record("uncaught/${thread.name}", error)
            if (previous != null) previous.uncaughtException(thread, error)
        }
    }
}
