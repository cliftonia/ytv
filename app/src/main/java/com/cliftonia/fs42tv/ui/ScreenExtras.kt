package com.cliftonia.fs42tv.ui

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.pluto.PlutoApi
import com.cliftonia.fs42tv.pluto.PlutoBoot
import com.cliftonia.fs42tv.pluto.PlutoGuide
import com.cliftonia.fs42tv.pluto.PlutoIds
import com.cliftonia.fs42tv.pluto.PlutoLines
import com.cliftonia.fs42tv.pluto.PlutoRoute
import com.cliftonia.fs42tv.pluto.PlutoSessions
import com.cliftonia.fs42tv.resolver.Loudness
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.schedule.Timetable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.tune.Tuned
import java.time.ZoneId
import java.util.concurrent.Executor

/**
 * The extras layered over the dial - each behind its own [Features] row - and the one place the
 * screen asks about them.
 *
 * Kept out of [ScreenDirector] on purpose. The director's rules about the blank, the banner and
 * the volume have each been wrong at least once; these features are new, unwatched on either
 * television, and switchable off. Holding them here means the director gains a handful of calls,
 * each of which returns "nothing to add" when its row is OFF, and the OFF path through the
 * director is the path it had before any of this existed.
 */
class ScreenExtras(private val deps: Deps) {

    class Deps(
        val features: Features,
        val plutoGuide: PlutoGuide,
        val runOnUi: (() -> Unit) -> Unit,
        val halted: () -> Boolean,
        val nowMillis: () -> Long,
        /** What is on a clock channel, with SKIP SPONSORS applied. */
        val timetable: Timetable,
        /** Pluto channels through Pluto's own route, behind PLUTO ROUTE. */
        val plutoRoute: PlutoRoute,
    )

    /**
     * What the dial plays for a live channel - Pluto's own route or the published url. Blocking;
     * the tune thread only - it is TuneController.Deps.livePlayable.
     */
    fun livePlayable(tuned: Tuned): Playable = deps.plutoRoute.forDial(tuned.channel, tuned.playable)

    /**
     * The guide music's version of the same, on a Pluto session of its own so it can never end
     * the programme playing under the guide. Blocking; the prefetch thread only.
     */
    fun besideTuned(tuned: Tuned): Tuned =
        if (tuned.channel.kind != "live") tuned
        else tuned.copy(playable = deps.plutoRoute.forBeside(tuned.channel, tuned.playable))

    /** The dial's player failed on [playable]; see [PlutoRoute.playbackFailed]. */
    fun plutoFailed(playable: Playable?) = deps.plutoRoute.playbackFailed(playable)

    val features: Features get() = deps.features

    /**
     * The timetable every clock-channel question goes through - the tuner, the guide, the banner
     * - built on these switches, so one row flipped changes all of them together.
     */
    val timetable: Timetable get() = deps.timetable

    /**
     * Something is in front of the blank - the guide, settings - or the app is out of sight.
     * Written by [syncCovered], which runs on each of those transitions (including onStop and
     * onResume), and read by the snow: animating 22 times a second behind a stopped activity -
     * a dead channel still "tuning" in the background - is battery and heat for nobody.
     */
    val screenCovered = mutableStateOf(false)

    /** Re-derived by the director on every transition that can hide the blank. Main thread only. */
    fun syncCovered(covered: Boolean) {
        screenCovered.value = covered
    }

    /**
     * The loudness figure of the clip last handed to the player; null for anything that is not a
     * resolved YouTube clip - live feeds, files, Pluto - which the gain reads as unity. Main
     * thread only, like the paint that writes it.
     */
    private var clipLoudnessDb: Double? = null

    /**
     * A clip was handed to the player. True when the level gain for it differs from the one in
     * force, so the director re-derives the volume at once rather than at the first frame -
     * which on a roll-over is after the new clip's audio has started.
     */
    fun clipPainted(playable: Playable): Boolean {
        val before = programmeGain()
        clipLoudnessDb = (playable as? Progressive)?.loudnessDb
        val after = programmeGain()
        if (after != 1f) {
            android.util.Log.i("fs42", "level: clip at ${clipLoudnessDb}dB -> gain $after")
        }
        return after != before
    }

    /** The programme's gain when nothing silences it: unity unless LEVEL VOLUME has a figure. */
    fun programmeGain(): Float =
        if (deps.features.isOn(Features.Flag.LEVEL_VOLUME)) Loudness.gain(clipLoudnessDb) else 1f

    /**
     * NOW and NEXT for [channel]'s banner, or null to leave the banner as it was.
     *
     * Answers from the cache only. On a miss it asks Pluto, and [onUpdate] runs on the UI thread
     * once the answer is in - the caller re-reads then, which hits the cache. A failed fetch never
     * calls back, so the banner simply keeps the channel name.
     */
    fun bannerLines(channel: Channel, onUpdate: () -> Unit): Pair<String, String>? {
        if (!deps.features.isOn(Features.Flag.PLUTO_GUIDE)) return null
        val id = PlutoIds.of(channel) ?: return null
        val schedule = deps.plutoGuide.cached(id)
        if (schedule == null) {
            deps.plutoGuide.request(id) { deps.runOnUi { if (!deps.halted()) onUpdate() } }
            return null
        }
        return PlutoLines.banner(schedule, deps.nowMillis(), ZoneId.systemDefault())
    }

    /**
     * The guide row's what-is-on text for [channel], or null to keep the row as it is.
     *
     * [onReady] null means cache only - used when the whole list is rebuilt, which must not turn
     * into a request per channel. Non-null asks the network on a miss and hands the line over on
     * the UI thread.
     */
    fun guideRow(channel: Channel, onReady: ((String) -> Unit)?): String? {
        if (!deps.features.isOn(Features.Flag.PLUTO_GUIDE)) return null
        val id = PlutoIds.of(channel) ?: return null
        val schedule = deps.plutoGuide.cached(id)
        if (schedule == null && onReady != null) {
            deps.plutoGuide.request(id) { fetched ->
                val line = PlutoLines.guideRow(fetched, deps.nowMillis(), ZoneId.systemDefault())
                    ?: return@request
                deps.runOnUi { if (!deps.halted()) onReady(line) }
            }
        }
        return schedule?.let { PlutoLines.guideRow(it, deps.nowMillis(), ZoneId.systemDefault()) }
    }

    companion object {
        /** Construction in one line for the activity, which is at its size limit. */
        fun create(
            prefs: SharedPreferences,
            prefetchExecutor: Executor,
            runOnUi: (() -> Unit) -> Unit,
            halted: () -> Boolean,
            /** The device's 12/24-hour setting, read each time a time is printed. */
            use24Hour: () -> Boolean,
        ): ScreenExtras {
            val features = Features.from(prefs)
            val now = { System.currentTimeMillis() }
            return ScreenExtras(Deps(
                features = features,
                plutoGuide = PlutoGuide(
                    fetch = PlutoApi::httpGet,
                    executor = prefetchExecutor,
                    nowMillis = now,
                    enabled = { features.isOn(Features.Flag.PLUTO_GUIDE) },
                ),
                runOnUi = runOnUi,
                halted = halted,
                nowMillis = now,
                timetable = Timetable(
                    skipsOn = { features.isOn(Features.Flag.SKIP_SPONSORS) },
                    halfHourOn = { features.isOn(Features.Flag.SCHEDULE) },
                    zone = { ZoneId.systemDefault() },
                    use24Hour = use24Hour,
                ),
                plutoRoute = PlutoRoute(
                    sessions = PlutoSessions(
                        boot = { PlutoBoot.fetchBoot(now()) },
                        server = PlutoBoot::fetchFromServer,
                        nowMillis = now,
                    ),
                    direct = { features.isOn(Features.Flag.PLUTO_ROUTE) },
                    nowMillis = now,
                    report = PlaybackDiagnostics::recordSource,
                ),
            ))
        }

    }
}
