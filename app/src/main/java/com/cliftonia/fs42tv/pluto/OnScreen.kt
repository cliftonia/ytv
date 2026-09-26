package com.cliftonia.fs42tv.pluto

/**
 * Which instant of a live Pluto stream - in its PROGRAM-DATE-TIME clock - the viewer is seeing.
 *
 * Needed because the playlist is read at its live edge and the player plays well behind it:
 * the owner watched the first break card go up a few seconds into Pluto's logo and come down a
 * few seconds before the programme was back - the card was following the edge, not the picture.
 *
 * Three answers, best first:
 *  - EXACT, Media3: its HLS window starts at the playlist's PDT, so window start plus position
 *    is the instant on screen. The engine reports it; see ChannelPlayback.programDateTimeMillis.
 *  - ANCHORED, mpv: libmpv has no PDT, but its demuxer is ffmpeg's, whose `live_start_index`
 *    defaults to -3 and is not overridden in MpvView: a live stream starts at the third-from-last
 *    segment of the window it read when loaded. The poller reads that playlist at the same moment
 *    (its first read is at the tune), so that segment's instant is the first frame's, and from
 *    there the instant moves with the time spent playing - wall time since the first frame, less
 *    the time stalled. Not mpv's own time-pos: Pluto's segments restart their timestamps at every
 *    discontinuity, which is exactly where a break begins and ends.
 *  - EDGE: three segments behind the latest window's edge (their EXTINF lengths), moving with
 *    the wall clock - where both engines start by default. Right to within a segment; used when
 *    nothing better is.
 *
 * Pure: every clock is a parameter.
 */
object OnScreen {

    /** Where ffmpeg starts a live window: the third segment from its end. */
    private const val LIVE_START_FROM_END = 3

    fun now(
        exact: Long?,
        joinsThirdFromLast: Boolean,
        view: BreakView,
        /** Wall-clock milliseconds when the stream was handed to the player. */
        loadedAt: Long,
        /** Time spent playing since the first frame, less stalls; null before a first frame. */
        playingMillis: Long?,
        wallNow: Long,
    ): Long? {
        exact?.let { return it }
        if (joinsThirdFromLast && playingMillis != null) {
            mpvAnchor(view, loadedAt)?.let { return it + playingMillis }
        }
        return edge(view, wallNow)
    }

    /**
     * The instant mpv's first frame showed. When the first read was asked for after the load,
     * the window may have slid since mpv read its own: one segment per WHOLE target duration
     * elapsed, floored. Floored because mpv spends the same wait before its own playlist read as
     * the poller does, so a late stamp overstates the gap; a rounded half-second of network wait
     * was a whole segment - five seconds - wrong in both directions.
     *
     * Under mpv a Pluto tune now hands it ONE media playlist chosen before the load (MasterPicker),
     * and the poller reads that same playlist first, with no master fetch in front of either: the
     * two reads land closer together, and on the same window. ffmpeg's live_start_index applies to
     * a media playlist opened directly just as to one found under a master.
     */
    fun mpvAnchor(view: BreakView, loadedAt: Long): Long? {
        val starts = view.firstWindowStarts
        if (starts.size < LIVE_START_FROM_END || view.targetDurationMillis <= 0) return null
        val late = (view.firstReadAt - loadedAt).coerceAtLeast(0L)
        val slid = (late / view.targetDurationMillis).toInt()
        return starts.getOrNull(starts.size - LIVE_START_FROM_END - slid)
    }

    fun edge(view: BreakView, wallNow: Long): Long? = view.edgeEnd?.let {
        it + (wallNow - view.readAt) - view.liveOffsetMillis
    }
}
