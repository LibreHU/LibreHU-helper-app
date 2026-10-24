package org.librehu.helper.device

import android.os.Build
import org.librehu.helper.root.Shell
import org.librehu.helper.root.Su
import java.io.File

/** What the head unit is made of, read from properties, /proc, /sys and /dev (root when available). */
object Device {
    enum class Platform(
        val label: String,
    ) {
        AUTOCHIPS("Autochips (MediaTek based)"),
        MEDIATEK("MediaTek"),
        QUALCOMM("Qualcomm"),
        UNISOC("Unisoc / Spreadtrum"),
        ROCKCHIP("Rockchip"),
        ALLWINNER("Allwinner"),
        OTHER("other"),
        ;

        /** MediaTek layout: a scatter file describes the partitions (SP Flash Tool). */
        val mediatek get() = this == AUTOCHIPS || this == MEDIATEK
    }

    data class Partition(
        val name: String,
        /** Block device (`/dev/block/mmcblk0p23`), resolved from the by-name link. */
        val device: String,
        val size: Long,
    )

    data class SerialPort(
        val path: String,
        val readable: Boolean,
        /** Processes holding it open (root only), "pid name". */
        val holders: List<String>,
    )

    /** Partitions holding data of this unit only (calibration, IMEI, MAC, keys) or the user's: never dumped. */
    val EXCLUDED =
        setOf(
            "nvram", "nvdata", "nvcfg", "proinfo", "persist", "metazone", "protect1", "protect2", "protect_f", "protect_s",
            "otp", "frp", "userdata",
        )

    private val BY_NAME =
        listOf(
            "/dev/block/by-name", "/dev/block/bootdevice/by-name", "/dev/block/platform/bootdevice/by-name",
        )

    fun prop(name: String): String = Shell.text("getprop $name").trim()

    fun platform(): Platform {
        val text =
            listOf(Build.HARDWARE, Build.BOARD, prop("ro.board.platform"), prop("ro.hardware"), prop("ro.product.board"), cpuHardware())
                .joinToString(" ")
                .lowercase()
        return when {
            Regex("\\bac8\\d{3}|autochips|\\batc\\b").containsMatchIn(text) || File("/proc/atc").exists() -> Platform.AUTOCHIPS
            Regex("\\bmt\\d{4}|mediatek").containsMatchIn(text) -> Platform.MEDIATEK
            Regex("qcom|msm\\d|sdm\\d|sm\\d{4}|qualcomm").containsMatchIn(text) -> Platform.QUALCOMM
            Regex("\\bums\\d|\\bsc\\d{4}|unisoc|sprd|spreadtrum").containsMatchIn(text) -> Platform.UNISOC
            Regex("\\brk\\d{4}|rockchip").containsMatchIn(text) -> Platform.ROCKCHIP
            Regex("\\bsun\\d+i|allwinner|\\bt[3-8]\\b").containsMatchIn(text) -> Platform.ALLWINNER
            else -> Platform.OTHER
        }
    }

    private fun cpuHardware(): String =
        runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Hardware") }?.substringAfter(':')?.trim() }
            .getOrNull()
            .orEmpty()

    /** The by-name folder of the boot storage, null when none is found. */
    fun byNameDir(): String? {
        BY_NAME.firstOrNull { File(it).isDirectory }?.let { return it }
        val found = Shell.text("ls -d /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name 2>/dev/null", root = true)
        return found.lines().map { it.trim() }.firstOrNull { it.startsWith("/dev/") }
    }

    /** Named partitions with their block device and size (root: every one; without: those Android lets apps see). */
    fun partitions(): List<Partition> {
        val dir = byNameDir() ?: return emptyList()
        val root = Su.get() != null
        val out = ArrayList<Partition>()
        val listing = Shell.text("ls -l $dir", root = root)
        for (line in listing.lines()) {
            val m = Regex("(\\S+) -> (\\S+)$").find(line.trim()) ?: continue
            val name = m.groupValues[1]
            val target = m.groupValues[2].let { if (it.startsWith("/")) it else "/dev/block/$it" }
            out += Partition(name, target, sizeOf(target, root))
        }
        return out.sortedBy { it.name }
    }

    private fun sizeOf(
        device: String,
        root: Boolean,
    ): Long {
        val block = device.substringAfterLast('/')
        runCatching { File("/sys/class/block/$block/size").readText().trim().toLong() * 512 }.getOrNull()?.let { return it }
        return if (root) Shell.text("blockdev --getsize64 $device", root = true).trim().toLongOrNull() ?: -1 else -1
    }

    /** Serial ports that may lead to the MCU or a CAN box, and who holds them. */
    fun serialPorts(): List<SerialPort> {
        val root = Su.get() != null
        val names =
            (File("/dev").listFiles().orEmpty().map { it.path } + Shell.text("ls /dev/tty* 2>/dev/null", root = root).lines())
                .map { it.trim() }
                .filter { Regex("^/dev/tty(S|MT|HS|HSL|USB|ACM|AMA|SAC|GS)\\d+$").matches(it) }
                .distinct()
                .sorted()
        val holders =
            if (root) {
                Shell
                    .text(
                        "for p in /proc/[0-9]*; do for f in \$p/fd/*; do l=\$(readlink \$f 2>/dev/null); " +
                            "case \$l in /dev/tty*) echo \"\$l \${p#/proc/} \$(cat \$p/comm 2>/dev/null)\";; esac; done; done 2>/dev/null",
                        root = true,
                        timeoutS = 30,
                    ).lines()
                    .mapNotNull { l -> l.trim().split(' ', limit = 2).takeIf { it.size == 2 } }
                    .groupBy({ it[0] }, { it[1] })
            } else {
                emptyMap()
            }
        return names.map { SerialPort(it, File(it).canRead(), holders[it].orEmpty().distinct()) }
    }
}
