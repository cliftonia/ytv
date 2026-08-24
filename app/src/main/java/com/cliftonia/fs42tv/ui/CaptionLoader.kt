package com.cliftonia.fs42tv.ui

import android.util.Log
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.VttCues
import java.util.concurrent.Executor

/**
 * Fetches a clip's subtitle track and hands the parsed cues to the overlay.
 *
 * On its own executor for the same reason the prefetch has one, in both directions: on the tune
 * executor this would add a round trip to googlevideo in front of the next channel change, and
 * on the prefetch executor it would queue behind two speculative resolves, so the captions for
 * the programme actually being watched would arrive after the neighbours nobody asked for.
 *
 * Failures are swallowed but SAID: captions are a courtesy, and a clip that plays with none is
 * a far better outcome than a stand-by card because a subtitle file would not download - but a
 * silent return is indistinguishable from a broken toggle, and that ambiguity is most of why
 * the toggle took so many attempts to fix.
 */
class CaptionLoader(
    private val executor: Executor,
    private val runOnUi: (() -> Unit) -> Unit,
    /** The tune generation right now; a fetch outlived by a channel change discards itself. */
    private val generationNow: () -> Int,
    private val halted: () -> Boolean,
    /** Receives the parsed track on the UI thread; empty clears the overlay. */
    private val show: (List<VttCues.Cue>) -> Unit,
) {

    /**
     * Fetch and parse the subtitle track of [playable], if it has one.
     *
     * The URL came out of the same extraction as the streams, so nothing is resolved again
     * here - this is one GET of a few tens of kilobytes. It runs after the player has been
     * handed the clip rather than before, because a picture with no captions yet is a far
     * better second than a caption with no picture yet.
     *
     * [requestGeneration] is re-checked after the download for the same reason every other
     * stage of a tune checks it: this is the slowest thing in the sequence, and a viewer
     * surfing past a channel would otherwise get its subtitles pasted over whatever they
     * landed on.
     */
    fun load(playable: Playable, requestGeneration: Int) {
        val url = (playable as? Progressive)?.captionUrl ?: run {
            Log.i("fs42", "captions: this clip offers no english track")
            return
        }
        executor.execute {
            val cues = runCatching {
                val body = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                    connectTimeout = TIMEOUT_MILLIS
                    readTimeout = TIMEOUT_MILLIS
                    try {
                        inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        disconnect()
                    }
                }
                VttCues.parse(body)
            }.getOrElse {
                Log.w("fs42", "captions: could not fetch the track: $it")
                PlaybackDiagnostics.recordCaptions("FETCH FAILED: $it")
                return@execute
            }
            // Said out loud because "captions do not work" has been diagnosed wrongly several
            // times over, and the number of cues separates a track that arrived and had nothing
            // in it from one that never arrived at all.
            Log.i("fs42", "captions: ${cues.size} cues parsed")
            PlaybackDiagnostics.recordCaptions("DRAWN: ${cues.size} cues")
            runOnUi {
                if (halted() || requestGeneration != generationNow()) return@runOnUi
                show(cues)
            }
        }
    }

    private companion object {
        /**
         * How long to wait for a subtitle file before giving up on it.
         *
         * Generous, because nothing is waiting on it: the picture is already up by the time
         * this runs, so a slow track costs a late caption rather than a late channel. Bounded
         * all the same - a connection that never answers would otherwise hold the caption
         * thread for the whole session.
         */
        const val TIMEOUT_MILLIS = 10_000
    }
}
