package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.resolver.ClipResolver
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.StreamResolver
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.schedule.Timetable
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
    /**
     * Set when the half-hour schedule has an "up next" card on, not a clip. [streamIndex] and
     * [stream] are then the programme the card announces, and nothing is handed to the player.
     */
    val card: Timetable.OnAir.Card? = null,
    /** When this clip's scheduled time runs out - half-hour schedule only. */
    val endsAt: Long? = null,
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
        /** The Settings rows that change what is on - see [Timetable]. Plain for callers without. */
        timetable: Timetable = Timetable.PLAIN,
    ): Tuned? {
        val streams = channel.streams
        if (streams.isEmpty()) return null

        // Only a clock-rotating channel has a schedule to join part-way through. Live feeds
        // carry a placeholder duration of 600 per stream, so computing a position from it
        // would seek an arbitrary distance into a live window.
        val onAir = if (channel.rotation == "clock") {
            timetable.at(channel, nowSeconds) ?: return null
        } else {
            null
        }

        val index = onAir?.index ?: 0
        val clip = onAir as? Timetable.OnAir.Clip
        val stream = streams.getOrNull(index) ?: return null

        // For a card, what it announces - resolvable ahead of time, which is how the programme
        // after a card starts as fast as a cache hit.
        val playable = playableFor(channel, stream) { _ ->
            StreamResolver.resolve(stream, cache, ladder, nowSeconds, refused)
        }
        return Tuned(
            channel, index, stream, playable,
            offsetSeconds = clip?.offsetSeconds ?: 0.0,
            card = onAir as? Timetable.OnAir.Card,
            endsAt = clip?.endsAt,
        )
    }

    /**
     * What follows [ended], a clip that reported finishing while the timetable still has it on.
     *
     * The published duration comes from yt-dlp's metadata; what actually plays is the shorter
     * of the separately-muxed tracks, so a clip routinely ends a little before its time. On the
     * half-hour schedule the answer is whatever the schedule has at the clip's scheduled end -
     * the top-up, the card or the next programme - joined from its start. On the continuous
     * rotation it is the next clip in the list, from zero, as it always was; a channel of one
     * clip has nothing else, and replays it.
     */
    fun following(
        ended: Tuned,
        cache: UrlCache?,
        ladder: List<String> = ClipResolver.DEFAULT_LADDER,
        refused: Set<String> = emptySet(),
        timetable: Timetable = Timetable.PLAIN,
    ): Tuned? {
        val channel = ended.channel
        ended.endsAt?.let { return tune(channel, cache, it, ladder, refused, timetable) }
        if (channel.streams.size < 2) return ended
        return tuneToIndex(channel, (ended.streamIndex + 1) % channel.streams.size)
    }

    /**
     * Tune to a specific clip, from its beginning, ignoring the clock.
     *
     * For the one case where the schedule is wrong rather than the app: a clip whose published
     * duration is longer than what actually plays ends while the rotation still believes it is on
     * air, so re-tuning would land straight back on it. There is nothing meaningful to seek to in
     * a programme that was never scheduled to be on now, so it starts at zero.
     */
    fun tuneToIndex(channel: Channel, index: Int): Tuned? {
        val stream = channel.streams.getOrNull(index) ?: return null
        return Tuned(channel, index, stream, playableFor(channel, stream, ::NeedsResolving), 0.0)
    }

    /**
     * What to hand the player for [stream], given the channel's kind; [clip] decides only the
     * youtube case, which is the one place [tune] and [tuneToIndex] differ.
     *
     * The server publishes an explicit discriminator; trust it rather than inferring from a null
     * id, so a youtube clip with a missing id never reaches an HLS parser. StreamResolver has its
     * own null-id fallback to Hls, meant for genuinely live streams; delegating to it for a
     * non-live stream with a missing id would let that fallback override the discriminator
     * above, so that malformed case is short-circuited here. It is reported as Unplayable rather
     * than NeedsResolving: there is no id to send the server, and its resolve endpoint rejects
     * anything that isn't an 11-character id, so asking it would be a network round trip that
     * exists only to fail.
     *
     * A file stream IS its url: a media file on the homelab's static server, already muxed, so
     * audioUrl stays null and there is nothing to resolve. It must sit ahead of the null-id
     * check, because file streams carry no id by design.
     */
    private inline fun playableFor(
        channel: Channel,
        stream: Stream,
        clip: (id: String) -> Playable,
    ): Playable = when {
        channel.kind == "live" -> Hls(stream.url)
        channel.kind == "file" -> Progressive(stream.url, audioUrl = null)
        stream.id == null ->
            Unplayable("${channel.name}: a ${channel.kind} stream has no video id to resolve")
        else -> clip(stream.id)
    }
}
