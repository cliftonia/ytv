package com.cliftonia.fs42tv.bench

import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.DialContract
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * How long "what is on now" takes to compute, for one tune and for the whole guide.
 *
 * The network half of a channel change is DialSurfBenchmark's job. This is the other cost the
 * dial pays on every tune and every guide open: pure arithmetic over the lineup, run on the
 * television's CPU. It exists so a change to that arithmetic - the half-hour schedule replaced a
 * single modulo with a packed cycle - can be compared against what it replaced, with numbers.
 *
 * JVM numbers on a Mac are a proxy: the 32-bit TCL is an order of magnitude slower. Compare runs
 * with each other, not with a budget measured somewhere else.
 *
 *   YTV_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*WhatsOnBenchmark*' -i
 */
class WhatsOnBenchmark {

    /** Skipped unless asked for: a measurement, not a regression test. */
    @Before
    fun onlyWhenAsked() = assumeTrue(System.getenv("YTV_BENCH") == "1")

    private fun dial(): List<Channel> {
        // The committed lineup at the repo root: the real channel sizes and clip lengths.
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "channels.json") }.first { it.exists() }
        return DialContract.parseDial(file.readText()).channels.filter { it.rotation == "clock" }
    }

    /** Median microseconds per call of [block], after a warm-up so the JIT is out of the picture. */
    private fun median(runs: Int, block: () -> Unit): Double {
        repeat(runs) { block() }
        val times = (1..runs).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1000.0
        }.sorted()
        return times[times.size / 2]
    }

    @Test
    fun `continuous rotation`() {
        val channels = dial()
        val now = System.currentTimeMillis() / 1000
        val biggest = channels.maxBy { it.streams.size }
        val oneTune = median(2000) {
            ClockRotation.playPointFor(biggest.streams.map { it.duration }, now)
        }
        val wholeGuide = median(200) {
            channels.forEach { c -> ClockRotation.playPointFor(c.streams.map { it.duration }, now) }
        }
        println("WHATSON continuous: ${channels.size} clock channels, largest ${biggest.streams.size} clips")
        println("WHATSON continuous: one tune (largest channel) %.1f us".format(oneTune))
        println("WHATSON continuous: whole guide %.1f us".format(wholeGuide))
    }
}
