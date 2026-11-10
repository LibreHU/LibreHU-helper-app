package org.librehu.helper.collect

import android.content.Context
import android.net.Uri
import android.os.Build
import org.librehu.helper.R
import org.librehu.helper.report.Report
import org.librehu.helper.root.Su

/**
 * LibreHU-service, when it is installed. What it says of itself, without root (its diagnostics provider, LibreHU-service
 * v198 and later): link, MCU, watchdog, CAN box, the stack trace of each of its threads, its last log lines and MCU
 * frames, its log file (kept across restarts), the standby journal (with the watchdog's reports) and the CAN export.
 * With root, what Android and the kernel say of its process: native stacks of its threads (`debuggerd -b`; Java stacks
 * with their locks too on Android 10 and later, `debuggerd -j`), where each thread waits in the kernel, the service's
 * record in the activity manager (restarts, crashes), Android's ANR traces of it and its dropbox entries. Read only.
 * For a frozen service: make the report while it is frozen, before a force stop.
 */
object ServiceDiag : Collector {
    override val id = "librehu-service"
    override val title = R.string.c_service
    override val summary = R.string.c_service_summary

    private const val PKG = "org.librehu.service"
    private const val PROVIDER = "content://org.librehu.service.diagnostics/"
    private const val DIR = "librehu-service"

    /** Items of the service's provider and their file in the report. */
    private val ITEMS =
        listOf(
            "report" to "report.txt",
            "service-log" to "service-log.txt",
            "standby-journal" to "standby-journal.txt",
            "can" to "can.txt",
        )

    override fun collect(
        context: Context,
        r: Report,
    ) {
        val installed = runCatching { context.packageManager.getPackageInfo(PKG, 0) }.isSuccess
        if (!installed) {
            r.skipped(DIR, "LibreHU-service is not installed")
            return
        }
        for ((item, file) in ITEMS) {
            r.stream("$DIR/$file", timeoutS = 60) { context.contentResolver.openInputStream(Uri.parse(PROVIDER + item)) }
        }
        if (Su.get() == null) {
            r.skipped("$DIR/threads-native.txt", "root needed: stacks of the service's threads seen from outside, ANR traces, dropbox")
            return
        }
        val pid = "pid=\$(pidof $PKG) || { echo 'LibreHU-service is not running'; exit 1; }"
        r.command("$DIR/threads-native.txt", "$pid; debuggerd -b \$pid", root = true, timeoutS = 60)
        if (Build.VERSION.SDK_INT >= 29) {
            r.command("$DIR/threads-java.txt", "$pid; debuggerd -j \$pid", root = true, timeoutS = 60)
        } else {
            r.skipped("$DIR/threads-java.txt", "Android 9: debuggerd has no -j; the Java stacks are in report.txt (from the service)")
        }
        r.command(
            "$DIR/threads-kernel.txt",
            "$pid; ps -T -p \$pid; echo; for t in /proc/\$pid/task/*; do echo \"\$(cat \$t/comm 2>/dev/null): \$(cat \$t/wchan 2>/dev/null)\"; done",
            root = true,
        )
        r.command("$DIR/activity.txt", "dumpsys activity services $PKG; dumpsys activity processes $PKG", root = true, timeoutS = 30)
        r.command(
            "$DIR/anr.txt",
            "ls -lt /data/anr 2>&1; n=0; for f in \$(ls -t /data/anr/* 2>/dev/null); do " +
                "grep -q '$PKG' \$f 2>/dev/null || continue; echo; echo \"== \$f\"; head -n 3000 \$f; n=\$((n+1)); [ \$n -ge 2 ] && break; done",
            root = true,
            timeoutS = 60,
        )
        // One tag per call: dumpsys dropbox keeps an entry only if it matches every argument.
        r.command(
            "$DIR/dropbox.txt",
            "for t in system_app_anr system_app_crash data_app_anr data_app_crash; do echo \"== \$t\"; " +
                "dumpsys dropbox --print \$t 2>/dev/null | grep -A 60 '$PKG' | head -n 1500; done",
            root = true,
            timeoutS = 60,
        )
    }
}
