package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.sync.Channel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words the guide and the banner draw for a clock channel on the half-hour schedule: what is
 * on and since when, and what is next and at what time - "NOW 7:30 Grand Designs · NEXT 8:00 ...".
 *
 * Null everywhere off the half-hour schedule, so every caller falls through to the lines it drew
 * before the schedule existed - and Pluto's NOW/NEXT, which comes from Pluto's own guide, is
 * never touched. Pure; the zone and the 12/24-hour choice come from the [Timetable].
 */
object ScheduleLines {

    // Locale.ENGLISH for the same reason PlutoLines uses it: the digits must be ones the OSD font
    // has, whatever language the television is set to. No AM/PM - a half-hour schedule on a
    // channel guide reads "7:30", and the device's own 24-hour setting is honoured for the rest.
    private val TWELVE = DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH)
    private val TWENTY_FOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun clock(epochSeconds: Long, zone: ZoneId, use24Hour: Boolean): String =
        (if (use24Hour) TWENTY_FOUR else TWELVE).format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

    /**
     * (title line, next line) for the banner, or null to keep the banner's own lines.
     *
     * [playingIndex] is the clip actually on air, when there is one. It can differ from the
     * schedule's - a dead clip replaced by the next that plays - and then the clip is named
     * without a time, because the time printed beside it would be the other programme's.
     */
    fun banner(
        channel: Channel,
        timetable: Timetable,
        nowSeconds: Long,
        playingIndex: Int?,
    ): Pair<String, String>? {
        if (!timetable.halfHour(channel)) return null
        val onAir = timetable.at(channel, nowSeconds) ?: return null
        val zone = timetable.zone()
        val h24 = timetable.use24Hour()
        fun title(index: Int) = channel.streams.getOrNull(index)?.title?.trim().orEmpty()
        fun time(at: Long) = clock(at, zone, h24)
        return when (onAir) {
            is Timetable.OnAir.Card ->
                "UP NEXT ${time(onAir.nextAt)} ${title(onAir.index)}".trim() to ""
            is Timetable.OnAir.Clip -> {
                val startsAt = onAir.startsAt
                val now = if ((playingIndex == null || playingIndex == onAir.index) && startsAt != null) {
                    "NOW ${time(startsAt)} ${title(onAir.index)}"
                } else {
                    "NOW ${title(playingIndex ?: onAir.index)}"
                }
                val next = timetable.upNext(channel, nowSeconds)
                    ?.let { (index, at) -> "NEXT ${time(at)} ${title(index)}" }
                    .orEmpty()
                now.trim() to next.trim()
            }
        }
    }

    /**
     * Both on one line for the guide, which has one line per channel and ellipsises at the real
     * edge - so NOW comes first and is the part guaranteed to survive.
     */
    fun guideRow(
        channel: Channel,
        timetable: Timetable,
        nowSeconds: Long,
        playingIndex: Int? = null,
    ): String? {
        val (now, next) = banner(channel, timetable, nowSeconds, playingIndex) ?: return null
        return if (next.isEmpty()) now else "$now · $next"
    }
}
