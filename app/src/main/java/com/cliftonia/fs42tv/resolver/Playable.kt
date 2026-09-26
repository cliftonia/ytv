package com.cliftonia.fs42tv.resolver

/** What the player should be handed. */
sealed interface Playable

/**
 * Separate video and audio streams, as YouTube serves them above 360p.
 *
 * [captionUrl] is a WebVTT track when the viewer has captions on and the clip offers an English
 * one, and null otherwise. Carried here rather than fetched by the engines because it comes out
 * of the same extraction as the streams - asking for it separately would mean a second round trip
 * for something already in hand.
 */
data class Progressive(
    val videoUrl: String,
    val audioUrl: String?,
    val captionUrl: String? = null,
    /**
     * YouTube's `audioConfig.loudnessDb` for the clip, when the resolve saw one - the LEVEL
     * VOLUME row's input; see [Loudness]. Server resolves carry it as `loudness_db` (the accelerator
     * reads YouTube's player reply). Null for files, live feeds and anything else that never passed
     * through a player response, which the gain reads as unity.
     */
    val loudnessDb: Double? = null,
) : Playable

/**
 * A live HLS feed. [url] is its identity everywhere - what Media3 plays, what a Pluto session's
 * failure is traced by, what the break poller is keyed on.
 *
 * [mediaUrl] and [audioUrl] are for mpv only: one media playlist chosen out of the master at [url]
 * and, when that variant is video-only, its separate audio rendition (see pluto/HlsMaster - mpv
 * opens a master by probing every variant, 7-11s against ~3s for one playlist). Null: play [url].
 * All three carry a Pluto session's token when they are Pluto's - never log them.
 */
data class Hls(val url: String, val mediaUrl: String? = null, val audioUrl: String? = null) : Playable

/** Nothing usable is cached; the caller must resolve this id on the device before it can play. */
data class NeedsResolving(val videoId: String) : Playable

/** The stream cannot be played and no amount of resolving would help. */
data class Unplayable(val reason: String) : Playable

/**
 * Why a playable cannot be handed to an engine, or null when it can be.
 *
 * Both engines reached this point with the same pair of branches and the same pair of strings.
 * They exist so a miss is legible rather than a silent black screen behind a healthy-looking
 * log line - which is the failure mode that costs the most time, because nothing anywhere says
 * the player was never given anything to play.
 *
 * Lives beside the vocabulary rather than in either engine, because both engines need exactly
 * this and neither owns it. It stays in `resolver` rather than `player` because `resolver` has
 * no dependency on `player` today, and moving these types would create one.
 */
fun unplayableReason(playable: Playable): String? = when (playable) {
    is Progressive, is Hls -> null
    // Both strings are the ones the two engines already logged, character for character. They
    // are read out of logcat while chasing a black screen, so changing their wording while
    // moving them would break every grep and note that refers to them.
    is NeedsResolving ->
        "no cached stream for video id ${playable.videoId}; needs server resolve"
    is Unplayable -> "cannot play: ${playable.reason}"
}
