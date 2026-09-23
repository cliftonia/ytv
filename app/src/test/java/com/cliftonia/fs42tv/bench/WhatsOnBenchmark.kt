package com.cliftonia.fs42tv.bench

import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.schedule.ScheduleLines
import com.cliftonia.fs42tv.schedule.Timetable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.DialContract
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.ZoneId

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

    private fun halfHour() = Timetable(
        skipsOn = { true }, halfHourOn = { true }, zone = { ZoneId.of("Australia/Brisbane") })

    /** The guide's work for one channel: what is on, and NOW/NEXT. */
    private fun guideRow(table: Timetable, channel: Channel, now: Long) {
        table.at(channel, now)
        ScheduleLines.guideRow(channel, table, now)
    }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(4) { System.gc(); Thread.sleep(50) }
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun `half-hour schedule`() {
        val channels = dial()
        val now = System.currentTimeMillis() / 1000
        // Cold: a fresh Timetable each run, so every channel's schedule is built from nothing.
        val coldGuide = median(30) {
            val table = halfHour()
            channels.forEach { guideRow(table, it, now) }
        } / 1000.0
        val perChannel = channels.map { c ->
            c to median(30) { guideRow(halfHour(), c, now) } / 1000.0
        }.sortedByDescending { it.second }
        val (slowest, slowestMs) = perChannel.first()

        val warm = halfHour()
        channels.forEach { guideRow(warm, it, now) }
        val biggest = channels.maxBy { it.streams.size }
        val warmTune = median(20_000) { warm.at(biggest, now) }
        val warmGuide = median(500) { channels.forEach { guideRow(warm, it, now) } }
        val y2100 = 4_115_059_200L
        channels.forEach { guideRow(warm, it, y2100) }
        val warmGuide2100 = median(500) { channels.forEach { guideRow(warm, it, y2100) } }

        val before = usedHeap()
        val held = halfHour()
        channels.forEach { guideRow(held, it, now) }
        val retainedMb = (usedHeap() - before) / 1_048_576.0
        // Keep the tables alive past the measurement.
        check(held.at(channels.first(), now) != null || warm.at(channels.first(), now) == null)

        println("WHATSON half-hour: cold guide %.2f ms".format(coldGuide))
        println("WHATSON half-hour: slowest cold channel %.3f ms (%d %s)".format(slowestMs, slowest.number, slowest.name))
        perChannel.take(5).forEach { (c, ms) -> println("WHATSON half-hour:   %.3f ms %d %s".format(ms, c.number, c.name)) }
        println("WHATSON half-hour: warm tune %.2f us, warm guide %.1f us, warm guide in 2100 %.1f us"
            .format(warmTune, warmGuide, warmGuide2100))
        println("WHATSON half-hour: retained heap for all schedules %.2f MB".format(retainedMb))
    }

    /** How much of a week each clock channel spends on the up-next card - the owner's ~2% target. */
    @Test
    fun `card airtime`() {
        val channels = dial()
        val table = halfHour()
        val from = System.currentTimeMillis() / 1000
        val week = 7 * 86_400L
        val shares = channels.map { c ->
            var t = from
            var card = 0L
            while (t < from + week) {
                val on = table.at(c, t) ?: break
                val end = when (on) {
                    is Timetable.OnAir.Card -> on.until.also { card += minOf(it, from + week) - t }
                    is Timetable.OnAir.Clip -> on.endsAt ?: break
                }
                t = maxOf(end, t + 1)
            }
            c to 100.0 * card / week
        }.sortedByDescending { it.second }
        println("WHATSON cards: mean %.2f%% of airtime over %d channels, %d over 20%%".format(
            shares.map { it.second }.average(), shares.size, shares.count { it.second > 20 }))
        shares.take(8).forEach { (c, pct) ->
            println("WHATSON cards:   %5.1f%% %d %s%s".format(pct, c.number, c.name, if (c.ordered) " [ordered]" else ""))
        }
    }
}
