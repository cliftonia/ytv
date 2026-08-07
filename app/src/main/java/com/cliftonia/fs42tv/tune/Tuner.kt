package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.resolver.Hls
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
        preferUhd: Boolean = false,
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
            else -> StreamResolver.resolve(stream, cache, preferUhd, nowSeconds)
        }

        return Tuned(channel, index, stream, playable, offset)
    }
}
