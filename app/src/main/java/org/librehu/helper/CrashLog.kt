package org.librehu.helper

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This app's own crashes and task errors, kept for the next start: a user who cannot take a logcat (crash on « export »
 * reported on 08/11/2026, Essgoo P3, Android 9) sees it in the Report tab and sends it with the next report
 * (`helper-crash.txt`); also readable with a file manager in Android/data/org.librehu.helper/files/crash.txt.
 */
object CrashLog {
    private const val TAG = "LibreHU-Helper"
    private const val FILE = "crash.txt"

    /** Saves any uncaught exception before Android's « app has stopped » (the previous handler still runs). */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(
        val app: Context,
        val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(
            t: Thread,
            e: Throwable,
        ) {
            runCatching { save(app, "crash in thread ${t.name}", e) }
            previous?.uncaughtException(t, e)
        }
    }

    /** Writes [e] with the app version and the device (also used for a task that failed with an Error). */
    fun save(
        context: Context,
        what: String,
        e: Throwable,
    ) {
        Log.e(TAG, what, e)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val text =
            buildString {
                appendLine("LibreHU Helper $version: $what")
                appendLine("date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())}")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE}), Android sdk ${Build.VERSION.SDK_INT}")
                appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}; process 64-bit: ${android.os.Process.is64Bit()}")
                appendLine("fingerprint: ${Build.FINGERPRINT}")
                appendLine()
                append(e.stackTraceToString())
            }
        files(context).forEach { runCatching { it.writeText(text) } }
    }

    /** The last saved crash, null when none. */
    fun last(context: Context): String? = files(context).firstOrNull { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        files(context).forEach { it.delete() }
    }

    private fun files(context: Context): List<File> = listOfNotNull(File(context.filesDir, FILE), context.getExternalFilesDir(null)?.let { File(it, FILE) })
}
