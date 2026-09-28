package com.cliftonia.fs42tv.fast

/**
 * The little of SCTE-35 a break needs, out of a base64 `splice_info_section` - what
 * `#EXT-OATCLS-SCTE35:` and `#EXT-X-SCTE35:CUE=` carry when a stream has no CUE-OUT of its own.
 *
 * Two commands say "out" or "in": `splice_insert` (0x05) by its out-of-network bit and optional
 * break duration, and `time_signal` (0x06) by its segmentation descriptors' type - a break or an
 * ad/placement-opportunity start or end. Anything else, a cancel, or bytes that do not add up is
 * null: a cue that cannot be read is no cue, never a break.
 *
 * Pure.
 */
object Scte35 {

    /** What one section says: out to a break of [durationMillis] (when given), or back in. */
    sealed interface Splice {
        data class Out(val durationMillis: Long?) : Splice
        object In : Splice
    }

    private const val TABLE_ID = 0xFC
    private const val SPLICE_INSERT = 0x05
    private const val TIME_SIGNAL = 0x06
    private const val SEGMENTATION_DESCRIPTOR = 0x02

    /** Segmentation types that open a break: break, and provider/distributor ad or placement. */
    private val STARTS = setOf(0x22, 0x30, 0x32, 0x34, 0x36, 0x38, 0x3A, 0x3C, 0x3E)
    private val ENDS = STARTS.map { it + 1 }.toSet()

    fun decode(base64: String): Splice? = runCatching {
        val bytes = java.util.Base64.getDecoder().decode(base64.trim().trim('"'))
        parse(Bits(bytes))
    }.getOrNull()

    private fun parse(b: Bits): Splice? {
        if (b.u(8) != TABLE_ID.toLong()) return null
        b.skip(2 + 2 + 12 + 8) // syntax, private, sap, section_length, protocol_version
        if (b.u(1) == 1L) return null // encrypted
        b.skip(6 + 33 + 8 + 12) // encryption algorithm, pts_adjustment, cw_index, tier
        val commandLength = b.u(12).toInt()
        val type = b.u(8).toInt()
        val commandStart = b.pos
        if (type == SPLICE_INSERT) return spliceInsert(b)
        if (type != TIME_SIGNAL) return null
        // A command length of 0xFFF is the legacy "unknown"; time_signal is one splice_time().
        if (commandLength != 0xFFF) b.pos = commandStart + commandLength * 8 else spliceTime(b)
        return descriptors(b)
    }

    private fun spliceInsert(b: Bits): Splice? {
        b.skip(32) // splice_event_id
        if (b.u(1) == 1L) return null // cancelled
        b.skip(7)
        val out = b.u(1) == 1L
        val program = b.u(1) == 1L
        val hasDuration = b.u(1) == 1L
        val immediate = b.u(1) == 1L
        b.skip(4)
        if (program && !immediate) spliceTime(b)
        if (!program) {
            repeat(b.u(8).toInt()) {
                b.skip(8)
                if (!immediate) spliceTime(b)
            }
        }
        val duration = if (hasDuration) {
            b.skip(1 + 6)
            b.u(33) / 90
        } else {
            null
        }
        return if (out) Splice.Out(duration) else Splice.In
    }

    private fun spliceTime(b: Bits) {
        if (b.u(1) == 1L) b.skip(6 + 33) else b.skip(7)
    }

    private fun descriptors(b: Bits): Splice? {
        val loopEnd = b.u(16).toInt() * 8 + b.pos
        while (b.pos < loopEnd) {
            val tag = b.u(8).toInt()
            val length = b.u(8).toInt()
            val next = b.pos + length * 8
            if (tag == SEGMENTATION_DESCRIPTOR) segmentation(b)?.let { return it }
            b.pos = next
        }
        return null
    }

    private fun segmentation(b: Bits): Splice? {
        b.skip(32 + 32) // identifier "CUEI", segmentation_event_id
        if (b.u(1) == 1L) return null // cancelled
        b.skip(7)
        val program = b.u(1) == 1L
        val hasDuration = b.u(1) == 1L
        b.skip(6)
        if (!program) b.skip(b.u(8).toInt() * 48)
        val duration = if (hasDuration) b.u(40) / 90 else null
        b.skip(8)
        b.skip(b.u(8).toInt() * 8) // upid
        return when (b.u(8).toInt()) {
            in STARTS -> Splice.Out(duration)
            in ENDS -> Splice.In
            else -> null
        }
    }

    /** Big-endian bit reader; reading past the end throws, which [decode] turns into null. */
    private class Bits(private val bytes: ByteArray) {
        var pos = 0

        fun u(n: Int): Long {
            var v = 0L
            repeat(n) {
                val byte = bytes[pos / 8].toInt()
                v = (v shl 1) or ((byte shr (7 - pos % 8)) and 1).toLong()
                pos++
            }
            return v
        }

        fun skip(n: Int) {
            require(pos + n <= bytes.size * 8)
            pos += n
        }
    }
}
