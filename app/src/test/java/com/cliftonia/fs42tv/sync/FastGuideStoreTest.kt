package com.cliftonia.fs42tv.sync

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide file on the television: cached, read from disk first, downloaded a few times a day,
 * and never asked for by a channel that has no FAST guide.
 */
class FastGuideStoreTest {

    private val base = 1_790_553_600L

    private fun guideText(title: String) =
        """{"generated": $base, "base": $base, "titles": ["", "$title"],
           "channels": {"samsung:US1": [0, 1, 600, 0]}}"""

    private val fast = Channel(number = 100, name = "F", kind = "live", guide = "samsung", guideId = "US1")
    private val pluto = Channel(number = 101, name = "P", kind = "live", pluto = PlutoRef("x"))

    private inner class Fixture(
        var now: Long = (base + 60) * 1000,
        var enabled: Boolean = true,
        var reply: () -> String = { guideText("Downloaded") },
    ) {
        val dir: File = java.nio.file.Files.createTempDirectory("fs42").toFile()
        val file = File(dir, FastGuideStore.FILE_NAME)
        val queued = mutableListOf<Runnable>()
        var fetches = 0
        val store = FastGuideStore(
            file = file,
            fetch = { fetches++; reply() },
            executor = Executor { queued += it },
            nowMillis = { now },
            enabled = { enabled },
        )

        fun drain() {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
        }
    }

    @Test
    fun `the first ask loads, answers nothing yet, and calls back once it has the file`() {
        val f = Fixture()
        var called = 0
        assertNull(f.store.titleOn(fast) { called++ })
        f.drain()
        assertEquals(1, called)
        assertEquals("Downloaded", f.store.titleOn(fast))
        assertTrue("what was downloaded is kept for the next launch", f.file.exists())
    }

    @Test
    fun `a fresh cached copy answers without the network`() {
        val f = Fixture()
        f.file.writeText(guideText("Cached"))
        f.file.setLastModified(f.now - 60_000)
        f.store.titleOn(fast)
        f.drain()
        assertEquals("Cached", f.store.titleOn(fast))
        assertEquals(0, f.fetches)
    }

    @Test
    fun `a stale cached copy answers first and is replaced by a download`() {
        val f = Fixture()
        f.file.writeText(guideText("Cached"))
        f.file.setLastModified(f.now - FastGuideStore.REFRESH_MILLIS - 1)
        f.store.titleOn(fast)
        f.drain()
        assertEquals(1, f.fetches)
        assertEquals("Downloaded", f.store.titleOn(fast))
    }

    @Test
    fun `a failed download is not retried until the retry wait has passed`() {
        val f = Fixture(reply = { error("offline") })
        f.store.titleOn(fast)
        f.drain()
        f.store.titleOn(fast)
        f.drain()
        assertEquals(1, f.fetches)
        f.now += FastGuideStore.RETRY_MILLIS
        f.reply = { guideText("Later") }
        f.store.titleOn(fast)
        f.drain()
        assertEquals("Later", f.store.titleOn(fast))
    }

    @Test
    fun `a bad download is never cached over a good copy`() {
        val f = Fixture(reply = { "<html>portal</html>" })
        f.file.writeText(guideText("Cached"))
        f.file.setLastModified(f.now - FastGuideStore.REFRESH_MILLIS - 1)
        f.store.titleOn(fast)
        f.drain()
        assertEquals("Cached", f.store.titleOn(fast))
        assertTrue(f.file.readText().contains("Cached"))
    }

    @Test
    fun `a pluto channel, or the row switched off, asks for nothing`() {
        val f = Fixture()
        assertNull(f.store.titleOn(pluto))
        f.enabled = false
        assertNull(f.store.titleOn(fast))
        assertTrue(f.queued.isEmpty())
    }

    @Test
    fun `the dial coming up loads a stale guide before any channel asks, unless the row is off`() {
        val f = Fixture(enabled = false)
        f.file.writeText(guideText("Cached"))
        f.file.setLastModified(f.now - FastGuideStore.LAUNCH_STALE_MILLIS - 1)
        f.store.lineupSeen("A")
        assertTrue(f.queued.isEmpty())
        f.enabled = true
        f.store.lineupSeen("A")
        f.drain()
        assertEquals(1, f.fetches)
        assertEquals("Downloaded", f.store.titleOn(fast))
    }

    @Test
    fun `many asks while loading start one load`() {
        val f = Fixture()
        repeat(5) { f.store.titleOn(fast) }
        assertEquals(1, f.queued.size)
    }
}
