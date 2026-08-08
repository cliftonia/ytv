package com.cliftonia.fs42tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.Unplayable
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
    private val main = Handler(Looper.getMainLooper())

    override val view: View = mpv

    override var onClipEnded: (() -> Unit)? = null
    override var onPlaybackError: ((String) -> Unit)? = null
    override var onFirstFrame: (() -> Unit)? = null
    override var onBuffering: ((Boolean) -> Unit)? = null

    private var requestedAtMillis = 0L

    /** Set once a picture is up, so a load in progress is never reported as a stall. */
    private var hasPicture = false

    /** Guards against reporting the same clip's end twice while the next tune is in flight. */
    private var ended = false

    init {
        mpv.initialize(context.filesDir.path, context.cacheDir.path)
        mpv.events = object : MpvView.Events {
            override fun onFileLoaded() {
                // Only now do end-file events refer to the clip the dial actually asked for.
                // Anything before this belongs to the outgoing file that `loadfile ... replace`
                // displaced, and acting on it re-tunes the channel in a tight loop - six times in
                // fifty milliseconds, measured.
                ended = false
            }

            override fun onShutdown() {
                // Distinct from a playback error on purpose: the URL may have been fine, and the
                // engine itself is now dead. Only a new instance fixes this.
                Log.w("fs42", "mpv core shut down; engine must be rebuilt")
                main.post { onPlaybackError?.invoke(ENGINE_DIED) }
            }

            override fun onFirstFrame() {
                if (hasPicture) return
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
        val url = when (playable) {
            is Progressive ->
                if (playable.audioUrl == null) playable.videoUrl
                // YouTube serves video and audio apart above 360p. mpv's EDL plays them as one
                // stream, which is how the box has always played these same URLs.
                else MpvView.edl(playable.videoUrl, playable.audioUrl)

            is Hls -> playable.url

            is NeedsResolving -> {
                Log.w("fs42", "no cached stream for video id ${playable.videoId}; needs server resolve")
                return
            }

            is Unplayable -> {
                Log.w("fs42", "cannot play: ${playable.reason}")
                return
            }
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

    override fun setVolume(volume: Float) {
        MPVLib.setPropertyInt("volume", (volume * 100).toInt())
    }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        mpv.events = null
        mpv.destroy()
    }
}
