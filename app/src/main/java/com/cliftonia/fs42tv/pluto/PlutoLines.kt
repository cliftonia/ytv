package com.cliftonia.fs42tv.pluto

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words the banner and the guide draw for a Pluto channel.
 *
 * Pure, and the zone is a parameter: the televisions are in Brisbane, the tests are wherever the
 * build runs, and a NEXT time printed in the wrong zone is confidently wrong in a way nobody would
 * trace back here.
 */
object PlutoLines {

    // 12-hour with AM/PM, because that is how a guide in Australia prints it. Locale.ENGLISH so a
    // device set to another language cannot turn "PM" into something the OSD font lacks.
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    /**
     * ("NOW <title>", "NEXT <time> <title>"), or null when nothing is on air - in which case the
     * banner keeps the lines it already had, exactly as without the guide. The second line is
     * empty when the six-hour window holds nothing after the programme on air.
     */
    fun banner(schedule: PlutoSchedule, nowMillis: Long, zone: ZoneId): Pair<String, String>? {
        val now = schedule.onAt(nowMillis) ?: return null
        val next = schedule.nextAfter(nowMillis)
        return "NOW ${now.title}" to (next?.let { "NEXT ${clock(it.startMillis, zone)} ${it.title}" } ?: "")
    }

    /**
     * Both on one line for the guide, which has one line per channel and ellipsises at the real
     * edge - so NOW comes first and is the part guaranteed to survive.
     */
    fun guideRow(schedule: PlutoSchedule, nowMillis: Long, zone: ZoneId): String? {
        val (now, next) = banner(schedule, nowMillis, zone) ?: return null
        return if (next.isEmpty()) now else "$now | $next"
    }

    fun clock(millis: Long, zone: ZoneId): String =
        CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone)).uppercase(Locale.ENGLISH)
}
