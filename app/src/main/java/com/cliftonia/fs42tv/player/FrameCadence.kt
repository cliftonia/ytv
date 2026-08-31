package com.cliftonia.fs42tv.player

/**
 * How a source frame rate maps onto a 60Hz panel, in words.
 *
 * This panel reports a single display mode, 60Hz, so there is nothing to switch it to. 30 and
 * 60fps map onto that cleanly. 25fps PAL - which a dial full of British and Australian programmes
 * carries a lot of - needs an uneven 2:2:2:2:3 cadence, and 23.976fps film needs 3:2. Both look
 * like stutter and NEITHER drops a frame, which is exactly what the dropped-frame counter showed:
 * zero, while the picture visibly juddered. That is why the log says which cadence is in play, and
 * why the answer cannot come from a frame counter.
 *
 * Pure, and out of the player for that reason: reaching it before meant a real
 * `onRenderedFirstFrame` from a real ExoPlayer with a real surface, so the one diagnostic that
 * explains every judder report had no test at all.
 */
object FrameCadence {

    fun describe(fps: Float): String = when {
        fps <= 0f -> "unknown"
        kotlin.math.abs(fps - 60f) < 1f -> "60fps - clean on a 60Hz panel"
        kotlin.math.abs(fps - 30f) < 1f -> "30fps - clean 2:2 on a 60Hz panel"
        kotlin.math.abs(fps - 50f) < 1f -> "50fps PAL - UNEVEN on a 60Hz panel"
        kotlin.math.abs(fps - 25f) < 1f -> "25fps PAL - UNEVEN on a 60Hz panel"
        // A wider window than the rest, because film arrives as both 24 and 23.976 and the two
        // are the same cadence problem. 1f would leave 23.976 falling through to "non-standard",
        // which is the single most common frame rate on this dial reported as an oddity.
        kotlin.math.abs(fps - 24f) < 1.5f -> "24fps film - 3:2 pulldown on a 60Hz panel"
        else -> "non-standard"
    }

    /**
     * The two frame-pacing modes worth offering, and what each trades away.
     *
     * AUDIO first, because it is now the measured winner and therefore the default. Under
     * `vo=mediacodec_embed` the question this list existed to leave open was settled on the
     * device, twice, same clip at the same pinned offset (25fps PAL, 1080p H.264):
     *
     *     DISPLAY: drops 11->19, late frames 68->137 and climbing, over 75 seconds
     *     AUDIO:   drops 2, late 0, avsync 0.00004s
     *
     * DISPLAY (`display-resample`) locks video to the panel's refresh and resamples audio to
     * follow - the setting that originally justified mpv, measured under `vo=gpu`. With
     * MediaCodec presenting the frames directly, chasing the display clock only makes frames
     * late; the presenter is already tied to the panel. It stays offered as the escape hatch,
     * because the judder it once fixed was real and this dial changes video outputs rarely but
     * not never.
     */
    val SYNC_MODES = listOf("AUDIO", "DISPLAY")

    fun optionFor(mode: String?): String =
        if (mode == "AUDIO") "audio" else "display-resample"
}
