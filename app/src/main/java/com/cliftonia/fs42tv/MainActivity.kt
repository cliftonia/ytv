package com.cliftonia.fs42tv

import android.app.ActivityManager
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.player.MpvChannelPlayer
import com.cliftonia.fs42tv.player.PlayerEngine
import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.ResolvedCache
import com.cliftonia.fs42tv.resolver.ServerResolver
import com.cliftonia.fs42tv.resolver.StreamResolver
import com.cliftonia.fs42tv.resolver.TierLadder
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.DialRepository
import com.cliftonia.fs42tv.sync.UrlCache
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.Tuned
import com.cliftonia.fs42tv.tune.Tuner
import com.cliftonia.fs42tv.ui.ChannelLabels
import com.cliftonia.fs42tv.ui.ChannelOsd
import com.cliftonia.fs42tv.ui.PickerMusic
import com.cliftonia.fs42tv.ui.ChannelPicker
import com.cliftonia.fs42tv.ui.StandBy
import com.cliftonia.fs42tv.ui.TuningBlank
import com.cliftonia.fs42tv.ui.UpdatePrompt
import com.cliftonia.fs42tv.update.Updater
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val SERVER = "http://192.168.4.203:4243"
private const val PREFS_NAME = "fs42"
private const val CHANNEL_KEY = "channel"

/**
 * Remembers the chosen video engine so an override survives a relaunch.
 *
 * Persisted rather than decided fresh each start because the point of the flag is to put Media3
 * back in a hurry when mpv misbehaves - and a setting that evaporates on the next launch is no
 * use at all in that moment.
 */
private const val ENGINE_KEY = "engine"
private const val NO_REMEMBERED_CHANNEL = -1

/** Long enough not to flash on the brief stalls that clear themselves. */
private const val STALL_CARD_MILLIS = 2_500L

/**
 * How long a playback error is given to fix itself before the stand-by card appears.
 *
 * A 403 on a signed URL recovers by dropping the dead id, asking the server for a fresh one and
 * tuning again. Measured end to end that is about 1.5s - a clean tune reaches first frame in
 * 910-2870ms, and the extra server resolve is the slow part. 4s covers the slow end with room,
 * while still being short enough that a channel which is genuinely dead says so rather than
 * sitting blank.
 */
private const val RECOVERY_GRACE_MILLIS = 4_000L

class MainActivity : ComponentActivity() {

    // @Volatile: assigned on the UI thread in onCreate, read from the executor when tuning.
    @Volatile private var player: ChannelPlayback? = null

    /**
     * Audio-only player for the music under the channel picker.
     *
     * A separate ExoPlayer rather than the main one, because the channel being watched must keep
     * playing underneath the list - the picker is translucent over it. Audio only, so the cost is
     * one stream of about 128kbps rather than a second video decode; video preloading was
     * abandoned over exactly that bandwidth budget, and this stays well inside it.
     *
     * Created on first use and released with the activity. Everything about it is best-effort:
     * guide music is atmosphere, and atmosphere is never worth an error.
     */
    private var musicPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    @Volatile private var navigator: DialNavigator? = null
    @Volatile private var destroyed: Boolean = false
    private var urls: UrlCache? = null

    /**
     * What is actually on air right now, as opposed to where the navigator points. A failed
     * tune leaves the previous picture up with the navigator already moved on, so this is set
     * only on a genuine success. Written on the executor thread, read from the UI thread by the
     * phase 2b corner indicator and banner; `@Volatile` is enough because `Tuned` is immutable.
     */
    @Volatile private var onAir: Tuned? = null

    // Compose state backing the tune banner. Written only from the runOnUiThread block below,
    // and only on a genuine success: a failed re-tune must not touch these, since bumping
    // bannerGeneration would replay the LaunchedEffect in ChannelOsd and pop a banner back up
    // for a channel that never changed.
    private val bannerChannelLine = mutableStateOf("")
    private val bannerTitleLine = mutableStateOf("")

    // Separate from `generation` below on purpose: that counter is bumped once per keypress, to
    // coalesce a burst of presses, and can advance even when a tune ultimately fails - it does
    // not increment exactly once per successful tune. Using it as the banner's LaunchedEffect
    // key would replay the auto-hide timer (and thus re-show the banner) on a failed re-tune
    // even though nothing on screen changed. This counter only advances alongside onAir itself.
    private val bannerGeneration = mutableStateOf(0)

    // Backs the channel picker. pickerRows/pickerStartIndex are captured once, at the moment
    // the picker opens, rather than derived live from navigator/onAir - the whole point of the
    // picker is that surfing is frozen while it's up, so nothing should move these under it.
    // Backs the stand-by card. A black screen is indistinguishable from a dead app or a
    // dead TV; the card says the app knows and is retrying.
    private val standByReason = mutableStateOf("")

    // True from choosing a channel until its first frame arrives, so the previous channel is not
    // left playing under a banner announcing a different one.
    private val tuning = mutableStateOf(false)

    /** True once a newer build has been downloaded and is sitting ready to install. */
    private val updateReady = mutableStateOf(false)

    /**
     * Set the channel's volume from the two things that can silence it, rather than from
     * whichever happened last.
     *
     * Both the guide music and a channel change want the programme audio down, and they overlap:
     * selecting from the picker closes it - restoring volume - immediately AFTER the tune has
     * muted, so a last-writer-wins approach let the previous channel's audio out for exactly the
     * split second the new one took to arrive. Deriving the value from both conditions makes the
     * order they fire in irrelevant.
     */
    private fun updateProgrammeVolume() {
        player?.setVolume(if (tuning.value || pickerVisible.value) 0f else 1f)
    }

    private val pickerVisible = mutableStateOf(false)
    private val pickerRows = mutableStateOf<List<Pair<String, String>>>(emptyList())
    private val pickerStartIndex = mutableStateOf(0)

    // Local var rather than only a local val in onCreate: opening the picker needs to flip this
    // view's descendantFocusability and pull focus onto it, which onKeyDown must be able to
    // reach after onCreate has returned.
    private lateinit var composeView: ComposeView

    private lateinit var prefs: SharedPreferences
    private lateinit var resolver: ServerResolver

    // urls.json covers about 46% of the dial's clips, so most tunes fall through to the server.
    // Without this, every later pass over the same channel pays that round trip again. Lives
    // for the session only: these URLs are signed and expire in hours, so persisting them would
    // mean starting up holding URLs that may already be dead.
    private val resolvedCache = ResolvedCache()

    /**
     * Video ids whose cached URL was rejected by the CDN, so the next tune asks the server for a
     * fresh one instead of handing back the same dead link.
     *
     * A signed googlevideo URL can be refused with 403 well inside its stated expiry, so the
     * timestamp alone cannot decide whether it is usable - the box learned the same thing and
     * keeps a `drop()` for exactly this. Without it a re-tune resolves to the identical dead URL
     * and fails identically, which is what put a stand-by card up on every attempt to select a
     * distant channel from the picker.
     */
    /**
     * Tiers the CDN refused this session, as `<id>/<tier>`.
     *
     * Separate from [deadIds], which condemns a whole clip and so forces a `/resolve` - and that
     * runs yt-dlp, measured at 7.7 and 12.2 seconds, well past the 4s after which the viewer is
     * shown a stand-by card. Nearly every clip is published with both an hd and an sd tier in a
     * file the app already holds, so a refused hd falls to sd with no round trip at all.
     */
    private val refusedTiers = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val deadIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Single-threaded so a rapid burst of channel presses queues in order rather than racing
    // each other over the shared navigator and player.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Bumped on every keypress. A tune captures the current value when queued and abandons
    // itself if the value has since moved on - that is how a burst of presses on the dial
    // collapses to only the last one actually reaching the player, instead of running every
    // intermediate channel to completion.
    private val generation = AtomicInteger(0)

    /** Drives the stand-by card when playback stalls mid-clip. */
    private val stallHandler by lazy { android.os.Handler(mainLooper) }

    /**
     * Delays the stand-by card after a playback error, so a fault the app repairs by itself is
     * never announced.
     *
     * Deliberately NOT the stall handler. Both post one delayed reveal and both clear their queue
     * before posting, so sharing one would let a stall cancel a pending error card and leave a
     * genuinely dead channel showing nothing but a blank screen forever.
     */
    private val recoveryHandler by lazy { android.os.Handler(mainLooper) }

    /**
     * Builds a fresh engine and swaps it into the layout, releasing the old one.
     *
     * Only mpv needs this, and only for one reason: a dead URL makes an EDL yield no streams at
     * all, which mpv treats as FATAL and shuts its core down - `idle=yes` does not cover a fatal.
     * Without a rebuild, one 403 blacks out the dial until the app is restarted by hand.
     */
    @Volatile private var rebuildEngine: (() -> Unit)? = null

    /**
     * Wall-clock seconds, or a frozen instant when one was supplied at launch.
     *
     * Every channel on this dial derives its clip and offset from the current time, so two runs
     * a few minutes apart are watching entirely different content - different bitrates, different
     * file sizes, different CDN hosts. That is a far larger source of variance than any setting
     * worth tuning, and it produced three separate false results before it was identified: the
     * same configuration measured 3892ms then 9971ms on the emulator, and 1779ms then 4483ms on
     * the television.
     *
     * Freezing the clock pins clip selection and offset, so a sweep compares configurations
     * against identical content instead of against whatever happened to be on air. Only ever set
     * for measurement; a launch without the extra behaves exactly as the remote does.
     */
    /**
     * Which quality tiers to ask for, from what this panel can actually show.
     *
     * Read from `Display.getMode()`, which reports the PHYSICAL mode - 3840x2160 on the TCL -
     * unlike `DisplayMetrics`, which on Android TV reports the UI layer and is 1920x1080 on the
     * same set. Reading the wrong one caps every 4K television at hd, which is precisely what
     * the old hard-wired `preferUhd = false` did.
     *
     * The UI layer being 1080p does not cap video: the app's UI and the video surface are
     * composited separately, and a SurfaceView renders at panel resolution regardless.
     */
    @Volatile private var ladder: List<String> = listOf("hd", "sd")

    @Volatile private var fixedNowSeconds: Long = -1L

    private fun nowSeconds(): Long =
        if (fixedNowSeconds > 0) fixedNowSeconds else System.currentTimeMillis() / 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        resolver = ServerResolver(fetch = { url -> URL(url).readText() }, baseUrl = SERVER)

        // Which engine plays the dial, and why it is not simply "the newer one".
        //
        // Media3 judders on this television - roughly two tunes in five come back with the
        // picture running fast then slow - and mpv does not, measured on the same clips at the
        // same offsets. androidx/media issue 2941 documents the same fault on BUILT-IN Android
        // TVs and explicitly NOT on Chromecast or Fire TV, which matches: a stick can change its
        // HDMI output mode, a panel with one mode cannot. So the choice is made from the number
        // of display modes rather than from a device name, and Media3 stays the default wherever
        // it works - it is a fifth of the install size and starts faster.
        //
        // Override with:  adb shell am start -n com.cliftonia.fs42tv/.MainActivity --es engine mpv
        val modeCount = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            display else windowManager.defaultDisplay)?.supportedModes?.size ?: 0
        val engine = PlayerEngine.parse(intent?.getStringExtra("engine"))
            ?: PlayerEngine.parse(prefs.getString(ENGINE_KEY, null))
            ?: PlayerEngine.default(modeCount)
        prefs.edit().putString(ENGINE_KEY, engine.name.lowercase()).apply()
        Log.i("fs42", "player engine $engine ($modeCount display mode(s))")
        composeView = ComposeView(this).apply {
            // The picker needs focus when open; the OSD does not, and must not steal it from
            // the D-pad channel-surfing handled in onKeyDown while the picker is closed.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Beneath the OSD, so the banner stays readable through the change.
                    TuningBlank(tuning.value)
                    UpdatePrompt(updateReady.value)
                    ChannelOsd(
                        channelLine = bannerChannelLine.value,
                        titleLine = bannerTitleLine.value,
                        generation = bannerGeneration.value,
                    )
                    StandBy(standByReason.value.isNotEmpty(), standByReason.value)
                    if (pickerVisible.value) {
                        ChannelPicker(
                            rows = pickerRows.value,
                            startIndex = pickerStartIndex.value,
                            onPick = ::onPickChannel,
                            onDismiss = ::closePicker,
                        )
                    }
                }
            }
        }
        fun matchParent() = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        fun newEngine(): ChannelPlayback = when (engine) {
            PlayerEngine.MPV -> MpvChannelPlayer(this)
            PlayerEngine.MEDIA3 -> ChannelPlayer(
                this, ChannelPlayer.dataSourceFactory(), canSwitchDisplayMode = modeCount > 1)
        }

        var player: ChannelPlayback = newEngine()
        this.player = player

        val root = FrameLayout(this).apply {
            addView(player.view, matchParent())
            addView(composeView, matchParent())
        }
        setContentView(root)


        // Both of these leave a black screen if nothing handles them, and neither reports itself:
        // a finished clip simply stops, and a rejected URL stops too. Re-tuning the channel is
        // the right answer to both - the clock rotation will pick whatever should be on now,
        // which after a finished clip is the next one along.
        //
        // Guarded by a fresh generation so a recovery cannot fight a channel change the viewer
        // has already made, and skipped entirely once the activity is gone.
        fun retuneCurrent(reason: String) {
            if (destroyed) return
            val channel = onAir?.channel ?: return
            Log.i("fs42", "re-tuning ${channel.number} ${channel.name} after $reason")
            val gen = generation.incrementAndGet()
            val at = SystemClock.elapsedRealtime()
            executor.execute { tuneTo(channel, gen, at) }
        }
        // Extracted so a rebuilt engine can be given the same callbacks. mpv shuts its
        // core down on a fatal, and a replacement with nothing listening reports no first
        // frame - the stand-by card would then never come down again.
        fun wire(player: ChannelPlayback) {
            player.onClipEnded = { retuneCurrent("clip ended") }
            player.onPlaybackError = { code ->
                if (code == MpvChannelPlayer.ENGINE_DIED) {
                    // The engine, not the clip. Rebuild first, then let the normal recovery below
                    // re-tune into the new instance.
                    rebuildEngine?.invoke()
                }
                // A rejected URL is the one error worth reacting to specifically: re-tuning without
                // forgetting it would resolve to the same dead link and fail the same way.
                // Engine-agnostic on purpose. Media3 names the fault precisely; mpv reports only
                // that the file ended in error, and its commonest cause by far is exactly this - a
                // signed URL the CDN refused. Matching only Media3's spellings meant an mpv 403
                // re-tuned to the very same dead URL, forever. Being wrong in the other direction
                // costs one server resolve.
                if (code.contains("BAD_HTTP_STATUS") || code.contains("FILE_NOT_FOUND") ||
                    code.startsWith("MPV_")) {
                    onAir?.stream?.id?.let { id ->
                        // Refuse the TIER, not the clip. Condemning the whole id forces a
                        // /resolve, which runs yt-dlp - measured at 7.7 and 12.2 seconds, well
                        // past the 4s after which the viewer is shown a stand-by card. Nearly
                        // every clip is published with both an hd and an sd tier in a file the
                        // app already holds, so the next rung costs no round trip at all.
                        //
                        // The failing tier is whichever rung the resolver would have taken - the
                        // first fresh one not already refused - so it can be recomputed here
                        // rather than threaded back out of the player.
                        val tier = ladder.firstOrNull {
                            StreamResolver.refusedKey(id, it) !in refusedTiers
                        }
                        if (tier != null) {
                            Log.w("fs42", "tier $tier refused for $id; falling to the next rung")
                            refusedTiers.add(StreamResolver.refusedKey(id, tier))
                        } else {
                            Log.w("fs42", "all tiers refused for $id; asking the server")
                            deadIds.add(id)
                            resolvedCache.forget(id)
                        }
                    }
                }

                // Do NOT put the stand-by card up yet. A signed googlevideo URL can be refused with
                // 403 while still inside its stated expiry, and the recovery below - drop the dead
                // id, ask the server for a fresh one, tune again - puts a picture back in about a
                // second. Announcing that as a fault showed the viewer an error code for something
                // the app had already fixed, which reads as far more broken than the brief blank a
                // channel change produces anyway.
                //
                // The card is only delayed, never skipped: if the retune has not produced a picture
                // by the time the grace period is up, this is a real fault and says so. Blanking
                // meanwhile is what a channel change already does, so the transition looks the same
                // as any other.
                tuning.value = true
                updateProgrammeVolume()
                recoveryHandler.removeCallbacksAndMessages(null)
                recoveryHandler.postDelayed({ standByReason.value = code }, RECOVERY_GRACE_MILLIS)
                retuneCurrent("playback error $code")
            }
            // The card comes down when a picture actually appears, not when a tune is merely
            // dispatched - a tune that fails again would otherwise clear it and leave black.
            player.onFirstFrame = {
                recoveryHandler.removeCallbacksAndMessages(null)
                standByReason.value = ""
                tuning.value = false
                updateProgrammeVolume()
            }

            // A stall is the third way this player goes quiet, and the only silent one - no error,
            // no end of media, just a stopped picture. The box calls the same condition a fault
            // after two seconds and puts a stand-by card up (field_player.py:575).
            //
            // The card is ALL that happens. Re-tuning on a stall was tried and made things far
            // worse: it discards whatever has buffered and restarts the deep seek, so on a
            // connection that cannot sustain the bitrate it produced a permanent cycle of six
            // seconds of picture and twelve of nothing. ExoPlayer keeps filling during a stall and
            // resumes by itself; interrupting that is the one thing that stops it recovering.
            player.onBuffering = { buffering ->
                stallHandler.removeCallbacksAndMessages(null)
                if (buffering) {
                    stallHandler.postDelayed({
                        if (!destroyed) standByReason.value = "BUFFERING"
                    }, STALL_CARD_MILLIS)
                    // NO automatic re-tune on a stall. It was tried and it made things materially
                    // worse: a re-tune discards whatever has buffered and restarts the deep seek, so
                    // on a connection that cannot sustain the bitrate it produced a permanent cycle -
                    // six seconds of playback, twelve seconds of nothing, repeat. ExoPlayer keeps
                    // filling the buffer during a stall and resumes on its own; interrupting that is
                    // the one thing guaranteed to stop it recovering.
                } else {
                    standByReason.value = ""
                }
            }
        }
        wire(player)

        // Below the callback wiring so a rebuilt engine gets the same callbacks the first one
        // had - a fresh player with nothing listening reports no first frame, so the stand-by
        // card would never come down again.
        rebuildEngine = {
            val dead = this.player
            val fresh = newEngine()
            wire(fresh)
            this.player = fresh
            player = fresh
            root.removeView(dead?.view)
            root.addView(fresh.view, 0, matchParent())
            dead?.release()
            Log.i("fs42", "engine rebuilt after shutdown")
        }

        checkForUpdate()

        val remembered = prefs.getInt(CHANNEL_KEY, NO_REMEMBERED_CHANNEL)

        val initialRequestedAt = SystemClock.elapsedRealtime()
        executor.execute {
            val repo = DialRepository(
                fetch = { url -> URL(url).readText() },
                cacheDir = cacheDir,
            )
            val synced = runCatching { repo.sync(SERVER) }.getOrNull()
            val dial = synced?.dial ?: repo.cachedDial()
            urls = synced?.urls ?: repo.cachedUrls()

            val channels = dial?.channels
            if (channels.isNullOrEmpty()) {
                Log.e("fs42", "no dial available; cannot surf")
                return@execute
            }

            val nav = DialNavigator(channels, remembered.takeIf { it > 0 })
            navigator = nav
            tuneTo(nav.current, generation.get(), initialRequestedAt)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val nav = navigator ?: return super.onKeyDown(keyCode, event)

        // Belt and braces alongside the focus handoff in openPicker(): once the picker is up,
        // the focused row already consumes D-pad up/down/centre before the activity would ever
        // see them, but this guard is what actually guarantees the channel-change keys are
        // inert here rather than relying on focus routing alone. KEYCODE_BACK is deliberately
        // not handled here at all - the picker owns its own dismissal via BackHandler.
        if (pickerVisible.value) {
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                surfTo(nav.up())
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                surfTo(nav.down())
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_GUIDE -> {
                // OK does double duty, but only while the update prompt is on screen - and the
                // prompt says so, so it is not a surprise. The alternative was a second key, and
                // this remote is a cheap universal one where INFO and MENU may not exist at all;
                // a long press would have meant taking over key tracking from the guide.
                //
                // Cleared before installing either way: whether the viewer accepts Android's
                // dialog or dismisses it, the prompt has done its job and must not sit there
                // hijacking the guide button afterwards.
                if (updateReady.value) {
                    updateReady.value = false
                    Updater(this, SERVER).install()
                } else {
                    openPicker(nav)
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Move the dial now, show it now, and let the picture catch up.
     *
     * The navigator used to be advanced INSIDE the executor lambda, which meant a second press
     * could not move the dial until the first tune's network round trip had finished - about two
     * seconds during which the remote appeared dead and nothing on screen acknowledged the
     * press. Surfing quickly was impossible even though every press was being registered.
     *
     * Advancing here makes the UI thread the navigator's single writer instead of the executor,
     * which is the same invariant moved rather than broken: `up()`/`down()`/`jumpTo` are now only
     * ever called from key handling, and the executor only reads.
     *
     * The banner goes up immediately with no title, because the title is not known until the
     * clip is resolved. That is how a television behaves - the number changes the instant you
     * press, and the programme name arrives with the picture.
     */
    private fun surfTo(target: Channel) {
        // The title comes from the clock rotation right here, not from the tune that follows.
        // Waiting for the tune meant the banner showed a bare channel name whenever the tune was
        // superseded - which is every press but the last when surfing quickly - and whenever a
        // channel was chosen from the picker, where the title was already known and thrown away.
        //
        // It costs one walk of one channel's clip list, no network, and it is the same
        // arithmetic the guide uses. What is on a channel is knowable without tuning to it.
        // Stop the old channel at the SOURCE rather than covering it. A Compose overlay needs a
        // recomposition and a frame to appear, and the previous channel keeps rendering
        // underneath in the meantime - which showed up as an intermittent flash of the old
        // picture right after choosing a new one. stop() ends that render immediately, and
        // PlayerView's own shutter takes the surface black in the same frame.
        //
        // Safe to do here: surfTo only runs on a deliberate channel change, and the tune that
        // follows calls setMediaSource and prepare regardless of what state the player was left
        // in. The blank overlay stays as well, to cover the gap between the shutter and the
        // first frame of the new channel.
        player?.stop()
        // A deliberate channel change supersedes any error still waiting to be
        // announced: the card would name a channel the viewer has already left.
        recoveryHandler.removeCallbacksAndMessages(null)
        standByReason.value = ""
        tuning.value = true
        updateProgrammeVolume()
        val (line, title) = ChannelLabels.bannerLinesFor(target, nowSeconds())
        bannerChannelLine.value = line
        bannerTitleLine.value = title
        bannerGeneration.value += 1

        val gen = generation.incrementAndGet()
        val requestedAt = SystemClock.elapsedRealtime()
        executor.execute { tuneTo(target, gen, requestedAt) }
    }

    /**
     * Opens the picker seeded on the channel actually on air - [onAir], not [DialNavigator.currentIndex]:
     * a tune that failed leaves the navigator pointed somewhere the picture never actually
     * reached, and the picker must open on what the viewer is looking at, not where the dial
     * silently moved to.
     */
    private fun openPicker(nav: DialNavigator) {
        // Opening the picker is a supersede point. onKeyDown queues `tuneTo(nav.up(), gen)` and
        // evaluates nav.up() when the EXECUTOR reaches it, not at keypress time - so a press
        // landing a moment before this one still has a tune in flight. Without this bump that
        // tune passes its own generation check, moves the navigator out from under the rows
        // captured below, and starts playing with its banner drawn behind the open list. That
        // was reproduced on device before this line existed, not theorised.
        generation.incrementAndGet()

        val onAirNumber = onAir?.channel?.number ?: nav.currentNumber
        val startIndex = nav.channels.indexOfFirst { it.number == onAirNumber }
            .let { if (it >= 0) it else nav.currentIndex }

        // The list goes up with channel names ONLY, immediately. Working out what is on each
        // of 111 channels means walking every channel's clip list, and doing that before the
        // first frame of the picker is drawn puts a visible pause between pressing the button
        // and seeing anything - the one moment where a guide has to feel instant.
        //
        // The titles arrive a beat later and the rows fill in underneath, which is what a
        // skeleton is for: structure now, detail when it exists.
        pickerRows.value = nav.channels.map { ChannelLabels.listRow(it) }
        pickerStartIndex.value = startIndex
        pickerVisible.value = true

        composeView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        composeView.requestFocus()
        fillPickerTitles(nav)
        startPickerMusic(nav)
    }

    /**
     * Work out what is on each channel and fill the rows in behind the already-visible list.
     *
     * Off the UI thread, and discarded if the picker has closed by the time it finishes - a
     * viewer who opened and dismissed the guide in under a second should not have rows quietly
     * rewritten underneath the channel they went back to watching.
     */
    private fun fillPickerTitles(nav: DialNavigator) {
        val channels = nav.channels
        executor.execute {
            val started = SystemClock.elapsedRealtime()
            val now = nowSeconds()
            val rows = channels.map { channel ->
                val title = ClockRotation
                    .playPointFor(channel.streams.map { it.duration }, now)
                    ?.let { channel.streams.getOrNull(it.index)?.title }
                ChannelLabels.listRow(channel, title)
            }
            val took = SystemClock.elapsedRealtime() - started
            runOnUiThread {
                if (destroyed || !pickerVisible.value) return@runOnUiThread
                Log.d("fs42", "guide titles for ${rows.size} channels in ${took}ms")
                pickerRows.value = rows
            }
        }
    }

    /**
     * Play the guide music and duck the channel underneath.
     *
     * Ducked rather than left alone: two audio sources at once is noise, and a guide channel
     * always replaced the programme audio rather than competing with it. The picture keeps
     * playing, so closing the picker restores sound to a channel that never stopped.
     */
    private fun startPickerMusic(nav: DialNavigator) {
        val channel = PickerMusic.choose(nav.channels) ?: return
        updateProgrammeVolume()
        executor.execute {
            if (destroyed) return@execute
            val now = nowSeconds()
            val tuned = Tuner.tune(channel, urls, now, ladder, refusedTiers.toSet()) ?: return@execute
            var playable: Playable = tuned.playable
            if (playable is NeedsResolving) {
                playable = resolvedCache.get(playable.videoId, now)
                    ?: resolver.resolveDetailed(playable.videoId, now, ladder)?.also {
                        resolvedCache.put(playable.videoId, it.playable, it.expiresAtSeconds)
                    }?.playable ?: return@execute
            }
            // Only the audio track is wanted, so the audio URL is handed over as the source and
            // the video URL is dropped entirely - no second decode, no second video fetch.
            val audioOnly = when (playable) {
                is Progressive -> playable.audioUrl?.let { Progressive(it, null) }
                is Hls -> playable
                else -> null
            } ?: return@execute
            // Always Media3, whatever plays the video. The guide music is an audio-only
            // stream under a translucent list; it has none of the frame-pacing problem that put
            // mpv on the video path, and giving it a second engine would mean a second set of
            // native libraries loaded to play 128kbps of bossa nova.
            val source = ChannelPlayer.sourceFor(
                ChannelPlayer.dataSourceFactory(), audioOnly) ?: return@execute

            runOnUiThread {
                if (destroyed || !pickerVisible.value) return@runOnUiThread
                val music = musicPlayer ?: androidx.media3.exoplayer.ExoPlayer.Builder(this)
                    .build().also { musicPlayer = it }
                Log.i("fs42", "guide music: ${channel.name}")
                music.setMediaSource(source, (tuned.offsetSeconds * 1000).toLong())
                music.prepare()
                music.playWhenReady = true
            }
        }
    }

    private fun stopPickerMusic() {
        // RELEASE, not stop(). stop() halts playback but keeps the instance, and with it a
        // hardware MediaCodec - a limited resource on this television, held idle alongside the
        // video decoder for as long as the app runs. Frame drops appeared across every channel
        // as soon as this player was introduced, which is what an extra codec instance looks
        // like from the outside. Recreating it on the next open costs a few hundred
        // milliseconds of music, against a picture that stays smooth.
        musicPlayer?.release()
        musicPlayer = null
        updateProgrammeVolume()
    }

    /**
     * Deliberately does NOT bump [generation], unlike [openPicker]. Closing happens either from
     * BACK - when nothing is queued, because onKeyDown refuses every channel key while the picker
     * is up - or from [onPickChannel], which runs immediately AFTER queueing the tune the viewer
     * just asked for. A bump here would supersede that tune and selecting a channel would quietly
     * do nothing.
     */
    private fun closePicker() {
        pickerVisible.value = false
        stopPickerMusic()
        composeView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    /**
     * OK on a row: resolves the row index back to a channel, moves the navigator to it exactly
     * like surfing does, tunes it on the executor, and closes. This is the one path that
     * actually changes the channel from the picker - reusing [tuneTo] rather than a second one,
     * per the same generation bookkeeping surfing uses.
     */
    private fun onPickChannel(index: Int) {
        val nav = navigator
        val channel = nav?.channels?.getOrNull(index)
        if (nav != null && channel != null) {
            // jumpTo on this thread, like up() and down(): key handling is the navigator's single
            // writer, and the executor only reads it.
            nav.jumpTo(channel.number)

            // Selecting the channel already on air must NOT re-tune. A re-tune tears the player
            // down and restarts the same clip at a freshly computed offset a few seconds later,
            // so the picture visibly jumps for no reason - the viewer asked for the channel they
            // are already watching, and the correct answer is "you have it". The banner is still
            // re-shown, because pressing OK on a channel is a request to be told what it is.
            if (onAir?.channel?.number == channel.number) {
                bannerGeneration.value += 1
            } else {
                surfTo(channel)
            }
        }
        closePicker()
    }

    /**
     * Runs on the background executor: resolves what a channel is showing right now and starts
     * it on the UI thread. A cache miss is resolved from the server before giving up; when even
     * that fails, the current picture is left up rather than blanking the screen.
     *
     * [requestGeneration] is checked at the start and again right before the result would reach
     * the player: if a later keypress has since bumped [generation], this tune is superseded and
     * abandons without touching the player, prefs, or [onAir]. That is what lets a burst of
     * presses skip every intermediate channel instead of running each one to completion.
     */
    private fun tuneTo(channel: Channel, requestGeneration: Int, requestedAtMillis: Long) {
        if (requestGeneration != generation.get()) {
            Log.d("fs42", "channel ${channel.number} ${channel.name}: superseded before tuning; abandoning")
            return
        }

        val now = nowSeconds()
        val tuned = Tuner.tune(channel, urls, now, ladder, refusedTiers.toSet())
        if (tuned == null) {
            Log.w("fs42", "channel ${channel.number} ${channel.name}: nothing on air")
            return
        }

        var playable: Playable = tuned.playable

        // A cached URL that the CDN already refused is worse than no cached URL at all: it will
        // be refused again. Treat it as a miss so the server is asked for a fresh one.
        val tunedId = tuned.stream.id
        if (tunedId != null && tunedId in deadIds && playable is Progressive) {
            playable = NeedsResolving(tunedId)
        }

        if (playable is NeedsResolving) {
            val videoId = playable.videoId
            val remembered = resolvedCache.get(videoId, now)
            if (remembered != null) {
                Log.d("fs42", "resolve hit from cache for $videoId")
                playable = remembered
            } else {
                Log.d("fs42", "resolve miss; asking the server for $videoId")
                val resolved = resolver.resolveDetailed(videoId, now, ladder)
                if (resolved != null) {
                    resolvedCache.put(videoId, resolved.playable, resolved.expiresAtSeconds)
                    deadIds.remove(videoId)
                    playable = resolved.playable
                } else {
                    Log.w(
                        "fs42",
                        "channel ${channel.number} ${channel.name}: server could not resolve " +
                            "$videoId; leaving current picture up",
                    )
                }
            }
        }

        Log.i(
            "fs42",
            "channel ${channel.number} ${channel.name}: clip ${tuned.streamIndex} at " +
                "${tuned.offsetSeconds}s -> ${playable::class.simpleName}",
        )

        // Only a Playable that genuinely reaches the player is a successful tune. A cache miss
        // the server also could not resolve, and anything Unplayable, must leave onAir and the
        // remembered channel as whatever last actually played - otherwise a dead channel becomes
        // what the app reports as on air, and what it resumes on next launch, with no picture
        // and no obvious reason why.
        val playedSuccessfully = when (playable) {
            is Progressive, is Hls -> true
            is NeedsResolving, is Unplayable -> false
        }

        if (requestGeneration != generation.get()) {
            Log.d("fs42", "channel ${channel.number} ${channel.name}: superseded before posting; abandoning")
            return
        }

        // onAir is a field, not a log line, because the navigator's position is not the same
        // thing as what is on screen: SharedPreferences is a consumer of this state, not its
        // owner.
        if (playedSuccessfully && !destroyed && requestGeneration == generation.get()) {
            onAir = tuned
            prefs.edit().putInt(CHANNEL_KEY, channel.number).apply()
        }

        // NeedsResolving here means the server round trip above also failed: play nothing and
        // leave whatever was already on screen rather than blanking it. The destroyed check
        // guards against a tune completing after onDestroy has already released the player -
        // most likely a resolver network call that outlived the activity.
        if (playable !is NeedsResolving && !destroyed) {
            runOnUiThread {
                // The generation is re-checked HERE, not only on the executor. runOnUiThread
                // queues behind whatever the main thread is already doing, so a tune that was
                // current when it posted can run after a newer keypress has already moved the
                // dial - snapping the picture and banner back to a channel the viewer surfed
                // past, and leaving it there if the newer tune then fails to resolve.
                if (requestGeneration != generation.get()) {
                    Log.d("fs42", "channel ${channel.number}: superseded before painting; abandoning")
                    return@runOnUiThread
                }
                if (!destroyed) {
                    player?.play(playable, tuned.offsetSeconds, requestedAtMillis)

                    // Only a genuine success touches the banner, and it reads the current onAir
                    // rather than this tune's outcome directly - a failed tune leaves onAir on
                    // whatever last actually played, exactly as the picture itself does. Bumping
                    // bannerGeneration regardless would replay ChannelOsd's LaunchedEffect and
                    // re-show the banner for a channel that never actually changed.
                    if (playedSuccessfully) {
                        onAir?.let { nowOnAir ->
                            val (channelLine, titleLine) = ChannelLabels.bannerLines(nowOnAir)
                            bannerChannelLine.value = channelLine
                            bannerTitleLine.value = titleLine
                        }
                        bannerGeneration.value += 1
                    }
                }
            }
        }
    }

    /** Guards against a second check while one is already running. */
    private val updateCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Ask the publisher whether there is a newer build, and fetch it if so.
     *
     * On its own thread, never the tuning executor: that executor is what makes a channel change
     * feel instant, and a 66MB download queued in front of a tune would undo the whole point of
     * it. Nothing is shown until the file is on disk, so an unreachable publisher - the normal
     * state of the set in the car - is completely silent.
     */
    private fun checkForUpdate() {
        if (updateReady.value) return
        if (!updateCheckRunning.compareAndSet(false, true)) return
        Thread {
            try {
                if (Updater(this, SERVER).downloadIfNewer(BuildConfig.VERSION_CODE)) {
                    runOnUiThread { if (!destroyed) updateReady.value = true }
                }
            } finally {
                updateCheckRunning.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Check again whenever the viewer comes back to the app.
     *
     * Launch alone was not enough: a television that stays on one channel for days never
     * relaunches, so a change published in the meantime would never be seen. Coming back from
     * the home screen is the natural moment to notice - and it costs one small request, since
     * the manifest is two fields and the apk is only fetched when it is genuinely newer.
     */
    override fun onResume() {
        super.onResume()
        checkForUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        stallHandler.removeCallbacksAndMessages(null)
        recoveryHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        // Both are null'd as well as released, so a tune that outlived the activity finds
        // nothing to touch rather than a released player.
        musicPlayer?.release()
        musicPlayer = null
        player?.release()
        player = null
    }
}
