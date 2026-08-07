package com.cliftonia.fs42tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These parse the REAL published files, captured from the live server. A contract
 * mismatch is not a crash at the boundary - it is a dial that renders and will not
 * tune, discovered on a television with no debugger attached.
 */
class DialContractTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `parses the real published dial`() {
        val dial = DialContract.parseDial(fixture("channels-sample.json"))
        assertTrue("the real dial has around 111 channels; far fewer means a parse that silently dropped some",
            dial.channels.size > 50)
        assertTrue("channels must arrive sorted, because surfing walks the list in order",
            dial.channels.map { it.number } == dial.channels.map { it.number }.sorted())
    }

    @Test
    fun `a youtube channel carries ids and a clock rotation`() {
        val dial = DialContract.parseDial(fixture("channels-sample.json"))
        val youtube = dial.channels.first { it.kind == "youtube" }
        assertEquals("clock", youtube.rotation)
        assertNotNull("without an id the app cannot look up a pre-resolved URL",
            youtube.streams.first().id)
    }

    @Test
    fun `a live channel has no id and no rotation`() {
        val dial = DialContract.parseDial(fixture("channels-sample.json"))
        val live = dial.channels.first { it.kind == "live" }
        assertNull("a live stream has no video id and must not fake one", live.streams.first().id)
        assertNull("a live stream has no clock position to compute", live.rotation)
    }

    @Test
    fun `non-latin titles survive parsing`() {
        val dial = DialContract.parseDial(fixture("channels-sample.json"))
        val titles = dial.channels.flatMap { it.streams }.map { it.title }
        assertTrue("titles are UTF-8 and often non-Latin; mangling them would show as garbage on screen",
            titles.any { it.any { ch -> ch.code > 0x7F } })
    }

    @Test
    fun `parses the real url cache and its tiers`() {
        val cache = DialContract.parseUrls(fixture("urls-sample.json"))
        assertTrue("an empty cache would mean every tune pays a server round trip",
            cache.urls.isNotEmpty())
        val tiers = cache.urls.values.first()
        assertTrue("hd is the tier every device can play and must always be present",
            tiers.containsKey("hd"))
    }

    @Test
    fun `an unknown field does not break parsing`() {
        val json = """{"generated":1,"channels":[{"number":1,"name":"X","kind":"live",
            "rotation":null,"streams":[],"somethingNew":true}]}"""
        val dial = DialContract.parseDial(json)
        assertEquals("the server must be free to add fields without breaking every installed app",
            1, dial.channels.size)
    }

    @Test
    fun `sync caches what it fetched`() {
        val dir = java.nio.file.Files.createTempDirectory("fs42").toFile()
        val repo = DialRepository(
            fetch = { url -> if (url.endsWith("channels.json")) fixture("channels-sample.json")
                             else fixture("urls-sample.json") },
            cacheDir = dir,
        )
        val dial = repo.sync("http://example")
        assertTrue(dial.channels.isNotEmpty())
        assertNotNull("without a cached copy the app is dead the moment the server is unreachable",
            repo.cachedDial())
    }

    @Test
    fun `a malformed response is not cached over a good one`() {
        val dir = java.nio.file.Files.createTempDirectory("fs42").toFile()
        val good = DialRepository({ if (it.endsWith("channels.json")) fixture("channels-sample.json")
                                    else fixture("urls-sample.json") }, dir)
        good.sync("http://example")
        val bad = DialRepository({ "{ this is not json" }, dir)
        runCatching { bad.sync("http://example") }
        assertTrue("a bad response must never destroy the last good cache",
            bad.cachedDial()!!.channels.isNotEmpty())
    }

    @Test
    fun `a good dial paired with a malformed url cache is not cached over a good one`() {
        val dir = java.nio.file.Files.createTempDirectory("fs42").toFile()
        val goodFetch: (String) -> String = { url ->
            if (url.endsWith("channels.json")) fixture("channels-sample.json") else fixture("urls-sample.json")
        }
        val good = DialRepository(goodFetch, dir)
        good.sync("http://example")
        val goodChannelNumbers = good.cachedDial()!!.channels.map { it.number }
        assertTrue("the cache must have something to protect before the failure case is exercised",
            goodChannelNumbers.isNotEmpty())

        // A different, but individually valid, channels.json - distinguishable from the
        // one cached above so an early write can be told apart from no write at all.
        val newButUnrelatedChannels = """{"generated":2,"channels":[{"number":999,"name":"Impostor",
            "kind":"live","rotation":null,"streams":[]}]}"""
        val halfBad = DialRepository(
            fetch = { url -> if (url.endsWith("channels.json")) newButUnrelatedChannels
                             else "{ this is not json" },
            cacheDir = dir,
        )
        runCatching { halfBad.sync("http://example") }
        assertEquals("a half-failed sync must not destroy the fallback that exists for when the server is unreachable",
            goodChannelNumbers, halfBad.cachedDial()!!.channels.map { it.number })
    }
}
