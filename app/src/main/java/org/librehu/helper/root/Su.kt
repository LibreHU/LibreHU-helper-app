package org.librehu.helper.root

import android.content.Context
import java.io.File

/**
 * Root, in two steps:
 * 1. the `su` binaries of the ROM itself (stock ROMs of head units often ship a silent factory root): the usual places,
 *    PATH, and a scan of /system, /vendor, /oem, /jancar… for files named `su`;
 * 2. then the known root managers (KernelSU Next, KernelSU, APatch, Magisk and its forks, SuperSU): their entry points
 *    are tried (Magisk's `magisk su` applet, KernelSU / APatch's kernel hook on /system/bin/su, SuperSU's /su/bin/su…).
 * Candidates are tried one after the other until one gives uid 0, each with two calling conventions: `su -c <cmd>`
 * (managers and most ROMs) and AOSP's userdebug `su 0 <cmd>`.
 */
object Su {
    enum class Mode { DASH_C, AOSP }

    data class Root(
        /** Command before the su arguments: `[/system/xbin/su]`, or `[/system/bin/magisk, su]`. */
        val prefix: List<String>,
        val mode: Mode,
        val source: String,
    ) {
        fun argv(cmd: String): List<String> =
            when (mode) {
                Mode.DASH_C -> prefix + listOf("-c", cmd)
                Mode.AOSP -> prefix + listOf("0", "sh", "-c", cmd)
            }

        override fun toString() = "${prefix.joinToString(" ")} ($mode, $source)"
    }

    data class Candidate(
        val prefix: List<String>,
        val source: String,
        val result: String,
    )

    private class Manager(
        val name: String,
        val packages: List<String>,
        /** Files whose presence shows the manager even when its app is hidden or renamed. */
        val markers: List<String>,
        val entries: List<List<String>>,
    )

    private val MANAGERS =
        listOf(
            Manager(
                "KernelSU Next",
                listOf("com.rifsxd.ksunext"),
                listOf("/data/adb/ksud", "/data/adb/ksu"),
                listOf(listOf("su"), listOf("/system/bin/su"), listOf("/data/adb/ksu/bin/su")),
            ),
            Manager(
                "KernelSU",
                listOf("me.weishu.kernelsu"),
                listOf("/data/adb/ksud", "/data/adb/ksu"),
                listOf(listOf("su"), listOf("/system/bin/su"), listOf("/data/adb/ksu/bin/su")),
            ),
            Manager(
                "APatch",
                listOf("me.bmax.apatch"),
                listOf("/data/adb/ap", "/data/adb/apd"),
                listOf(listOf("su"), listOf("/system/bin/su"), listOf("/data/adb/ap/bin/su")),
            ),
            Manager(
                "Magisk",
                listOf("com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk"),
                listOf("/data/adb/magisk", "/sbin/.magisk", "/debug_ramdisk/.magisk"),
                listOf(
                    listOf("su"), listOf("/sbin/su"), listOf("/debug_ramdisk/su"), listOf("/system/bin/su"),
                    listOf("/system/bin/magisk", "su"), listOf("/sbin/magisk", "su"), listOf("/debug_ramdisk/magisk", "su"),
                    listOf("/data/adb/magisk/magisk", "su"),
                ),
            ),
            Manager(
                "SuperSU",
                listOf("eu.chainfire.supersu"),
                listOf("/system/xbin/daemonsu", "/su/bin/daemonsu", "/sbin/daemonsu"),
                listOf(listOf("su"), listOf("/su/bin/su"), listOf("/sbin/su"), listOf("/system/xbin/su"), listOf("/system/bin/su")),
            ),
            Manager(
                "Superuser (Koush / phh)",
                listOf("com.koushikdutta.superuser", "me.phh.superuser"),
                emptyList(),
                listOf(listOf("su"), listOf("/system/xbin/su"), listOf("/system/bin/su")),
            ),
        )

    /** Folders scanned for files named `su` (missing ones skipped). */
    private val SCAN_ROOTS =
        listOf(
            "/system", "/system_ext", "/product", "/vendor", "/odm", "/oem", "/jancar", "/cust", "/preload",
            "/sbin", "/su", "/data/local",
        )

    /** Usual places of a ROM's own su. */
    private val ROM_PATHS =
        listOf(
            "/system/xbin/su", "/system/bin/su", "/system/sbin/su", "/sbin/su", "/vendor/bin/su", "/vendor/xbin/su",
            "/oem/bin/su", "/data/local/tmp/su", "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
        )

    private const val MAX_DEPTH = 4
    private const val PROBE_S = 25L

    @Volatile
    private var root: Root? = null

    /** True once [scan] ran. */
    @Volatile
    var scanned = false
        private set

    @Volatile
    var candidates: List<Candidate> = emptyList()
        private set

    /** Root managers found (app or marker files). */
    @Volatile
    var managers: List<String> = emptyList()
        private set

    /** Other files whose name ends in `su` (daemonsu…), listed in the report but never run. */
    @Volatile
    var others: List<String> = emptyList()
        private set

    /** The working root found by the last [scan], null before it. */
    fun get(): Root? = root

    /** The working root, scanning first when it never ran (slow: off the main thread). */
    fun get(context: Context): Root? {
        if (!scanned) scan(context)
        return root
    }

    @Synchronized
    fun scan(context: Context): Root? {
        // 1. The ROM's own su.
        val rom = LinkedHashSet<String>()
        ROM_PATHS.filter { File(it).exists() }.forEach { rom += it }
        System
            .getenv("PATH")
            ?.split(':')
            ?.filter { it.isNotBlank() }
            ?.map { "$it/su" }
            ?.filter { File(it).exists() }
            ?.forEach { rom += it }
        val similar = LinkedHashSet<String>()
        val visited = HashSet<String>()
        for (r in SCAN_ROOTS) walk(File(r), 0, visited, rom, similar)
        val steps = ArrayList<Pair<List<String>, String>>()
        rom.forEach { steps += listOf(it) to "rom" }

        // 2. The root managers, installed or leaving marker files.
        val pm = context.packageManager
        val found =
            MANAGERS.filter { m ->
                m.packages.any { runCatching { pm.getPackageInfo(it, 0) }.isSuccess } || m.markers.any { File(it).exists() }
            }
        managers = found.map { it.name }
        found.forEach { m -> m.entries.forEach { steps += it to "manager: ${m.name}" } }
        // Last chance: su through PATH, whatever provides it (a kernel hook needs no file).
        steps += listOf("su") to "PATH"

        val tried = ArrayList<Candidate>()
        val seen = HashSet<List<String>>()
        var chosen: Root? = null
        for ((prefix, source) in steps) {
            if (!seen.add(prefix)) continue
            val first = prefix.first()
            if (first.startsWith("/") && !File(first).exists() && first != "/system/bin/su") continue
            if (chosen != null) {
                tried += Candidate(prefix, source, "not tried (root already found)")
                continue
            }
            val (mode, detail) = probe(prefix)
            tried += Candidate(prefix, source, if (mode != null) "OK ($mode): $detail" else detail)
            if (mode != null) chosen = Root(prefix, mode, source)
        }
        candidates = tried
        others = similar.toList()
        root = chosen
        scanned = true
        return chosen
    }

    /** Text for the report: what was found and tried. */
    fun report(): String =
        buildString {
            appendLine("root: ${root ?: "none"}")
            appendLine("root managers found: ${managers.ifEmpty { listOf("none") }.joinToString()}")
            appendLine()
            appendLine("candidates, in the order tried:")
            candidates.forEach { appendLine("  [${it.source}] ${it.prefix.joinToString(" ")}: ${it.result}") }
            if (others.isNotEmpty()) {
                appendLine()
                appendLine("other files named *su (not run):")
                others.forEach { appendLine("  $it") }
            }
        }

    private fun probe(prefix: List<String>): Pair<Mode?, String> {
        val a = tryId(prefix + listOf("-c", "id"))
        if (a.first) return Mode.DASH_C to a.second
        val b = tryId(prefix + listOf("0", "id"))
        if (b.first) return Mode.AOSP to b.second
        return null to a.second.ifEmpty { b.second }.take(200)
    }

    private fun tryId(args: List<String>): Pair<Boolean, String> {
        var out = ""
        val r =
            Shell.exec(args, root = false, timeoutS = PROBE_S, mergeErrors = true) { input ->
                out = input.bufferedReader().readText().trim()
            }
        val text =
            when {
                r.timedOut -> "timeout"
                r.error != null && out.isEmpty() -> r.error
                else -> out.lines().firstOrNull().orEmpty()
            }
        return out.contains("uid=0") to text
    }

    private fun walk(
        dir: File,
        depth: Int,
        visited: MutableSet<String>,
        found: MutableSet<String>,
        similar: MutableSet<String>,
    ) {
        if (depth > MAX_DEPTH) return
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
        if (!visited.add(canonical)) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (f.isDirectory) {
                walk(f, depth + 1, visited, found, similar)
            } else if (f.name == "su") {
                found += f.path
            } else if (f.name.endsWith("su") && f.name.length <= 12) {
                similar += f.path
            }
        }
    }
}
