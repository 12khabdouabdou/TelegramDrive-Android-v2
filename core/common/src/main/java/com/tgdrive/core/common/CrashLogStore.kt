package com.tgdrive.core.common

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Lightweight store for the most recent uncaught exception stacktrace so
 * users can inspect or copy crash details from Settings -> About without
 * swallowing or suppressing fatal process termination.
 */
object CrashLogStore {
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 16 * 1024

    fun save(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("Timestamp: ${Instant.now()}")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
                pw.println("Thread: ${thread.name}")
                pw.println()
                throwable.printStackTrace(pw)
            }
            val text = sw.toString().take(MAX_CHARS)
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(text)
        } catch (_: Throwable) {
            // Best-effort diagnostic logging only; never mask the original exception.
        }
    }

    fun recordNonFatal(context: Context, tag: String, throwable: Throwable) {
        try {
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("Timestamp: ${Instant.now()}")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
                pw.println("NonFatal: $tag")
                pw.println()
                throwable.printStackTrace(pw)
            }
            val text = sw.toString().take(MAX_CHARS)
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(text)
        } catch (_: Throwable) {
            // Best-effort diagnostic logging only; never mask the original exception.
        }
    }

    fun read(context: Context): String? =
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.isFile && file.length() > 0L) file.readText() else null
        } catch (_: Throwable) {
            null
        }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {}
    }
}
