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

    var events: Events? = null

    /** True between a load and its first presented frame, so mid-clip restarts are not reported. */
    private var awaitingFirstFrame = false

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
        MPVLib.setOptionString("video-sync", "display-resample")
        // A separate feature that blends frames. display-resample does not need it and on a
        // 32-bit SoC it is expensive - off unless it is ever measured to help.
        MPVLib.setOptionString("interpolation", "no")

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
        MPVLib.setOptionString("vo", "mediacodec_embed")
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
        MPVLib.setOptionString("audio-stream-silence", "yes")
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
            "display-fps-override=${MPVLib.getPropertyString("display-fps-override")}")
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
    fun playAt(url: String, startSeconds: Double) {
        awaitingFirstFrame = true
        // Newer mpv takes an insertion INDEX before the per-file options; without it the options
        // string is parsed as that index and the command is rejected outright.
        MPVLib.command("loadfile", url, "replace", "0", "start=${startSeconds.toInt()}")
    }

}
