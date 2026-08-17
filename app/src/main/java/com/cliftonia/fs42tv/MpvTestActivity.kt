package com.cliftonia.fs42tv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.util.Log
import android.widget.FrameLayout
import android.view.ViewGroup
import com.cliftonia.fs42tv.player.MpvView
import com.cliftonia.fs42tv.resolver.DeviceResolver
import java.util.concurrent.Executors

/**
 * Plays one clip through libmpv, at an offset, and nothing else.
 *
 * The whole point is to answer a single question that a year of ExoPlayer tuning could not:
 * does THIS content, at a deep wall-clock offset, on THIS panel, play smoothly under a different
 * frame-timing architecture? Separate from MainActivity deliberately - the app works, and an
 * experiment that can break it is an experiment that will not get run.
 *
 * Launch:
 *   adb shell am start -n com.cliftonia.fs42tv/.MpvTestActivity \
 *       --es v <videoId> --ei offset <seconds>
 */
class MpvTestActivity : Activity() {

    private var view: MpvView? = null
    private val io = Executors.newSingleThreadExecutor()

    /** Its own instance, so the harness stays runnable without MainActivity having started. */
    private val resolver = DeviceResolver()

    /**
     * The clips this harness can switch between, and where it is in that list.
     *
     * Switching on ONE mpv instance is the point. The fault being chased is not "does a clip
     * play" - it is that under Media3 roughly two tunes in five came back with the picture
     * running fast then slow, and stayed that way for the whole clip. A single successful
     * playback says nothing about that; only repeated switching does.
     */
    private var clips: List<String> = emptyList()
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mpv = MpvView(this)
        view = mpv
        setContentView(FrameLayout(this).apply {
            addView(mpv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        mpv.initialize(filesDir.path, cacheDir.path)

        clips = (intent.getStringExtra("ids") ?: intent.getStringExtra("v") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (clips.isEmpty()) {
            Log.e("fs42mpv", "no ids: pass --es ids <id,id,id>")
            return
        }
        val offset = intent.getIntExtra("offset", 0).toDouble()
        load(clips[0], offset)
    }

    /** Channel up/down switches clip, exactly as the dial does. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> 1
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> -1
            else -> return super.onKeyDown(keyCode, event)
        }
        index = ((index + step) % clips.size + clips.size) % clips.size
        // A different offset every switch, because how far a target sits from its keyframe is
        // one of the things that varied per tune under Media3.
        load(clips[index], (60 + (index * 227) % 1500).toDouble())
        return true
    }

    private fun load(videoId: String, offset: Double) {

        // Resolve off the main thread: this is the same resolver the app uses, and extracting
        // takes seconds.
        io.execute {
            try {
                val resolved = resolver.resolveDetailed(
                    videoId, System.currentTimeMillis() / 1000, listOf("hd", "sd", "uhd"))
                if (resolved == null) {
                    Log.e("fs42mpv", "no playable tier for $videoId")
                    return@execute
                }
                val video = resolved.playable.videoUrl
                val audio = resolved.playable.audioUrl.orEmpty()
                val url = if (audio.isEmpty()) video else MpvView.edl(video, audio)
                Log.i("fs42mpv", "playing $videoId at ${offset.toInt()}s, " +
                    "audio=${if (audio.isEmpty()) "muxed" else "separate"}")
                runOnUiThread { view?.playAt(url, offset) }
            } catch (e: Exception) {
                Log.e("fs42mpv", "resolve failed: $e")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        view?.destroy()
        view = null
    }
}
