package org.librehu.helper.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeviceTreeTextTest {
    private fun v(
        name: String,
        bytes: ByteArray,
        anonymise: Boolean = false,
    ) = DeviceTreeText.value(name, bytes, bytes.size.toLong(), anonymise)

    @Test
    fun stringsAndLists() {
        assertEquals("\"mediatek,mt6765-dsi\"", v("compatible", "mediatek,mt6765-dsi\u0000".toByteArray()))
        assertEquals("\"a\", \"bc\"", v("compatible", "a\u0000bc\u0000".toByteArray()))
    }

    @Test
    fun cellsBytesAndFlags() {
        assertEquals("<0x00000001 0x40000000>", v("reg", byteArrayOf(0, 0, 0, 1, 0x40, 0, 0, 0)))
        assertEquals("[01 02 03]", v("x", byteArrayOf(1, 2, 3)))
        assertEquals("(true)", v("no-map", ByteArray(0)))
        // printable but not NUL-terminated: not a string (a cell that happens to be ASCII)
        assertEquals("<0x41424344>", v("x", "ABCD".toByteArray()))
        // NUL inside a 4-byte value: cells
        assertEquals("<0x00000041>", v("x", byteArrayOf(0, 0, 0, 0x41)))
    }

    @Test
    fun longBinaryIsCut() {
        val b = ByteArray(300) { 1 }
        val s = DeviceTreeText.value("x", b, 300, false)
        assertTrue(s.endsWith(" ... (300 bytes)"))
        assertEquals(64, s.substringBefore(" ...").removePrefix("<").removeSuffix(">").split(" ").size)
    }

    @Test
    fun longStringIsWhole() {
        val text = "console=tty0 " + "x".repeat(400)
        assertEquals("\"$text\"", v("bootargs", (text + "\u0000").toByteArray()))
    }

    @Test
    fun macAndSensitive() {
        val mac = byteArrayOf(0x70, 0x49, 0xA2.toByte(), 0x11, 0x22, 0x33)
        assertEquals("70:49:a2:11:22:33", v("local-mac-address", mac))
        assertEquals("<redacted> (6 bytes)", v("local-mac-address", mac, anonymise = true))
        assertEquals("<redacted> (4 bytes)", v("atag,devinfo", byteArrayOf(1, 2, 3, 4), anonymise = true))
        assertEquals("<redacted> (8 bytes)", v("kaslr-seed", ByteArray(8), anonymise = true))
        assertEquals("<0x01020304>", v("atag,devinfo", byteArrayOf(1, 2, 3, 4), anonymise = false))
    }

    @Test
    fun dumpTree() {
        val root = Files.createTempDirectory("dt").toFile()
        try {
            File(root, "chosen").mkdirs()
            File(root, "chosen/bootargs").writeBytes("console=ttyS0\u0000".toByteArray())
            File(root, "chosen/atag,videolfb-fps").writeBytes(byteArrayOf(0, 0, 0x17, 0x70))
            File(root, "#size-cells").writeBytes(byteArrayOf(0, 0, 0, 2))
            val out = DeviceTreeText.dump(root, anonymise = true)!!
            assertEquals(
                listOf(
                    "./#size-cells: <0x00000002>",
                    "./chosen/atag,videolfb-fps: <0x00001770>",
                    "./chosen/bootargs: \"console=ttyS0\"",
                ),
                out.trim().lines(),
            )
            assertNull(DeviceTreeText.dump(File(root, "missing"), anonymise = false))
        } finally {
            root.deleteRecursively()
        }
    }
}
