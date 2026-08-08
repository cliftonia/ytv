package com.cliftonia.fs42tv.player

import android.content.Context
import android.util.AttributeSet
// `is` is a Kotlin keyword, so mpv's package has to be quoted to be importable.
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib

/**
 * libmpv on a SurfaceView, configured for this dial.
 *
 * Here as an experiment, not a replacement. The judder on this television has survived every
 * ExoPlayer-side change tried: the content's frame rate, the panel, the audio clock, the merged
 * video+audio sources, a fresh player per tune, the frame-rate override and the deep seek were
 * each measured and each ruled out. mpv is worth trying because its frame timing is a different
 * architecture rather than a different tuning of the same one.
 *
 * The options below come from two places: the box's own `~/.config/mpv/mpv.conf`, which plays
 * these exact googlevideo URLs, and `video-sync`, which is the one thing mpv can do that Media3
 * cannot.
 */
class MpvView(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {

    override fun initOptions() {
        // Lock video to the display's real refresh rather than to a media clock, resampling audio
        // to keep sync. This is the whole reason for trying mpv: ExoPlayer schedules frames
        // against a media clock and lets the compositor land them where they fall, which on a
        // 60Hz-only panel showing 24 and 25fps content is exactly where the judder lives.
        MPVLib.setOptionString("video-sync", "display-resample")
        MPVLib.setOptionString("interpolation", "no")

        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        // Copy back rather than pure passthrough: the surface is shared with a Compose overlay,
        // and mediacodec (direct) hands the decoder the window, which fights that.
        MPVLib.setOptionString("hwdec", "mediacodec-copy")
        MPVLib.setOptionString("profile", "fast")

        // Startup, lifted from the box's [googlevideo] profile. These files are plain mp4 whose
        // shape is already known, so deep probing is a second of black screen bought for nothing.
        MPVLib.setOptionString("demuxer-lavf-analyzeduration", "0.1")
        MPVLib.setOptionString("demuxer-lavf-probesize", "524288")
        MPVLib.setOptionString("cache-pause-initial", "no")
        MPVLib.setOptionString("cache-secs", "3")
        MPVLib.setOptionString("demuxer-readahead-secs", "0")
        MPVLib.setOptionString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2")

        // Nothing on this dial is interactive, and none of it has subtitles worth drawing.
        MPVLib.setOptionString("sub-auto", "no")
        MPVLib.setOptionString("osc", "no")
        MPVLib.setOptionString("input-default-bindings", "no")
    }

    override fun postInitOptions() = Unit

    override fun observeProperties() = Unit

    /**
     * Start [url] at [startSeconds], the way a channel is joined mid-programme.
     *
     * `start=` is part of the load rather than a seek afterwards, for the same reason Media3's
     * start position is: a seek issued before playback has begun is silently dropped, and every
     * channel then opens at 00:00.
     */
    fun playAt(url: String, startSeconds: Double) {
        // Newer mpv takes an insertion INDEX before the per-file options; without it the
        // options string is parsed as that index and the whole command is rejected.
        MPVLib.command("loadfile", url, "replace", "0", "start=${startSeconds.toInt()}")
    }

    companion object {
        /**
         * mpv's EDL syntax for playing separate video and audio files as one stream.
         *
         * YouTube serves them apart above 360p. The box already does exactly this - see
         * `edl_url()` in fs42/yt_cache.py - and the byte-length prefixes are what make it safe
         * to embed URLs full of `&`, `;` and `=` without escaping.
         */
        fun edl(videoUrl: String, audioUrl: String): String {
            val v = videoUrl.toByteArray(Charsets.UTF_8).size
            val a = audioUrl.toByteArray(Charsets.UTF_8).size
            return "edl://!no_clip;!track_meta,title=video;%$v%$videoUrl" +
                ";!new_stream;!no_clip;!track_meta,title=audio;%$a%$audioUrl"
        }
    }
}
