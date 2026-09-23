package com.cliftonia.fs42tv.ui

import android.content.Context
import android.util.Log
import com.cliftonia.fs42tv.player.Media3Sources
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.tune.Tuned
import java.util.concurrent.Executor

/**
 * The music under the guide - and under the "up next" card, which is the same cable-channel
 * idea: listings over bossa nova. One player for both, so the guide opened over a card never
 * plays two tracks at once.
 *
 * Owns the second ExoPlayer. Everything about it is best-effort: atmosphere is never worth an
 * error. Main thread for [play] and [release]; the resolve runs on [Deps.speculativeExecutor].
 */
class GuideMusic(private val deps: Deps) {

    class Deps(
        val context: Context,
        /** A playable for the music channel now - TuneController.resolveForAudio. Blocking. */
        val resolveForAudio: (Channel) -> Tuned?,
        /**
         * The prefetch thread. Speculative work, and never on the tune executor: a resolve is
         * 2.4s on the device, and queued there it sat in front of the tune of the channel the
         * viewer picked a moment after opening the guide.
         */
        val speculativeExecutor: Executor,
        val runOnUi: (() -> Unit) -> Unit,
        val halted: () -> Boolean,
        /** True between onStop and onStart - music must not start over the launcher. */
        val stoppedNow: () -> Boolean,
    )

    /**
     * Audio-only player for the music.
     *
     * A separate ExoPlayer rather than the main one, because the channel being watched must keep
     * playing under the translucent guide. Audio only, so the cost is one stream of about
     * 128kbps rather than a second video decode. Created on first use, released aggressively.
     */
    private var player: androidx.media3.exoplayer.ExoPlayer? = null

    /**
     * Start the music from [channels]' music channel, for as long as [wanted] says so - it is
     * checked again after the resolve, which is seconds later.
     */
    fun play(channels: List<Channel>, wanted: () -> Boolean) {
        val channel = PickerMusic.choose(channels) ?: return
        deps.speculativeExecutor.execute {
            // Checked before the resolve as well as after: music nobody wants any more has no
            // use for a resolve, and the resolve is the expensive part.
            if (deps.halted() || !wanted()) return@execute
            val tuned = deps.resolveForAudio(channel) ?: return@execute
            // Only the audio track is wanted, so the audio URL is handed over as the source
            // and the video URL is dropped entirely - no second decode, no second video fetch.
            val audioOnly = when (val playable = tuned.playable) {
                is Progressive -> playable.audioUrl?.let { Progressive(it, null) }
                is Hls -> playable
                else -> null
            } ?: return@execute
            // Always Media3, whatever plays the video. The music is an audio-only stream; it has
            // none of the frame-pacing problem that put mpv on the video path, and giving it a
            // second engine would mean a second set of native libraries loaded to play 128kbps
            // of bossa nova.
            val source = Media3Sources.sourceFor(
                Media3Sources.dataSourceFactory(), audioOnly) ?: return@execute

            deps.runOnUi {
                // The stopped check is what keeps bossa nova off the launcher: onStop releases
                // the player, but a resolve already in flight lands here afterwards and would
                // otherwise build a fresh ExoPlayer and play music behind the home screen (the
                // picker deliberately survives HOME, so the guide stays "wanted").
                if (deps.halted() || deps.stoppedNow() || !wanted()) return@runOnUi
                val music = player
                    ?: androidx.media3.exoplayer.ExoPlayer.Builder(deps.context)
                        .build().also { player = it }
                // Video off, whatever the source. A progressive clip is already cut down to its
                // audio url above, but an HLS music channel is one muxed A/V stream - and left
                // alone this player would claim a second hardware video decoder for a picture
                // nobody sees, which on this television shows up as frame drops on the channel
                // underneath. Disabling the track type means it is never selected at all.
                music.trackSelectionParameters = music.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                    .build()
                Log.i("fs42", "guide music: ${channel.name}")
                music.setMediaSource(source, (tuned.offsetSeconds * 1000).toLong())
                music.prepare()
                music.playWhenReady = true
            }
        }
    }

    /**
     * RELEASE, not stop(). stop() halts playback but keeps the instance, and with it a hardware
     * MediaCodec - a limited resource on this television, held idle alongside the video decoder
     * for as long as the app runs. Frame drops appeared across every channel as soon as this
     * player was introduced, which is what an extra codec instance looks like from the outside.
     * Recreating it on the next use costs a few hundred milliseconds of music, against a picture
     * that stays smooth.
     */
    fun release() {
        player?.release()
        player = null
    }
}
