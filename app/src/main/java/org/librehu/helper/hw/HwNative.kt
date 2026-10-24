package org.librehu.helper.hw

/** JNI of libhwprobe (app/src/main/cpp/hwprobe.c): read-only I²C and serial probes. */
object HwNative {
    /** 128 values: [0] = -errno when the bus cannot be opened; else 0 nothing, 1 answer, 2 used by a driver. */
    @JvmStatic external fun i2cScan(path: String): IntArray

    /** Register [reg] of [addr] (write register, then read [len] bytes), null on failure. */
    @JvmStatic external fun i2cRead(
        path: String,
        addr: Int,
        reg: Int,
        len: Int,
        tries: Int,
    ): ByteArray?

    /** Current speed of a serial port, 0 unknown, -errno when it cannot be opened. */
    @JvmStatic external fun serialBaud(path: String): Int

    /** Bytes received in [ms]; [baud] 0 keeps the port's settings. Null when it cannot be opened. */
    @JvmStatic external fun serialRead(
        path: String,
        baud: Int,
        ms: Int,
    ): ByteArray?
}
