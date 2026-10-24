package org.librehu.helper.root

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Shell commands, as root when [Su] found a working `su` and the caller asks for it, else as the app. The timeout is
 * enforced by a watchdog that kills the process: the output is read on the calling thread, so a command that never
 * ends cannot block it past [timeoutS].
 */
object Shell {
    data class Result(
        val exit: Int,
        val timedOut: Boolean,
        val root: Boolean,
        val error: String? = null,
    ) {
        val ok get() = exit == 0 && !timedOut && error == null
    }

    /** argv of [cmd]: through `su` when [root] is asked and available, else `sh -c`. */
    fun argv(
        cmd: String,
        root: Boolean,
    ): Pair<List<String>, Boolean> {
        val su = if (root) Su.get() else null
        return if (su != null) su.argv(cmd) to true else listOf("sh", "-c", cmd) to false
    }

    /** Output of a short command (stdout and stderr), empty on failure. */
    fun text(
        cmd: String,
        root: Boolean = false,
        timeoutS: Long = 20,
    ): String {
        val out = ByteArrayOutputStream()
        run(cmd, root, timeoutS, out, mergeErrors = true)
        return out.toString(Charsets.UTF_8.name()).trim()
    }

    /** [cmd] line by line to [onLine] (stdout and stderr merged). */
    fun lines(
        cmd: String,
        root: Boolean,
        timeoutS: Long,
        onLine: (String) -> Unit,
    ): Result {
        val (args, asRoot) = argv(cmd, root)
        return exec(args, asRoot, timeoutS, mergeErrors = true) { input ->
            input.bufferedReader().forEachLine(onLine)
        }
    }

    /** Raw stdout of [cmd] to [out] (binary dumps); stderr is kept apart and returned in [Result.error]. */
    fun run(
        cmd: String,
        root: Boolean,
        timeoutS: Long,
        out: OutputStream,
        mergeErrors: Boolean = false,
    ): Result {
        val (args, asRoot) = argv(cmd, root)
        return exec(args, asRoot, timeoutS, mergeErrors) { input -> input.copyTo(out, 1 shl 16) }
    }

    /** Runs [args] directly (no shell), output to [read]; used by [Su] to probe candidates. */
    fun exec(
        args: List<String>,
        root: Boolean,
        timeoutS: Long,
        mergeErrors: Boolean,
        read: (java.io.InputStream) -> Unit,
    ): Result {
        val p =
            try {
                ProcessBuilder(args).redirectErrorStream(mergeErrors).start()
            } catch (e: IOException) {
                return Result(-1, timedOut = false, root = root, error = e.message ?: "exec")
            }
        runCatching { p.outputStream.close() }
        val timedOut = AtomicBoolean(false)
        val watchdog =
            thread(isDaemon = true, name = "shell-watchdog") {
                try {
                    if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                        timedOut.set(true)
                        p.destroy()
                        Thread.sleep(500)
                        if (p.isAlive) p.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                }
            }
        val errors = ByteArrayOutputStream()
        val errReader =
            if (mergeErrors) {
                null
            } else {
                thread(isDaemon = true, name = "shell-stderr") {
                    try {
                        p.errorStream.copyTo(errors)
                    } catch (_: IOException) {
                    }
                }
            }
        var readError: String? = null
        try {
            read(p.inputStream)
        } catch (e: IOException) {
            if (!timedOut.get()) readError = e.message ?: "read"
        }
        val finished =
            try {
                p.waitFor(timeoutS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
        if (!finished) {
            timedOut.set(true)
            p.destroyForcibly()
        }
        watchdog.interrupt()
        errReader?.join(1_000)
        val stderr = errors.toString(Charsets.UTF_8.name()).trim().take(2_000)
        return Result(
            exit = if (finished) p.exitValue() else -1,
            timedOut = timedOut.get(),
            root = root,
            error = readError ?: stderr.ifEmpty { null }?.takeIf { finished && p.exitValue() != 0 },
        )
    }
}
