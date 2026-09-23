package com.cliftonia.fs42tv.player

import com.cliftonia.fs42tv.schedule.SkipRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the playback watcher does with each position it reads.
 *
 * The rules that matter are the ones that would fight something else: a seek that lands short
 * of its target (mpv seeks to keyframes) must not become a seek loop, and a range reaching the
 * end of the clip must end it exactly once, the way a natural end does.
 */
class SkipWatchTest {

    private val ranges = listOf(SkipRange(30.0, 75.0), SkipRange(790.0, 812.0))

    @Test
    fun `outside every range nothing happens`() {
        val watch = SkipWatch(ranges, clipEndSeconds = 812.0)
        assertEquals(SkipWatch.Action.None, watch.check(10.0))
        assertEquals(SkipWatch.Action.None, watch.check(75.0))
        assertEquals(SkipWatch.Action.None, watch.check(null))
    }

    @Test
    fun `entering a range seeks to its end`() {
        val watch = SkipWatch(ranges, clipEndSeconds = 812.0)
        assertEquals(SkipWatch.Action.SeekTo(75.0), watch.check(30.1))
    }

    @Test
    fun `a seek that lands short is played through rather than repeated`() {
        // mpv runs hr-seek=no, so a seek lands on the keyframe it finds - which can be inside the
        // range still. Seeking again would land on the same keyframe, forever.
        val watch = SkipWatch(ranges, clipEndSeconds = 812.0)
        assertEquals(SkipWatch.Action.SeekTo(75.0), watch.check(31.0))
        assertEquals(SkipWatch.Action.None, watch.check(72.0))
        assertEquals(SkipWatch.Action.None, watch.check(74.5))
    }

    @Test
    fun `a range that reaches the end of the clip ends it, once`() {
        val watch = SkipWatch(ranges, clipEndSeconds = 812.0)
        assertEquals(SkipWatch.Action.EndClip, watch.check(790.2))
        assertEquals("the natural end is reported once; so is this", SkipWatch.Action.None,
            watch.check(790.5))
    }

    @Test
    fun `a range within a second of the end counts as reaching it`() {
        // The published duration is metadata, and the real file is often a little shorter.
        val watch = SkipWatch(listOf(SkipRange(500.0, 599.5)), clipEndSeconds = 600.0)
        assertEquals(SkipWatch.Action.EndClip, watch.check(501.0))
    }

    @Test
    fun `joining inside a range still skips it`() {
        // The clock seek lands on a range's end exactly, but an engine that starts on a keyframe
        // can open a second or two inside the range it was meant to be past.
        val watch = SkipWatch(ranges, clipEndSeconds = 812.0)
        assertEquals(SkipWatch.Action.SeekTo(75.0), watch.check(73.0))
    }
}
