package com.cliftonia.fs42tv.fast

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuePlaylistTest {

    private fun cues(body: String) = CuePlaylist.parse(body)!!.segments.map { it.cues }

    private fun media(vararg lines: String) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:100")
        lines.forEach { appendLine(it) }
    }

    @Test
    fun `CUE-OUT with a duration, in every spelling, marks the segment after it`() {
        listOf("#EXT-X-CUE-OUT:DURATION=120", "#EXT-X-CUE-OUT:120.000", "#EXT-X-CUE-OUT:DURATION=120,ID=7").forEach { tag ->
            val parsed = cues(media("#EXTINF:6.0,", "a.ts", tag, "#EXTINF:6.0,", "b.ts"))
            assertEquals(tag, listOf(emptyList(), listOf(Cue.Out(120_000))), parsed)
        }
    }

    @Test
    fun `a bare CUE-OUT has no duration`() {
        assertEquals(listOf(listOf(Cue.Out(null))), cues(media("#EXT-X-CUE-OUT", "#EXTINF:6.0,", "a.ts")))
    }

    @Test
    fun `CUE-OUT-CONT says how far into the break a segment is, in both spellings`() {
        val parsed = cues(media(
            "#EXT-X-CUE-OUT-CONT:ElapsedTime=30.5,Duration=120,SCTE35=/DAl", "#EXTINF:6.0,", "a.ts",
            "#EXT-X-CUE-OUT-CONT:36/120", "#EXTINF:6.0,", "b.ts",
            "#EXT-X-CUE-OUT-CONT:ElapsedTime=42", "#EXTINF:6.0,", "c.ts",
        ))
        assertEquals(listOf(
            listOf(Cue.Cont(30_500, 120_000)),
            listOf(Cue.Cont(36_000, 120_000)),
            listOf(Cue.Cont(42_000, null)),
        ), parsed)
    }

    @Test
    fun `CUE-IN marks the programme's first segment back`() {
        assertEquals(listOf(emptyList(), listOf(Cue.In)),
            cues(media("#EXTINF:6.0,", "a.ts", "#EXT-X-DISCONTINUITY", "#EXT-X-CUE-IN", "#EXTINF:6.0,", "b.ts")))
    }

    @Test
    fun `segments are numbered from the media sequence, with EXTINF lengths and PDTs`() {
        val window = CuePlaylist.parse(media(
            "#EXT-X-PROGRAM-DATE-TIME:2026-09-28T01:00:00.000Z", "#EXTINF:6.006,", "a.ts",
            "#EXTINF:5.5,", "b.ts",
        ))!!
        assertEquals(6_000L, window.targetDurationMillis)
        assertEquals(listOf(100L, 101L), window.segments.map { it.seq })
        assertEquals(listOf(6_006L, 5_500L), window.segments.map { it.durationMillis })
        assertEquals(Instant.parse("2026-09-28T01:00:00Z").toEpochMilli(), window.segments[0].programDateTime)
        assertNull(window.segments[1].programDateTime)
    }

    @Test
    fun `OATCLS is read when a segment has no CUE tag, and yields to one when it has`() {
        val out = Scte35Bytes.spliceInsert(out = true, durationMillis = 90_000)
        val back = Scte35Bytes.spliceInsert(out = false, durationMillis = null)
        val parsed = cues(media(
            "#EXT-OATCLS-SCTE35:$out", "#EXTINF:6.0,", "a.ts",
            "#EXT-OATCLS-SCTE35:$back", "#EXTINF:6.0,", "b.ts",
            "#EXT-OATCLS-SCTE35:$out", "#EXT-X-CUE-OUT:60", "#EXTINF:6.0,", "c.ts",
            "#EXT-X-SCTE35:CUE=\"$back\",ID=\"9\"", "#EXTINF:6.0,", "d.ts",
        ))
        assertEquals(listOf(listOf(Cue.Out(90_000)), listOf(Cue.In), listOf(Cue.Out(60_000)), listOf(Cue.In)), parsed)
    }

    @Test
    fun `a DATERANGE is kept by ID with its SCTE35 side and times`() {
        val window = CuePlaylist.parse(media(
            "#EXT-X-DATERANGE:ID=\"b1\",START-DATE=\"2026-09-28T01:00:00Z\",PLANNED-DURATION=90,SCTE35-OUT=0xFC30",
            "#EXTINF:6.0,", "a.ts",
            "#EXT-X-DATERANGE:ID=\"b1\",START-DATE=\"2026-09-28T01:00:00Z\",END-DATE=\"2026-09-28T01:01:15Z\",SCTE35-IN=0xFC30",
            "#EXTINF:6.0,", "b.ts",
        ))!!
        val merged = window.dates[0].merged(window.dates[1])
        assertTrue(merged.out && merged.into)
        assertEquals(Instant.parse("2026-09-28T01:00:00Z").toEpochMilli(), merged.startMillis)
        assertEquals("END-DATE over PLANNED-DURATION", 75_000L, merged.durationMillis)
        assertEquals(90_000L, window.dates[0].durationMillis)
    }

    @Test
    fun `not a media playlist says nothing`() {
        assertNull(CuePlaylist.parse(null))
        assertNull(CuePlaylist.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n"))
        assertNull(CuePlaylist.parse("<html>502</html>"))
    }
}

class Scte35Test {

    @Test
    fun `splice_insert out with its break duration, and back in`() {
        assertEquals(Scte35.Splice.Out(90_000), Scte35.decode(Scte35Bytes.spliceInsert(true, 90_000)))
        assertEquals(Scte35.Splice.Out(null), Scte35.decode(Scte35Bytes.spliceInsert(true, null)))
        assertEquals(Scte35.Splice.In, Scte35.decode(Scte35Bytes.spliceInsert(false, null)))
    }

    @Test
    fun `time_signal by its segmentation type`() {
        assertEquals(Scte35.Splice.Out(30_000), Scte35.decode(Scte35Bytes.timeSignal(0x34, 30_000)))
        assertEquals(Scte35.Splice.In, Scte35.decode(Scte35Bytes.timeSignal(0x35, null)))
        assertNull("a programme start is not a break", Scte35.decode(Scte35Bytes.timeSignal(0x10, null)))
    }

    @Test
    fun `junk is no cue`() {
        assertNull(Scte35.decode("not base64 at all!"))
        assertNull(Scte35.decode("AAAA"))
        assertNull(Scte35.decode(""))
    }
}

/** Hand-built splice_info_sections, for the tests. */
object Scte35Bytes {

    private fun section(type: Int, command: ByteArray, descriptors: ByteArray = ByteArray(0)): String {
        val head = byteArrayOf(0xFC.toByte(), 0x30, 0x00, 0x00, 0x00, 0, 0, 0, 0, 0,
            0xFF.toByte(), (0xF0 or (command.size shr 8)).toByte(), command.size.toByte(), type.toByte())
        val loop = byteArrayOf((descriptors.size shr 8).toByte(), descriptors.size.toByte())
        return java.util.Base64.getEncoder().encodeToString(head + command + loop + descriptors + ByteArray(4))
    }

    private fun duration40(millis: Long, top: Int): ByteArray {
        val ticks = millis * 90
        val v = (top.toLong() shl 33) or ticks
        return ByteArray(5) { i -> (v shr (8 * (4 - i))).toByte() }
    }

    fun spliceInsert(out: Boolean, durationMillis: Long?): String {
        val flags = (if (out) 0x80 else 0) or 0x40 or (if (durationMillis != null) 0x20 else 0) or 0x10 or 0x0F
        val command = byteArrayOf(0, 0, 0, 1, 0x7F, flags.toByte()) +
            (durationMillis?.let { duration40(it, 0x7F) } ?: ByteArray(0)) + byteArrayOf(0, 1, 0, 0)
        return section(0x05, command)
    }

    fun timeSignal(typeId: Int, durationMillis: Long?): String {
        val command = byteArrayOf(0xFE.toByte(), 0, 0, 0, 0)
        val body = "CUEI".toByteArray() + byteArrayOf(0, 0, 0, 2, 0x7F,
            (0x80 or (if (durationMillis != null) 0x40 else 0) or 0x3F).toByte()) +
            (durationMillis?.let { duration40(it, 0) } ?: ByteArray(0)) +
            byteArrayOf(0, 0, typeId.toByte(), 1, 1)
        return section(0x06, command, byteArrayOf(0x02, body.size.toByte()) + body)
    }
}
