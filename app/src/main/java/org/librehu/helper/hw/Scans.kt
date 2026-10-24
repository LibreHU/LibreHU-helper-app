package org.librehu.helper.hw

import android.content.Context
import org.librehu.helper.analysis.FrameGuesser
import org.librehu.helper.device.Device
import org.librehu.helper.root.Shell

/**
 * Hardware scans for a port: I²C buses (and the Apple MFi coprocessor of CarPlay), serial port traffic, the MCU and
 * its protocol, the CAN box and its protocol (on its own port, or relayed inside the MCU's frames as on Jancar
 * units), and steering wheel key captures.
 */
object Scans {
    /** Speeds tried on a free port when its own gives nothing readable. */
    val SPEEDS = listOf(115200, 38400, 19200, 9600, 57600, 230400, 460800)

    // --- I²C -----------------------------------------------------------------------------------------------------

    data class I2cDevice(
        val address: Int,
        /** 1 answered, 2 used by a kernel driver. */
        val state: Int,
        /** Kernel name of the device bound at this address (/sys/bus/i2c/devices/N-00AA/name). */
        val name: String?,
    )

    data class I2cBus(
        val path: String,
        val error: String?,
        val devices: List<I2cDevice>,
    )

    fun i2cBuses(): List<String> =
        Shell
            .text("ls /dev/i2c-* 2>/dev/null", root = true)
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("/dev/i2c-") }
            .sortedBy { it.removePrefix("/dev/i2c-").toIntOrNull() ?: 999 }

    fun i2cScan(context: Context): List<I2cBus> {
        val buses = i2cBuses()
        if (buses.isEmpty()) return emptyList()
        val names = kernelI2cNames()
        return Hw.run(context, listOf("i2cscan") + buses, timeoutS = 120).mapNotNull { line ->
            val parts = line.split(' ')
            if (parts.size != 3 || parts[0] != "I2C") return@mapNotNull null
            val bus = parts[1]
            val values = parts[2].split(',').mapNotNull { it.toIntOrNull() }
            if (values.size != 128) return@mapNotNull I2cBus(bus, line, emptyList())
            if (values[0] < 0) return@mapNotNull I2cBus(bus, "errno ${-values[0]}", emptyList())
            val n = bus.removePrefix("/dev/i2c-")
            val devices = (3..0x77).filter { values[it] != 0 }.map { I2cDevice(it, values[it], names["$n-%04x".format(it)]) }
            I2cBus(bus, null, devices)
        }
    }

    private fun kernelI2cNames(): Map<String, String> =
        Shell
            .text("for d in /sys/bus/i2c/devices/*-*; do echo \"\$(basename \$d) \$(cat \$d/name 2>/dev/null)\"; done", root = true)
            .lines()
            .mapNotNull { l -> l.trim().split(' ', limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() } }
            .associate { it[0] to it[1] }

    data class Mfi(
        val bus: String,
        val address: Int,
        val deviceVersion: String,
        val firmwareVersion: String,
        val protocolMajor: String,
        val certificateLength: Int?,
        val appleCertificateLength: Int?,
    ) {
        /** An accessory certificate of a plausible size: an MFi coprocessor (LibreHU-xcertplay's test). */
        val found get() = (certificateLength ?: 0) in 1..1280
    }

    /** Apple MFi coprocessor (CarPlay / iAP2 authentication) at 0x10 / 0x11 of every bus. */
    fun mfi(context: Context): List<Mfi> {
        val buses = i2cBuses()
        if (buses.isEmpty()) return emptyList()
        return Hw.run(context, listOf("mfi") + buses, timeoutS = 120).mapNotNull { line ->
            val p = line.split(' ')
            if (p.size != 8 || p[0] != "MFI") return@mapNotNull null
            Mfi(p[1], p[2].toInt(), p[3], p[4], p[5], p[6].toIntOrNull(16), p[7].toIntOrNull(16))
        }
    }

    // --- Serial ----------------------------------------------------------------------------------------------------

    data class Uart(
        val port: String,
        val rxDelta: Long,
        val txDelta: Long,
        val line: String,
    )

    /**
     * Traffic of the UARTs over [ms] from the kernel's counters (/proc/tty/driver, root): a port whose rx / tx grow
     * talks to something (MCU, CAN box, GPS, Bluetooth chip). Nothing is opened.
     */
    fun uartActivity(ms: Long = 2_000): List<Uart> {
        val prefixes = ttyPrefixes()
        val first = uartCounters(prefixes)
        if (first.isEmpty()) return emptyList()
        Thread.sleep(ms)
        val second = uartCounters(prefixes)
        return second
            .map { (port, v) ->
                val before = first[port]
                Uart(port, v.first - (before?.first ?: v.first), v.second - (before?.second ?: v.second), v.third)
            }.sortedByDescending { it.rxDelta + it.txDelta }
    }

    /** Driver name (`serial`, `mtk-uart`…) to /dev prefix (`/dev/ttyS`), from /proc/tty/drivers. */
    private fun ttyPrefixes(): Map<String, String> =
        Shell
            .text("cat /proc/tty/drivers", root = true)
            .lines()
            .mapNotNull { l ->
                val c = l.trim().split(Regex("\\s+"))
                if (c.size >= 5 && c[1].startsWith("/dev/tty")) c[0] to c[1] else null
            }.toMap()

    private fun uartCounters(prefixes: Map<String, String>): Map<String, Triple<Long, Long, String>> {
        val out = HashMap<String, Triple<Long, Long, String>>()
        val text = Shell.text("for f in /proc/tty/driver/*; do echo \"## \$(basename \$f)\"; cat \$f; done", root = true)
        var prefix = ""
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("## ")) {
                val driver = l.removePrefix("## ")
                prefix = prefixes[driver] ?: prefixes.entries.firstOrNull { it.key.contains(driver) || driver.contains(it.key) }?.value ?: ""
                continue
            }
            val idx = l.substringBefore(':').trim().toIntOrNull() ?: continue
            val tx = Regex("tx:(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val rx = Regex("rx:(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (prefix.isNotEmpty()) out["$prefix$idx"] = Triple(rx, tx, l)
        }
        return out
    }

    fun capture(
        context: Context,
        port: String,
        baud: Int,
        ms: Int,
    ): ByteArray? {
        val line = Hw.run(context, listOf("serial", port, baud.toString(), ms.toString()), timeoutS = ms / 1000L + 30).firstOrNull { it.startsWith("SERIAL") } ?: return null
        val p = line.split(' ')
        return if (p.size >= 2 && p[1] == "OK") HwProbe.unhex(p.getOrElse(2) { "" }) else null
    }

    fun currentBaud(
        context: Context,
        port: String,
    ): Int = Hw.run(context, listOf("baud", port)).firstOrNull { it.startsWith("BAUD") }?.split(' ')?.getOrNull(2)?.toIntOrNull() ?: 0

    data class PortResult(
        val port: String,
        val baud: Int,
        /** True when the speed was set for the capture (the port's own gave nothing readable). */
        val speedChanged: Boolean,
        val holders: List<String>,
        val rxDelta: Long,
        val bytes: ByteArray,
        val guesses: List<FrameGuesser.Guess>,
        /** Protocols relayed inside one command's frames (CAN box bytes in MCU frames), by command. */
        val nested: Map<Int, List<FrameGuesser.Guess>>,
    ) {
        val best get() = guesses.firstOrNull()
        val confident get() = best?.let { it.coverage > 0.6 && it.checksumRate > 0.8 } == true
    }

    /**
     * Looks for the MCU and the CAN box: the serial ports, the busiest first, read at their own speed; a port giving
     * nothing readable is tried at [SPEEDS] when nobody holds it, or when [changeSpeedOfUsedPorts] (that disturbs the
     * maker's service while it lasts). Reads only: the maker's service may miss bytes during a capture.
     */
    fun findProtocols(
        context: Context,
        changeSpeedOfUsedPorts: Boolean,
        onStep: (String) -> Unit,
    ): List<PortResult> {
        onStep("UART")
        val activity = uartActivity().associateBy { it.port }
        val ports = Device.serialPorts()
        val ordered = ports.sortedByDescending { (activity[it.path]?.rxDelta ?: 0) + if (it.holders.isNotEmpty()) 1_000_000 else 0 }
        val out = ArrayList<PortResult>()
        for (p in ordered) {
            onStep(p.path)
            val own = currentBaud(context, p.path)
            var bytes = capture(context, p.path, 0, 2_500) ?: continue
            var baud = own
            var changed = false
            var guesses = FrameGuesser.guess(bytes)
            val free = p.holders.isEmpty()
            if (!good(guesses) && (free || changeSpeedOfUsedPorts)) {
                for (s in SPEEDS) {
                    onStep("${p.path} @ $s")
                    val b = capture(context, p.path, s, 1_500) ?: continue
                    val g = FrameGuesser.guess(b)
                    if (good(g)) {
                        bytes = b
                        guesses = g
                        baud = s
                        changed = true
                        break
                    }
                }
            }
            out += PortResult(p.path, baud, changed, p.holders, activity[p.path]?.rxDelta ?: 0, bytes, guesses, nested(guesses.firstOrNull()))
        }
        return out.sortedByDescending { (if (it.confident) 2 else 0) + if (it.bytes.isNotEmpty()) 1 else 0 }
    }

    private fun good(g: List<FrameGuesser.Guess>) = g.firstOrNull()?.let { it.coverage > 0.6 && it.checksumRate > 0.8 } == true

    /** Protocols inside one command's payloads (CAN box frames relayed by the MCU). */
    fun nested(g: FrameGuesser.Guess?): Map<Int, List<FrameGuesser.Guess>> {
        if (g == null) return emptyMap()
        return g.frames
            .groupBy { it.cmd }
            .filter { (_, frames) -> frames.sumOf { it.data.size } >= 48 }
            .mapValues { (_, frames) ->
                val joined = frames.fold(ByteArray(0)) { acc, f -> acc + f.data }
                FrameGuesser.guess(joined, 1).filter { it.coverage > 0.5 && it.checksumRate > 0.8 }
            }.filterValues { it.isNotEmpty() }
    }

    // --- Steering wheel keys ---------------------------------------------------------------------------------------

    data class KeyCapture(
        /** Frames (command + data, hex) seen only while a key was pressed. */
        val frames: List<String>,
        /** Input events from the kernel (wired keys through an ADC / GPIO driver). */
        val events: List<String>,
    )

    /**
     * What a key press sends: [idleMs] of capture without touching anything, then [pressMs] while the user presses
     * the key; the frames of the second only are the key's. Kernel input events are captured at the same time.
     */
    fun keyCapture(
        context: Context,
        port: String?,
        baud: Int,
        spec: FrameGuesser.Spec?,
        nestedCmd: Int?,
        nestedSpec: FrameGuesser.Spec?,
        idleMs: Int,
        pressMs: Int,
        onPress: () -> Unit,
    ): KeyCapture {
        fun frames(bytes: ByteArray): Set<String> {
            if (spec == null) return emptySet()
            val outer = FrameGuesser.frames(bytes, spec)
            val list =
                if (nestedCmd != null && nestedSpec != null) {
                    val joined = outer.filter { it.cmd == nestedCmd }.fold(ByteArray(0)) { a, f -> a + f.data }
                    FrameGuesser.frames(joined, nestedSpec)
                } else {
                    outer
                }
            return list.map { "%02X: %s".format(it.cmd, HwProbe.hex(it.data)) }.toSet()
        }
        val idle = if (port != null) capture(context, port, baud, idleMs)?.let(::frames).orEmpty() else emptySet()
        onPress()
        val events = ArrayList<String>()
        val getevent =
            Thread {
                Shell.lines("getevent -lt", root = true, timeoutS = pressMs / 1000L + 1) { if (it.contains("EV_KEY") || it.contains("EV_ABS")) events += it.trim() }
            }.apply { start() }
        val pressed = if (port != null) capture(context, port, baud, pressMs)?.let(::frames).orEmpty() else emptySet()
        getevent.join(pressMs + 3_000L)
        return KeyCapture((pressed - idle).sorted(), events)
    }
}
