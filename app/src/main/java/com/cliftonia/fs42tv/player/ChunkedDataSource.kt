package com.cliftonia.fs42tv.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import android.net.Uri
import android.os.SystemClock
import android.util.Log

/**
 * Reads a stream as a series of BOUNDED byte ranges rather than one open-ended request.
 *
 * This is the single most important thing in the playback path, and it was found by measuring
 * the same googlevideo URL four ways within one second:
 *
 * | request                                  | throughput   |
 * |------------------------------------------|--------------|
 * | no Range header                          |   2.57 Mbps  |
 * | `Range: bytes=0-`      (open-ended)      |   2.57 Mbps  |
 * | `Range: bytes=0-2097151`  (bounded 2MB)  | 154.23 Mbps  |
 * | `Range: bytes=0-8388607`  (bounded 8MB)  | 398.85 Mbps  |
 *
 * googlevideo throttles an unbounded request to roughly the video's own bitrate and serves a
 * bounded one at line speed. It is the BOUNDEDNESS that matters, not the presence of the header:
 * an open-ended range is throttled identically to no range at all - which is exactly what
 * ExoPlayer's `DefaultHttpDataSource` sends when it does not know how much it wants.
 *
 * That one detail explains nearly every playback complaint in this project: four seconds to a
 * first frame, six seconds of playback followed by a stall, sixteen seconds for a 4K clip, and
 * why live HLS was always fast at 215ms while YouTube was not - HLS fetches discrete segments,
 * which are bounded requests by nature.
 *
 * The wrapper is deliberately thin. It hands each bounded chunk to a real
 * [DataSource] and opens the next one when that chunk is exhausted, so redirects, timeouts,
 * cross-protocol rules and everything else stay exactly as they were.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long = RangeWindows.DEFAULT_WINDOW,
) : DataSource {

    private var spec: DataSpec? = null

    /** Absolute position in the resource, so each chunk knows where to start. */
    private var position = 0L

    /** Bytes still owed to the caller across all chunks, or [C.LENGTH_UNSET] when unknown. */
    private var remaining = C.LENGTH_UNSET.toLong()

    /** Bytes left in the chunk currently open. */
    private var chunkRemaining = 0L

    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        opened = true
        openChunk()

        // Report the TRUE remaining length, not "unknown". The first bounded response carries
        // `Content-Range: bytes a-b/total`, which is the only place the total appears - and
        // without it ExoPlayer treats the stream as unbounded, which costs seeking and changes
        // how it buffers. Chunking must be invisible to everything above this class.
        if (remaining == C.LENGTH_UNSET.toLong()) {
            totalLength()?.let { remaining = it - position }
        }
        return remaining
    }

    /**
     * Total resource size from `Content-Range`, or null when the header is absent.
     *
     * Both casings are tried because Media3 hands the headers back as a plain map rather than a
     * case-insensitive one, and which casing arrives depends on the server.
     */
    private fun totalLength(): Long? = RangeWindows.totalLength(
        upstream.responseHeaders["Content-Range"]?.firstOrNull()
            ?: upstream.responseHeaders["content-range"]?.firstOrNull()
    )

    /**
     * Open the next bounded window.
     *
     * The length asked for is capped at [chunkSize] even when the caller wanted everything,
     * because "everything" is the request shape that gets throttled. When the total length is
     * unknown the first chunk's response reveals it, and the remaining count is filled in from
     * there rather than guessed.
     */
    private fun openChunk() {
        val base = spec ?: return
        val want = if (remaining == C.LENGTH_UNSET.toLong()) chunkSize else minOf(chunkSize, remaining)
        // Timed per chunk, because the failure this needs to explain is bimodal: the same code
        // produced 1238ms channel changes and 11873ms ones in one run. Either some chunks open
        // slowly, or far more of them are being opened than expected - and only one of those is
        // fixable by changing the chunk size.
        val started = SystemClock.elapsedRealtime()
        val resolved = upstream.open(
            base.buildUpon().setPosition(position).setLength(want).build()
        )
        chunkRemaining = if (resolved == C.LENGTH_UNSET.toLong()) want else resolved
        opens += 1
        Log.d("fs42chunk", "open #$opens at $position want=$want got=$resolved " +
            "in ${SystemClock.elapsedRealtime() - started}ms")
    }

    /** How many bounded windows this source has opened, for the log above. */
    private var opens = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        if (chunkRemaining == 0L) {
            // This chunk is spent. Close it and open the next window at the new position; the
            // caller never sees the seam.
            upstream.close()
            openChunk()
            if (chunkRemaining == 0L) return C.RESULT_END_OF_INPUT
        }

        val cap = minOf(length.toLong(), chunkRemaining).toInt()
        val read = upstream.read(buffer, offset, cap)
        if (read == C.RESULT_END_OF_INPUT) {
            // The chunk ended early. If the resource itself is finished this is genuinely the
            // end; otherwise the next window will carry on.
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            chunkRemaining = 0L
            return read(buffer, offset, length)
        }

        position += read
        chunkRemaining -= read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        return read
    }

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (!opened) return
        opened = false
        spec = null
        upstream.close()
    }

    companion object {
        /** Wraps any factory so every source it makes reads in bounded windows. */
        fun factory(upstream: DataSource.Factory): DataSource.Factory =
            DataSource.Factory { ChunkedDataSource(upstream.createDataSource()) }
    }
}
