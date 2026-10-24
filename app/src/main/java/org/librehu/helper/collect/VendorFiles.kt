package org.librehu.helper.collect

import android.content.Context
import org.librehu.helper.R
import org.librehu.helper.report.Report
import org.librehu.helper.root.Shell

/**
 * The head unit maker's configuration: listing of its folders and copy of their small text files (CAN box, key,
 * touch, audio, car model settings: /jancar/config on the UJC201), plus the audio policy and media codecs files that
 * a port needs. Text only (no binaries) and at most [MAX_FILE] per file.
 */
object VendorFiles : Collector {
    override val id = "vendor"
    override val title = R.string.c_vendor
    override val summary = R.string.c_vendor_summary

    private val DIRS = listOf("/jancar", "/oem", "/custom", "/cust", "/preload", "/vendor/etc/car", "/system/etc/car")
    private val CONFIGS =
        listOf(
            "/vendor/etc/audio_policy_configuration.xml", "/system/etc/audio_policy_configuration.xml",
            "/vendor/etc/audio_policy.conf", "/system/etc/audio_policy.conf", "/vendor/etc/mixer_paths.xml",
            "/vendor/etc/media_codecs.xml", "/vendor/etc/media_profiles_V1_0.xml", "/system/build.prop",
            "/vendor/build.prop", "/odm/etc/build.prop", "/vendor/etc/init/hw/init.\$(getprop ro.hardware).rc",
        )
    private const val MAX_FILE = 256 * 1024
    private const val MAX_FILES = 400

    override fun collect(
        context: Context,
        r: Report,
    ) {
        val existing = DIRS.filter { Shell.text("[ -d $it ] && echo y", root = true) == "y" }
        r.command("vendor/listing.txt", existing.joinToString("; ") { "echo \"== $it\"; ls -lR $it 2>&1 | head -n 2000" }.ifEmpty { "echo none" }, root = true, timeoutS = 120)
        val files =
            existing
                .flatMap { d ->
                    Shell.text("find $d -type f -size -${MAX_FILE / 1024}k 2>/dev/null", root = true, timeoutS = 60).lines()
                }.map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_FILES)
        for (f in files) {
            // Text files only: grep -I treats a file with NUL bytes as binary.
            if (Shell.text("grep -Iq . '$f' && echo t", root = true) != "t") continue
            r.command("vendor/files$f", "cat '$f'", root = true)
        }
        for (c in CONFIGS) {
            val path = Shell.text("echo $c").trim()
            if (Shell.text("[ -f '$path' ] && echo y", root = true) == "y") r.command("vendor/config$path", "cat '$path'", root = true)
        }
    }
}
