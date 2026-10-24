package org.librehu.helper.collect

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import org.librehu.helper.R
import org.librehu.helper.device.VendorApps
import org.librehu.helper.report.Report
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android and ROM: build identity (with the real Android version from the SDK level: some ROMs fake
 * `ro.build.version.release`, Jancar's says 12 on Android 9), every property, kernel line, partitions, mounts,
 * services and HALs, processes, and the head unit maker's apps (device/VendorApps.kt).
 */
object SystemInfo : Collector {
    override val id = "system"
    override val title = R.string.c_system
    override val summary = R.string.c_system_summary

    /** Android version of each SDK level (developer.android.com/tools/releases/platforms). */
    private val RELEASES =
        mapOf(
            21 to "5.0", 22 to "5.1", 23 to "6", 24 to "7.0", 25 to "7.1", 26 to "8.0", 27 to "8.1", 28 to "9",
            29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16",
        )

    override fun collect(
        context: Context,
        r: Report,
    ) {
        r.text("android/build.txt", build(context))
        r.command("android/getprop.txt", "getprop")
        r.command("android/kernel.txt", "uname -a; echo; cat /proc/version; echo; cat /proc/cmdline", root = true)
        r.command("android/cpuinfo.txt", "cat /proc/cpuinfo")
        r.command("android/meminfo.txt", "cat /proc/meminfo; echo; cat /proc/swaps 2>/dev/null")
        r.command("android/mounts.txt", "cat /proc/mounts", root = true)
        r.command("android/df.txt", "df -h 2>/dev/null || df")
        r.command(
            "android/partitions.txt",
            "cat /proc/partitions; echo; for d in /dev/block/by-name /dev/block/bootdevice/by-name " +
                "/dev/block/platform/*/by-name /dev/block/platform/*/*/by-name; do [ -d \$d ] && { echo \"== \$d\"; ls -l \$d; }; done",
            root = true,
        )
        r.command("android/fstab.txt", "for f in /vendor/etc/fstab.* /system/etc/fstab.* /odm/etc/fstab.* /fstab.* /etc/fstab*; do [ -f \$f ] && { echo \"== \$f\"; cat \$f; }; done", root = true)
        r.command("android/selinux.txt", "getenforce; echo; id; echo; ls -Z /dev/ttyS* /dev/ttyMT* /dev/ttyHS* 2>/dev/null")
        r.command("android/services.txt", "service list", root = true)
        r.command("android/hal.txt", "lshal 2>/dev/null || echo 'lshal: none'", root = true)
        r.command("android/processes.txt", "ps -A -Z 2>/dev/null || ps -A 2>/dev/null || ps", root = true)
        r.command("android/init-rc.txt", "ls -l /system/etc/init /vendor/etc/init /odm/etc/init / 2>/dev/null; echo; getprop | grep -E 'init.svc|ro.boot'", root = true)
        val packages = installed(context)
        r.text("android/packages.txt", packages.joinToString("\n") { describe(context, it) })
        r.text(
            "android/vendor-apps.txt",
            VendorApps.find(context).joinToString("\n") {
                "${it.pkg} | ${it.label} | ${it.version} | ${it.role.label}${it.role.librehu?.let { l -> " -> $l" } ?: ""} | score ${it.score}: ${it.reasons.joinToString()} | ${it.apk}${if (it.enabled) "" else " | DISABLED"}"
            },
        )
    }

    fun installed(context: Context): List<PackageInfo> =
        runCatching { context.packageManager.getInstalledPackages(0) }.getOrDefault(emptyList()).sortedBy { it.packageName }

    private fun describe(
        context: Context,
        p: PackageInfo,
    ): String {
        val ai = p.applicationInfo
        val system = ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updated = ai != null && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

        @Suppress("DEPRECATION")
        val installer = runCatching { context.packageManager.getInstallerPackageName(p.packageName) }.getOrNull()
        return listOf(
            p.packageName,
            "v${p.versionName} (${p.longVersionCode})",
            if (system) (if (updated) "system,updated" else "system") else "user",
            "uid=${ai?.uid}",
            "target=${ai?.targetSdkVersion}",
            ai?.sourceDir.orEmpty(),
            "installer=${installer ?: "-"}",
        ).joinToString(" | ")
    }

    private fun build(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        val expected = RELEASES[sdk]
        val release = Build.VERSION.RELEASE
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealMetrics(metrics)
        val fields =
            listOf(
                "date" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date()),
                "manufacturer" to Build.MANUFACTURER,
                "brand" to Build.BRAND,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "product" to Build.PRODUCT,
                "board" to Build.BOARD,
                "hardware" to Build.HARDWARE,
                "soc" to if (sdk >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "-",
                "abis" to Build.SUPPORTED_ABIS.joinToString(),
                "sdk" to sdk.toString(),
                "release (declared)" to release,
                "release (from sdk)" to (expected ?: "?"),
                "release faked" to if (expected != null && release.substringBefore('.') != expected.substringBefore('.').removeSuffix("L")) "YES" else "no",
                "security patch" to Build.VERSION.SECURITY_PATCH,
                "incremental" to Build.VERSION.INCREMENTAL,
                "display" to Build.DISPLAY,
                "id" to Build.ID,
                "type" to Build.TYPE,
                "tags" to Build.TAGS,
                "fingerprint" to Build.FINGERPRINT,
                "bootloader" to Build.BOOTLOADER,
                "radio" to (Build.getRadioVersion() ?: "-"),
                "screen" to "${metrics.widthPixels}x${metrics.heightPixels} ${metrics.densityDpi} dpi",
                "vm" to (System.getProperty("java.vm.version") ?: "-"),
            )
        return fields.joinToString("\n") { (k, v) -> "$k: $v" }
    }
}
