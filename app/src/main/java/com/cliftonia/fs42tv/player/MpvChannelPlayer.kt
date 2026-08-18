package com.cliftonia.fs42tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.unplayableReason
import `is`.xyz.mpv.MPVLib

/**
 * The dial driven by libmpv instead of Media3.
 *
 * Here because Media3's frame pacing judders on this television and mpv's does not - measured on
 * the same clips, the same wall-clock offsets and the same panel, after eight separate attempts
 * to fix it on the Media3 side each failed. The single option that matters is
 * `video-sync=display-resample`, set in [MpvView].
 *
 * Everything else in this class exists to make mpv behave the way the rest of the app already
 * expects: report a first frame, report the end of a clip, report a failure rather than sitting
 * black, and mute on demand.
 */
class MpvChannelPlayer(context: Context) : ChannelPlayback {

    companion object {
        /** Error code meaning "this player is finished" rather than "this clip failed". */
        const val ENGINE_DIED = "MPV_SHUTDOWN"
    }


    private val mpv = MpvView(context)

    /**
     * Fetches googlevideo in bounded windows on mpv's behalf.
     *
     * mpv asks ffmpeg for a whole file and googlevideo answers an open-ended request at roughly
     * the video's own bitrate - 3.61 Mbps measured, against 2.2 Mbps of content. That margin is
     * why mpv took 7-10s to a picture where Media3 took 1.5s. Media3 is fast because
     * ChunkedDataSource makes every read bounded; this gives mpv the same thing from outside.
     */
    private val proxy = ChunkedProxy()
    private val main = Handler(Looper.getMainLooper())

    override val view: View = mpv

    override var onClipEnded: (() -> Unit)? = null
    override var onPlaybackError: ((String) -> Unit)? = null
    override var onFirstFrame: (() -> Unit)? = null
    override var onBuffering: ((Boolean) -> Unit)? = null

    private var requestedAtMillis = 0L

    /**
     * Set once a picture is up, so a load in progress is never reported as a stall.
     *
     * `@Volatile` because mpv delivers events on its own native thread while `play` and `stop` are
     * called from the UI thread. Without it neither side is guaranteed to see the other's write.
     */
    @Volatile private var hasPicture = false

    /**
     * Guards against reporting the same clip's end twice while the next tune is in flight.
     *
     * `@Volatile` for the same reason, and it matters more here: a stale read means an end-file
     * event for the OUTGOING clip is acted on, which re-tunes the channel while a load is already
     * in flight - the tight loop measured at six re-tunes in fifty milliseconds. A plain boolean
     * closed the window that was reproduced but not the race underneath it.
     */
    @Volatile private var ended = false

    /**
     * True once [release] has run. Nothing may touch the mpv core afterwards.
     *
     * libmpv's binding is a process-global singleton: after `destroy` the handle is null, and its
     * native property setters respond to a null handle by logging and calling `exit(1)`. So a late
     * event arriving from mpv's own thread - one already past the `events` null-check when release
     * began - could take the whole process down, or trigger a SECOND engine rebuild against a core
     * that is already gone.
     */
    @Volatile private var released = false

    init {
        mpv.initialize(context.filesDir.path, context.cacheDir.path)
        mpv.events = object : MpvView.Events {
            override fun onFileLoaded() {
                if (released) return
                // Only now do end-file events refer to the clip the dial actually asked for.
                // Anything before this belongs to the outgoing file that `loadfile ... replace`
                // displaced, and acting on it re-tunes the channel in a tight loop - six times in
                // fifty milliseconds, measured.
                ended = false
            }

            override fun onShutdown() {
                if (released) return
                // Distinct from a playback error on purpose: the URL may have been fine, and the
                // engine itself is now dead. Only a new instance fixes this.
                // Report WHAT mpv said, not merely that it died. "MPV_SHUTDOWN" on a stand-by
                // card tells nobody anything; mpv logged the actual reason a moment earlier.
                val reason = MpvLog.lastReason()
                Log.w("fs42", "mpv core shut down; engine must be rebuilt. reason: $reason")
                val code = if (reason.isNullOrEmpty()) ENGINE_DIED else "$ENGINE_DIED: $reason"
                main.post { onPlaybackError?.invoke(code) }
            }

            override fun onFirstFrame() {
                if (released || hasPicture) return
                hasPicture = true
                val requested = requestedAtMillis
                if (requested > 0) {
                    Log.i("fs42", "first frame ${SystemClock.elapsedRealtime() - requested} ms")
                    requestedAtMillis = 0L
                }
                main.post { this@MpvChannelPlayer.onFirstFrame?.invoke() }
            }

            override fun onEndFile(reason: String) {
                // mpv reports the end of a file for BOTH a clip finishing and a load failing, and
                // the dial's response differs completely: one moves to whatever is on next, the
                // other must drop a dead URL first or it will resolve straight back to it.
                if (ended) return
                ended = true
                if (reason == "error") {
                    Log.w("fs42", "playback failed: MPV_END_FILE_ERROR")
                    main.post { onPlaybackError?.invoke("MPV_ERROR") }
                } else {
                    main.post { onClipEnded?.invoke() }
                }
            }

            override fun onBuffering(buffering: Boolean) {
                // Before the first picture this is loading, not a stall - the same line the
                // Media3 path draws, and the reason a stand-by card does not cover every tune.
                if (!hasPicture) return
                main.post { this@MpvChannelPlayer.onBuffering?.invoke(buffering) }
            }
        }
    }

    override fun play(playable: Playable, startAtSeconds: Double, requestedAtMillis: Long) {
        // Which tracks go through the proxy is decided in MpvSource, which needs no libmpv and
        // is therefore testable. The reason a miss is logged at all is that a miss which logs
        // nothing is a black screen with a healthy-looking log above it - worded once in
        // unplayableReason and shared with the Media3 engine.
        val url = MpvSource.urlFor(playable, proxy::proxied) ?: run {
            Log.w("fs42", unplayableReason(playable).orEmpty())
            return
        }
        hasPicture = false
        // Stays TRUE across the load. `loadfile ... replace` makes mpv end the outgoing file,
        // and that end-file is indistinguishable from the new clip finishing - clearing the guard
        // here meant every channel change was immediately read as "clip ended" and re-tuned, over
        // and over. onFileLoaded clears it once the new file is really the current one.
        ended = true
        this.requestedAtMillis = requestedAtMillis
        mpv.playAt(url, startAtSeconds)
    }

    override fun stop() {
        // Deliberately NOT mpv's `stop` command.
        //
        // `stop` ends the instance: mpv emits end-file and then `event: shutdown`, even with
        // idle=yes set both as an option and as a property. After that every later tune loads
        // into a dead player and the screen stays black for good - which is exactly what one
        // channel change did.
        //
        // Nothing is lost by leaving it out. `loadfile ... replace` replaces whatever is playing,
        // and the gap between the two is already covered: the app blanks and mutes across every
        // channel change, with TuningBlank drawn over the video surface by the Compose overlay.
        // Muting here is the part that actually matters, so the outgoing channel's audio does not
        // play under an incoming channel's banner.
        ended = true
        setVolume(0f)
    }

    override fun setPaused(paused: Boolean) {
        // mpv's own pause property rather than stopping: the demuxer cache and the decoded
        // frames survive, so coming back is instant instead of paying the seek again.
        MPVLib.setPropertyBoolean("pause", paused)
    }

    override fun setVolume(volume: Float) {
        MPVLib.setPropertyInt("volume", (volume * 100).toInt())
    }

    override fun release() {
        // Order matters. `released` first, so any event already in flight on mpv's thread finds
        // the door shut; then the callbacks are dropped so nothing can reach the activity even if
        // it slips past; then the queue is cleared; and only then is the core destroyed.
        //
        // Clearing the callbacks is the part that was missing. `events = null` cannot stop a
        // callback that is already past its own null-check, and that callback's `main.post` lands
        // AFTER the queue was drained - so a dying engine could ask for a second rebuild, against
        // a core the first rebuild had already replaced.
        released = true
        onPlaybackError = null
        onClipEnded = null
        onFirstFrame = null
        onBuffering = null
        proxy.release()
        main.removeCallbacksAndMessages(null)
        mpv.events = null
        // Before destroy: the observer lives on a static list that outlives the core, so leaving
        // it registered leaks this whole view - and the activity behind it - once per rebuild.
        mpv.detachObserver()
        mpv.destroy()
    }
}
