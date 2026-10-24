package org.librehu.helper.compat

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import org.librehu.helper.R
import org.librehu.helper.device.Device
import org.librehu.helper.device.VendorApps
import org.librehu.helper.root.Shell
import org.librehu.helper.root.Su

/**
 * How well this head unit suits LibreHU, check by check, each with what may go wrong and what to try. Based on what
 * the LibreHU apps need (minSdk 28, arm64 for LibreHU-service, root for its hardware functions, the MCU on a serial
 * port that nobody else holds) and on what the UJC201 port taught (fake Android version, maker's service holding
 * the MCU port, verified boot).
 */
object Compatibility {
    enum class Level { OK, INFO, WARN, PROBLEM }

    data class Check(
        val level: Level,
        val title: String,
        val detail: String,
        /** Basic troubleshooting, empty when nothing to do. */
        val fix: String = "",
    )

    fun run(context: Context): List<Check> {
        val c = ArrayList<Check>()
        val s = { id: Int, args: Array<out Any> -> context.getString(id, *args) }

        fun add(
            level: Level,
            title: Int,
            detail: String,
            fix: String = "",
        ) {
            c += Check(level, context.getString(title), detail, fix)
        }

        // Android version: the LibreHU apps need Android 9 (SDK 28).
        val sdk = Build.VERSION.SDK_INT
        if (sdk >= 28) {
            add(Level.OK, R.string.k_android, s(R.string.k_android_ok, arrayOf(sdk, Build.VERSION.RELEASE)))
        } else {
            add(Level.PROBLEM, R.string.k_android, s(R.string.k_android_old, arrayOf(sdk)), context.getString(R.string.k_android_fix))
        }

        // ABI: LibreHU-service ships arm64-v8a native code only.
        val abis = Build.SUPPORTED_ABIS.toList()
        when {
            "arm64-v8a" in abis -> add(Level.OK, R.string.k_abi, abis.joinToString())
            else -> add(Level.PROBLEM, R.string.k_abi, s(R.string.k_abi_bad, arrayOf(abis.joinToString())), context.getString(R.string.k_abi_fix))
        }

        // SoC family.
        val platform = Device.platform()
        val pl = "${platform.label} (${Build.HARDWARE}, ${Device.prop("ro.board.platform")})"
        when (platform) {
            Device.Platform.AUTOCHIPS -> add(Level.OK, R.string.k_soc, s(R.string.k_soc_ac, arrayOf(pl)))
            Device.Platform.MEDIATEK -> add(Level.INFO, R.string.k_soc, s(R.string.k_soc_mtk, arrayOf(pl)), context.getString(R.string.k_soc_mtk_fix))
            else -> add(Level.WARN, R.string.k_soc, s(R.string.k_soc_other, arrayOf(pl)), context.getString(R.string.k_soc_other_fix))
        }

        // Root.
        val root = Su.get()
        if (root != null) {
            add(Level.OK, R.string.k_root, root.toString())
        } else {
            add(Level.WARN, R.string.k_root, context.getString(R.string.k_root_none), context.getString(R.string.k_root_fix))
        }

        // The maker's apps holding the hardware: the MCU port must be free for LibreHU-service.
        val ports = Device.serialPorts()
        val held = ports.filter { it.holders.isNotEmpty() }
        val vendor = VendorApps.find(context, held.flatMap { p -> p.holders.map { it.substringAfter(' ') } }.toSet())
        val services = vendor.filter { it.role == VendorApps.Role.MCU_SERVICE || it.role == VendorApps.Role.CANBUS }
        if (services.isNotEmpty()) {
            add(
                Level.WARN,
                R.string.k_vendor,
                s(R.string.k_vendor_found, arrayOf(services.joinToString { "${it.label} (${it.pkg})" })),
                context.getString(R.string.k_vendor_fix),
            )
        } else {
            add(Level.INFO, R.string.k_vendor, s(R.string.k_vendor_none, arrayOf(vendor.size)))
        }

        // Serial ports (MCU, CAN box).
        when {
            ports.isEmpty() -> add(Level.WARN, R.string.k_serial, context.getString(R.string.k_serial_none), context.getString(R.string.k_serial_none_fix))
            held.isNotEmpty() ->
                add(
                    Level.INFO,
                    R.string.k_serial,
                    s(R.string.k_serial_held, arrayOf(held.joinToString { "${it.path} ← ${it.holders.joinToString()}" })),
                    context.getString(R.string.k_serial_held_fix),
                )
            else -> add(Level.INFO, R.string.k_serial, s(R.string.k_serial_list, arrayOf(ports.joinToString { it.path })))
        }

        // LibreHU already installed?
        val pm = context.packageManager
        val installed = listOf("org.librehu.service", "org.librehu.launcher", "org.librehu.ota").filter { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
        add(Level.INFO, R.string.k_librehu, if (installed.isEmpty()) context.getString(R.string.k_librehu_none) else installed.joinToString())

        // Fake Android version (Jancar says 12 on Android 9): apps asking the version get it wrong.
        val expected = mapOf(28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12", 33 to "13", 34 to "14", 35 to "15", 36 to "16")[sdk]
        if (expected != null && Build.VERSION.RELEASE.substringBefore('.') != expected) {
            add(Level.INFO, R.string.k_fake, s(R.string.k_fake_detail, arrayOf(Build.VERSION.RELEASE, expected)), context.getString(R.string.k_fake_fix))
        }

        // Verified boot / dm-verity: modifying /system needs the user to disable it first.
        val state = Device.prop("ro.boot.verifiedbootstate").ifEmpty { "?" }
        val verity = Device.prop("ro.boot.veritymode").ifEmpty { "?" }
        add(
            if (state == "green") Level.INFO else Level.OK,
            R.string.k_avb,
            s(R.string.k_avb_detail, arrayOf(state, verity)),
            if (state == "green") context.getString(R.string.k_avb_fix) else "",
        )

        // SELinux.
        val selinux = Shell.text("getenforce").ifEmpty { "?" }
        add(Level.INFO, R.string.k_selinux, selinux)

        // Screen: LibreHU's apps are laid out for 1280×720 at 160 dpi (the UJC201) and larger.
        val m = DisplayMetrics()

        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealMetrics(m)
        val w = maxOf(m.widthPixels, m.heightPixels)
        val h = minOf(m.widthPixels, m.heightPixels)
        val dp = w * 160 / maxOf(m.densityDpi, 1)
        add(
            if (dp >= 960) Level.OK else Level.WARN,
            R.string.k_screen,
            s(R.string.k_screen_detail, arrayOf(w, h, m.densityDpi, dp)),
            if (dp >= 960) "" else context.getString(R.string.k_screen_fix),
        )

        // Storage for the dumps.
        val free = runCatching { StatFs(context.getExternalFilesDir(null)?.path ?: "/sdcard").availableBytes }.getOrDefault(-1L)
        add(if (free in 0 until 2_000_000_000L) Level.WARN else Level.INFO, R.string.k_storage, s(R.string.k_storage_detail, arrayOf(free / 1_000_000)))

        // Partitions layout: A/B, dynamic partitions, by-name folder.
        val ab = Device.prop("ro.build.ab_update") == "true" || Device.prop("ro.boot.slot_suffix").isNotEmpty()
        val dynamic = Device.prop("ro.boot.dynamic_partitions") == "true"
        add(Level.INFO, R.string.k_layout, s(R.string.k_layout_detail, arrayOf(if (ab) "A/B" else "A", if (dynamic) "super" else "-", Device.byNameDir() ?: "-")))

        return c.sortedBy { -it.level.ordinal }
    }
}
