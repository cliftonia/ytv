package com.cliftonia.fs42tv.ui

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import com.cliftonia.fs42tv.pluto.PlutoApi
import com.cliftonia.fs42tv.pluto.PlutoGuide
import com.cliftonia.fs42tv.pluto.PlutoIds
import com.cliftonia.fs42tv.pluto.PlutoLines
import com.cliftonia.fs42tv.resolver.Loudness
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
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
        /** Main looper: the hiss's fade steps and its cap. */
        val handler: Handler,
        /** Downloads the corner logos for Pluto channels. */
        val logos: ImageCache<ImageBitmap>,
    )

    val features: Features get() = deps.features

    private val hiss = Hiss(deps.handler)
    private val hissGate = HissGate()
    private val hissCap = Runnable { applyHiss(hissGate.timedOut()) }

    /**
     * Start or fade the channel-change hiss. Called by the director wherever it re-derives the
     * programme volume, with the same two facts that rule uses - see [HissGate] for why the hiss
     * is exactly that rule's complement. Main thread only.
     */
    fun syncHiss(tuning: Boolean, covered: Boolean) {
        val enabled = deps.features.isOn(Features.Flag.STATIC)
        applyHiss(hissGate.update(HissGate.wanted(enabled, tuning, covered)))
    }

    private fun applyHiss(action: HissGate.Action) {
        when (action) {
            HissGate.Action.START -> {
                hiss.start()
                deps.handler.removeCallbacks(hissCap)
                deps.handler.postDelayed(hissCap, HISS_CAP_MILLIS)
            }
            HissGate.Action.FADE -> {
                deps.handler.removeCallbacks(hissCap)
                hiss.fadeOut()
            }
            HissGate.Action.NONE -> Unit
        }
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

    /** The corner logo on screen, or null. Compose state, written on the UI thread only. */
    val bug = mutableStateOf<BugState?>(null)
    private val bugTrigger = BugTrigger()
    private var bugGeneration = 0

    /** A channel change or the launch tune began - the next first frame is a new arrival. */
    fun tuneStarted() = bugTrigger.tuneStarted()

    /**
     * A picture arrived. Puts the corner logo up when [BugTrigger] says this is an arrival and
     * the LOGO row is on - the trigger is fed either way, so switching LOGO on mid-programme
     * does not treat the clip already playing as new.
     */
    fun firstFrame(onAir: Tuned?) {
        val tuned = onAir ?: return
        val channel = tuned.channel
        val arrival = bugTrigger.firstFrame(
            channel.number, tuned.streamIndex, clock = channel.rotation == "clock")
        if (!arrival || !deps.features.isOn(Features.Flag.LOGO)) return
        val generation = ++bugGeneration
        bug.value = BugState(StationBug.label(channel), channel.number.toString(), null, generation)
        // Pluto's own logo when its guide has one. Through the guide, so with PLUTO GUIDE off
        // there is no Pluto traffic at all and the bug stays text.
        if (!deps.features.isOn(Features.Flag.PLUTO_GUIDE)) return
        val id = PlutoIds.of(channel) ?: return
        deps.plutoGuide.request(id) { schedule ->
            val url = schedule.logoUrl ?: return@request
            deps.logos.get(url) { image ->
                deps.runOnUi {
                    val showing = bug.value
                    if (!deps.halted() && showing?.generation == generation) {
                        bug.value = showing.copy(logo = image)
                    }
                }
            }
        }
    }

    /** LOGO switched off: take the bug down now rather than letting it run out. */
    fun hideBug() {
        bug.value = null
    }

    /** On destroy: nothing of the extras may outlive the activity. */
    fun release() {
        deps.handler.removeCallbacks(hissCap)
        hiss.release()
    }

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
        ): ScreenExtras {
            val features = Features.from(prefs)
            val now = { System.currentTimeMillis() }
            // The prefetch thread for the logos too: small, rare, and never ahead of a tune.
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
                handler = Handler(Looper.getMainLooper()),
                logos = ImageCache(load = ::loadLogo, executor = prefetchExecutor),
            ))
        }

        /**
         * The longest a hiss runs. A normal tune lands in one to three seconds; anything longer is
         * a channel in trouble, and it stays quiet from here until that tune ends.
         */
        const val HISS_CAP_MILLIS = 5_000L
    }
}
