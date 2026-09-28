package com.cliftonia.fs42tv.sync

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How fresh `fast_guide.json` and `details.json` are kept: stale at launch past half an hour,
 * hourly while running, ten minutes after a failure, and at once for a new lineup.
 */
class PublishedFileTest {

    private val minute = 60_000L
    private val start = 1_790_553_600_000L

    private inner class Fixture(
        val dir: File = java.nio.file.Files.createTempDirectory("fs42").toFile(),
        var now: Long = start,
        var reply: () -> String = { "good:downloaded" },
    ) {
        val file = File(dir, "published.json")
        val queued = mutableListOf<Runnable>()
        var fetches = 0

        /** While set, a download runs this first - something happening while it is under way. */
        var during: (() -> Unit)? = null
        val published = PublishedFile(
            file = file, url = "https://example.invalid/published.json",
            fetch = { fetches++; during?.invoke(); reply() },
            parse = { text -> text.takeIf { it.startsWith("good:") } ?: error("not ours") },
            executor = Executor { queued += it }, nowMillis = { now },
            refreshMillis = PublishedFile.REFRESH_MILLIS, retryMillis = PublishedFile.RETRY_MILLIS,
            label = "test", launchStaleMillis = PublishedFile.LAUNCH_STALE_MILLIS,
        )

        fun cached(text: String, ageMillis: Long, stamp: String? = null) {
            file.writeText(text)
            file.setLastModified(now - ageMillis)
            if (stamp != null) File(file.path + ".lineup").writeText(stamp)
        }

        fun drain() {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
        }

        fun seen(stamp: String) {
            published.lineupSeen(stamp)
            drain()
        }
    }

    @Test
    fun `at launch a copy under half an hour old is kept`() {
        val f = Fixture()
        f.cached("good:cached", 29 * minute, stamp = "A")
        f.seen("A")
        assertEquals(0, f.fetches)
        assertEquals("good:cached", f.published.get())
    }

    @Test
    fun `at launch a copy over half an hour old is downloaded again, before anything asks`() {
        val f = Fixture()
        f.cached("good:cached", 31 * minute, stamp = "A")
        f.seen("A")
        assertEquals(1, f.fetches)
        assertEquals("good:downloaded", f.published.get())
    }

    @Test
    fun `while running, at most hourly`() {
        val f = Fixture()
        f.cached("good:cached", 20 * minute, stamp = "A")
        f.seen("A")
        f.now += 39 * minute
        f.published.get()
        f.drain()
        assertEquals("an hour from the copy's own download, not from launch", 0, f.fetches)
        f.now += 1 * minute
        f.published.get()
        f.drain()
        assertEquals(1, f.fetches)
        f.now += 59 * minute
        f.published.get()
        f.seen("A")
        assertEquals(1, f.fetches)
        f.now += 1 * minute
        f.published.get()
        f.drain()
        assertEquals(2, f.fetches)
    }

    @Test
    fun `a new lineup downloads at once, and is remembered across launches`() {
        val f = Fixture()
        f.cached("good:cached", 1 * minute, stamp = "A")
        f.seen("A")
        f.seen("B")
        assertEquals(1, f.fetches)
        f.seen("B")
        assertEquals(1, f.fetches)
        f.file.setLastModified(f.now)
        val relaunched = Fixture(dir = f.dir, now = f.now + 5 * minute)
        relaunched.seen("B")
        assertEquals("taken under B already", 0, relaunched.fetches)
    }

    @Test
    fun `a copy from before stamps were kept is fetched once for the lineup`() {
        val f = Fixture()
        f.cached("good:cached", 1 * minute)
        f.seen("A")
        assertEquals(1, f.fetches)
        f.seen("A")
        assertEquals(1, f.fetches)
    }

    @Test
    fun `after a failure nothing is asked for ten minutes, not even for a new lineup`() {
        val f = Fixture(reply = { error("offline") })
        f.cached("good:cached", 1 * minute, stamp = "A")
        f.seen("B")
        assertEquals(1, f.fetches)
        f.now += 9 * minute
        f.seen("C")
        f.published.get()
        f.drain()
        assertEquals(1, f.fetches)
        f.now += 1 * minute
        f.reply = { "good:later" }
        f.published.get()
        f.drain()
        assertEquals(2, f.fetches)
        assertEquals("good:later", f.published.get())
    }

    @Test
    fun `a bad reply is never cached, and leaves the old lineup's stamp`() {
        val f = Fixture(reply = { "<html>portal</html>" })
        f.cached("good:cached", 1 * minute, stamp = "A")
        f.seen("B")
        assertEquals("good:cached", f.published.get())
        assertEquals("good:cached", f.file.readText())
        assertEquals("A", File(f.file.path + ".lineup").readText())
    }

    @Test
    fun `a lineup that changes during a download gets its own download straight after`() {
        val f = Fixture()
        f.cached("good:cached", 1 * minute, stamp = "A")
        f.during = { f.during = null; f.published.lineupSeen("C") }
        f.seen("B")
        assertEquals(2, f.fetches)
        assertEquals("C", File(f.file.path + ".lineup").readText())
    }

    @Test
    fun `noted but not loaded when the feature is off`() {
        val f = Fixture()
        f.published.lineupSeen("A", load = false)
        f.drain()
        assertEquals(0, f.fetches)
        assertNull(f.published.get())
    }
}
