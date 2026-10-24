package org.librehu.helper.hw

import android.content.Context
import org.librehu.helper.root.Shell
import org.librehu.helper.root.Su
import java.io.File

/** Runs [HwProbe] commands: as root through app_process when root is available, else in the app. */
object Hw {
    @Volatile
    private var loaded = false

    fun run(
        context: Context,
        args: List<String>,
        timeoutS: Long = 60,
    ): List<String> {
        val ai = context.applicationInfo
        val lib = File(ai.nativeLibraryDir, "libhwprobe.so").path
        if (Su.get() == null) {
            if (!loaded) {
                runCatching { System.loadLibrary("hwprobe") }.onFailure { return listOf("ERR load ${it.message}") }
                loaded = true
            }
            return runCatching { HwProbe.exec(args) }.getOrElse { listOf("ERR ${it.message}") }
        }
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "") + "'" }
        val cmd = "CLASSPATH='${ai.sourceDir}' app_process /system/bin ${HwProbe::class.java.name} '$lib' $quoted"
        val lines = ArrayList<String>()
        val r = Shell.lines(cmd, root = true, timeoutS = timeoutS) { lines += it }
        if (lines.isEmpty() && !r.ok) lines += "ERR exit ${r.exit}${if (r.timedOut) " timeout" else ""} ${r.error.orEmpty()}"
        return lines
    }
}
