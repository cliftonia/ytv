package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.resolver.AudioChoice.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing between the several audio tracks YouTube now ships on one video.
 *
 * Reported from the sofa as "the guy is normally in english but now he's in german", on a channel
 * whose content never changed. Picking by bitrate alone takes whichever dub happens to be fattest,
 * and no amount of filtering the lineup could ever fix that - the video was right, the track was
 * wrong.
 */
class AudioChoiceTest {

    private fun track(tag: String?, bitrate: Int = 128, kind: Kind = Kind.ORIGINAL,
                      container: String? = "m4a") =
        AudioChoice.Track(bitrate, tag, kind, container)

    @Test
    fun `english wins over a fatter dub in another language`() {
        // The reported bug, exactly. The German dub is the higher bitrate and must still lose.
        val got = AudioChoice.pick(listOf(
            track("de", bitrate = 256, kind = Kind.DUBBED),
            track("en", bitrate = 128),
        ))
        assertEquals("en", got?.languageTag)
    }

    @Test
    fun `an untagged track is not demoted`() {
        // A video with one audio track does not label it, and that is most videos. Refusing
        // untagged tracks would silence nearly the whole dial.
        assertEquals(192, AudioChoice.pick(listOf(track(null, bitrate = 192)))?.bitrate)
        assertEquals(192, AudioChoice.pick(listOf(track("", bitrate = 192)))?.bitrate)
    }

    @Test
    fun `the audio description track is never chosen`() {
        // A narrator describing the action over the top of it, which is unmistakably wrong on a
        // television where nobody asked for it.
        val got = AudioChoice.pick(listOf(
            track("en", bitrate = 320, kind = Kind.DESCRIPTIVE),
            track("en", bitrate = 128, kind = Kind.ORIGINAL),
        ))
        assertEquals(128, got?.bitrate)
    }

    @Test
    fun `a descriptive track alone is refused rather than played`() {
        assertNull(AudioChoice.pick(listOf(track("en", kind = Kind.DESCRIPTIVE))))
    }

    @Test
    fun `an original beats a dub of the same language`() {
        val got = AudioChoice.pick(listOf(
            track("en", bitrate = 160, kind = Kind.DUBBED),
            track("en", bitrate = 160, kind = Kind.ORIGINAL),
        ))
        assertEquals(Kind.ORIGINAL, got?.kind)
    }

    @Test
    fun `bitrate still decides between equals, as it always did`() {
        val got = AudioChoice.pick(listOf(track("en", bitrate = 128), track("en", bitrate = 256)))
        assertEquals(256, got?.bitrate)
    }

    @Test
    fun `a foreign track is played when it is the only one`() {
        // A genuinely German video should still have sound. This chooses between tracks; it does
        // not decide what belongs on the dial.
        assertEquals("de", AudioChoice.pick(listOf(track("de")))?.languageTag)
    }

    @Test
    fun `regional english tags count`() {
        for (tag in listOf("en", "en-US", "en-GB", "en_AU")) {
            assertTrue(tag, AudioChoice.isEnglishOrUntagged(tag))
        }
        for (tag in listOf("de", "es-419", "hi")) {
            assertTrue(tag, !AudioChoice.isEnglishOrUntagged(tag))
        }
    }

    @Test
    fun `nothing offered means nothing chosen`() {
        assertNull(AudioChoice.pick(emptyList()))
    }
}
