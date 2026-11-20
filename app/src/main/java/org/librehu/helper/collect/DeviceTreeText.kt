package org.librehu.helper.collect

import java.io.File

/**
 * Text view of the live device tree (/proc/device-tree), read by the app itself: one line per property,
 * `./node/property: value`, the value shown as the kernel holds it:
 * - a list of strings (`"a", "b"`) when every byte is printable ASCII and each string ends with a NUL,
 * - else 32-bit cells (`<0x00000001 0x40000000>`, big-endian, as in a .dts) when the size is a multiple of 4,
 * - else bytes (`[01 02 03]`); an empty property (a flag) is `(true)`.
 * A `*mac-address` of 6 bytes is written `aa:bb:cc:dd:ee:ff`, so that the report's redactor masks it like any MAC.
 * A string list is shown whole (up to [READ_BYTES], bootargs is often longer than 256 bytes); other values show their
 * first [MAX_BYTES] bytes, then the size of the property.
 *
 * The shell version before (`tr -cd '[:print:] '`) gave nothing usable on Android: toybox's tr has no character
 * classes, it kept only the letters of the text « [:print:] » (report of 28/07/2026, UJC201_64).
 */
object DeviceTreeText {
    const val MAX_BYTES = 256
    const val READ_BYTES = 4096

    /**
     * Values of one unit only (MAC addresses, serial numbers, random seeds, chip ID / efuse data of the MediaTek LK):
     * written `<redacted> (N bytes)` when the report is anonymised.
     */
    private val SENSITIVE = Regex("(?i)(mac-address|serial|kaslr-seed|rng-seed|devinfo|masp|hwid|uuid|socid|cid$)")

    fun value(
        name: String,
        bytes: ByteArray,
        size: Long,
        anonymise: Boolean,
    ): String {
        if (anonymise && SENSITIVE.containsMatchIn(name)) return "<redacted> ($size bytes)"
        if (size == 0L) return "(true)"
        if (name.endsWith("mac-address") && size == 6L && bytes.size == 6) {
            return bytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        }
        strings(bytes, size)?.let { return it.joinToString(", ") { s -> "\"$s\"" } }
        val shown = if (bytes.size > MAX_BYTES) bytes.copyOf(MAX_BYTES) else bytes
        val more = if (size > shown.size) " ... ($size bytes)" else ""
        return if (shown.size % 4 == 0) {
            "<" + (shown.indices step 4).joinToString(" ") { i ->
                val v = ((shown[i].toInt() and 0xFF) shl 24) or ((shown[i + 1].toInt() and 0xFF) shl 16) or
                    ((shown[i + 2].toInt() and 0xFF) shl 8) or (shown[i + 3].toInt() and 0xFF)
                "0x%08x".format(v)
            } + ">" + more
        } else {
            "[" + shown.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } + "]" + more
        }
    }

    /** The strings of a NUL-terminated string list, or null when [bytes] is not one (whole property read only). */
    private fun strings(
        bytes: ByteArray,
        size: Long,
    ): List<String>? {
        if (size != bytes.size.toLong() || bytes.isEmpty() || bytes.last() != 0.toByte()) return null
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (i in 0 until bytes.size - 1) {
            val c = bytes[i].toInt() and 0xFF
            when {
                c == 0 -> {
                    if (cur.isEmpty()) return null
                    out += cur.toString()
                    cur.setLength(0)
                }
                c in 0x20..0x7E -> cur.append(c.toChar())
                else -> return null
            }
        }
        if (cur.isEmpty()) return null
        out += cur.toString()
        return out
    }

    /** Every property under [root], sorted by path, or null when [root] cannot be listed by the app. */
    fun dump(
        root: File,
        anonymise: Boolean,
    ): String? {
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        if (!base.isDirectory || base.list().isNullOrEmpty()) return null
        val files = base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).path }.sorted().toList()
        val sb = StringBuilder()
        for (rel in files) {
            val f = File(base, rel)
            val line =
                runCatching {
                    val size = f.length()
                    val bytes = f.inputStream().use { input -> input.readNBytesCompat(READ_BYTES) }
                    // /proc reports 0 as the size of some files: the bytes read are then the size
                    val real = if (size == 0L || size < bytes.size) bytes.size.toLong() else size
                    value(f.name, bytes, real, anonymise)
                }.getOrElse { "<unreadable: ${it.javaClass.simpleName}>" }
            sb.append("./").append(rel).append(": ").append(line).append('\n')
        }
        return sb.toString()
    }

    /** InputStream.readNBytes is Java 11 (API 33 on Android): up to [n] bytes. */
    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }
}
