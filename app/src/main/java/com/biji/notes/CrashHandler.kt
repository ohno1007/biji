package com.biji.notes

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches uncaught exceptions on every thread, writes a timestamped
 * stack trace to `crashes.log` under `filesDir`, then delegates to the
 * previously-installed handler so the OS still terminates the process
 * normally. The Settings screen reads the log back via [readCrashes].
 */
class CrashHandler(
    private val context: Context,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    private val logFile: File
        get() = File(context.filesDir, "crashes.log")

    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching {
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("==== ${nowStamp()} thread=${t.name} ====")
                e.printStackTrace(pw)
                pw.println()
            }
            // Keep ~64 KB of history.
            val existing = if (logFile.exists()) logFile.readText() else ""
            val combined = (sw.toString() + existing).take(64 * 1024)
            logFile.writeText(combined, Charsets.UTF_8)
        }
        previous?.uncaughtException(t, e)
    }

    companion object {
        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context, previous))
        }

        fun readCrashes(context: Context): String =
            runCatching {
                File(context.filesDir, "crashes.log").takeIf { it.exists() }?.readText()
            }.getOrNull().orEmpty()

        fun clearCrashes(context: Context) {
            runCatching { File(context.filesDir, "crashes.log").delete() }
        }

        private fun nowStamp(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }
}
