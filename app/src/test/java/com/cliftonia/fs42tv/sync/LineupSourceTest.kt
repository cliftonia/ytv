package com.cliftonia.fs42tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineupSourceTest {

    @Test
    fun `nothing saved means youtube`() {
        assertEquals(LineupSource.YOUTUBE, LineupSource.parse(null))
        assertEquals(LineupSource.YOUTUBE, LineupSource.parse("something-removed"))
    }

    @Test
    fun `a saved source is read back`() {
        LineupSource.values().forEach { assertEquals(it, LineupSource.parse(it.name.lowercase())) }
    }

    @Test
    fun `next cycles through every source`() {
        assertEquals(LineupSource.LIVE, LineupSource.YOUTUBE.next())
        assertEquals(LineupSource.YOUTUBE, LineupSource.LIVE.next())
    }

    @Test
    fun `the settings row reads YOUTUBE and LIVE TV`() {
        assertEquals(listOf("YOUTUBE", "LIVE TV"), LineupSource.values().map { it.label })
    }

    @Test
    fun `youtube keeps the keys every installed app already uses`() {
        // Renaming either would reset the remembered channel, or orphan the cached dial, on the
        // first launch after the update.
        assertEquals("channel", LineupSource.YOUTUBE.channelKey)
        assertEquals("channels.json", LineupSource.YOUTUBE.cacheFile)
        assertEquals("https://raw.githubusercontent.com/cliftonia/ytv/main/channels.json",
            LineupSource.YOUTUBE.url)
    }

    @Test
    fun `live tv has its own file and its own remembered channel`() {
        assertEquals("live.json", LineupSource.LIVE.cacheFile)
        assertEquals("https://raw.githubusercontent.com/cliftonia/ytv/main/live.json",
            LineupSource.LIVE.url)
        assertNotEquals(LineupSource.YOUTUBE.channelKey, LineupSource.LIVE.channelKey)
    }

    @Test
    fun `a saved PLUTO TV choice is LIVE TV`() {
        assertEquals(LineupSource.LIVE, LineupSource.parse("pluto"))
        assertEquals(LineupSource.LIVE, LineupSource.parse("PLUTO"))
    }

    @Test
    fun `the saved pluto choice is rewritten as live, and nothing else is touched`() {
        assertEquals("live", LineupSource.migrated("pluto"))
        assertNull(LineupSource.migrated(null))
        assertNull(LineupSource.migrated("youtube"))
        assertNull(LineupSource.migrated("live"))
    }

    @Test
    fun `live tv starts on a fresh channel, never the pluto dial's remembered number`() {
        // Every number moved; the old key's channel would land somewhere arbitrary.
        assertNotEquals("channel.pluto", LineupSource.LIVE.channelKey)
        assertNotEquals("pluto.json", LineupSource.LIVE.cacheFile)
    }
}
