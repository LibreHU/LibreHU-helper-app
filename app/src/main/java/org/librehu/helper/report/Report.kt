package org.librehu.helper.report

import org.json.JSONArray
import org.json.JSONObject
import org.librehu.helper.root.Shell
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One zip of the report, written as it goes (nothing large is kept in memory). Text files go through the
 * [redactor] when anonymisation is on; every entry is listed in `manifest.json` with its size and SHA-256.
 */
class Report(
    out: OutputStream,
    private val redactor: Redactor?,
    private val progress: (String) -> Unit,
) : Closeable {
    private val zip = ZipOutputStream(BufferedOutputStream(out, 1 shl 16))
    private val entries = JSONArray()
    private var sink: EntrySink? = null

    /** Number of entries and bytes (uncompressed) written so far. */
    var count = 0
        private set
    var bytes = 0L
        private set

    fun text(
        name: String,
        content: String,
    ) {
        val s = begin(name)
        content.lineSequence().forEach { s.line(redact(it)) }
        end("")
    }

    /**
     * Output of [cmd] (stdout and stderr), with the command as first line and its exit status as last. [root]: as
     * root when available, else as the app.
     */
    fun command(
        name: String,
        cmd: String,
        root: Boolean = false,
        timeoutS: Long = 60,
    ): Shell.Result {
        progress(name)
        val s = begin(name)
        val (_, asRoot) = Shell.argv(cmd, root)
        s.line("# $ $cmd${if (asRoot) "   (root)" else ""}")
        val r = Shell.lines(cmd, root, timeoutS) { s.line(redact(it)) }
        s.line("# exit=${r.exit}${if (r.timedOut) " (timeout ${timeoutS}s)" else ""}${r.error?.let { " error: $it" } ?: ""}")
        end(if (r.ok) "" else "exit ${r.exit}")
        return r
    }

    /** Raw stdout of [cmd] (binary), not anonymised. */
    fun binary(
        name: String,
        cmd: String,
        root: Boolean,
        timeoutS: Long,
    ): Shell.Result {
        progress(name)
        val s = begin(name)
        val r = Shell.run(cmd, root, timeoutS, s)
        end(if (r.ok) "" else "exit ${r.exit}${if (r.timedOut) " timeout" else ""} ${r.error.orEmpty()}".trim())
        return r
    }

    fun file(
        name: String,
        f: File,
    ) {
        progress(name)
        val s = begin(name)
        f.inputStream().use { it.copyTo(s, 1 shl 16) }
        end("")
    }

    /** A note in the manifest for something left out on purpose (excluded partition, missing root…). */
    fun skipped(
        name: String,
        why: String,
    ) {
        entries.put(JSONObject().put("name", name).put("skipped", why))
    }

    override fun close() {
        sink?.let { end("unfinished") }
        val manifest =
            JSONObject()
                .put("format", "librehu-helper-report")
                .put("version", 1)
                .put("anonymised", redactor != null)
                .put("entries", entries)
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(manifest.toString(1).toByteArray())
        zip.closeEntry()
        zip.close()
    }

    private fun redact(line: String) = redactor?.apply(line) ?: line

    private fun begin(name: String): EntrySink {
        sink?.let { end("unfinished") }
        zip.putNextEntry(ZipEntry(name))
        return EntrySink(zip, name).also { sink = it }
    }

    private fun end(note: String) {
        val s = sink ?: return
        sink = null
        zip.closeEntry()
        count++
        bytes += s.size
        val e = JSONObject().put("name", s.name).put("size", s.size).put("sha256", s.hex())
        if (note.isNotEmpty()) e.put("note", note)
        entries.put(e)
    }

    /** Writes into the current zip entry, counting and hashing, without closing the zip. */
    private class EntrySink(
        private val target: OutputStream,
        val name: String,
    ) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            digest.update(b.toByte())
            size++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            target.write(b, off, len)
            digest.update(b, off, len)
            size += len
        }

        fun line(s: String) = write((s + "\n").toByteArray())

        fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }

        override fun close() {}
    }
}
