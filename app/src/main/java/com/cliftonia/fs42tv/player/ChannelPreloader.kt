package com.cliftonia.fs42tv.player

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the neighbouring channels buffered so tuning to one does not start from cold.
 *
 * Why this should help, from measurement rather than hope: on this dial a live HLS channel
 * reaches its first frame in about 215 ms while a YouTube channel takes about 4,300 ms, in the
 * same build on the same device. The resolve step is not the difference - instrumenting it
 * showed it barely fires. The difference is opening and buffering two separate googlevideo
 * connections, merged for video and audio, against fetching one HLS manifest. That is exactly
 * the work preloading can do in advance.
 *
 * The player is built from this manager's own builder, via [ExoPlayer]. That is not a
 * convenience: a preloaded source is only reusable by a player that shares the manager's track
 * selector, load control and bandwidth meter. Build the player separately and every preloaded
 * byte is discarded on tune.
 *
 * Preloading runs on the manager's own `Looper`, not the app's executor. Nothing here should be
 * moved onto that executor - what belongs there is anything touching `DialNavigator`, whose
 * index is documented as single-writer.
 *
 * Disk caching is available in this API (`Builder.setCache`, `PreCacheHelper`) and is
 * deliberately not used: this project already tried caching media to disk on the box and
 * abandoned it over how much storage it consumed.
 */
@UnstableApi
class ChannelPreloader(
    context: Context,
    dataSourceFactory: DataSource.Factory,
    private val budget: Int,
    private val preloadWindowMillis: Long = DEFAULT_PRELOAD_WINDOW_MILLIS,
) {


    /** Index -> where in the plan it sits, 0 being the best. Absent means "do not preload". */
    private val ranks = ConcurrentHashMap<Int, Int>()

    /** Index -> the clock position that channel should be preloaded at, in milliseconds. */
    private val startPositions = ConcurrentHashMap<Int, Long>()

    /** Sources currently handed to the manager, so they can be removed when the plan changes. */
    private val added = ConcurrentHashMap<Int, MediaSource>()

    private val statusControl =
        TargetPreloadStatusControl<Int, PreloadStatus> { index ->
            val rank = ranks[index]
            when {
                rank == null -> PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
                // The channel ahead and the reserved one behind both buffer real bytes, but
                // from a window half the size, so total bytes in flight stay below what ONE
                // channel cost at the wider window.
                //
                // This is not caution, it is a measurement. Buffering bytes for two neighbours
                // took the switch median from 4342ms to 6982ms and dropped the proportion of
                // presses that rendered at all from 11-in-12 to 7-in-12. Each YouTube channel
                // opens two googlevideo connections, one video and one audio, so two buffering
                // neighbours put six concurrent fetches against the one stream the viewer is
                // actually watching. Preloading has to take bandwidth from somewhere, and the
                // only place it can take it from is the picture on screen.
                rank <= 1 -> PreloadStatus.specifiedRangeLoaded(
                    startPositions[index] ?: 0L,
                    preloadWindowMillis,
                )
                // Track selection still resolves the source and settles DNS, TLS and the
                // container probe - real work removed from the critical path - without
                // competing for bandwidth against playback.
                else -> PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            }
        }

    /**
     * Buffer targets modelled on the box's mpv configuration, which plays this exact content
     * over this exact network without stalling.
     *
     * mpv's googlevideo profile reads lazily - `cache-secs=3`, `demuxer-readahead-secs=0` - so it
     * pulls at roughly playback rate. Media3 defaults to `minBufferMs = 50000`: it races fifty
     * seconds ahead as fast as the connection allows. Against googlevideo, which throttles a
     * single connection to about the video's own bitrate, that is a consumer permanently
     * demanding more than it can be given, and the observed result was five to seven seconds of
     * playback followed by a stall, repeatedly.
     *
     * 20s is still far more headroom than mpv keeps, while no longer treating the connection as
     * something to be raced. `bufferForPlayback` stays at the Media3 default: an earlier attempt
     * to shave it produced worse results, and the theory that a thinner start buffer helps was
     * not supported.
     */
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(
            /* minBufferMs = */ 20_000,
            /* maxBufferMs = */ 20_000,
            /* bufferForPlaybackMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()

    private val builder = DefaultPreloadManager.Builder(context, statusControl)
        .setDataSourceFactory(dataSourceFactory)
        .setLoadControl(loadControl)

    private val manager: DefaultPreloadManager = builder.build()

    /** The player to actually play with. Shares this manager's resources; see the class note. */
    val exo: ExoPlayer = builder.buildExoPlayer()

    /**
     * Point the preloader at a new dial position.
     *
     * [sourceAt] is asked for a source and its clock start position for each index the plan
     * wants; returning null simply drops that neighbour, which is the correct response to a
     * channel that cannot be resolved right now. Best-effort throughout - a preload that fails
     * must never disturb the channel actually playing.
     */
    fun apply(
        dialSize: Int,
        currentIndex: Int,
        rebuild: Boolean = false,
        wantedStartMillis: (Int) -> Long? = { null },
        sourceAt: (Int) -> Pair<MediaSource, Long>?,
    ) {
        val plan = PreloadPlan.forPosition(dialSize, currentIndex, budget)

        runCatching {
            // Drop anything the new plan does not want before adding, so the manager never
            // briefly holds budget + previous-budget sources at once. On the 1.5 GB device that
            // transient is the difference between fitting and not.
            //
            // [rebuild] additionally drops what the plan DOES still want, so the periodic
            // refresh re-adds it at a recomputed clock offset. Without that this method would
            // skip every index it already holds and the refresh would be a no-op that looks
            // like it is working - the failure mode being guarded against is silent.
            for ((index, source) in added) {
                // A rebuild only discards neighbours whose preloaded position has actually gone
                // stale. Dropping and refetching all of them on a timer - which is what this did
                // first - produces a bandwidth spike every refresh interval that competes with
                // the stream being watched, and shows up as playback stopping and starting about
                // once a minute. Refetching a buffer that is still nearly right is strictly worse
                // than leaving it alone.
                val drifted = rebuild && run {
                    val want = wantedStartMillis(index)
                    val have = startPositions[index]
                    want == null || have == null || kotlin.math.abs(want - have) > DRIFT_TOLERANCE_MILLIS
                }
                if (drifted || index !in plan) {
                    manager.remove(source)
                    added.remove(index)
                    ranks.remove(index)
                    startPositions.remove(index)
                }
            }

            plan.forEachIndexed { rank, index ->
                ranks[index] = rank
                if (added.containsKey(index)) return@forEachIndexed
                val (source, startMillis) = sourceAt(index) ?: return@forEachIndexed
                startPositions[index] = startMillis
                added[index] = source
                manager.add(source, index)
            }

            manager.setCurrentPlayingIndex(currentIndex)
            manager.invalidate()
            Log.d("fs42", "preloading ${plan.size} neighbours of index $currentIndex: $plan")
        }.onFailure {
            Log.w("fs42", "preload pass failed; playback is unaffected", it)
        }
    }

    companion object {
        /**
         * How much of each neighbour to buffer, by default.
         *
         * Chosen on the EMULATOR, where it was the least-bad of four options, and it should be
         * treated as provisional until re-measured on hardware. The emulator's constrained
         * bandwidth made preloading compete with playback, so every setting there was tuned to
         * take as little throughput as possible:
         *
         * | configuration                | forward | reverse |
         * |------------------------------|---------|---------|
         * | no preloading                | 4342 ms | 4317 ms |
         * | two neighbours, 5 s window   | 6982 ms | -       |
         * | one neighbour, 5 s window    | 3753 ms | 5789 ms |
         * | two neighbours, 2 s window   | 3892 ms | 4402 ms |
         *
         * On the real television the picture inverts: with preloading OFF the median was 2472 ms
         * and only 5 presses in 18 rendered at all, against 1779 ms and 13 in 18 with it ON.
         * There is headroom on real hardware that the emulator never had, so a wider window is
         * likely to be better here rather than worse.
         */
        const val DEFAULT_PRELOAD_WINDOW_MILLIS = 2_000L

        /**
         * How far a preloaded position may drift before it is worth refetching.
         *
         * Generous on purpose. The buffered window is only a couple of seconds, so a buffer
         * that is 10s stale is genuinely useless - but refetching costs bandwidth taken
         * directly from the picture on screen, so the bar for spending it has to be a real
         * miss rather than a small one.
         */
        const val DRIFT_TOLERANCE_MILLIS = 10_000L
    }

    fun release() {
        runCatching { manager.release() }
        added.clear()
        ranks.clear()
        startPositions.clear()
    }
}
