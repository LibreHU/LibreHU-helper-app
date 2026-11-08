package org.librehu.helper.collect

import android.content.Context
import org.librehu.helper.R
import org.librehu.helper.report.Report
import org.librehu.helper.root.Su

/** Logs: logcat (every buffer; the app's own lines only without root), kernel log, previous boot's, crashes. */
object Logs : Collector {
    override val id = "logs"
    override val title = R.string.c_logs
    override val summary = R.string.c_logs_summary

    override fun collect(
        context: Context,
        r: Report,
    ) {
        r.command("logs/logcat.txt", "logcat -b all -d -v threadtime 2>/dev/null || logcat -d -v threadtime", root = true, timeoutS = 180)
        r.command("logs/logcat-crash.txt", "logcat -b crash -d -v threadtime", root = true)
        if (Su.get() != null) {
            r.command("logs/dmesg.txt", "dmesg 2>/dev/null || cat /proc/kmsg", root = true, timeoutS = 60)
        } else {
            // As an app, Android's seccomp filter kills dmesg (syslog call, SIGSYS): a tombstone and nothing read.
            r.skipped("logs/dmesg.txt", "root needed: Android does not let an app read the kernel log")
        }
        r.command(
            "logs/last-boot.txt",
            "for f in /sys/fs/pstore/* /proc/last_kmsg /data/misc/recovery/last_kmsg*; do [ -f \$f ] && { echo \"== \$f\"; cat \$f; }; done",
            root = true,
        )
        r.command(
            "logs/tombstones.txt",
            "ls -l /data/tombstones /data/anr 2>&1; for f in \$(ls -t /data/tombstones/tombstone_* 2>/dev/null | head -n 3); " +
                "do echo; echo \"== \$f\"; head -n 300 \$f; done",
            root = true,
        )
        r.command("logs/boot-reasons.txt", "getprop | grep -iE 'boot|reason|shutdown'", root = true)
    }
}
