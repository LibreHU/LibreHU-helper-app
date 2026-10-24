package org.librehu.helper.collect

import android.content.Context
import org.json.JSONArray
import org.librehu.helper.R
import org.librehu.helper.analysis.FrameGuesser
import org.librehu.helper.hw.HwProbe
import org.librehu.helper.hw.Scans
import org.librehu.helper.report.Report

/**
 * The Hardware tab's scans in the report: I²C buses (reads only), Apple MFi coprocessor, UART traffic, MCU and CAN
 * box protocols (ports read at their own speed; a free port also at the usual speeds) with draft LibreHU-service
 * profiles. Off by default: it probes the hardware.
 */
object HardwareScan : Collector {
    override val id = "scan"
    override val title = R.string.c_scan
    override val summary = R.string.c_scan_summary
    override val default = false

    override fun collect(
        context: Context,
        r: Report,
    ) {
        r.text("scan/i2c.txt", Text.i2c(Scans.i2cScan(context)))
        r.text("scan/mfi.txt", Text.mfi(Scans.mfi(context)))
        r.text("scan/uart-activity.txt", Scans.uartActivity().joinToString("\n") { "${it.port} rx+${it.rxDelta} tx+${it.txDelta}  ${it.line}" }.ifEmpty { "no counters (root needed)" })
        val ports = Scans.findProtocols(context, changeSpeedOfUsedPorts = false) {}
        r.text("scan/protocols.txt", Text.protocols(ports))
        val profiles = JSONArray()
        ports.filter { it.confident }.forEach { p ->
            val g = p.best ?: return@forEach
            val id = "draft-" + p.port.substringAfterLast('/').lowercase()
            profiles.put(FrameGuesser.profile(g, id, p.port, p.baud))
            p.nested.forEach { (cmd, list) ->
                list.firstOrNull()?.let { profiles.put(FrameGuesser.profile(it, "$id-cmd%02x".format(cmd), "${p.port} (cmd %02X)".format(cmd), p.baud)) }
            }
        }
        r.text("scan/draft-profiles.json", profiles.toString(1))
        ports.filter { it.bytes.isNotEmpty() }.forEach { p ->
            r.text("scan/captures/${p.port.substringAfterLast('/')}-${p.baud}.hex", HwProbe.hex(p.bytes).chunked(64).joinToString("\n"))
        }
    }

    /** Text forms shared with the Hardware tab. */
    object Text {
        fun i2c(buses: List<Scans.I2cBus>): String =
            if (buses.isEmpty()) {
                "no /dev/i2c-* (or not readable)"
            } else {
                buses.joinToString("\n\n") { b ->
                    "== ${b.path}" + (b.error?.let { " ($it)" } ?: "") + "\n" +
                        b.devices.joinToString("\n") { d ->
                            "  0x%02x %s%s".format(d.address, if (d.state == 2) "UU (driver)" else "answers", d.name?.let { " — $it" } ?: "")
                        }
                }
            }

        fun mfi(list: List<Scans.Mfi>): String {
            val found = list.filter { it.found }
            val head = if (found.isEmpty()) "no MFi coprocessor found at 0x10 / 0x11" else "MFi coprocessor: " + found.joinToString { "${it.bus} 0x%02x".format(it.address) }
            return head + "\n\n" +
                list.joinToString("\n") {
                    "${it.bus} 0x%02x deviceVersion=%s firmware=%s protocolMajor=%s certificateLength=%s appleCertificateLength=%s".format(
                        it.address, it.deviceVersion, it.firmwareVersion, it.protocolMajor, it.certificateLength ?: "-", it.appleCertificateLength ?: "-",
                    )
                }
        }

        fun guess(g: FrameGuesser.Guess): String {
            val s = g.spec
            return "${g.known ?: "unknown protocol"}: header ${s.hexHeader()}, length at ${s.lengthOffset} (${s.lengthCounts.name.lowercase()}" +
                "${if (s.lengthAdd != 0) " %+d".format(s.lengthAdd) else ""}), command at ${s.cmdOffset}, checksum ${s.checksum.name.lowercase()}" +
                " from ${s.checksumFrom}${if (s.checksumAdjust != 0) " %+d".format(s.checksumAdjust) else ""}; ${g.frames.size} frames, " +
                "%.0f %% of the bytes, checksum OK %.0f %%; commands %s".format(
                    g.coverage * 100,
                    g.checksumRate * 100,
                    g.commands.entries.joinToString { (c, n) -> "%02X×%d".format(c, n) },
                )
        }

        fun protocols(ports: List<Scans.PortResult>): String =
            if (ports.isEmpty()) {
                "no serial port could be read (root needed)"
            } else {
                ports.joinToString("\n\n") { p ->
                    buildString {
                        append("== ${p.port} @ ${p.baud}${if (p.speedChanged) " (speed set for the capture)" else ""}: ${p.bytes.size} bytes in the capture")
                        if (p.holders.isNotEmpty()) append(", held by ${p.holders.joinToString()}")
                        if (p.rxDelta > 0) append(", rx +${p.rxDelta}")
                        append('\n')
                        if (p.guesses.isEmpty()) append("  no framing found\n")
                        p.guesses.forEach { append("  ").append(guess(it)).append('\n') }
                        p.nested.forEach { (cmd, list) -> list.forEach { append("  inside command %02X: ".format(cmd)).append(guess(it)).append('\n') } }
                    }.trimEnd()
                }
            }
    }
}
