package com.cliftonia.fs42tv.ui

import android.util.Log
import com.cliftonia.fs42tv.ads.AdCatalog
import com.cliftonia.fs42tv.ads.AdPicker
import com.cliftonia.fs42tv.player.MpvChannelPlayer
import com.cliftonia.fs42tv.resolver.Loudness
import com.cliftonia.fs42tv.resolver.Progressive

/**
 * Vintage Australian commercials in a Pluto ad break, in place of Pluto's logo - the BREAK ADS
 * row. [PlutoBreak] decides when a break starts and when the programme is back; this owns what
 * plays in between: a reel from the Internet Archive (`ads.json`, see ads/AdCatalogStore),
 * joined at the start of a commercial ([AdPicker]), commercials back to back from there.
 *
 * ON THE SAME PLAYER. The reel is handed to the dial's own engine as a progressive file, and the
 * Pluto stream stops: the TCL cannot afford a second decoder, and one player means the programme's
 * audio question disappears - nothing of Pluto is playing. The break's clock keeps running without
 * it (the poller reads playlists itself; OnScreen.continued), and the return is a tune.
 *
 * NEVER A FAULT. Anything wrong with a reel - it will not open, errors, never shows a frame, stalls
 * for good, runs out more than [MAX_REELS] times - falls back to the card for the rest of this
 * break: no stand-by card, no retry storm, no blame on the Pluto session. The one exception is the
 * engine itself dying (mpv's core shutdown): that is not the reel's to swallow, and it goes to the
 * ordinary error path, which rebuilds the engine and tunes the channel again.
 *
 * While a reel loads the card stays up and the programme stays silent, so the join is WE'LL BE
 * RIGHT BACK and then the commercials, never a flash of the logo or of black.
 *
 * FROM THE HOME SERVER WHEN IT CAN. At home a reel is opened from the server's copy of the same
 * file ([Deps.mirror], ads/AdMirror): about a second to a frame instead of the archive's nine. A
 * copy that will not open - not mirrored yet, the reel server down - or shows no frame within
 * [MIRROR_LOAD_MILLIS] is the archive's url for the SAME reel and cut, with the full
 * [LOAD_MILLIS]; only the archive failing too is the card. Away from home it is the archive, as
 * it always was.
 *
 * Main thread only.
 */
class BreakAds(private val deps: Deps) {

    class Deps(
        /** The BREAK ADS row, read at every break. OFF: every break is the card, as before. */
        val enabled: () -> Boolean,
        /** The reels in hand, never blocking; null when there are none yet. */
        val catalog: () -> AdCatalog?,
        /** Refresh the catalog off the UI thread if it is stale - cheap to call. */
        val warm: () -> Unit,
        /** Where the home server keeps reel `id`, or null to open it from the archive. */
        val mirror: (id: String) -> String? = { null },
        /** Hand the reel to the dial's player, at a second from the start of the file. */
        val play: (reel: Progressive, startAtSeconds: Double) -> Unit,
        /** A reel failed: stop it and hold the player still under the card. */
        val park: () -> Unit,
        /** Runs a block on the main thread after a delay; returns what cancels it. */
        val later: (delayMillis: Long, block: () -> Unit) -> (() -> Unit),
        /** The picture or the sound changed - reel on screen, or fell back to the card. */
        val changed: () -> Unit,
    )

    enum class Stage {
        /** The player is not ours: Pluto, or anything else. */
        IDLE,
        /** A reel is handed over and has not shown a frame; the card is still up. */
        LOADING,
        /** Commercials on screen, at the programme's volume. */
        SHOWING,
        /** The reel failed: the card for the rest of this break, the player parked under it. */
        FAILED,
        /**
         * The break is over (or something else took the screen) but the reel is still the file on
         * the player until the next load replaces it - seconds for a tune, indefinitely under an
         * overlay, where no tune is issued. Its frames, errors, ends and stalls are still the
         * reel's and are swallowed: routed to the channel's handlers they would blame the Pluto
         * session for a dead archive url, or drop the blank and play a commercial out loud.
         */
        RETIRING,
    }

    var stage = Stage.IDLE
        private set

    /** The player is on a reel, parked after one, or retiring one: Pluto is not playing. */
    val onPlayer: Boolean get() = stage != Stage.IDLE

    /** Commercials are what the viewer sees: no card, no silence, no music. */
    val picture: Boolean get() = stage == Stage.SHOWING

    /**
     * The volume the reel plays at: turned down to a Pluto programme's loudness, never up. The
     * archive's commercials were mastered hot - measured, up to 9 dB louder than the film they
     * interrupt - and a break that jumps in volume is the one thing a real station never did.
     */
    var gain = 1f
        private set

    /** A reel on its way: the card waits for it without starting the music. */
    val loading: Boolean get() = stage == Stage.LOADING

    private val recentReels = ArrayDeque<String>()
    private val recentPicks = ArrayDeque<String>()
    private var breakSeed = 0L
    private var channel = 0
    private var reels = 0
    private var cancelTimer: (() -> Unit)? = null

    /**
     * The archive's url and cut for a reel loading from the mirror; null otherwise. Read only
     * while LOADING, and set afresh by every [load].
     */
    private var archive: Pair<String, Double>? = null

    /** A Pluto channel was loaded: have the catalog ready by its first break. */
    fun warm() {
        if (deps.enabled()) deps.warm()
    }

    /**
     * A break reached the screen on [channelNumber], starting at [breakStart] on the stream's
     * clock: play commercials if the row is on and a reel can be had. False leaves the break to
     * the card exactly as before - the player untouched.
     */
    fun start(channelNumber: Int, breakStart: Long): Boolean {
        if (stage != Stage.IDLE || !deps.enabled()) return false
        channel = channelNumber
        breakSeed = breakStart
        reels = 0
        return load()
    }

    private fun load(): Boolean {
        val pick = AdPicker.pick(deps.catalog()?.reels.orEmpty(), AdPicker.seed(breakSeed, channel, reels),
            recentReels.toList(), recentPicks.toList()) ?: return false
        reels++
        remember(recentReels, pick.reel.id, AdPicker.RECENT_REELS)
        remember(recentPicks, pick.key, AdPicker.RECENT_PICKS)
        // Archive urls carry no token, so the reel is named in full - the owner can open it.
        Log.i("fs42", "break ads on $channel: reel ${pick.reel.id} (${pick.reel.era.ifEmpty { "?" }}) " +
            "at ${pick.cutSeconds}s, reel $reels of this break, loudness ${pick.reel.loudness} LUFS - ${pick.reel.url}")
        stage = Stage.LOADING
        gain = Loudness.gain(pick.reel.loudness?.let { it - PROGRAMME_LUFS })
        val mirrored = deps.mirror(pick.reel.id)
        archive = mirrored?.let { pick.reel.url to pick.cutSeconds }
        if (mirrored != null) {
            Log.i("fs42", "break ads on $channel: from the home server - $mirrored")
            open(mirrored, pick.cutSeconds, MIRROR_LOAD_MILLIS)
        } else {
            open(pick.reel.url, pick.cutSeconds, LOAD_MILLIS)
        }
        return true
    }

    private fun open(url: String, at: Double, loadMillis: Long) {
        deps.play(Progressive(url, audioUrl = null), at)
        arm(loadMillis, "no picture after ${loadMillis / 1000}s")
    }

    /**
     * The mirror's copy would not load: the archive's, same reel, same cut, same gain. False when
     * the reel was not from the mirror - the caller falls back to the card as before.
     */
    private fun fromArchive(reason: String): Boolean {
        val (url, at) = archive ?: return false
        archive = null
        Log.i("fs42", "break ads on $channel: home server copy $reason - from the archive instead")
        open(url, at, LOAD_MILLIS)
        return true
    }

    /** The player's first frame. True when it was the reel's - the caller then does nothing else. */
    fun firstFrame(): Boolean {
        if (!onPlayer) return false
        if (stage == Stage.LOADING) {
            cancel()
            archive = null
            stage = Stage.SHOWING
            deps.changed()
        }
        return true
    }

    /** The player failed. True when the reel owned it - handled here, not a fault. */
    fun failed(code: String): Boolean {
        if (!onPlayer) return false
        if (code.startsWith(MpvChannelPlayer.ENGINE_DIED)) {
            // Even while retiring: a rebuild is the only way back to a player at all.
            // The engine is gone, not the reel: the error path rebuilds it and tunes the channel.
            Log.w("fs42", "break ads: the engine died under a reel; handing it to the error path")
            stop()
            return false
        }
        if (stage == Stage.LOADING && fromArchive("failed: $code")) return true
        if (stage == Stage.LOADING || stage == Stage.SHOWING) fallBack("the reel failed: $code")
        return true
    }

    /** The reel ran out. True when it was ours: the next reel, or the card. */
    fun ended(): Boolean {
        if (!onPlayer) return false
        if (stage == Stage.FAILED || stage == Stage.RETIRING) return true
        cancel()
        if (reels >= MAX_REELS || !load()) fallBack("the reel ran out")
        return true
    }

    /** The player stalled or recovered. True when the reel owns it - the Pluto rules do not apply. */
    fun buffering(stalled: Boolean): Boolean {
        if (!onPlayer) return false
        if (stage == Stage.SHOWING) {
            cancel()
            if (stalled) arm(STALL_MILLIS, "stalled for ${STALL_MILLIS / 1000}s")
        }
        return true
    }

    /**
     * The break is over, or something else took the screen: nothing of ours is pending, but the
     * reel is still on the player until a load replaces it - see [Stage.RETIRING].
     */
    fun retire() {
        cancel()
        if (stage != Stage.IDLE) stage = Stage.RETIRING
    }

    /** Something else was loaded onto the player: the reel is gone, and its events with it. */
    fun stop() {
        cancel()
        stage = Stage.IDLE
    }

    private fun fallBack(reason: String) {
        cancel()
        Log.i("fs42", "break ads on $channel: $reason - the card for the rest of this break")
        stage = Stage.FAILED
        deps.park()
        deps.changed()
    }

    private fun arm(millis: Long, reason: String) {
        cancel()
        cancelTimer = deps.later(millis) {
            cancelTimer = null
            if (stage == Stage.LOADING && fromArchive(reason)) return@later
            if (stage == Stage.LOADING || stage == Stage.SHOWING) fallBack(reason)
        }
    }

    private fun cancel() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    private fun remember(into: ArrayDeque<String>, key: String, keep: Int) {
        into.addLast(key)
        while (into.size > keep) into.removeFirst()
    }

    companion object {
        /**
         * How long a reel has to show a frame. Past a slow archive.org open with its seek (a few
         * seconds on the Cinema Stream channel), short of the viewer wondering what the card is for.
         */
        const val LOAD_MILLIS = 10_000L

        /**
         * How long the home server's copy has to show a frame before the archive's is tried. On
         * the LAN a join is about a second; four is a server that is not answering, and still
         * leaves the archive its full [LOAD_MILLIS] - the worst case is fourteen seconds of card.
         */
        const val MIRROR_LOAD_MILLIS = 4_000L

        /** A stall this long mid-reel is the card: a break is too short to wait out a slow line. */
        const val STALL_MILLIS = 8_000L

        /**
         * Where a Pluto programme sits: measured on eight US channels (27 Sep 2026), -22 to -26
         * LUFS but one, and ATSC A/85's -24 - the level American broadcast mixes to.
         */
        const val PROGRAMME_LUFS = -24.0

        /** Reels run out rarely - they are long, breaks are short - but never loop through them. */
        const val MAX_REELS = 3

        /** Construction for [PlutoBreak.create]: the row, the catalog, and the dial's player. */
        fun create(
            extras: ScreenExtras,
            player: () -> com.cliftonia.fs42tv.player.ChannelPlayback?,
            later: (Long, () -> Unit) -> (() -> Unit),
            changed: () -> Unit,
        ): BreakAds? {
            val store = extras.adCatalog ?: return null
            return BreakAds(Deps(
                enabled = { extras.features.isOn(Features.Flag.BREAK_ADS) },
                catalog = store::current,
                warm = store::refreshIfStale,
                mirror = extras.adMirror,
                play = { reel, at -> player()?.play(reel, at, android.os.SystemClock.elapsedRealtime()) },
                // Stopped AND paused, as under the up-next card: on mpv stop() only mutes, and a
                // reel that half-works would otherwise decode on under the card. The return's tune
                // un-pauses (ScreenDirector.returnFromBreak).
                park = {
                    player()?.stop()
                    player()?.setPaused(true)
                },
                later = later,
                changed = changed,
            ))
        }
    }
}
