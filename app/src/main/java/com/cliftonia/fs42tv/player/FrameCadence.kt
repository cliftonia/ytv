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
     * DISPLAY locks video to the panel's real refresh and resamples the audio to follow. It is
     * the reason mpv is in this app: nothing else fixed the judder.
     *
     * AUDIO is mpv's default. Video is timed against the audio clock, so the two CANNOT drift
     * apart, at the cost of the pacing above.
     *
     * Offered rather than decided because `vo=mediacodec_embed` - forced on this television, since
     * gpu and gpu-next both SIGSEGV in its Mali driver - means MediaCodec presents the frames.
     * Whether mpv's pacing still governs anything under that vo is genuinely unsettled, and the
     * device is the only thing that can settle it. Judder and drift are different faults with
     * different cures.
     */
    val SYNC_MODES = listOf("DISPLAY", "AUDIO")

    fun optionFor(mode: String?): String =
        if (mode == "AUDIO") "audio" else "display-resample"
}
