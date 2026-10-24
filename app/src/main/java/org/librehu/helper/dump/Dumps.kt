package org.librehu.helper.dump

import android.content.Context
import org.librehu.helper.device.Device
import org.librehu.helper.device.Scatter
import org.librehu.helper.device.VendorApps
import org.librehu.helper.report.Output
import org.librehu.helper.report.Report
import org.librehu.helper.root.Shell
import org.librehu.helper.root.Su
import java.io.File

/** Partition images and APK backups, each in its own zip (a FAT32 USB key takes files of 4 GB at most). */
object Dumps {
    /** A partition to dump: by its block device, or by offset in a raw device (scatter entries without a by-name). */
    data class Part(
        val name: String,
        val device: String,
        /** Offset in [device], 0 for a whole partition device. */
        val offset: Long,
        val size: Long,
        /** Why it is never dumped, null when it can be. */
        val excluded: String?,
    )

    /** Kernel, recovery, device tree and verified boot images (the report's option). */
    val BOOT_IMAGES = Regex("^(boot|recovery|dtbo|vbmeta|vendor_boot|init_boot|dtb|lk|logo)(_a|_b)?$")

    /**
     * Partitions from the by-name folder and, when given, a MediaTek scatter file: an entry of the scatter without a
     * by-name link is read from the raw device (EMMC_USER: mmcblk0, EMMC_BOOT1/2: mmcblk0boot0/1) at its address.
     */
    fun parts(scatter: Scatter.Info?): List<Part> {
        val byName = Device.partitions()
        val out = ArrayList<Part>()
        for (p in byName) out += Part(p.name, p.device, 0, p.size, excludedWhy(p.name))
        if (scatter != null) {
            val known = byName.map { it.name.lowercase() }.toSet()
            for (e in scatter.entries) {
                if (e.name.lowercase() in known || e.size <= 0) continue
                val raw =
                    when (e.region.uppercase()) {
                        "EMMC_BOOT1", "EMMC_BOOT_1" -> "/dev/block/mmcblk0boot0"
                        "EMMC_BOOT2", "EMMC_BOOT_2" -> "/dev/block/mmcblk0boot1"
                        "EMMC_USER", "" -> "/dev/block/mmcblk0"
                        else -> continue
                    }
                out += Part(e.name, raw, e.start, e.size, excludedWhy(e.name))
            }
        }
        return out.sortedBy { it.name }
    }

    private fun excludedWhy(name: String): String? {
        val n = name.lowercase().removeSuffix("_a").removeSuffix("_b")
        return when {
            n == "userdata" -> "user data"
            n in Device.EXCLUDED -> "data of this unit only (calibration, IMEI, MAC, keys)"
            else -> null
        }
    }

    /** Shell command writing [p] to stdout: the partition device whole, or a slice of a whole disk. */
    fun readCommand(p: Part): String {
        val wholeDisk = p.device.matches(Regex(".*/mmcblk\\d+(boot\\d)?$"))
        if (p.offset == 0L && !wholeDisk) return "cat '${p.device}'"
        val bs = if (p.offset % 4096 == 0L && p.size % 4096 == 0L) 4096 else 512
        return "dd if='${p.device}' bs=$bs skip=${p.offset / bs} count=${p.size / bs} 2>/dev/null"
    }

    /** Seconds allowed for [size] bytes (eMMC reads at 10 MB/s or more, a USB key writes at a few MB/s). */
    fun timeoutFor(size: Long): Long = maxOf(300L, size / 1_000_000L)

    /** Writes [p] as `<name>.img` into [r]. */
    fun image(
        r: Report,
        p: Part,
    ) {
        if (p.excluded != null) {
            r.skipped("${p.name}.img", p.excluded)
            return
        }
        if (Su.get() == null) {
            r.skipped("${p.name}.img", "no root")
            return
        }
        r.binary("${p.name}.img", readCommand(p), root = true, timeoutS = timeoutFor(p.size))
    }

    /**
     * Full dump into a new folder of [out]: one zip per partition (`<name>.zip` holding `<name>.img`), and
     * `info.zip` with the partition table, the scatter used, and the SHA-256 of each image (in each zip's manifest).
     */
    fun fullDump(
        context: Context,
        out: Output,
        parts: List<Part>,
        scatterText: String?,
        onStep: (String, Int, Int) -> Unit,
    ): String {
        val folder = out.folder(Output.stamp("fulldump"))
        val chosen = parts.filter { it.excluded == null }
        chosen.forEachIndexed { i, p ->
            onStep(p.name, i + 1, chosen.size)
            val (stream, _) = folder.create("${p.name}.zip")
            Report(stream, null) {}.use { r -> image(r, p) }
        }
        val (stream, _) = folder.create("info.zip")
        Report(stream, null) {}.use { r ->
            r.text("partitions.txt", parts.joinToString("\n") { "${it.name}\t${it.device}\toffset=${it.offset}\tsize=${it.size}\t${it.excluded ?: "dumped"}" })
            if (scatterText != null) r.text("scatter.txt", scatterText)
            r.command("android/getprop.txt", "getprop")
            r.command("android/partitions.txt", "cat /proc/partitions; ls -l ${Device.byNameDir() ?: "/dev/block"}", root = true)
        }
        return folder.where
    }

    /** The chosen maker's apps (base and split APKs) into one zip, with a list of what they are. */
    fun apks(
        context: Context,
        out: Output,
        apps: List<VendorApps.App>,
        onStep: (String, Int, Int) -> Unit,
    ): String {
        val name = Output.stamp("apks") + ".zip"
        val (stream, where) = out.create(name)
        Report(stream, null) {}.use { r ->
            r.text("apps.txt", apps.joinToString("\n") { "${it.pkg}\t${it.label}\t${it.version}\t${it.role.label}\tscore=${it.score}\t${it.reasons.joinToString()}\t${it.apk}" })
            apps.forEachIndexed { i, a ->
                onStep(a.label, i + 1, apps.size)
                (listOf(a.apk) + a.splits).forEachIndexed { k, path ->
                    val entry = "${a.pkg}/${if (k == 0) "base.apk" else File(path).name}"
                    val f = File(path)
                    if (f.canRead()) r.file(entry, f) else r.binary(entry, "cat '$path'", root = true, timeoutS = 300)
                }
                // The odex / vdex next to a system APK (pre-optimised ROMs ship the code there).
                val dir = File(a.apk).parent ?: return@forEachIndexed
                Shell.text("ls $dir/oat/*/* 2>/dev/null", root = true).lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { o ->
                    r.binary("${a.pkg}/oat/${File(File(o).parent ?: "").name}/${File(o).name}", "cat '$o'", root = true, timeoutS = 300)
                }
            }
        }
        return where
    }
}
