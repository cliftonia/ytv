package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.HalfHourSchedule.OnAir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Test-only probes for [HalfHourSchedule]: turn each answer into the span of time it covers,
 * walk a stretch of time span by span, and check the spec's invariants on the way.
 */
internal object ScheduleProbe {

    const val SLOT = 1800L
    const val SHORT = 300

    /** One thing on air, from [start] to [end] (epoch seconds), and its offset at [start]. */
    data class Span(val kind: Char, val index: Int, val start: Long, val end: Long, val offsetAtStart: Double) {
        val length: Long get() = end - start
    }

    fun span(r: OnAir, t: Long): Span = when (r) {
        is OnAir.Programme -> Span('P', r.index, r.slotStart, r.endsAt, r.offsetSeconds - (t - r.slotStart))
        is OnAir.TopUp -> Span('T', r.index, r.start, r.end, r.offsetSeconds - (t - r.start))
        is OnAir.Card -> Span('C', r.nextIndex, r.start, r.until, 0.0)
    }

    fun local(t: Long, zone: ZoneId): Long = t + zone.rules.getOffset(Instant.ofEpochSecond(t)).totalSeconds

    /** The part key a LOCAL wall-clock second falls in. */
    fun partAt(localSeconds: Long): String {
        val hour = Math.floorMod(localSeconds, 86_400L) / 3600
        return when {
            hour >= 23 || hour < 6 -> "late"
            hour < 12 -> "breakfast"
            hour < 18 -> "afternoon"
            else -> "prime"
        }
    }

    /** Local-second start of the part containing [localSeconds] (a 23:00 for late). */
    fun partStartLocal(localSeconds: Long): Long {
        val dayStart = Math.floorDiv(localSeconds, 86_400L) * 86_400L
        val hour = (localSeconds - dayStart) / 3600
        return when {
            hour >= 23 -> dayStart + 23 * 3600
            hour < 6 -> dayStart - 3600
            hour < 12 -> dayStart + 6 * 3600
            hour < 18 -> dayStart + 12 * 3600
            else -> dayStart + 18 * 3600
        }
    }

    fun partSlots(part: String): Int = when (part) {
        "late" -> 14
        "breakfast" -> 12
        "afternoon" -> 12
        else -> 10
    }

    /** The pool the spec says a part draws from: its tagged streams, or all when none are. */
    fun pool(durations: List<Int>, parts: List<List<String>>, part: String): List<Int> {
        val playable = durations.indices.filter { durations[it] > 0 }
        val tagged = playable.filter { part in parts.getOrElse(it) { emptyList() } }
        return tagged.ifEmpty { playable }
    }

    /**
     * Walk [from] to [to] span by span. Asserts every answer is non-null, contains the instant it
     * was asked about, starts exactly where the previous one ended (no gap, no overlap), and is
     * the same thing - with the offset advancing one second per second - at its last second.
     */
    fun walk(s: HalfHourSchedule, from: Long, to: Long, label: String = ""): List<Span> {
        val out = ArrayList<Span>()
        var t = from
        var guard = 0
        while (t < to) {
            val r = s.at(t)
            assertNotNull("$label nothing on air at $t", r)
            val sp = span(r!!, t)
            assertTrue("$label span $sp does not contain the instant $t asked about", sp.start <= t && t < sp.end)
            if (out.isNotEmpty()) {
                assertEquals("$label gap or overlap after ${out.last()} -> $sp", out.last().end, sp.start)
            }
            val last = s.at(sp.end - 1)
            assertNotNull(last)
            val lastSpan = span(last!!, sp.end - 1)
            assertEquals("$label the span $sp is not the same thing at its last second (${sp.end - 1}): $last",
                sp, lastSpan)
            out += sp
            t = sp.end
            if (++guard > 100_000) fail("$label walk did not advance")
        }
        return out
    }

    /** Run [block] on a daemon thread; fail (rather than hang the suite) past [seconds]. */
    fun <T> within(seconds: Long, what: String, block: () -> T): T {
        val task = FutureTask(block)
        val thread = Thread(task, "within").apply { isDaemon = true }
        thread.start()
        return try {
            task.get(seconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            thread.interrupt()
            throw AssertionError("$what did not finish within ${seconds}s (hang)")
        } catch (e: ExecutionException) {
            throw AssertionError("$what threw ${e.cause}", e.cause)
        }
    }
}
