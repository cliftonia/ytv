package com.cliftonia.fs42tv.ui

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.ads.AdCatalogStore
import com.cliftonia.fs42tv.ads.AdMirror
import com.cliftonia.fs42tv.pluto.BreakPoller
import com.cliftonia.fs42tv.pluto.MasterPicker
import com.cliftonia.fs42tv.pluto.MasterPrefetch
import com.cliftonia.fs42tv.pluto.PlutoApi
import com.cliftonia.fs42tv.pluto.PlutoBoot
import com.cliftonia.fs42tv.pluto.PlutoGuide
import com.cliftonia.fs42tv.pluto.PlutoIds
import com.cliftonia.fs42tv.pluto.PlutoLines
import com.cliftonia.fs42tv.pluto.PlutoRoute
import com.cliftonia.fs42tv.pluto.PlutoSessions
import com.cliftonia.fs42tv.pluto.SessionPool
import com.cliftonia.fs42tv.pluto.VariantCache
import com.cliftonia.fs42tv.relay.FastRelay
import com.cliftonia.fs42tv.resolver.Loudness
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.schedule.Timetable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.FastGuideStore
import com.cliftonia.fs42tv.tune.TuneController
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
        /** Under mpv, one playlist out of a Pluto master instead of the master - a 3s start, not 9. */
        val masterPicker: MasterPicker? = null,
        /** The break commercials' reels (BREAK ADS), cached in the cache directory; null for none. */
        val adCatalog: AdCatalogStore? = null,
        /** The home server's copy of a reel by id, when the server is reachable - see AdMirror. */
        val adMirror: (String) -> String? = { null },
        /** Reads the Pluto neighbours' masters ahead of a surf, into [masterPicker]'s cache. */
        val masterPrefetch: MasterPrefetch? = null,
        /** The reachable resolve server's base url, or null - the US relay's host; see FastRelay. */
        val relayServer: () -> String? = { null },
        /** What is on the LIVE TV dial's FAST channels, behind FAST GUIDE; null for none. */
        val fastGuide: FastGuideStore? = null,
    )

    /**
     * What the dial plays for a live channel - Pluto's own route or the published url. Blocking;
     * the tune thread only - it is TuneController.Deps.livePlayable.
     */
    fun livePlayable(tuned: Tuned, stillWanted: () -> Boolean): Playable? {
        // A US-only feed goes through the home server's relay, or fails here when there is none.
        val relayed = FastRelay.route(tuned.stream, tuned.playable, deps.relayServer)
        val routed = deps.plutoRoute.forDial(tuned.channel, relayed)
        // Pluto channels, either route, and the LIVE TV dial's FAST feeds: mpv handed a master
        // probes every variant before its first frame - 19-22s on Spark TV and Rakuten, against
        // 5-11s on the one it picks. The YouTube dial's news feeds are left as they were.
        val picker = deps.masterPicker
        val fast = MasterPrefetch.isFast(tuned.channel)
        if ((tuned.channel.pluto == null && !fast) || picker == null) return routed
        // Asked again between the two blocking steps: a session fetch can take seconds, and a
        // master read after it seconds more, on the one tune thread the superseding tune is
        // queued behind. Under fast surfing every stale read delayed the channel actually wanted.
        if (!stillWanted()) return null
        // A remembered pick only for the direct route: the route hands back a new playable for a
        // direct master, whose url names its session, and the published one itself for LEGACY,
        // whose jmp2 url does not. See VariantCache.
        // A FAST feed has no session: its pick is remembered by its master url, for hours.
        return picker.forMpv(routed, cacheable = !fast && routed !== tuned.playable, fast = fast)
    }

    /**
     * A picture came up on [tune]'s channel: a few seconds on, read the Pluto neighbours on
     * [dial] ahead of a surf - unless by then the tune has moved on, or [busy] (a tune under way,
     * a break's commercials on the player, the app out of sight). See [MasterPrefetch].
     */
    fun plutoPictureUp(tune: TuneController, dial: List<Channel>, busy: () -> Boolean) {
        val prefetch = deps.masterPrefetch ?: return
        val onAir = tune.onAir ?: return
        val generation = tune.generationNow()
        prefetch.pictureUp(onAir.channel, dial) {
            tune.generationNow() == generation && !busy() && !deps.halted()
        }
    }

    /** The app left the screen: every read ahead cancelled, and nothing it brings back kept. */
    fun appStopped() {
        deps.masterPrefetch?.stop()
    }

    /**
     * The guide music's version of the same, on a Pluto session of its own so it can never end
     * the programme playing under the guide. Blocking; the prefetch thread only.
     */
    fun besideTuned(tuned: Tuned): Tuned =
        if (tuned.channel.kind != "live") tuned
        else tuned.copy(playable = deps.plutoRoute.forBeside(tuned.channel,
            FastRelay.route(tuned.stream, tuned.playable, deps.relayServer)))

    /**
     * The dial's player failed on [playable]: its remembered pick is forgotten, always, and when
     * [session] is true its session is judged too - see [PlutoRoute.playbackFailed]. False for a
     * demuxer stall, which is ffmpeg, not a refused token.
     */
    fun plutoFailed(playable: Playable?, session: Boolean = true) {
        deps.masterPicker?.forget(playable)
        if (session) deps.plutoRoute.playbackFailed(playable)
    }

    /** The dial gave up on [playable] for want of a picture; see [PlutoRoute.noPicture]. */
    fun plutoNoPicture(playable: Playable?) {
        deps.masterPicker?.forget(playable)
        deps.plutoRoute.noPicture(playable)
    }

    /**
     * [listener] runs on the UI thread when a Pluto channel that fell back for want of a session
     * could now have one - see [PlutoRoute.onSessionReady].
     */
    fun onPlutoSessionReady(listener: (Channel) -> Unit) {
        deps.plutoRoute.onSessionReady = { channel ->
            deps.runOnUi { if (!deps.halted()) listener(channel) }
        }
    }

    /**
     * A Pluto channel that fell back to its legacy url for want of a session - a television just
     * woken - plays Pluto's bumper without an error, so nothing else would ever re-tune it. Once,
     * when a session can be had, and only if the viewer is still on that channel and [idle] - no
     * tune in flight, nothing open over it: a re-tune under the guide changes the channel under
     * the list.
     */
    fun retuneWhenPlutoSessionReady(tune: () -> com.cliftonia.fs42tv.tune.TuneController, idle: () -> Boolean) {
        onPlutoSessionReady { channel ->
            val still = tune().onAir?.takeIf { it.card == null }?.channel?.number == channel.number
            if (still && idle()) tune().retuneCurrent("a pluto session is available")
        }
    }

    val features: Features get() = deps.features

    /** `ads.json`, for [BreakAds]; null when this build of the screen has no cache directory. */
    val adCatalog: AdCatalogStore? get() = deps.adCatalog

    /** Where the home server keeps reel `id`, or null to play it from the archive. */
    val adMirror: (String) -> String? get() = deps.adMirror

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
     *
     * A FAST channel answers from `fast_guide.json` instead - NOW only, see [fastTitle].
     */
    fun bannerLines(channel: Channel, onUpdate: () -> Unit): Pair<String, String>? {
        fastTitle(channel) { deps.runOnUi { if (!deps.halted()) onUpdate() } }?.let { return it to "" }
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
        val fast = fastTitle(channel, onReady?.let { ready ->
            { fastTitle(channel, null)?.let { line -> deps.runOnUi { if (!deps.halted()) ready(line) } } }
        })
        if (fast != null) return fast
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

    /**
     * The title on air on a FAST channel of the LIVE TV dial, from `fast_guide.json` - shown
     * exactly where Pluto's NOW title goes, and like it with no "NOW" in front. Null for every
     * other channel, and with FAST GUIDE off. [onLoaded] runs on the prefetch thread when the
     * first copy of the file lands, so a caller that asked too early can read again.
     */
    private fun fastTitle(channel: Channel, onLoaded: (() -> Unit)?): String? =
        deps.fastGuide?.titleOn(channel, onLoaded)

    companion object {
        /** Construction in one line for the activity, which is at its size limit. */
        fun create(
            prefs: SharedPreferences,
            prefetchExecutor: Executor,
            runOnUi: (() -> Unit) -> Unit,
            halted: () -> Boolean,
            /** The device's 12/24-hour setting, read each time a time is printed. */
            use24Hour: () -> Boolean,
            /** The QUALITY ladder while mpv plays the dial, else null - see [MasterPicker]. */
            mpvLadder: () -> List<String>? = { null },
            /** Where `ads.json` is kept, like the lineups; null for no break commercials. */
            cacheDir: java.io.File? = null,
            /** The reachable resolve server's base url, or null - the break reels' mirror. */
            homeServer: () -> String? = { null },
        ): ScreenExtras {
            val features = Features.from(prefs)
            val now = { System.currentTimeMillis() }
            val elapsed = android.os.SystemClock::elapsedRealtime
            val sessions = PlutoSessions(
                boot = { PlutoBoot.fetchBoot(now()) },
                server = { region -> PlutoBoot.fetchFromServer(region) },
                freshServer = { region -> PlutoBoot.fetchFromServer(region, fresh = true) },
                nowMillis = now,
                // One channel per session: the screen's, and one each side for reading ahead.
                poolSize = SessionPool.SIZE,
                slotServer = { region, slot, fresh -> PlutoBoot.fetchFromServer(region, fresh, client = "dial$slot") },
            )
            val picker = MasterPicker(BreakPoller::httpFetch, mpvLadder, elapsed, VariantCache(elapsed, sessions::claimOf),
                fastCache = VariantCache(elapsed, ttlMillis = VariantCache.FAST_TTL_MILLIS, capacity = VariantCache.FAST_CAPACITY))
            val route = PlutoRoute(
                sessions = sessions,
                direct = { features.isOn(Features.Flag.PLUTO_ROUTE) },
                nowMillis = now,
                report = PlaybackDiagnostics::recordSource,
                // Timed on the main looper, run on the prefetch thread: the check may fetch.
                // Never onto an executor already shut down by onDestroy.
                later = { delay, block ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!halted()) runCatching { prefetchExecutor.execute(block) }
                    }, delay)
                },
                // Sessions rotate only while their masters are read ahead - mpv, see SessionPool.
                rotate = picker::prefetching,
            )
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
                plutoRoute = route,
                masterPicker = picker,
                masterPrefetch = MasterPrefetch.onDevice(route, picker, halted,
                    fastMaster = { channel -> FastRelay.liveUrl(channel, homeServer) },
                    ready = { _, _ -> }),
                adCatalog = cacheDir?.let {
                    AdCatalogStore(java.io.File(it, AdCatalogStore.FILE_NAME), AdCatalogStore::httpFetch)
                },
                adMirror = { id -> AdMirror.url(homeServer(), id) },
                relayServer = homeServer,
                fastGuide = cacheDir?.let {
                    FastGuideStore(
                        file = java.io.File(it, FastGuideStore.FILE_NAME),
                        fetch = FastGuideStore::httpFetch,
                        executor = prefetchExecutor,
                        nowMillis = now,
                        enabled = { features.isOn(Features.Flag.FAST_GUIDE) },
                    )
                },
            ))
        }

    }
}
