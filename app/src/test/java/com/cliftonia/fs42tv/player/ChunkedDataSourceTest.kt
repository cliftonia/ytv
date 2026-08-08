package com.cliftonia.fs42tv.player

import android.net.TestUri
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fake upstream [DataSource] backed by an in-memory byte array, standing in for
 * `DefaultHttpDataSource`. It records every [DataSpec] it is opened with so tests can assert on
 * the SHAPE of the requests ChunkedDataSource issues upstream - bounded vs. open-ended, and at
 * what position - which is the property under test, not just the bytes that come back.
 */
private class FakeUpstream(
    private val resource: ByteArray,
    private val contentRangeHeaderName: String = "Content-Range",
    private val includeContentRange: Boolean = true,
    /**
     * Forces end-of-input on the FIRST open after this many bytes, even though more of the
     * requested window remains - simulating a connection that closed mid-chunk rather than a
     * clean chunk boundary. Null means every open delivers its full resolved length.
     */
    private val truncateFirstOpenAfter: Int? = null,
) : DataSource {

    val openSpecs = mutableListOf<DataSpec>()
    var closeCalls = 0
        private set

    private var cursor = 0
    private var openRemaining = 0L
    private var deliveredThisOpen = 0

    override fun open(dataSpec: DataSpec): Long {
        openSpecs.add(dataSpec)
        cursor = dataSpec.position.toInt()
        deliveredThisOpen = 0
        val requestedEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            resource.size.toLong()
        } else {
            minOf(dataSpec.position + dataSpec.length, resource.size.toLong())
        }
        openRemaining = requestedEnd - dataSpec.position
        return openRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val truncateAt = truncateFirstOpenAfter
        val isFirstOpen = openSpecs.size == 1
        if (isFirstOpen && truncateAt != null && deliveredThisOpen >= truncateAt) {
            return C.RESULT_END_OF_INPUT
        }
        if (openRemaining <= 0L) return C.RESULT_END_OF_INPUT

        var toRead = minOf(length.toLong(), openRemaining).toInt()
        if (isFirstOpen && truncateAt != null) {
            toRead = minOf(toRead, truncateAt - deliveredThisOpen)
        }
        if (toRead <= 0) return C.RESULT_END_OF_INPUT

        System.arraycopy(resource, cursor, buffer, offset, toRead)
        cursor += toRead
        openRemaining -= toRead
        deliveredThisOpen += toRead
        return toRead
    }

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun getUri(): Uri = TestUri("https://fake/video")

    override fun getResponseHeaders(): Map<String, List<String>> {
        if (!includeContentRange) return emptyMap()
        val spec = openSpecs.lastOrNull() ?: return emptyMap()
        val start = spec.position
        val end = start + openRemaining + deliveredThisOpen - 1
        return mapOf(contentRangeHeaderName to listOf("bytes $start-$end/${resource.size}"))
    }

    override fun close() {
        closeCalls += 1
    }
}

/**
 * `ChunkedDataSource` is what stands between ExoPlayer and a 154x throughput cliff: an
 * open-ended `Range: bytes=0-` (what `DefaultHttpDataSource` sends when it does not know how
 * much it wants) is throttled to a video's own bitrate by googlevideo, while a bounded range is
 * served at line speed. Nothing here can be exercised on-device without a real network, so these
 * tests pin the request shape and the byte bookkeeping against a fake upstream instead.
 *
 * Plain JUnit, not Robolectric: the class touches only the `DataSource` interface plus
 * `android.net.Uri` and `android.util.Log` from the class it wraps. `unitTests
 * .isReturnDefaultValues` (set in `app/build.gradle.kts`) is enough to make `Log.d` a no-op;
 * `Uri` needed one small fixture (`TestUri`, alongside this file) to reach its package-private
 * constructor, which is far cheaper than pulling in Robolectric's shadow framework for a class
 * with no other Android surface.
 */
@UnstableApi
class ChunkedDataSourceTest {

    private fun resourceOf(size: Int) = ByteArray(size) { (it % 256).toByte() }

    private fun specAt(position: Long, length: Long) =
        DataSpec(TestUri("https://fake/video"), position, length)

    /** Drains a [DataSource] with a caller buffer smaller than the chunk size, on purpose - a
     *  buffer that happens to equal the chunk size would never exercise a read spanning the
     *  seam between two upstream chunks. */
    private fun readAll(source: DataSource, callerBufferSize: Int = 6): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(callerBufferSize)
        while (true) {
            val read = source.read(buf, 0, buf.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    @Test
    fun `every upstream request is bounded, never open-ended`() {
        // This is the entire reason the class exists: an unbounded request is throttled
        // identically to no Range header at all. If any request upstream carries
        // C-LENGTH_UNSET, the wrapper has silently stopped doing its job.
        val resource = resourceOf(20)
        val upstream = FakeUpstream(resource)
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)

        chunked.open(specAt(0, C.LENGTH_UNSET.toLong()))
        readAll(chunked)
        chunked.close()

        assertTrue("expected more than one chunk to be opened for a 20-byte resource with an " +
            "8-byte window", upstream.openSpecs.size > 1)
        for (spec in upstream.openSpecs) {
            assertTrue("a request with length=${spec.length} at position=${spec.position} is " +
                "exactly the open-ended shape googlevideo throttles to 2.5 Mbps",
                spec.length != C.LENGTH_UNSET.toLong())
        }
    }

    @Test
    fun `bytes are continuous across a chunk boundary`() {
        val resource = resourceOf(20)
        val upstream = FakeUpstream(resource)
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)

        chunked.open(specAt(0, resource.size.toLong()))
        val result = readAll(chunked)
        chunked.close()

        assertArrayEquals("a gap or duplicate at the seam between chunks is invisible to a " +
            "byte-count assertion but corrupts the decoded frame, so the bytes themselves - " +
            "in order - are what must match", resource, result)
    }

    @Test
    fun `reading a resource larger than several chunks stays continuous`() {
        // A single boundary crossing could pass by accident; several in a row are much harder
        // to get right by accident, particularly the last, undersized chunk.
        val resource = resourceOf(37)
        val upstream = FakeUpstream(resource)
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)

        chunked.open(specAt(0, resource.size.toLong()))
        val result = readAll(chunked, callerBufferSize = 5)
        chunked.close()

        assertArrayEquals(resource, result)
        assertEquals("37 bytes at an 8-byte window is 4 full chunks plus a 5-byte remainder",
            5, upstream.openSpecs.size)
    }

    @Test
    fun `total length is recovered from Content-Range on an unbounded read`() {
        // ExoPlayer treats a stream with no known length as unbounded, which changes how it
        // seeks and buffers - so an unbounded caller request must come back from open() with
        // the true length, taken from the first chunk's Content-Range response.
        val resource = resourceOf(20)
        val upstream = FakeUpstream(resource)
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)

        val reportedLength = chunked.open(specAt(0, C.LENGTH_UNSET.toLong()))
        chunked.close()

        assertEquals("the caller asked for everything and the only place the true size " +
            "appears is the Content-Range header on the first bounded response",
            resource.size.toLong(), reportedLength)
    }

    @Test
    fun `header lookup is case-insensitive`() {
        val resource = resourceOf(20)
        val upstream = FakeUpstream(resource, contentRangeHeaderName = "content-range")
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)

        val reportedLength = chunked.open(specAt(0, C.LENGTH_UNSET.toLong()))
        chunked.close()

        assertEquals("real HTTP stacks are inconsistent about header casing; a lookup that " +
            "only matches the canonical case silently falls back to treating the stream as " +
            "unbounded", resource.size.toLong(), reportedLength)
    }

    @Test
    fun `end of chunk is distinguished from end of resource`() {
        // The upstream signals RESULT_END_OF_INPUT twice in this run: once mid-chunk because
        // the simulated connection closed early, and once for real when the resource is
        // exhausted. Only the second one may reach the caller.
        val resource = resourceOf(20)
        val upstream = FakeUpstream(resource, truncateFirstOpenAfter = 5)
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)

        chunked.open(specAt(0, resource.size.toLong()))
        val result = readAll(chunked)
        chunked.close()

        assertArrayEquals("an early end-of-input on the first chunk must be recovered by " +
            "opening the next window at the true position, not reported to the caller as the " +
            "end of the whole resource", resource, result)
        assertEquals("the connection died after 5 bytes, not after the full 8-byte window - " +
            "the recovery request must resume from what was actually consumed, not from where " +
            "the abandoned chunk would have ended", 5L, upstream.openSpecs[1].position)
    }

    @Test
    fun `close is safe to call twice`() {
        val upstream = FakeUpstream(resourceOf(20))
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)
        chunked.open(specAt(0, 20))

        chunked.close()
        chunked.close()

        assertEquals("a second close() must be a no-op, not a second close of an already-" +
            "closed upstream connection", 1, upstream.closeCalls)
    }

    @Test
    fun `close is safe to call when never opened`() {
        val upstream = FakeUpstream(resourceOf(20))
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)

        chunked.close()

        assertEquals("closing a source that was never opened must not touch the upstream " +
            "connection at all", 0, upstream.closeCalls)
    }

    @Test
    fun `a caller-supplied bounded length is respected and never exceeded`() {
        // The chunk window is 8 bytes but the caller only wants 5; the request upstream, and
        // the bytes handed back, must both stop at 5 - not at the chunk size.
        val resource = resourceOf(20)
        val upstream = FakeUpstream(resource)
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)

        chunked.open(specAt(0, 5))
        val result = readAll(chunked)
        chunked.close()

        assertEquals("reading past what the caller bounded the request to hands ExoPlayer " +
            "bytes it never asked for", 5, result.size)
        assertEquals(1, upstream.openSpecs.size)
        assertEquals(5L, upstream.openSpecs[0].length)
    }

    @Test
    fun `reading from a non-zero start position requests the right absolute offsets`() {
        // The normal case here: a channel's playhead starts tens of minutes into a file, not
        // at byte zero. Both the first chunk and the one after it must be positioned absolutely.
        val resource = resourceOf(40)
        val upstream = FakeUpstream(resource)
        val chunked = ChunkedDataSource(upstream, chunkSize = 8)

        chunked.open(specAt(12, 20))
        readAll(chunked)
        chunked.close()

        assertEquals("the first request must start at the caller's position, not at zero",
            12L, upstream.openSpecs[0].position)
        assertEquals("the second chunk must resume where the first left off, not restart " +
            "from the caller's original position", 20L, upstream.openSpecs[1].position)
    }
}
