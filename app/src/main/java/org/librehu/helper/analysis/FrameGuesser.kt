package org.librehu.helper.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * Guesses the framing of an unknown serial protocol (MCU, CAN box) from a capture, in the terms of LibreHU-service's
 * MCU profiles (`librehu-mcu-profile` v1, docs/mcu-profiles.md): header bytes, offset of the length and command bytes
 * (from the start of the frame), what the length counts, and a one-byte checksum (sum, xor or two's complement, from
 * a given byte, plus an adjustment). Head unit MCUs and CAN boxes (Raise, Hiworld…) vary along exactly these lines.
 *
 * 1. Headers: the most frequent 1- and 2-byte sequences.
 * 2. Framing: for each header and length layout, the frame size announced by the length byte must land on the next
 *    header; the layout chaining the most frames wins.
 * 3. Checksum: every type, start and adjustment is tried on the frames found.
 */
object FrameGuesser {
    enum class LengthCounts { DATA, CMD_DATA, FRAME, AFTER_LENGTH }

    /** [XOR8_INV]: xor then inverted (`xor ^ 0xFF`), seen on Raise boxes; not in LibreHU-service's profiles yet. */
    enum class Checksum { NONE, SUM8, XOR8, TWOS8, XOR8_INV }

    data class Spec(
        val header: List<Int>,
        val lengthOffset: Int,
        val lengthCounts: LengthCounts,
        val lengthAdd: Int,
        val cmdOffset: Int,
        val checksum: Checksum,
        val checksumFrom: Int = 0,
        val checksumAdjust: Int = 0,
        val maxData: Int = 255,
    ) {
        val prefix get() = maxOf(lengthOffset, cmdOffset) + 1
        private val cs get() = if (checksum == Checksum.NONE) 0 else 1

        /** Total size announced by length byte [len], -1 when impossible. */
        fun total(len: Int): Int {
            val v = len - lengthAdd
            val t =
                when (lengthCounts) {
                    LengthCounts.DATA -> prefix + v + cs
                    LengthCounts.CMD_DATA -> prefix + v - 1 + cs
                    LengthCounts.FRAME -> v
                    LengthCounts.AFTER_LENGTH -> v + lengthOffset + 1
                }
            val data = t - prefix - cs
            return if (data < 0 || data > maxData) -1 else t
        }

        fun checksumOf(
            b: ByteArray,
            start: Int,
            end: Int,
        ): Int {
            var acc = 0
            for (i in start + checksumFrom until end) {
                val v = b[i].toInt() and 0xFF
                acc = if (checksum == Checksum.XOR8 || checksum == Checksum.XOR8_INV) acc xor v else acc + v
            }
            val base =
                when (checksum) {
                    Checksum.TWOS8 -> -acc
                    Checksum.XOR8_INV -> acc xor 0xFF
                    else -> acc
                }
            return (base + checksumAdjust) and 0xFF
        }

        fun hexHeader() = header.joinToString(" ") { "%02X".format(it) }
    }

    data class Frame(
        val start: Int,
        val size: Int,
        val cmd: Int,
        val data: ByteArray,
        val checksumOk: Boolean,
    )

    data class Guess(
        val spec: Spec,
        /** Name of a protocol LibreHU already knows, null when new. */
        val known: String?,
        val frames: List<Frame>,
        /** Share of the capture inside chained frames (0..1). */
        val coverage: Double,
        /** Share of the frames whose checksum matches (0..1, 1 without checksum). */
        val checksumRate: Double,
    ) {
        /** Frames per command byte. */
        val commands: Map<Int, Int> get() = frames.groupingBy { it.cmd }.eachCount().toSortedMap()
    }

    /** Protocols documented in LibreHU (handoff 7.2 / 7.3, LibreHU-service core/mcu and core/can). */
    val KNOWN: Map<String, Spec> =
        mapOf(
            "Jancar JAC_V1 (MCU)" to Spec(listOf(0xEE, 0xFA), 2, LengthCounts.CMD_DATA, 0, 3, Checksum.SUM8, 0, 0),
            "Hiworld (CAN box)" to Spec(listOf(0x5A, 0xA5), 2, LengthCounts.DATA, 0, 3, Checksum.SUM8, 2, -1),
        )

    private const val MIN_FRAMES = 4

    /** Best guesses first; empty when nothing frames the capture. */
    fun guess(
        bytes: ByteArray,
        max: Int = 3,
    ): List<Guess> {
        if (bytes.size < 16) return emptyList()
        val results = ArrayList<Guess>()
        for (header in headers(bytes)) {
            val starts = occurrences(bytes, header)
            if (starts.size < MIN_FRAMES) continue
            val scored = layouts(header).map { it to chained(bytes, starts, it) }.toList()
            val top = scored.maxOfOrNull { it.second } ?: continue
            if (top < MIN_FRAMES || top < starts.size / 3) continue
            // Several layouts cut the same frames (length counting the command and a checksum, or neither): the
            // one whose checksum matches wins, then the simplest.
            val best =
                scored
                    .filter { it.second == top }
                    .map { finish(bytes, starts, it.first) }
                    .sortedWith(
                        compareByDescending<Guess> { if (it.spec.checksum != Checksum.NONE && it.checksumRate >= 0.9) 1 else 0 }
                            .thenByDescending { it.coverage }
                            .thenBy { kotlin.math.abs(it.spec.lengthAdd) }
                            .thenBy { it.spec.lengthCounts.ordinal },
                    ).first()
            results += best
        }
        return results
            .filter { it.frames.size >= MIN_FRAMES }
            .sortedWith(compareByDescending<Guess> { it.coverage * (0.5 + it.checksumRate / 2) }.thenByDescending { it.spec.header.size })
            .distinctBy { it.spec.header }
            .let { list ->
                // A 1-byte header that only repeats a 2-byte one already found is dropped.
                list.filterNot { g -> g.spec.header.size == 1 && list.any { it.spec.header.size > 1 && it.spec.header[0] == g.spec.header[0] && it.frames.size >= g.frames.size } }
            }.take(max)
    }

    /** Frames of [bytes] cut with a known [spec] (nested protocols, tests). */
    fun frames(
        bytes: ByteArray,
        spec: Spec,
    ): List<Frame> {
        val starts = occurrences(bytes, spec.header)
        return cut(bytes, starts, spec)
    }

    /** Draft `librehu-mcu-profile` v1 for LibreHU-service (inputs and outputs left to fill in). */
    fun profile(
        g: Guess,
        id: String,
        port: String,
        baud: Int,
    ): JSONObject {
        val s = g.spec
        return JSONObject()
            .put("format", "librehu-mcu-profile")
            .put("version", 1)
            .put("id", id)
            .put("name", "Draft $id (LibreHU Helper)")
            .put("description", "Framing guessed from a capture of $port at $baud baud: ${g.frames.size} frames, " + "%.0f %% of the bytes, checksum %.0f %%. Inputs and outputs to fill in.".format(g.coverage * 100, g.checksumRate * 100))
            .put("serial", JSONObject().put("port", port).put("baud", baud))
            .put(
                "frame",
                JSONObject()
                    .put("header", s.hexHeader())
                    .put("lengthOffset", s.lengthOffset)
                    .put("lengthCounts", s.lengthCounts.name.lowercase())
                    .put("lengthAdd", s.lengthAdd)
                    .put("cmdOffset", s.cmdOffset)
                    .put("checksum", s.checksum.name.lowercase())
                    .put("checksumFrom", s.checksumFrom)
                    .put("checksumAdjust", s.checksumAdjust)
                    .put("maxData", maxOf(s.maxData.coerceAtMost(255), g.frames.maxOfOrNull { it.data.size } ?: 0)),
            ).put("inputs", JSONArray())
            .put("outputs", JSONObject())
    }

    // --- Steps ---------------------------------------------------------------------------------------------------

    private fun headers(b: ByteArray): List<List<Int>> {
        val pairs = HashMap<Int, Int>()
        for (i in 0 until b.size - 1) {
            val k = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            pairs[k] = (pairs[k] ?: 0) + 1
        }
        val singles = IntArray(256)
        for (x in b) singles[x.toInt() and 0xFF]++
        val two =
            pairs.entries
                .filter { it.value >= MIN_FRAMES && it.key != 0x0000 && it.key != 0xFFFF }
                .sortedByDescending { it.value }
                .take(10)
                .map { listOf(it.key shr 8, it.key and 0xFF) }
        val one =
            (0..255)
                .filter { singles[it] >= MIN_FRAMES && it != 0x00 && it != 0xFF }
                .sortedByDescending { singles[it] }
                .take(4)
                .map { listOf(it) }
        return two + one
    }

    private fun occurrences(
        b: ByteArray,
        header: List<Int>,
    ): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i <= b.size - header.size) {
            if (header.indices.all { (b[i + it].toInt() and 0xFF) == header[it] }) out += i
            i++
        }
        return out
    }

    private fun layouts(header: List<Int>): Sequence<Spec> =
        sequence {
            val h = header.size
            for (lengthOffset in h..h + 2) {
                for (cmdOffset in listOf(lengthOffset + 1, lengthOffset - 1).filter { it >= h }) {
                    for (counts in LengthCounts.entries) {
                        for (add in listOf(0, 1, -1, 2, -2)) {
                            for (cs in listOf(Checksum.SUM8, Checksum.NONE)) {
                                yield(Spec(header, lengthOffset, counts, add, cmdOffset, cs))
                            }
                        }
                    }
                }
            }
        }

    /** Number of frames whose announced end is the next header (or the end of the capture). */
    private fun chained(
        b: ByteArray,
        starts: List<Int>,
        spec: Spec,
    ): Int {
        val set = starts.toHashSet()
        var n = 0
        for (s in starts) {
            if (s + spec.lengthOffset >= b.size) break
            val total = spec.total(b[s + spec.lengthOffset].toInt() and 0xFF)
            if (total <= spec.prefix) continue
            val end = s + total
            if (end == b.size || (end < b.size && end in set)) n++
        }
        return n
    }

    private fun cut(
        b: ByteArray,
        starts: List<Int>,
        spec: Spec,
    ): List<Frame> {
        val out = ArrayList<Frame>()
        var next = 0
        for (s in starts) {
            if (s < next || s + spec.prefix > b.size) continue
            val total = spec.total(b[s + spec.lengthOffset].toInt() and 0xFF)
            if (total <= 0 || s + total > b.size) continue
            val csEnd = if (spec.checksum == Checksum.NONE) s + total else s + total - 1
            val ok = spec.checksum == Checksum.NONE || (b[s + total - 1].toInt() and 0xFF) == spec.checksumOf(b, s, csEnd)
            out += Frame(s, total, b[s + spec.cmdOffset].toInt() and 0xFF, b.copyOfRange(s + spec.prefix, csEnd), ok)
            next = s + total
        }
        return out
    }

    private fun finish(
        b: ByteArray,
        starts: List<Int>,
        framing: Spec,
    ): Guess {
        var spec = framing
        if (framing.checksum != Checksum.NONE) {
            val frames = cut(b, starts, framing)
            var bestRate = -1.0
            for (type in listOf(Checksum.SUM8, Checksum.XOR8, Checksum.TWOS8, Checksum.XOR8_INV)) {
                for (from in 0..framing.prefix - 1) {
                    for (adjust in listOf(0, -1, 1, -2, 2)) {
                        val s = framing.copy(checksum = type, checksumFrom = from, checksumAdjust = adjust)
                        val ok =
                            frames.count { f ->
                                (b[f.start + f.size - 1].toInt() and 0xFF) == s.checksumOf(b, f.start, f.start + f.size - 1)
                            }
                        val rate = if (frames.isEmpty()) 0.0 else ok.toDouble() / frames.size
                        if (rate > bestRate + 1e-9) {
                            bestRate = rate
                            spec = s
                        }
                    }
                }
            }
            // A last byte matching nothing is data: same framing without checksum is not possible (sizes differ),
            // so the checksum stays, reported with its low rate.
        }
        val frames = cut(b, starts, spec)
        val covered = frames.sumOf { it.size }
        val rate = if (frames.isEmpty()) 0.0 else frames.count { it.checksumOk }.toDouble() / frames.size
        val known = KNOWN.entries.firstOrNull { (_, k) -> same(k, spec) }?.key
        return Guess(spec, known, frames, covered.toDouble() / b.size, rate)
    }

    /**
     * Same frames on the wire: the sizes the length byte gives (DATA + 1 = CMD_DATA…) and the checksum of sample
     * frames (Hiworld's sum from byte 2 minus 1 is also a sum from byte 0, since 5A + A5 = FF) are compared, not
     * the parameters.
     */
    private fun same(
        a: Spec,
        b: Spec,
    ): Boolean {
        if (a.header != b.header || a.lengthOffset != b.lengthOffset || a.cmdOffset != b.cmdOffset) return false
        if ((0..64).any { a.total(it) != b.total(it) }) return false
        if ((a.checksum == Checksum.NONE) != (b.checksum == Checksum.NONE)) return false
        if (a.checksum == Checksum.NONE) return true
        val rnd = kotlin.random.Random(7)
        return (0 until 16).all {
            val frame = ByteArray(a.prefix + rnd.nextInt(0, 12) + 1)
            rnd.nextBytes(frame)
            a.header.forEachIndexed { i, h -> frame[i] = h.toByte() }
            a.checksumOf(frame, 0, frame.size - 1) == b.checksumOf(frame, 0, frame.size - 1)
        }
    }
}
