package org.librehu.helper.hw

/**
 * Hardware probes as text lines, run as root by `app_process` (`main`, through [Hw]) or in the app when the devices
 * are readable without root ([exec]). Read only: I²C reads and serial reads, nothing written to a device except the
 * register number of an I²C register read.
 *
 * Commands: `baud <port>…`, `serial <port> <baud> <ms>`, `i2cscan <bus>…`, `i2cread <bus> <addr> <reg> <len>`,
 * `mfi <bus>…`.
 */
object HwProbe {
    /** Apple MFi coprocessor addresses (RST low / high) and registers, as LibreHU-xcertplay probes them. */
    val MFI_ADDRESSES = listOf(0x10, 0x11)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("ERR usage: HwProbe <libhwprobe.so> <command> …")
            return
        }
        try {
            System.load(args[0])
        } catch (e: Throwable) {
            println("ERR load ${e.message}")
            return
        }
        exec(args.drop(1)).forEach(::println)
        System.out.flush()
    }

    fun exec(args: List<String>): List<String> {
        val out = ArrayList<String>()
        when (args.firstOrNull()) {
            "baud" -> args.drop(1).forEach { out += "BAUD $it ${HwNative.serialBaud(it)}" }
            "serial" -> {
                val bytes = HwNative.serialRead(args[1], args[2].toInt(), args[3].toInt())
                out += if (bytes == null) "SERIAL FAIL" else "SERIAL OK ${hex(bytes)}"
            }
            "i2cscan" -> args.drop(1).forEach { out += "I2C $it ${HwNative.i2cScan(it).joinToString(",")}" }
            "i2cread" -> {
                val b = HwNative.i2cRead(args[1], args[2].toInt(), args[3].toInt(), args[4].toInt(), 20)
                out += if (b == null) "READ FAIL" else "READ OK ${hex(b)}"
            }
            "mfi" ->
                for (bus in args.drop(1)) {
                    for (a in MFI_ADDRESSES) {
                        fun reg(
                            r: Int,
                            len: Int,
                        ) = HwNative.i2cRead(bus, a, r, len, 20)?.let { hex(it) } ?: "-"
                        out += "MFI $bus $a ${reg(0x00, 1)} ${reg(0x01, 1)} ${reg(0x02, 1)} ${reg(0x30, 2)} ${reg(0x50, 2)}"
                    }
                }
            else -> out += "ERR unknown command"
        }
        return out
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
