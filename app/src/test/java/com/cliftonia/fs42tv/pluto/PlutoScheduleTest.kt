package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pluto's per-channel guide: finding the channel id, reading the reply, and the lines drawn.
 *
 * The fixture is a REAL reply from `api.pluto.tv/v2/channels/<id>`, captured from Australia on
 * 23 Sep 2026 and trimmed of the fields nothing reads. A shape mismatch here is not a crash; it
 * is a banner that silently never shows a programme, which nobody would think to report.
 */
class PlutoScheduleTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val brisbane = ZoneId.of("Australia/Brisbane")

    @Test
    fun `the channel id comes out of the jmp2 stream url`() {
        assertEquals("68487fb3f212bedacf5a53e3",
            PlutoIds.idFrom("https://jmp2.uk/plu-68487fb3f212bedacf5a53e3.m3u8"))
    }

    @Test
    fun `anything that is not a pluto url has no id`() {
        assertNull(PlutoIds.idFrom("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertNull(PlutoIds.idFrom("https://jmp2.uk/plu-short.m3u8"))
        assertNull(PlutoIds.idFrom(""))
    }

    @Test
    fun `a channel is pluto by its first stream, whatever its name`() {
        val pluto = Channel(1, "50 Cent Action", "live", streams = listOf(
            Stream(url = "https://jmp2.uk/plu-68487fb3f212bedacf5a53e3.m3u8", duration = 600)))
        val youtube = Channel(2, "Pluto Fans", "youtube", "clock", listOf(
            Stream(id = "aqz-KE-bpKQ", url = "https://youtu.be/aqz-KE-bpKQ", duration = 600)))
        assertEquals("68487fb3f212bedacf5a53e3", PlutoIds.of(pluto))
        assertNull(PlutoIds.of(youtube))
        assertNull(PlutoIds.of(Channel(3, "Empty", "live")))
    }

    @Test
    fun `a published pluto id wins over the one in the url`() {
        val channel = Channel(1, "Hallmark", "live",
            streams = listOf(Stream(url = "https://jmp2.uk/plu-68487fb3f212bedacf5a53e3.m3u8", duration = 600)),
            pluto = com.cliftonia.fs42tv.sync.PlutoRef("628e685ba3811100070551a8", "us"))
        assertEquals("628e685ba3811100070551a8", PlutoIds.of(channel))
    }

    @Test
    fun `the request asks for six hours from now, whole seconds`() {
        val url = PlutoApi.url("68487fb3f212bedacf5a53e3", at("2026-09-23T02:30:00.456Z"))
        assertEquals("https://api.pluto.tv/v2/channels/68487fb3f212bedacf5a53e3" +
            "?start=2026-09-23T02:30:00Z&stop=2026-09-23T08:30:00Z", url)
    }

    @Test
    fun `parses the real reply`() {
        val schedule = PlutoApi.parse(fixture("pluto-channel-sample.json"))
        assertNotNull(schedule)
        assertEquals(4, schedule!!.programmes.size)
        val first = schedule.programmes.first()
        assertEquals("Transporter 3", first.title)
        assertEquals(at("2026-09-23T02:00:36.000Z"), first.startMillis)
        assertEquals(at("2026-09-23T03:55:52.000Z"), first.stopMillis)
        // logo.path, the small 280x80 rendition, ahead of the full-size colour PNG.
        assertTrue(schedule.logoUrl!!.contains("/logo.png"))
    }

    @Test
    fun `now and next at a moment inside the first programme`() {
        val schedule = PlutoApi.parse(fixture("pluto-channel-sample.json"))!!
        val now = at("2026-09-23T03:00:00Z")
        assertEquals("Transporter 3", schedule.onAt(now)?.title)
        assertEquals("Carjacked", schedule.nextAfter(now)?.title)
    }

    @Test
    fun `a boundary belongs to the programme starting, not the one ending`() {
        val schedule = PlutoApi.parse(fixture("pluto-channel-sample.json"))!!
        val boundary = at("2026-09-23T03:55:52Z")
        assertEquals("Carjacked", schedule.onAt(boundary)?.title)
        assertEquals("The Command", schedule.nextAfter(boundary)?.title)
    }

    @Test
    fun `nothing on air, nothing to say`() {
        val schedule = PlutoApi.parse(fixture("pluto-channel-sample.json"))!!
        assertNull(schedule.onAt(at("2026-09-24T00:00:00Z")))
        assertNull(PlutoLines.banner(schedule, at("2026-09-24T00:00:00Z"), brisbane))
    }

    @Test
    fun `the cache holds until the programme on air ends`() {
        val schedule = PlutoApi.parse(fixture("pluto-channel-sample.json"))!!
        assertEquals(at("2026-09-23T03:55:52Z"), schedule.freshUntil(at("2026-09-23T03:00:00Z")))
    }

    @Test
    fun `a reply that is not a guide is null, not an exception`() {
        assertNull(PlutoApi.parse("<html>rate limited</html>"))
        assertNull(PlutoApi.parse(""))
        // Valid JSON with nothing in it: not worth caching as a schedule.
        assertNull(PlutoApi.parse("{}"))
    }

    @Test
    fun `an entry missing its title falls back to the episode name`() {
        val text = """{"timelines":[{"start":"2026-09-23T02:00:00Z","stop":"2026-09-23T03:00:00Z",
            "title":"","episode":{"name":"Pilot"}}]}"""
        assertEquals("Pilot", PlutoApi.parse(text)!!.programmes.single().title)
    }

    @Test
    fun `an entry with an unreadable time is dropped, not fatal`() {
        val text = """{"timelines":[{"start":"yesterday","stop":"2026-09-23T03:00:00Z","title":"A"},
            {"start":"2026-09-23T03:00:00Z","stop":"2026-09-23T04:00:00Z","title":"B"}]}"""
        assertEquals(listOf("B"), PlutoApi.parse(text)!!.programmes.map { it.title })
    }

    @Test
    fun `banner lines read NOW and NEXT with the local start time`() {
        val schedule = PlutoApi.parse(fixture("pluto-channel-sample.json"))!!
        val lines = PlutoLines.banner(schedule, at("2026-09-23T03:00:00Z"), brisbane)
        // 03:55:52Z is 1:55 PM in Brisbane (UTC+10, no daylight saving).
        assertEquals("Transporter 3" to "NEXT 1:55 PM Carjacked", lines)
    }

    @Test
    fun `the guide row fits both on one line`() {
        val schedule = PlutoApi.parse(fixture("pluto-channel-sample.json"))!!
        assertEquals("Transporter 3 | NEXT 1:55 PM Carjacked",
            PlutoLines.guideRow(schedule, at("2026-09-23T03:00:00Z"), brisbane))
    }

    @Test
    fun `the last programme in the window has no next line`() {
        val schedule = PlutoApi.parse(fixture("pluto-channel-sample.json"))!!
        val lines = PlutoLines.banner(schedule, at("2026-09-23T09:00:00Z"), brisbane)
        assertEquals("Hunter Killer" to "", lines)
    }
}
