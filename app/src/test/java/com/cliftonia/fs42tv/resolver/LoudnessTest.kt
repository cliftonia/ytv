package com.cliftonia.fs42tv.resolver

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Matched volume: reading YouTube's loudness figure and turning it into a gain.
 *
 * The figure is `playerConfig.audioConfig.loudnessDb` in the player response the extractor has
 * already downloaded - how far above YouTube's reference loudness the clip is mastered. The trap
 * is that the same response carries a DIFFERENT `loudnessDb` on every adaptive audio format, so
 * the parser must take the one inside `audioConfig` and nothing else.
 */
class LoudnessTest {

    /** Shaped like the real watch-page response of 23 Sep 2026, trimmed to what matters. */
    private val response = """
        {"streamingData":{"adaptiveFormats":[
          {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","loudnessDb":0.14999962},
          {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","loudnessDb":-5.3400002}]},
         "playerConfig":{"granularVariableSpeedConfig":{"minimumPlaybackRate":25},
          "audioConfig":{"loudnessDb":6.02,"perceptualLoudnessDb":-7.98,
            "enablePerFormatLoudness":true,"loudnessTargetLkfs":-14}}}
    """.trimIndent()

    @Test
    fun `takes the clip's figure from audioConfig, not a format's`() {
        assertEquals(6.02, Loudness.parse(response)!!, 1e-9)
    }

    @Test
    fun `a negative and an exponent both read`() {
        assertEquals(-8.7700005, Loudness.parse("""{"audioConfig":{"loudnessDb":-8.7700005}}""")!!, 1e-9)
        assertEquals(-0.00012, Loudness.parse("""{"audioConfig":{"loudnessDb":-1.2E-4}}""")!!, 1e-12)
    }

    @Test
    fun `the iOS player response has no loudnessDb, so it is derived from perceptual loudness`() {
        // Captured from NewPipeExtractor 0.26.5's `player` call for Big Buck Bunny on 23 Sep 2026.
        // The ANDROID client's reel_item_watch said loudnessDb -4.709999 for the same clip.
        val ios = """{"playerConfig":{"audioConfig":{"perceptualLoudnessDb":-18.71,
            "enablePerFormatLoudness":true,"trackAbsoluteLoudnessLkfs":-18.71,
            "loudnessTargetLkfs":-14,"loudnessNormalizationConfig":{"applyStatefulNormalization":false}}}}"""
        assertEquals(-4.71, Loudness.parse(ios)!!, 1e-9)
    }

    @Test
    fun `a figure inside a nested object is not the clip's`() {
        assertNull(Loudness.parse(
            """{"audioConfig":{"enablePerFormatLoudness":true,"inner":{"loudnessDb":9.0}}}"""))
    }

    @Test
    fun `the android client's reel endpoint is scanned`() {
        val (_, captured) = LoudnessCapture.capture {
            LoudnessCapture.offer(
                "https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch?prettyPrint=false",
                """{"playerConfig":{"audioConfig":{"loudnessDb":-4.709999}}}""")
        }
        assertEquals(-4.709999, captured!!, 1e-9)
    }

    @Test
    fun `a response with no audioConfig has no figure`() {
        assertNull(Loudness.parse("""{"adaptiveFormats":[{"loudnessDb":3.0}]}"""))
        assertNull(Loudness.parse("""{"audioConfig":{"enablePerFormatLoudness":true}}"""))
        assertNull(Loudness.parse(""))
    }

    @Test
    fun `a loud clip is turned down by exactly its excess`() {
        // YouTube TV's own formula: gain = min(1, 10^(-loudnessDb/20)). 6.02dB over is half.
        assertEquals(0.5f, Loudness.gain(6.02), 0.001f)
        assertEquals(0.1f, Loudness.gain(20.0), 0.0001f)
    }

    @Test
    fun `a quiet clip is never turned up`() {
        // Above unity would clip on some sinks, and the programme's quiet passages would come
        // up with the noise floor. YouTube caps at one; so does this.
        assertEquals(1f, Loudness.gain(-8.77), 0f)
        assertEquals(1f, Loudness.gain(0.0), 0f)
    }

    @Test
    fun `no figure, or a nonsense one, is unity`() {
        assertEquals(1f, Loudness.gain(null), 0f)
        assertEquals(1f, Loudness.gain(Double.NaN), 0f)
        // Anything past the floor is a garbage figure, not a clip mastered 60dB hot.
        assertEquals(Loudness.MIN_GAIN, Loudness.gain(60.0), 0f)
    }

    @Test
    fun `the capture sees only what its own thread downloaded`() {
        val (value, captured) = LoudnessCapture.capture {
            LoudnessCapture.offer("https://www.youtube.com/youtubei/v1/player", response)
            // A second player response for the same clip must not overwrite the first.
            LoudnessCapture.offer("https://www.youtube.com/youtubei/v1/player",
                """{"audioConfig":{"loudnessDb":-1.0}}""")
            "done"
        }
        assertEquals("done", value)
        assertEquals(6.02, captured!!, 1e-9)
    }

    @Test
    fun `responses that are not the player are not scanned`() {
        val (_, captured) = LoudnessCapture.capture {
            LoudnessCapture.offer("https://www.youtube.com/youtubei/v1/next", response)
        }
        assertNull(captured)
    }

    @Test
    fun `outside a capture an offer goes nowhere`() {
        LoudnessCapture.offer("https://www.youtube.com/youtubei/v1/player", response)
        val (_, captured) = LoudnessCapture.capture { }
        assertNull(captured)
    }

    @Test
    fun `two threads resolving at once keep their own figures`() {
        // The tune and the neighbour prefetch resolve concurrently through one downloader.
        val pool = Executors.newFixedThreadPool(2)
        val a = pool.submit<Double?> {
            LoudnessCapture.capture {
                LoudnessCapture.offer("/youtubei/v1/player", """{"audioConfig":{"loudnessDb":1.5}}""")
            }.second
        }
        val b = pool.submit<Double?> {
            LoudnessCapture.capture {
                LoudnessCapture.offer("/youtubei/v1/player", """{"audioConfig":{"loudnessDb":-2.5}}""")
            }.second
        }
        assertEquals(1.5, a.get(5, TimeUnit.SECONDS)!!, 1e-9)
        assertEquals(-2.5, b.get(5, TimeUnit.SECONDS)!!, 1e-9)
        pool.shutdown()
    }
}
