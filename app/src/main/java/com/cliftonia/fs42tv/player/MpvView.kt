package com.cliftonia.fs42tv.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
// `is` is a Kotlin keyword, so mpv's package has to be quoted to be importable.
import `is`.xyz.mpv.BaseMPVView
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode

/**
 * libmpv on a SurfaceView, configured for this dial.
 *
 * Here because Media3's frame pacing judders on this television and mpv's does not, measured on
 * the same clips at the same wall-clock offsets after eight Media3-side theories were each ruled
 * out. The option that matters is `video-sync=display-resample`.
 *
 * The settings below come from three places, kept distinguishable on purpose: mpv-android's own
 * MPVView (the reference implementation), the box's `~/.config/mpv/mpv.conf` which plays these
 * exact googlevideo URLs, and this panel's measured refresh rate.
 */
/**
 * Which pacing mode to ask mpv for; see initOptions.
 *
 * A file-level variable rather than a constructor argument because `BaseMPVView.initialize()`
 * calls `initOptions()` itself, so there is no parameter to thread through. Set before the engine
 * is built and read once during init.
 */
var videoSyncMode: String? = null

/**
 * How far to hold the picture back, in milliseconds, to meet audio that arrives late downstream.
 *
 * A file-level variable for the same reason as [videoSyncMode]: `BaseMPVView.initialize()` calls
 * `initOptions()` itself, so there is no constructor parameter to thread through. Unlike the
 * pacing mode this one is ALSO settable while a clip is playing - see
 * [MpvChannelPlayer.setAudioHoldMillis] - because the right value cannot be reasoned to, only
 * heard, and trimming it by ear needs the sound to keep running while you turn the knob.
 *
 * See [AudioSync] for the measurement that put it here.
 */
var audioHoldMillis: Int = 0

class MpvView(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {

    /** What the dial needs to hear about. Set before use; cleared on destroy. */
    interface Events {
        fun onFileLoaded()

        /**
         * mpv terminated and cannot play anything again.
         *
         * Not theoretical: when an EDL's video URL is refused with 403 both segments fail, mpv
         * logs `No video or audio streams selected` as FATAL and shuts the core down - `idle=yes`
         * does not cover a fatal. One dead URL would otherwise black out the dial permanently.
         */
        fun onShutdown()
        fun onFirstFrame()
        fun onEndFile(reason: String)
        fun onBuffering(buffering: Boolean)
    }

    // Same two threads as awaitingFirstFrame, and release() relies on the null being seen.
    @Volatile var events: Events? = null

    /** True between a load and its first presented frame, so mid-clip restarts are not reported. */
    // mpv delivers events on its own native thread while playAt/release run on the UI
    // thread; @Volatile for the same reason every equivalent flag in MpvChannelPlayer has it.
    @Volatile private var awaitingFirstFrame = false

    private val observer = object : MPVLib.EventObserver {
        override fun event(eventId: Int, node: MPVNode) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> events?.onFileLoaded()

                // PLAYBACK_RESTART fires once decoding has produced output and playback is
                // actually running - after a load and after any seek. Guarded so only the first
                // per clip counts as "the picture appeared".
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART ->
                    if (awaitingFirstFrame) {
                        awaitingFirstFrame = false
                        events?.onFirstFrame()
                    }

                // mpv reports the end of a file for a clip finishing AND for a load failing, and
                // the dial's response differs completely: one moves to whatever is on next, the
                // other must drop a dead URL first or it resolves straight back to it. When the
                // reason cannot be read, "eof" is the safer default - re-tuning is harmless,
                // while wrongly declaring an error discards a URL that was never dead.
                MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> events?.onShutdown()

                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    val reason = runCatching { node.toJson() }.getOrDefault("")
                    events?.onEndFile(if (reason.contains("error")) "error" else "eof")
                }
            }
        }

        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) = Unit
        override fun eventProperty(property: String, value: MPVNode) = Unit

        override fun eventProperty(property: String, value: Boolean) {
            // paused-for-cache is mpv's stall: playback stopped because the cache ran dry. It is
            // the direct equivalent of Media3's STATE_BUFFERING, and the only one worth surfacing.
            if (property == "paused-for-cache") events?.onBuffering(value)
        }
    }

    override fun initOptions() {
        // --- the reason mpv is here ---------------------------------------------------------
        // Lock video to the display's real refresh and resample audio to follow, rather than
        // scheduling frames against a media clock and letting the compositor place them.
        // Chosen at runtime rather than fixed, because which is right here is genuinely unsettled
        // and the device is the only thing that can answer.
        //
        // `display-resample` locks video to the panel's real refresh and RESAMPLES THE AUDIO to
        // follow. It is why mpv is in this app at all: it is the only thing that fixed the judder.
        //
        // But `vo=mediacodec_embed` means MediaCodec presents the frames and mpv never touches
        // the pixels - that vo was forced on us because gpu and gpu-next both SIGSEGV in this
        // television's Mali driver. Resampling audio to follow a clock mpv does not fully own is
        // a plausible cause of the audio sliding against the picture, which is what is reported.
        //
        // `audio` is mpv's default: video is timed against the audio clock and CANNOT drift from
        // it, at the cost of the frame pacing that display-resample buys.
        //
        // So both are offered and the setting says which. Judder and drift are different faults
        // with different cures, and guessing between them has now cost several rounds.
        MPVLib.setOptionString("video-sync", FrameCadence.optionFor(videoSyncMode))
        // A separate feature that blends frames. display-resample does not need it and on a
        // 32-bit SoC it is expensive - off unless it is ever measured to help.
        MPVLib.setOptionString("interpolation", "no")

        // Deliberate A/V offset, for a delay that happens BELOW the player and that mpv therefore
        // cannot measure. On this television the sound leaves over Bluetooth SBC to a paired
        // speaker which reports its own buffering as zero, so mpv's `avsync` reads +-6ms while
        // the viewer hears the audio well behind the picture. Set as an option as well as a
        // runtime property so it survives an engine rebuild after a shutdown, which happens
        // often enough that a trim that quietly reset itself would look like the fault returning.
        MPVLib.setOptionString(
            "audio-delay", AudioSync.mpvAudioDelaySeconds(audioHoldMillis).toString())

        // display-resample is only as good as mpv's idea of the refresh rate, and mpv's own
        // detection is unreliable on Android - the reference implementation overrides it for
        // exactly this reason. This panel reports 60.000004Hz, not 60; that difference is the
        // difference between resampling to the right rate and slowly drifting against it.
        val refreshHz = DisplayRefresh.of(context, this)
        if (refreshHz != null) {
            MPVLib.setOptionString("display-fps-override", refreshHz.toString())
            Log.i("fs42", "mpv display-fps-override=$refreshHz")
        } else {
            // No trustworthy refresh rate, so do NOT resample audio against a guess. Syncing
            // video to the audio clock is mpv's default and cannot drift; it gives up the frame
            // pacing that put mpv here in the first place, which is the right way round - judder
            // is irritating, audio out of step with a talking head is unwatchable.
            Log.w("fs42", "no display refresh rate; falling back to video-sync=audio")
            MPVLib.setOptionString("video-sync", "audio")
        }

        // --- video output, from mpv-android's reference implementation -----------------------
        // No GL at all. mediacodec_embed hands decoded frames straight to the SurfaceView and
        // mpv never opens a GL context, which is the point: both `gpu` and `gpu-next` crashed
        // this television outright - SIGSEGV in /vendor/lib/egl/libGLES_mali.mt5879.so, on the
        // app's OWN RenderThread rather than any mpv thread. mpv's EGL use and the Compose
        // overlay drawing above the video could not share this Mali driver.
        //
        // The judder fix survives the change: video-sync=display-resample governs WHEN a frame
        // is released, not who draws it, so mpv still paces to the display's real refresh.
        // What is given up is everything mpv would do to the pixels - scaling, interpolation,
        // colour management - none of which this dial asks for.
        //
        // MEASURED, AND NOT WHAT HAPPENS: `current-vo` reads `gpu` on this television, not
        // `mediacodec_embed`. The reason is in the library, not here - `BaseMPVView` keeps its
        // own `voInUse` field, initialised to "gpu", and `surfaceCreated` writes THAT to the `vo`
        // property once the surface arrives, after `initOptions` has run. The option below is set
        // and then overwritten. `BaseMPVView.setVo(...)` is the only thing that changes both.
        //
        // Left as it is on purpose. Switching to mediacodec_embed for real is a change to the
        // vo that this file records as having SIGSEGV'd in the Mali driver, it belongs to the
        // mid-session shutdown fault rather than to audio sync, and the audio measurement above
        // rules the vo out either way: mpv is in sync under `gpu` too.
        // NOT set here - see useDirectVideoOutput. `BaseMPVView` keeps its own `voInUse` field
        // initialised to "gpu" and writes THAT to the vo property from `surfaceCreated`, after
        // `initOptions` has run, so an option set here is overwritten before a frame is drawn.
        // Every theory that reasoned from this line rather than from `current-vo` was reasoning
        // about a video output that was not running.
        MPVLib.setOptionString("hwdec", "mediacodec")

        // --- audio --------------------------------------------------------------------------
        // opensles FIRST, audiotrack only as a fallback.
        //
        // mpv-android lists audiotrack first, and on this device it aborts the whole process when
        // one clip ends and the next loads:
        //   FORTIFY: pthread_mutex_lock called on a destroyed mutex
        //   Fatal signal 6 (SIGABRT) in tid ... (ao/audiotrack)
        // - the audio output racing its own teardown. A dial rolls a clip over on every channel
        // every few minutes, so that is not an edge case here, it is the normal path.
        MPVLib.setOptionString("ao", "opensles,audiotrack")
        // Hold the audio device open between clips, streaming silence when nothing is playing.
        //
        // Without it mpv closes the output on every loadfile and opens it again for the next
        // one, and the hardware makes that audible: a speaker click on every single channel
        // change. This dial changes channel constantly, so an artefact that a normal player
        // produces once per file happens here every few seconds.
        //
        // The option exists for precisely this - it was added for AV receivers that click or
        // mute while re-syncing - and the cost is a device kept open, which this app wants
        // anyway since it is never idle for long.
        // DELIBERATELY NOT audio-stream-silence.
        //
        // It was set to stop a speaker click on every channel change, which this dial makes
        // constantly - and it worked. But mpv warns about it on every single load:
        //
        //     [ao/opensles] The --audio-stream-silence option is set.
        //                   This will break certain player behavior.
        //
        // That warning was being logged and never read. The option holds the audio device open
        // streaming silence between clips, so the audio clock never stops - and BOTH pacing modes
        // derive video timing from that clock, `audio` directly and `display-resample` through
        // the resampler. Audio sliding against the picture is exactly what a clock that keeps
        // running when nothing is playing would produce.
        //
        // The option exists for AV receivers that mute while re-syncing. A click at a channel
        // change is a second of mild annoyance; a programme whose voices do not match the mouths
        // is unwatchable, so the trade goes the other way.
        // A short grace period before the device is considered ready, so the first moments of a
        // clip are not swallowed while the output is still coming up.
        MPVLib.setOptionString("audio-wait-open", "0.2")

        // --- https --------------------------------------------------------------------------
        // Every URL on this dial is https, and Android has no /etc/ssl/certs for mpv to find.
        // Without a bundle it can only connect by not verifying, which is not a trade worth
        // making on a device fetching signed URLs over someone else's network.
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", CaBundle.extract(context))

        // --- startup, lifted from the box's [googlevideo] profile ---------------------------
        // These are plain mp4 whose shape is already known, so deep probing is a second of black
        // screen bought for nothing.
        // Land on the keyframe rather than decoding forward to the exact frame.
        //
        // Every tune joins a clip at a clock-derived offset, routinely tens of minutes in. With
        // mpv's default precise seeking it reaches that frame by decoding and discarding every
        // frame from the preceding keyframe - measured at 3.77s between opening the decoder and
        // showing a picture, against 0.38s for the proxy to deliver the bytes.
        //
        // Being a second or two off the exact wall-clock position cannot be perceived here: the
        // illusion is that the channel was already running when you arrived.
        MPVLib.setOptionString("hr-seek", "no")

        MPVLib.setOptionString("demuxer-lavf-analyzeduration", "0.1")
        MPVLib.setOptionString("demuxer-lavf-probesize", "524288")
        MPVLib.setOptionString("cache-pause-initial", "no")
        MPVLib.setOptionString("cache-secs", "3")
        MPVLib.setOptionString("demuxer-readahead-secs", "0")
        MPVLib.setOptionString("demuxer-max-bytes", "33554432")
        MPVLib.setOptionString("demuxer-max-back-bytes", "8388608")
        // reconnect: a dropped CDN connection recovers silently rather than ending the clip.
        // multiple_requests: keeps ffmpeg issuing further RANGE requests on one connection.
        // googlevideo throttles an unbounded request to roughly the video's own bitrate and
        // serves bounded ones at line speed - the discovery ChunkedDataSource exists for, and
        // the reason mpv's first frame is slower than Media3's until this is right.
        MPVLib.setOptionString(
            "stream-lavf-o",
            "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2,multiple_requests=1",
        )

        // --- nothing on this dial is interactive --------------------------------------------
        MPVLib.setOptionString("sub-auto", "no")
        MPVLib.setOptionString("osc", "no")
        MPVLib.setOptionString("input-default-bindings", "no")
        MPVLib.setOptionString("input-vo-keyboard", "no")
        // Stay alive with nothing loaded: the dial tunes into this instance repeatedly, and an
        // mpv that shuts down when its file ends would take the app with it.
        MPVLib.setOptionString("idle", "yes")
        // Send warnings and errors to the log observer. Without a msg-level mpv reports almost
        // nothing through the callback, and the reason for a shutdown is exactly what is wanted.
        MPVLib.setOptionString("msg-level", "all=warn")
    }

    override fun postInitOptions() {
        // Re-assert idle AFTER init, as a property this time.
        //
        // Set only as a pre-init option it was accepted - mpv logged `event: idle` on startup -
        // and then mpv still emitted `event: shutdown` the moment a file failed to open, which
        // kills the instance for good: every later tune loads into a dead player and the screen
        // stays black with nothing in the log. A dial tunes into one instance hundreds of times,
        // so surviving a failed load is not optional here.
        MPVLib.setPropertyString("idle", "yes")
        // Do not tear down the video output between files either. Without this each tune
        // reinitialises the whole gpu context, which on this SoC is visible as a longer black
        // gap than the channel change itself needs.
        MPVLib.setPropertyString("keep-open", "no")
        MPVLib.setPropertyString("vid", "auto")
        // Read back rather than assumed: mpv logged `event: idle` at startup and then still shut
        // itself down the moment a file failed to open, so whether this option is actually held
        // is the difference between a dial that survives a 403 and one that dies on the first.
        // Read back, because an option that silently fails to apply is what caused the audio
        // drift this block exists to prevent - and the only way to know is to ask.
        Log.i("fs42", "mpv idle=${MPVLib.getPropertyString("idle")} " +
            "keep-open=${MPVLib.getPropertyString("keep-open")} " +
            "video-sync=${MPVLib.getPropertyString("video-sync")} " +
            "display-fps-override=${MPVLib.getPropertyString("display-fps-override")} " +
            "audio-delay=${MPVLib.getPropertyString("audio-delay")}")
        PlaybackDiagnostics.recordSync(
            MPVLib.getPropertyString("video-sync"),
            MPVLib.getPropertyString("display-fps-override"))
    }

    /**
     * mpv's own log, at warning and above.
     *
     * Registered alongside the event observer and on the same process-global list, so it is
     * removed in [detachObserver] for the same reason.
     */
    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            MpvLog.record(prefix, level, text)
        }
    }

    override fun observeProperties() {
        // Registered against a PROCESS-GLOBAL list - `MPVLib` is an object, and its observer list
        // is static. The base class's `destroy()` does not remove it, so every engine rebuild used
        // to leave one behind, and each holds this view, its context, and therefore the whole
        // activity with the parsed nine-thousand-clip dial hanging off it. See `detach`.
        MPVLib.addObserver(observer)
        // mpv explains every failure it has, immediately before acting on it. Without this the
        // explanation goes only to logcat, which needs an authorised adb connection to a
        // television that does not have one - so a shutdown could only ever be reported as the
        // fact that it happened.
        MPVLib.addLogObserver(logObserver)
        // Only what the dial acts on. The reference implementation observes nineteen properties
        // because it draws a full player UI; each one is a JNI callback on every change, and this
        // app draws its banner from its own clock arithmetic instead.
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    /**
     * Unregister from mpv's global observer list.
     *
     * Must be called before `destroy()`. `BaseMPVView.destroy()` clears properties and tears the
     * core down but never touches the observer list, which is static on `MPVLib` and therefore
     * outlives every instance. Without this each engine rebuild leaks an entire activity graph on
     * a television with 2.34GB of memory, and every future mpv event is dispatched to every dead
     * observer as well as the live one.
     */
    fun detachObserver() {
        runCatching { MPVLib.removeObserver(observer) }
            .onFailure { Log.w("fs42", "could not remove the mpv observer: $it") }
        runCatching { MPVLib.removeLogObserver(logObserver) }
            .onFailure { Log.w("fs42", "could not remove the mpv log observer: $it") }
    }

    /**
     * Start [url] at [startSeconds], the way a channel is joined mid-programme.
     *
     * `start=` is part of the load rather than a seek afterwards: a seek issued before playback
     * has begun is silently dropped, and every channel then opens at 00:00. The Media3 path
     * passes its start position into setMediaSource for the same reason.
     */
    /**
     * Attach a subtitle track to whatever is playing, and show it.
     *
     * Called after the file is loaded rather than passed with it. `sub-add` is mpv's documented
     * way to add a track at runtime, it takes `select` so the viewer does not have to find a
     * track menu this dial has no button for, and unlike a per-file option it can be checked
     * afterwards by reading `sid`.
     */
    /**
     * Ask MediaCodec to present frames straight to this SurfaceView.
     *
     * This is the difference between us and the YouTube app at 4K, and it is why 4K stuttered
     * here on a panel that plays 4K perfectly well elsewhere. Under `vo=gpu` a hardware-decoded
     * frame goes MediaCodec -> AImageReader -> GL texture -> composite -> present, so every frame
     * is copied through the GPU: at 2160p that is 8.3 million pixels a frame on a 32-bit Mali.
     * `mediacodec_embed` is the zero-copy path - the decoder renders onto the surface and nothing
     * touches the pixels in between.
     *
     * It could not be used before because mpv cannot draw subtitles under it. That cost is gone:
     * the app draws its own cues in the Compose overlay now, which was forced by this same vo
     * question from the other side.
     *
     * `setVo` rather than `setOptionString`, because only this updates `voInUse` as well - the
     * field the base class writes back over the property when the surface is created.
     */
    fun useDirectVideoOutput() {
        runCatching { setVo("mediacodec_embed") }
            .onFailure { Log.w("fs42", "could not switch to mediacodec_embed: $it") }
        Log.i("fs42", "vo requested=mediacodec_embed current=${MPVLib.getPropertyString("current-vo")}")
    }

    fun addSubtitle(url: String) {
        runCatching { MPVLib.command("sub-add", url, "select") }
            .onFailure { Log.w("fs42", "sub-add failed: $it") }
    }

    fun playAt(
        url: String,
        startSeconds: Double,
        audioFile: String? = null,
        subFile: String? = null,
    ) {
        awaitingFirstFrame = true
        // Per-FILE options, so they apply to this load and are gone by the next one. `audio-file`
        // set as a property would persist, and the following clip - which has its own audio, or
        // none - would inherit the last one's track.
        //
        // Safe to build by concatenation only because both urls are the proxy's own
        // `http://127.0.0.1:<port>/<id>`, which carries no comma or equals sign. mpv parses this
        // string as a comma-separated key=value list, so a raw googlevideo url with either would
        // be cut in half. If the proxy is ever bypassed this has to be revisited.
        val options = buildString {
            append("start=").append(startSeconds.toInt())
            if (audioFile != null) append(",audio-file=").append(audioFile)
            // The subtitle is NOT set here. It is added with `sub-add` once the file is
            // loaded - see addSubtitle - because a per-file option is applied while mpv is still
            // opening the file, gives no indication of whether it worked, and cannot be checked
            // afterwards. `sub-add ... select` is the documented runtime way, and it fails
            // loudly rather than silently.
        }
        // Newer mpv takes an insertion INDEX before the per-file options; without it the options
        // string is parsed as that index and the command is rejected outright.
        MPVLib.command("loadfile", url, "replace", "0", options)
    }

}
