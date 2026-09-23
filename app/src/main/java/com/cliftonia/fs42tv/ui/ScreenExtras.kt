package com.cliftonia.fs42tv.ui

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.cliftonia.fs42tv.pluto.PlutoApi
import com.cliftonia.fs42tv.pluto.PlutoGuide
import com.cliftonia.fs42tv.pluto.PlutoIds
import com.cliftonia.fs42tv.pluto.PlutoLines
import com.cliftonia.fs42tv.sync.Channel
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
            ))
        }

        /**
         * The longest a hiss runs. A normal tune lands in one to three seconds; anything longer is
         * a channel in trouble, and it stays quiet from here until that tune ends.
         */
        const val HISS_CAP_MILLIS = 5_000L
    }
}
