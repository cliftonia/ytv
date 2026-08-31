package com.cliftonia.fs42tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
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
        /**
         * How close to the published end an "error" ending still counts as the clip finishing.
         *
         * Generous, because the published duration comes from yt-dlp's metadata and what
         * actually plays is the shorter of two separately-muxed tracks - gaps of several
         * seconds are ordinary. A 403 mid-clip is nowhere near the tail, so the two do not
         * overlap in practice.
         */
        private const val END_SLACK_SECONDS = 20.0

        /** Error code meaning "this player is finished" rather than "this clip failed". */
        const val ENGINE_DIED = "MPV_SHUTDOWN"

        /**
         * How long after a load to ask mpv what it thinks the subtitle is doing.
         *
         * Long enough that the clip is genuinely running and a cue is due - every clip on this
         * dial joins partway through, so there is dialogue almost immediately - and short enough
         * that it lands well before the average clip rolls over.
         */
        private const val SUBTITLE_PROBE_MILLIS = 8_000L
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

    /** The caption handed to the last load, so onFileLoaded can report what mpv did with it. */
    @Volatile private var wantedCaption: String? = null

    init {
        mpv.initialize(context.filesDir.path, context.cacheDir.path)
        // After initialize, because the base class writes its own vo over the property when the
        // surface is created and this is the call that changes both.
        mpv.useDirectVideoOutput()
        mpv.events = object : MpvView.Events {
            override fun onFileLoaded() {
                if (released) return
                // Ask mpv what it actually did with the subtitle, rather than assuming the
                // option took. `sid` is the selected track and `sub-text` is what is on screen
                // at this instant; between them they distinguish "never loaded" from "loaded and
                // not selected" from "selected and this clip simply has no cue here yet".
                val caption = wantedCaption
                if (caption != null) {
                    // Added here, once the file is open, because a track added before there is
                    // anything to attach it to is discarded.
                    mpv.addSubtitle(caption)
                    val sid = runCatching { MPVLib.getPropertyString("sid") }.getOrNull()
                    val count = runCatching { MPVLib.getPropertyString("track-list/count") }
                        .getOrNull()
                    // `current-vo`, because the option and the reality differ. MpvView asks for
                    // `vo=mediacodec_embed`; this television answers `current-vo=gpu`, measured.
                    // Every theory that started from the option rather than from this reading was
                    // reasoning about a vo that is not running.
                    val vo = runCatching { MPVLib.getPropertyString("current-vo") }.getOrNull()
                    PlaybackDiagnostics.recordCaptions("MPV sid=$sid tracks=$count vo=$vo")
                    Log.i("fs42", "mpv subtitle: sid=$sid tracks=$count current-vo=$vo")
                    logSubtitleState()
                    // Hidden, because the app draws the cues itself now - see CaptionLine - and
                    // two renderers drawing the same track would stack two copies of every line.
                    //
                    // Do NOT read a later `sub-visibility=no` in the log as the cause of fault 2.
                    // It is set here, deliberately, AFTER mpv has been asked what it thinks; the
                    // measurement below was taken with it still `yes` and nothing on the panel.
                    runCatching { MPVLib.setPropertyBoolean("sub-visibility", false) }
                }
                // mpv measures the audio/video offset itself and publishes it as `avsync`, in
                // seconds. "The audio is delayed" was chased five times without anyone once
                // asking mpv how far - and it has known the whole time.
                //
                // OUTSIDE the caption branch. It used to be inside it, so on a dial where almost
                // no clip carries a caption the probe never ran once, which is why five builds
                // shipped with the logging in place and the number still unobserved.
                //
                // Sampled repeatedly rather than once, because the two candidate faults look
                // identical in a single reading: a fixed offset (a seek landing the two tracks
                // apart, or a latency mpv cannot see) holds still, while drift (a resampler
                // running against the wrong clock) grows. One sample cannot tell them apart.
                probeSync()
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
                // An "error" end in the last seconds of a clip is a ROLL-OVER, not a fault.
                // These files are separately-muxed video and audio, and whichever track is
                // shorter ends the file "in error" moments before the published duration - so
                // routing it to the error path raised the blank, armed the stand-by grace, and
                // whenever the re-tune's resolve outran the four seconds, flashed TECHNICAL
                // DIFFICULTIES at the viewer on every single programme boundary. The dial's
                // correct response to both endings is identical: play whatever is on next.
                // A genuine dead url still reports as an error, because it fails at the START.
                val nearEnd = runCatching {
                    val position = MPVLib.getPropertyDouble("time-pos")
                    val duration = MPVLib.getPropertyDouble("duration")
                    position != null && duration != null && duration > 0 &&
                        duration - position < END_SLACK_SECONDS
                }.getOrDefault(false)
                if (reason == "error" && hasPicture && !nearEnd) {
                    Log.w("fs42", "playback failed: MPV_END_FILE_ERROR")
                    main.post { onPlaybackError?.invoke("MPV_ERROR") }
                } else if (reason == "error" && !hasPicture) {
                    // Never showed a frame: a dead url or an unplayable stream.
                    Log.w("fs42", "playback failed: MPV_END_FILE_ERROR (no picture)")
                    main.post { onPlaybackError?.invoke("MPV_ERROR") }
                } else {
                    if (reason == "error") {
                        Log.i("fs42", "clip ended in error near its tail; treating as roll-over")
                    }
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

    /**
     * Ask mpv where it thinks audio and video are, several times across one clip.
     *
     * `avsync` is mpv's OWN measurement of the offset between the audio it has played and the
     * video it has shown, in seconds and signed: positive means video is ahead of audio, which
     * is what "the audio is delayed" would look like from inside the player.
     *
     * It is worth reading only alongside `current-vo`. mpv is asked for `mediacodec_embed` and
     * has been observed reporting `gpu`, and under `gpu` the frames are drawn by mpv while under
     * `mediacodec_embed` they are presented by MediaCodec - a difference that changes who owns
     * the video clock and therefore what `avsync` even means.
     */
    private fun probeSync() {
        // 3s, 10s, 25s, 50s. The first is after the seek has settled and the cache has filled;
        // the last is far enough out that a resampler running at the wrong rate would have
        // accumulated a visible error even at a few parts per million.
        for (atSeconds in intArrayOf(3, 10, 25, 50)) {
            main.postDelayed({
                if (released) return@postDelayed
                fun prop(name: String) =
                    runCatching { MPVLib.getPropertyString(name) }.getOrNull()
                val off = prop("avsync")
                val delay = prop("audio-delay")
                // Frame drops, alongside the sync numbers, because "4K drops frames like crazy" has two
                            // completely different causes with different cures. `frame-drop-count` is the
                            // DECODER failing to keep up; `vo-delayed-frame-count` is the output missing its
                            // vsync deadline; and a `demuxer-cache-duration` near zero means neither - the
                            // bytes simply are not arriving, which is a fetch problem and not a decode one.
                            val drops = prop("frame-drop-count")
                            val late = prop("vo-delayed-frame-count")
                            val cache = prop("demuxer-cache-duration")
                            val fps = prop("estimated-vf-fps")
                            Log.w("fs42", "FRAMES drops=$drops late=$late cache=${cache}s " +
                                "fps=$fps container=${prop("container-fps")} " +
                                "res=${prop("video-params/w")}x${prop("video-params/h")} " +
                                "codec=${prop("video-codec")} hwdec=${prop("hwdec-current")}")
                            Log.w("fs42", "AVSYNC t=${atSeconds}s avsync=$off audio-delay=$delay " +
                    "vo=${prop("current-vo")} ao=${prop("current-ao")} " +
                    "vsync=${prop("video-sync")} fps=${prop("container-fps")} " +
                    "estimated=${prop("estimated-vf-fps")} " +
                    "display-fps=${prop("display-fps-override")} " +
                    "vf-fps=${prop("estimated-display-fps")} " +
                    "pos=${prop("time-pos")} apts=${prop("audio-pts")} " +
                    "drop=${prop("frame-drop-count")}/${prop("decoder-frame-drop-count")} " +
                    "delayed=${prop("vo-delayed-frame-count")}")
                PlaybackDiagnostics.recordSync("avsync ${off}s", delay)
            }, atSeconds * 1_000L)
        }
    }

    override fun play(playable: Playable, startAtSeconds: Double, requestedAtMillis: Long) {
        // Which tracks go through the proxy is decided in MpvSource, which needs no libmpv and
        // is therefore testable. The reason a miss is logged at all is that a miss which logs
        // nothing is a black screen with a healthy-looking log above it - worded once in
        // unplayableReason and shared with the Media3 engine.
        val load = MpvSource.loadFor(playable, proxy::proxied) ?: run {
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
        wantedCaption = load.subFile
        mpv.playAt(load.url, startAtSeconds, load.audioFile, load.subFile)
    }

    /**
     * Read mpv's own view of the subtitle once the clip is properly under way.
     *
     * Separates the two things `sid=1` cannot: whether mpv DECODED a cue, and whether it DREW
     * one. `sub-text` is the text mpv believes is on screen at this instant and is populated by
     * the subtitle decoder, independently of the renderer. A non-empty `sub-text` beside a
     * screenshot with no caption on it means the track is fine and the drawing is not, which is
     * the whole of fault 2 in one line.
     *
     * Delayed rather than read at file-load, because at load there is no cue yet - the earlier
     * diagnostic read `sid` at a moment when `sub-text` was legitimately empty, which is
     * precisely why it could not tell these two cases apart.
     *
     * What it returned on the TCL, channel 21, 21 Aug 2026, with a screenshot taken at the same
     * moment showing NO caption anywhere on the panel:
     *
     *     sub-text=" \nJULIA." sub-visibility=yes sid=1 sub-pos=100.000000 sub-scale=1.000000
     *     sub-ass-override=scale osd-width=1920 osd-height=1080   (current-vo=gpu)
     *
     * So the track downloaded, decoded, was selected, was visible, was positioned inside a
     * full-size 1920x1080 OSD - and no pixel of it reached the display. Nothing about the
     * subtitle is wrong; the drawing is. That rules out every off-screen theory at once:
     * `sub-pos=100` with `osd-height=1080` cannot be overscanned off the bottom of a panel whose
     * own banner is legible in the same screenshot.
     *
     * The explanation that fits is `hwdec=mediacodec` - the direct one, not `-copy`. MediaCodec
     * presents decoded frames straight to the SurfaceView, so mpv's renderer never composites
     * the picture that is actually shown and anything it draws on top goes nowhere. The one
     * option that would test that is `hwdec=mediacodec-copy`, which MpvView records as a SIGSEGV
     * in this television's Mali driver - so it is not a trade this dial can make, and drawing the
     * cue in Compose instead is the only way it appears at all.
     */
    private fun logSubtitleState() {
        main.postDelayed({
            if (released) return@postDelayed
            fun read(name: String) =
                runCatching { MPVLib.getPropertyString(name) }.getOrNull()
            Log.i("fs42", "mpv subtitle state: sub-text=${read("sub-text")?.take(60)} " +
                "sub-visibility=${read("sub-visibility")} sid=${read("sid")} " +
                "sub-pos=${read("sub-pos")} sub-scale=${read("sub-scale")} " +
                "sub-ass-override=${read("sub-ass-override")} osd-width=${read("osd-width")} " +
                "osd-height=${read("osd-height")}")
        }, SUBTITLE_PROBE_MILLIS)
    }

    /**
     * mpv's `time-pos`, which is seconds from the start of the file.
     *
     * Guarded by [released] and by runCatching for the same reason everything else here is: after
     * `destroy` the native handle is null and libmpv's own accessors respond to that by calling
     * `exit(1)`. This is polled several times a second by the caption overlay, so it is the most
     * likely thing in the class to still be running when the engine is torn down.
     */
    override fun positionSeconds(): Double? {
        if (released) return null
        return runCatching { MPVLib.getPropertyString("time-pos")?.toDoubleOrNull() }.getOrNull()
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

    /**
     * Hold the picture back by [holdMillis] to meet audio that arrives late below the player.
     *
     * Applied to the running clip, not the next one, and that is the point: the correct value
     * depends on what the television is paired with and is not knowable from inside the app - on
     * this set the sink reports its own buffering as zero - so it can only be found by turning it
     * up until the mouths match. That needs the sound to keep playing while the row is pressed.
     *
     * Also written into the options for the next engine build; see [audioHoldMillis].
     */
    fun setAudioHoldMillis(holdMillis: Int) {
        audioHoldMillis = holdMillis
        val seconds = AudioSync.mpvAudioDelaySeconds(holdMillis)
        runCatching { MPVLib.setPropertyDouble("audio-delay", seconds) }
            .onFailure { Log.w("fs42", "could not set audio-delay: $it") }
        Log.i("fs42", "audio hold ${holdMillis}ms -> mpv audio-delay=$seconds")
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
