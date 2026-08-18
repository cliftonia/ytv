package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.resolver.ClipResolver
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.StreamResolver
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.UrlCache

/** Everything needed to start a channel: which clip, how far in, and what to hand the player. */
data class Tuned(
    val channel: Channel,
    val streamIndex: Int,
    val stream: Stream,
    val playable: Playable,
    val offsetSeconds: Double,
)

/**
 * Composes the three steps that decide what a channel is showing right now.
 *
 * Pure and I/O free, so the preload manager, the banner and the reverse slot can all call it
 * for channels other than the one on screen - and so every branch is testable on the JVM.
 */
object Tuner {

    fun tune(
        channel: Channel,
        cache: UrlCache?,
        nowSeconds: Long,
        ladder: List<String> = ClipResolver.DEFAULT_LADDER,
        /**
         * Tiers the CDN has already refused this session, as `<id>/<tier>` - see
         * [StreamResolver.refusedKey]. Passed through so a 403 on hd falls to the sd already
         * published beside it, instead of a `/resolve` round trip that runs yt-dlp for seconds.
         */
        refused: Set<String> = emptySet(),
    ): Tuned? {
        val streams = channel.streams
        if (streams.isEmpty()) return null

        // Only a clock-rotating channel has a schedule to join part-way through. Live feeds
        // carry a placeholder duration of 600 per stream, so computing a position from it
        // would seek an arbitrary distance into a live window.
        val point = if (channel.rotation == "clock") {
            ClockRotation.playPointFor(streams.map { it.duration }, nowSeconds) ?: return null
        } else {
            null
        }

        val index = point?.index ?: 0
        val offset = point?.offsetSeconds ?: 0.0
        val stream = streams[index]

        // The server publishes an explicit discriminator; trust it rather than inferring from
        // a null id, so a youtube clip with a missing id never reaches an HLS parser.
        // StreamResolver has its own null-id fallback to Hls, meant for genuinely live streams;
        // delegating to it for a non-live stream with a missing id would let that fallback
        // override the discriminator above, so that malformed case is short-circuited here.
        // It is reported as Unplayable rather than NeedsResolving: there is no id to send the
        // server, and its resolve endpoint rejects anything that isn't an 11-character id, so
        // asking it would be a network round trip that exists only to fail.
        val playable: Playable = when {
            channel.kind == "live" -> Hls(stream.url)
            stream.id == null ->
                Unplayable("${channel.name}: a ${channel.kind} stream has no video id to resolve")
            else -> StreamResolver.resolve(stream, cache, ladder, nowSeconds, refused)
        }

        return Tuned(channel, index, stream, playable, offset)
    }

    /**
     * Tune to a specific clip, from its beginning, ignoring the clock.
     *
     * For the one case where the schedule is wrong rather than the app: a clip whose published
     * duration is longer than what actually plays ends while the rotation still believes it is on
     * air, so re-tuning would land straight back on it. There is nothing meaningful to seek to in
     * a programme that was never scheduled to be on now, so it starts at zero.
     */
    fun tuneToIndex(
        channel: Channel,
        index: Int,
        refused: Set<String> = emptySet(),
        ladder: List<String> = ClipResolver.DEFAULT_LADDER,
    ): Tuned? {
        val stream = channel.streams.getOrNull(index) ?: return null
        val playable: Playable = when {
            channel.kind == "live" -> Hls(stream.url)
            stream.id == null ->
                Unplayable("${channel.name}: a ${channel.kind} stream has no video id to resolve")
            else -> NeedsResolving(stream.id)
        }
        return Tuned(channel, index, stream, playable, 0.0)
    }
}
