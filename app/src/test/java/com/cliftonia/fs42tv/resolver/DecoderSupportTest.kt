package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stopped the 4K television crashing on half the dial.
 *
 * YouTube publishes nothing above 1080p in H.264, so asking a 4K panel for its native resolution
 * always lands on VP9 or AV1. On the TCL - 4K screen, 32-bit userspace - that produced a black
 * picture under a correct banner, and on some clips took the whole app down inside mediacodec.
 *
 * It read as "some channels don't work" because it depends on whether that particular UPLOAD has
 * a 4K rendition, which varies clip by clip within a channel.
 */
class DecoderSupportTest {

    /** A 4K panel whose silicon only does H.264 to 1080p. This is the television in question. */
    private val tcl = DecoderSupport.of(DecoderSupport.AVC to 1080)

    /** A stick that genuinely decodes 4K VP9 - the Chromecast. */
    private val chromecast = DecoderSupport.of(
        DecoderSupport.AVC to 2160, DecoderSupport.VP9 to 2160, DecoderSupport.AV1 to 2160)

    @Test
    fun `a limited device refuses 4k vp9`() {
        assertFalse(tcl.canPlay("vp9", 2160))
        assertFalse(tcl.canPlay("vp09.00.51.08", 2160))
    }

    @Test
    fun `the same device still plays 1080p h264`() {
        assertTrue(tcl.canPlay("avc1.640028", 1080))
        assertTrue(tcl.canPlay("avc1.4d401f", 720))
    }

    @Test
    fun `it refuses h264 above what it reported`() {
        // The height limit is per codec, not global: a decoder that does 1080p H.264 does not
        // necessarily do 1440p H.264, and YouTube does occasionally publish one.
        assertFalse(tcl.canPlay("avc1.640033", 1440))
    }

    @Test
    fun `a capable device keeps its 4k`() {
        // The fix must not cost the Chromecast its picture quality. It reports 19 display modes
        // and decodes 4K VP9, and it should still be handed 4K VP9.
        assertTrue(chromecast.canPlay("vp9", 2160))
        assertTrue(chromecast.canPlay("av01.0.08M.08", 2160))
    }

    @Test
    fun `a codec the device never mentioned is refused`() {
        // Absence is not permission. The TCL reported no VP9 decoder at all, and the old code
        // treated "not mentioned" as "fine" simply by never asking.
        assertFalse(tcl.canPlay("vp9", 360))
        assertFalse(tcl.canPlay("av01.0.00M.08", 240))
    }

    @Test
    fun `an unreadable codec string is refused rather than assumed`() {
        assertFalse(tcl.canPlay(null, 720))
        assertFalse(tcl.canPlay("", 720))
        assertFalse(tcl.canPlay("something-new-from-youtube", 720))
    }

    @Test
    fun `codec families are read from youtube's profile strings`() {
        // YouTube writes full profile strings and the digits change per rendition, so matching
        // has to be on the prefix or this silently stops recognising anything.
        assertEquals(DecoderSupport.AVC, DecoderSupport.family("avc1.640028"))
        assertEquals(DecoderSupport.VP9, DecoderSupport.family("vp09.00.51.08"))
        assertEquals(DecoderSupport.VP9, DecoderSupport.family("vp9"))
        assertEquals(DecoderSupport.AV1, DecoderSupport.family("av01.0.08M.08"))
        assertEquals(DecoderSupport.HEVC, DecoderSupport.family("hev1.1.6.L93.B0"))
        assertEquals(DecoderSupport.UNKNOWN, DecoderSupport.family("wibble"))
    }

    @Test
    fun `a 4k television keeps its 4k`() {
        // The point of asking the device rather than assuming: this panel decodes 4K VP9 and
        // must keep getting it. The rule exists to stop a device being handed what it CANNOT
        // decode, not to cap everything at 1080p.
        val fourKTv = DecoderSupport.of(DecoderSupport.AVC to 2160, DecoderSupport.VP9 to 2160)
        assertTrue(fourKTv.canPlay("vp09.00.51.08", 2160))
        assertTrue(fourKTv.canPlay("avc1.640028", 1080))
    }

    @Test
    fun `the conservative fallback plays something rather than nothing`() {
        // Used when the device will not answer. A picture at 1080p beats no picture at 2160p.
        assertTrue(DecoderSupport.CONSERVATIVE.canPlay("avc1.640028", 1080))
        assertFalse(DecoderSupport.CONSERVATIVE.canPlay("vp9", 2160))
    }
}
