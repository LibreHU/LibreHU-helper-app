package org.librehu.helper.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class FrameGuesserTest {
    /** JAC_V1: EE FA <len = data + 1> <cmd> <data> <sum of every byte before> (handoff 7.2). */
    private fun jac(
        cmd: Int,
        data: List<Int>,
    ): List<Int> {
        val f = mutableListOf(0xEE, 0xFA, data.size + 1, cmd) + data
        return f + (f.sum() and 0xFF)
    }

    /** Hiworld: 5A A5 LEN CMD D0..Dn-1 CS, CS = ((LEN + CMD + ΣD) & 0xFF) - 1 (handoff 7.3). */
    private fun hiworld(
        cmd: Int,
        data: List<Int>,
    ): List<Int> {
        val body = listOf(data.size, cmd) + data
        return listOf(0x5A, 0xA5) + body + (((body.sum() and 0xFF) - 1) and 0xFF)
    }

    private fun bytes(frames: List<List<Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        frames.forEach { f -> f.forEach { out.write(it) } }
        return out.toByteArray()
    }

    private val rnd = Random(42)

    private fun data() = List(rnd.nextInt(0, 9)) { rnd.nextInt(256) }

    @Test
    fun recognisesJac() {
        val cmds = listOf(0x00, 0x04, 0x0B, 0x1F, 0x20, 0x09)
        val capture = bytes(List(60) { jac(cmds[it % cmds.size], data()) })
        val g = FrameGuesser.guess(capture).first()
        assertEquals("Jancar JAC_V1 (MCU)", g.known)
        assertEquals(60, g.frames.size)
        assertEquals(1.0, g.checksumRate, 0.0)
    }

    @Test
    fun recognisesHiworld() {
        val capture = bytes(List(50) { hiworld(listOf(0x11, 0x12, 0x41)[it % 3], data()) })
        val g = FrameGuesser.guess(capture).first()
        assertEquals("Hiworld (CAN box)", g.known)
        assertEquals(1.0, g.checksumRate, 0.0)
    }

    @Test
    fun findsHiworldInsideJacFrames() {
        // Jancar relays the CAN box bytes in MCU frames 0x10 (handoff 7.2).
        val can = (0 until 40).map { hiworld(0x11, listOf(0x08, it % 25, 0x01)) }
        val capture = bytes(can.map { jac(0x10, it) } + List(20) { jac(0x1F, data()) })
        val mcu = FrameGuesser.guess(capture).first()
        assertEquals("Jancar JAC_V1 (MCU)", mcu.known)
        val payload = bytes(mcu.frames.filter { it.cmd == 0x10 }.map { f -> f.data.map { it.toInt() and 0xFF } })
        assertEquals("Hiworld (CAN box)", FrameGuesser.guess(payload).first().known)
    }

    @Test
    fun guessesAnUnknownXorProtocolThroughNoise() {
        // 2E header, length after the command, xor checksum from the command (a Raise-like layout).
        fun frame(
            cmd: Int,
            d: List<Int>,
        ): List<Int> {
            val body = listOf(cmd, d.size) + d
            return listOf(0x2E) + body + (body.fold(0) { a, b -> a xor b } xor 0xFF)
        }
        val noise = List(5) { rnd.nextInt(256) }
        val capture = bytes(listOf(noise) + List(80) { frame(listOf(0x20, 0x21, 0x24)[it % 3], data()) })
        val g = FrameGuesser.guess(capture).first()
        assertEquals(listOf(0x2E), g.spec.header)
        assertEquals(FrameGuesser.Checksum.XOR8_INV, g.spec.checksum)
        assertTrue("checksum ${g.checksumRate}", g.checksumRate > 0.95)
        assertTrue("coverage ${g.coverage}", g.coverage > 0.9)
    }

    @Test
    fun randomBytesGiveNoConfidentGuess() {
        val capture = ByteArray(4000).also { rnd.nextBytes(it) }
        val g = FrameGuesser.guess(capture)
        assertTrue(g.none { it.coverage > 0.5 && it.checksumRate > 0.9 })
    }

    @Test
    fun draftProfileUsesTheServiceFormat() {
        val g = FrameGuesser.guess(bytes(List(30) { jac(0x1F, data()) })).first()
        val p = FrameGuesser.profile(g, "draft-test", "/dev/ttyS1", 115200)
        assertEquals("librehu-mcu-profile", p.getString("format"))
        assertEquals("EE FA", p.getJSONObject("frame").getString("header"))
        assertEquals("sum8", p.getJSONObject("frame").getString("checksum"))
    }
}
