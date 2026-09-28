package com.cliftonia.fs42tv.sync

import com.cliftonia.fs42tv.relay.FastRelay
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.tune.Tuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * live.json, as published by `curation/publish_live.py` - five channels cut from the real file:
 * a Pluto channel, FAST channels direct and US-only, one with a Xumo guide, and a music channel.
 *
 * The LIVE TV dial reads through the same Channel model as the other two; what matters is that
 * its extra fields arrive, that Pluto's ref still does, and that a US-only feed still reaches the
 * relay.
 */
class LiveDialContractTest {

    private val dial = DialContract.parseDial(
        javaClass.classLoader!!.getResourceAsStream("live-sample.json")!!.bufferedReader().readText())

    private fun named(name: String) = dial.channels.first { it.name == name }

    @Test
    fun `every channel is a live feed with its block and sub-block`() {
        assertEquals(5, dial.channels.size)
        dial.channels.forEach {
            assertEquals("live", it.kind)
            assertNull(it.streams.single().id)
            assertNotNull(it.block)
            assertNotNull(it.sub)
        }
        assertEquals("Movies" to "Action", named("50 Cent Action").let { it.block to it.sub })
    }

    @Test
    fun `a pluto channel keeps its ref, so every pluto feature still finds it`() {
        val pluto = named("50 Cent Action")
        assertEquals("68487fb3f212bedacf5a53e3", pluto.pluto?.id)
        assertFalse("pluto finds its own breaks", pluto.cueBreaks)
        assertNull("pluto has its own guide", pluto.guide)
    }

    @Test
    fun `a fast channel carries cue breaks and its guide key, music carries neither cue`() {
        val fast = named("Sparkle Movies")
        assertTrue(fast.cueBreaks)
        assertEquals("samsung:GBAJ400042T1", FastGuide.keyOf(fast))
        assertNull(fast.pluto)
        assertFalse(named("Stingray Jukebox Oldies").cueBreaks)
        assertEquals("samsung:US4700006XL", FastGuide.keyOf(named("Stingray Jukebox Oldies")))
        assertEquals("xumo:99991333", FastGuide.keyOf(named("Dove Channel")))
    }

    @Test
    fun `a us-only fast channel goes through the relay, a direct one does not`() {
        val server = { "http://192.168.1.10:4242" }
        val far = named("National Lampoon")
        assertEquals("us", far.streams.single().route)
        // The dial's own tune of it, then the step ScreenExtras.livePlayable takes for every live one.
        val tuned = Tuner.tuneToIndex(far, 0)!!
        val relayed = FastRelay.route(tuned.stream, tuned.playable, server)
        assertEquals(FastRelay.url(server(), far.streams.single().url), (relayed as Hls).url)
        val near = named("Sparkle Movies")
        val direct = FastRelay.route(near.streams.single(), Hls(near.streams.single().url), server)
        assertEquals(near.streams.single().url, (direct as Hls).url)
    }
}
