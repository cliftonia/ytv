package com.cliftonia.fs42tv.bench

import com.cliftonia.fs42tv.resolver.DecoderSupport
import com.cliftonia.fs42tv.resolver.DeviceResolver
import com.cliftonia.fs42tv.resolver.ResolvedCache
import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.sync.DialContract
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Ignore
import org.junit.Test

/**
 * Time a channel change, across the whole dial, against the real network.
 *
 * "Channel switching is slow" was reported three times and chased three times without a number,
 * because the only place it could be observed was a television with no adb connection. This
 * measures the half that does not need one.
 *
 * A tune is two halves. RESOLVE turns a video id into signed urls and is pure JVM work over the
 * network, so it runs here exactly as it runs on the television. PLAY opens a decoder, demuxes,
 * seeks and produces a frame, and needs the hardware - nothing here can measure it. Knowing which
 * half dominates is the entire question, and this answers the first half definitively rather than
 * inferring both.
 *
 * Deliberately measured against the PUBLISHED lineup and the real clock, so the clips resolved are
 * the clips a viewer would actually land on. A benchmark against a fixture would miss that some
 * channels are full of 4K uploads and others are not.
 *
 * `@Ignore` because it needs the network and takes minutes. Run it by hand:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*DialSurfBenchmark*' -i
 */
@Ignore("hits the live network and takes minutes; a measurement, not a regression test")
class DialSurfBenchmark {

    private val lineupUrl = "https://raw.githubusercontent.com/cliftonia/ytv/main/channels.json"
    private val serverUrl = "http://100.74.3.68:4243"

    /** The ladder a 4K panel uses, which is what both televisions are. */
    private val ladder = listOf("uhd", "hd", "sd")

    private data class Tune(val number: Int, val name: String, val id: String, val millis: Long,
                            val ok: Boolean)

    private fun dial() = DialContract.parseDial(URL(lineupUrl).readText())
        .channels.filter { it.kind == "youtube" }

    /** What is on air on a channel right now, by the same arithmetic the television runs. */
    private fun onAir(channel: com.cliftonia.fs42tv.sync.Channel): String? =
        ClockRotation.playPointFor(channel.streams.map { it.duration },
                                   System.currentTimeMillis() / 1000)
            ?.let { channel.streams.getOrNull(it.index)?.id }

    private fun report(label: String, tunes: List<Tune>) {
        val ok = tunes.filter { it.ok }
        val times = ok.map { it.millis }.sorted()
        if (times.isEmpty()) {
            println("\n$label: nothing resolved"); return
        }
        fun pct(p: Int) = times[(times.size - 1) * p / 100]
        println("\n=== $label ===")
        println("  resolved %d of %d".format(ok.size, tunes.size))
        println("  median %dms   p90 %dms   p99 %dms   worst %dms"
            .format(pct(50), pct(90), pct(99), times.last()))
        println("  a viewer waits this long before the player has even started")
        tunes.filter { it.ok }.sortedByDescending { it.millis }.take(5).forEach {
            println("    %5dms  ch%-3d %s".format(it.millis, it.number, it.name))
        }
        tunes.filterNot { it.ok }.take(5).forEach {
            println("    FAILED   ch%-3d %s (%s)".format(it.number, it.name, it.id))
        }
    }

    @Test
    fun `surf the whole dial and time every resolve`() {
        val resolver = DeviceResolver(decoders = DecoderSupport.EVERYTHING)
        val channels = dial()
        println("surfing %d channels, cold cache".format(channels.size))
        val tunes = channels.map { channel ->
            val id = onAir(channel)
            if (id == null) {
                Tune(channel.number, channel.name, "-", 0, false)
            } else {
                val started = System.nanoTime()
                val resolved = runCatching {
                    resolver.resolveDetailed(id, System.currentTimeMillis() / 1000, ladder)
                }.getOrNull()
                val millis = (System.nanoTime() - started) / 1_000_000
                print(if (resolved != null) "." else "x")
                Tune(channel.number, channel.name, id, millis, resolved != null)
            }
        }
        println()
        report("ON-DEVICE RESOLVE, every channel, cold", tunes)
    }

    @Test
    fun `the cache is what makes a second visit instant`() {
        // Proves the thing the neighbour prefetch relies on: once a clip is resolved, coming back
        // to that channel costs nothing. If this is not near zero the prefetch cannot help either.
        val resolver = DeviceResolver(decoders = DecoderSupport.EVERYTHING)
        val cache = ResolvedCache()
        val channels = dial().take(12)
        val now = System.currentTimeMillis() / 1000
        for (channel in channels) {
            val id = onAir(channel) ?: continue
            resolver.resolveDetailed(id, now, ladder)
                ?.let { cache.put(id, it) }
        }
        val tunes = channels.mapNotNull { channel ->
            val id = onAir(channel) ?: return@mapNotNull null
            val started = System.nanoTime()
            val hit = cache.get(id, System.currentTimeMillis() / 1000)
            Tune(channel.number, channel.name, id,
                 (System.nanoTime() - started) / 1_000_000, hit != null)
        }
        report("CACHED, second visit to the same channel", tunes)
    }

    @Test
    fun `what the server accelerator would save`() {
        // The question a server exists to answer, measured rather than argued. The server
        // pre-warms the whole dial, so its number is what a channel change would cost if the
        // television asked it instead of resolving alone.
        val healthy = runCatching {
            URL("$serverUrl/health").readText().contains("\"ok\":true")
        }.getOrDefault(false)
        if (!healthy) {
            println("\nserver unreachable - nothing to compare"); return
        }
        val channels = dial()
        val tunes = channels.mapNotNull { channel ->
            val id = onAir(channel) ?: return@mapNotNull null
            val started = System.nanoTime()
            val ok = runCatching {
                (URL("$serverUrl/resolve?v=$id").openConnection() as HttpURLConnection).run {
                    connectTimeout = 2_000
                    readTimeout = 10_000
                    val body = inputStream.bufferedReader().use { it.readText() }
                    disconnect()
                    body.contains("\"video\"")
                }
            }.getOrDefault(false)
            print(if (ok) "." else "x")
            Tune(channel.number, channel.name, id,
                 (System.nanoTime() - started) / 1_000_000, ok)
        }
        println()
        report("VIA SERVER, every channel", tunes)
    }
}
