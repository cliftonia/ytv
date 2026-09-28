package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.PlutoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule the read ahead must never break, checked end to end - real [PlutoSessions],
 * [SessionPool] and [PlutoRoute] under [MasterPrefetch], first rounds and refreshes, across a
 * long surf: no master is ever read on the session on screen, because on Pluto that read ends the
 * programme being watched.
 */
class ReadAheadInvariantTest {

    private var now = 0L
    private var serial = 0
    private val sessions = PlutoSessions(
        boot = { PlutoSession("https://s.pluto.tv", "sid=$serial", "jwt${serial++}", "AU", now + 3 * 3_600_000L) },
        server = { null },
        nowMillis = { now },
        poolSize = SessionPool.SIZE,
    )
    private val route = PlutoRoute(sessions, direct = { true }, nowMillis = { now }, report = {}, rotate = { true })
    private val cache = VariantCache({ now }, sessions::claimOf)

    /** The jwt of the session the last tune took - the one on screen. */
    private var onScreen: String? = null
    private var waiting: (() -> Unit)? = null
    private val queued = ArrayDeque<() -> Unit>()
    /** Every master read ahead: its session's jwt, beside the one on screen at that moment. */
    private val reads = mutableListOf<Pair<String, String?>>()

    private val prefetch = MasterPrefetch(
        schedule = { _, block -> waiting = block; ({ waiting = null }) },
        background = { block -> queued.addLast(block); ({}) },
        ahead = route::readAhead,
        prefetching = { true },
        read = { url, refresh, _ ->
            reads += jwtOf(url) to onScreen
            val age = if (refresh) MasterPrefetch.REFRESH_AGE_MILLIS else VariantCache.TTL_MILLIS
            if (cache.has(url, ENGINE, age)) false
            else {
                cache.put(url, ENGINE, VariantCache.Choice("$url#v", null), cache.claim(url))
                true
            }
        },
    )

    private fun pluto(number: Int) = Channel(number = number, name = "Pluto $number", kind = "live",
        pluto = PlutoRef("id$number"))

    private val dial = (1..6).map(::pluto)

    private fun jwtOf(url: String) = url.substringAfter("jwt=").substringBefore('&')

    /** Tune [channel] as the dial does, and say whether its pick was already remembered. */
    private fun tune(channel: Channel): Boolean {
        val master = (route.forDial(channel, Hls("https://jmp2/${channel.number}.m3u8")) as Hls).url
        onScreen = jwtOf(master)
        val hit = cache.get(master, ENGINE) != null
        if (!hit) cache.put(master, ENGINE, VariantCache.Choice("$master#v", null), cache.claim(master))
        prefetch.pictureUp(channel, dial) { true }
        return hit
    }

    private fun runWait() {
        waiting?.let { waiting = null; it() }
        while (queued.isNotEmpty()) queued.removeFirst()()
    }

    @Test
    fun `no master is ever read on the session on screen - first reads, refreshes, surfs and jumps`() {
        var hits = 0
        val path = listOf(1, 2, 3, 4, 5, 6, 1, 6, 5, 3, 3, 4, 2)
        for ((step, number) in path.withIndex()) {
            now += SessionPool.QUIET_MILLIS + 1
            if (tune(dial[number - 1])) hits++
            runWait()
            // Every other channel is sat on long enough for two refreshes.
            if (step % 2 == 0) repeat(2) {
                now += MasterPrefetch.REFRESH_MILLIS
                runWait()
            }
        }
        assertTrue(reads.isNotEmpty())
        reads.forEach { (read, screen) -> assertNotEquals("a read ahead on the session on screen", screen, read) }
        assertTrue("surfs by one land on a pick read ahead: $hits", hits >= 6)
    }

    @Test
    fun `a refresh re-reads a neighbour on its own session, which a surf then plays`() {
        now = SessionPool.QUIET_MILLIS
        tune(dial[1])
        runWait()
        val first = reads.map { it.first }.toSet()
        assertEquals(2, first.size)
        now += MasterPrefetch.REFRESH_MILLIS
        runWait()
        // The same two sessions read again - the neighbours' own - and the pick is fresh.
        assertEquals(first, reads.drop(2).map { it.first }.toSet())
        now += MasterPrefetch.REFRESH_MILLIS - 1_000
        assertTrue("a pick refreshed at four minutes is good past the five", tune(dial[2]))
    }

    companion object {
        private const val ENGINE = "mpv@1080"
    }
}
