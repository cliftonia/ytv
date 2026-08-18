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
        val repo = DialRepository(fetch = { fixture("channels-sample.json") }, cacheDir = dir)
        val result = repo.sync("http://example/channels.json")
        assertTrue(result.dial.channels.isNotEmpty())
        assertNotNull("without a cached copy the app is dead the moment the lineup is unreachable",
            repo.cachedDial())
    }

    @Test
    fun `sync clears the url cache left by the version that published one`() {
        // Signed urls last about six hours; the file this deletes was published nightly by a
        // server that no longer exists, so anything still on disk expired long ago. Freshness
        // checks would reject it anyway - this is about not carrying three megabytes of dead
        // json around on a device with 2.3GB of storage.
        val dir = java.nio.file.Files.createTempDirectory("fs42").toFile()
        val stale = java.io.File(dir, "urls.json").apply { writeText("{}") }
        DialRepository(fetch = { fixture("channels-sample.json") }, cacheDir = dir)
            .sync("http://example/channels.json")
        assertTrue("the stale url cache should be gone after a sync", !stale.exists())
    }

    @Test
    fun `a malformed response is not cached over a good one`() {
        // This is the whole reason parsing happens before writing. GitHub can serve a truncated
        // file mid-push, and the cached lineup is the only thing standing between that and a
        // television with no channels - which, in the car, is a television with no way to recover.
        val dir = java.nio.file.Files.createTempDirectory("fs42").toFile()
        DialRepository({ fixture("channels-sample.json") }, dir).sync("http://example/channels.json")
        val bad = DialRepository({ "{ this is not json" }, dir)
        runCatching { bad.sync("http://example/channels.json") }
        assertTrue("a bad response must never destroy the last good cache",
            bad.cachedDial()!!.channels.isNotEmpty())
    }

    @Test
    fun `a lineup that parses but has no channels is refused`() {
        // `{}` and `{"channels":[]}` both parse perfectly, because `channels` defaults to empty.
        // Caching that would overwrite the last good lineup with nothing, which in the car is a
        // television with no dial and no way back except a good fetch.
        val dir = java.nio.file.Files.createTempDirectory("fs42").toFile()
        DialRepository({ fixture("channels-sample.json") }, dir).sync("http://example/channels.json")
        val empty = DialRepository({ """{"generated":1,"channels":[]}""" }, dir)
        val outcome = runCatching { empty.sync("http://example/channels.json") }
        assertTrue("an empty lineup must be rejected rather than cached", outcome.isFailure)
        assertTrue("the last good lineup must survive an empty publish",
            empty.cachedDial()!!.channels.isNotEmpty())
    }
}
