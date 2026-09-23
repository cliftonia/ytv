package com.cliftonia.fs42tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * How a launch gets its dial: from the cache at once when there is one, from the network with a
 * card and a retry when there is not - and never twice, because a second delivery re-tunes a
 * viewer who is already watching.
 */
class DialLoaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private inner class Fixture(dir: File = tmp.newFolder()) {
        var network: () -> String = { error("offline") }
        val fetched = mutableListOf<String>()
        val delivered = mutableListOf<Int>()
        var cards = 0
        val retries = mutableListOf<Pair<Long, () -> Unit>>()
        val refreshes = mutableListOf<Runnable>()
        val cacheFile = File(dir, LineupSource.YOUTUBE.cacheFile)

        val loader = DialLoader(
            source = LineupSource.YOUTUBE,
            cacheDir = dir,
            executor = { it.run() },
            runOnUi = { it() },
            halted = { false },
            loaded = { delivered.isNotEmpty() },
            retry = { delay, block -> retries.add(delay to block) },
            onNoDial = { cards++ },
            onDial = { channels, _ -> delivered.add(channels.size) },
            elapsedMillis = { 0 },
            fetch = { url -> fetched.add(url); network() },
            refresh = { refreshes.add(it) },
        )
    }

    @Test
    fun `no cache and a working network delivers the fetched dial once and caches it`() {
        val f = Fixture()
        f.network = { fixture("channels-sample.json") }
        f.loader.load()
        assertEquals(1, f.delivered.size)
        assertTrue(f.cacheFile.exists())
        assertTrue("nothing left to refresh", f.refreshes.isEmpty())
    }

    @Test
    fun `no cache and no network puts the card up and retries`() {
        val f = Fixture()
        f.loader.load()
        assertEquals(1, f.cards)
        assertTrue(f.delivered.isEmpty())
        assertEquals(30_000L, f.retries.single().first)
        // The hotspot comes up; the retry revives the dial without a relaunch.
        f.network = { fixture("channels-sample.json") }
        f.retries.single().second()
        assertEquals(1, f.delivered.size)
    }

    @Test
    fun `an empty lineup is no lineup`() {
        // `{}` parses, and caching it would overwrite the last good dial with nothing.
        val f = Fixture()
        f.network = { "{}" }
        f.loader.load()
        assertEquals(1, f.cards)
        assertTrue(f.delivered.isEmpty())
    }

    @Test
    fun `a retry that races a success does nothing`() {
        val f = Fixture()
        f.loader.load()
        f.delivered.add(1)                       // a dial arrived some other way meanwhile
        f.retries.single().second()
        assertEquals(1, f.fetched.size)
    }

    @Test
    fun `a cached dial is delivered at once, before any fetch`() {
        val f = Fixture()
        f.cacheFile.writeText(fixture("channels-sample.json"))
        f.loader.load()
        assertEquals(1, f.delivered.size)
        assertTrue("the first picture waits on no network", f.fetched.isEmpty())
        assertEquals(0, f.cards)
    }

    @Test
    fun `the cache-first refresh updates the file for next launch without delivering again`() {
        val f = Fixture()
        f.cacheFile.writeText(fixture("channels-sample.json"))
        f.loader.load()
        f.network = { fixture("pluto-sample.json") }
        f.refreshes.single().run()
        assertEquals("delivered exactly once - a second would re-tune the viewer", 1, f.delivered.size)
        assertEquals(fixture("pluto-sample.json"), f.cacheFile.readText())
    }

    @Test
    fun `a failed refresh leaves the cached dial alone`() {
        val f = Fixture()
        val good = fixture("channels-sample.json")
        f.cacheFile.writeText(good)
        f.loader.load()
        f.refreshes.single().run()               // offline
        assertEquals(good, f.cacheFile.readText())
        assertEquals(0, f.cards)
        assertTrue(f.retries.isEmpty())
    }
}
