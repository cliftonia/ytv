package com.cliftonia.fs42tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals(LineupSource.PLUTO, LineupSource.YOUTUBE.next())
        assertEquals(LineupSource.YOUTUBE, LineupSource.PLUTO.next())
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
    fun `pluto has its own file and its own remembered channel`() {
        assertEquals("pluto.json", LineupSource.PLUTO.cacheFile)
        assertEquals("https://raw.githubusercontent.com/cliftonia/ytv/main/pluto.json",
            LineupSource.PLUTO.url)
        assertNotEquals(LineupSource.YOUTUBE.channelKey, LineupSource.PLUTO.channelKey)
    }
}
