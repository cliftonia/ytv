package com.cliftonia.fs42tv.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
// `is` is a Kotlin keyword, so mpv's package has to be quoted to be importable.
import `is`.xyz.mpv.BaseMPVView
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
        displayRefreshHz()?.let {
            MPVLib.setOptionString("display-fps-override", it.toString())
            Log.i("fs42", "mpv display-fps-override=$it")
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
        MPVLib.setOptionString("ao", "audiotrack,opensles")

        // --- https --------------------------------------------------------------------------
        // Every URL on this dial is https, and Android has no /etc/ssl/certs for mpv to find.
        // Without a bundle it can only connect by not verifying, which is not a trade worth
        // making on a device fetching signed URLs over someone else's network.
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", caBundlePath())

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
        Log.i("fs42", "mpv idle=${MPVLib.getPropertyString("idle")} " +
            "keep-open=${MPVLib.getPropertyString("keep-open")}")
    }

    override fun observeProperties() {
        MPVLib.addObserver(observer)
        // Only what the dial acts on. The reference implementation observes nineteen properties
        // because it draws a full player UI; each one is a JNI callback on every change, and this
        // app draws its banner from its own clock arithmetic instead.
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    /**
     * Path to a CA bundle on disk, extracting it from assets the first time.
     *
     * mpv's TLS is mbedtls reading a PEM file from a path; it cannot use Android's system trust
     * store, and an asset is not a path. Without this every https URL fails to open with
     * `mbedtls_x509_crt_parse_file ... -15872` and the channel goes straight to a re-tune.
     * mpv-android ships the same bundle for the same reason.
     *
     * Copied every run rather than only when absent: it is 182KB against an app that downloads
     * megabytes of video per minute, and a half-written file from a killed first launch would
     * otherwise poison TLS until someone cleared the app's data.
     */
    private fun caBundlePath(): String {
        val out = java.io.File(context.filesDir, "cacert.pem")
        runCatching {
            context.assets.open("cacert.pem").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { Log.e("fs42", "could not extract cacert.pem: $it") }
        return out.path
    }

    /** The panel's real refresh rate, or null when it cannot be read. */
    private fun displayRefreshHz(): Float? {
        val d = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
                ?.defaultDisplay
        }
        return d?.mode?.refreshRate
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

    companion object {
        /**
         * mpv's EDL syntax for playing separate video and audio files as one stream.
         *
         * YouTube serves them apart above 360p. The box already does exactly this - `edl_url()`
         * in fs42/yt_cache.py - and the byte-length prefixes are what make it safe to embed URLs
         * full of `&`, `;` and `=` without escaping any of it.
         */
        fun edl(videoUrl: String, audioUrl: String): String {
            val v = videoUrl.toByteArray(Charsets.UTF_8).size
            val a = audioUrl.toByteArray(Charsets.UTF_8).size
            return "edl://!no_clip;!track_meta,title=video;%$v%$videoUrl" +
                ";!new_stream;!no_clip;!track_meta,title=audio;%$a%$audioUrl"
        }
    }
}
