package com.cliftonia.fs42tv.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.player.Media3Sources
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.TuneController
import java.util.concurrent.Executor

/**
 * The channel guide: the list, the music underneath it, and the rules for opening and closing.
 *
 * Owns its own states and the second ExoPlayer, so the activity's only involvement is focus -
 * which genuinely belongs to it, since focus lives on the activity's ComposeView.
 */
class GuidePicker(private val deps: Deps) {

    class Deps(
        val context: Context,
        val tune: TuneController,
        val director: ScreenDirector,
        val navigator: () -> DialNavigator?,
        /** Shares the tune executor deliberately: guide work must queue behind real tunes. */
        val executor: Executor,
        val runOnUi: (() -> Unit) -> Unit,
        val halted: () -> Boolean,
        /** True between onStop and onStart - guide music must not start over the launcher. */
        val stoppedNow: () -> Boolean,
        val nowSeconds: () -> Long,
        val elapsedMillis: () -> Long,
        /** Grants or blocks the ComposeView's descendant focus, and pulls focus when granting. */
        val focus: (Boolean) -> Unit,
    )

    // Captured once, at the moment the picker opens, rather than derived live from the
    // navigator - the whole point of the picker is that surfing is frozen while it is up, so
    // nothing should move these under it.
    val visible = mutableStateOf(false)
    val rows = mutableStateOf<List<Pair<String, String>>>(emptyList())
    val startIndex = mutableStateOf(0)

    /**
     * Audio-only player for the music under the list.
     *
     * A separate ExoPlayer rather than the main one, because the channel being watched must
     * keep playing underneath - the picker is translucent over it. Audio only, so the cost is
     * one stream of about 128kbps rather than a second video decode. Created on first use and
     * released aggressively: everything about it is best-effort, and atmosphere is never worth
     * an error.
     */
    private var musicPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    /**
     * Opens seeded on the channel actually on air - not [DialNavigator.currentIndex]: a failed
     * tune leaves the navigator pointed somewhere the picture never reached, and the picker
     * must open on what the viewer is looking at, not where the dial silently moved to.
     */
    fun open() {
        val nav = deps.navigator() ?: return
        // Opening the picker is a supersede point. A press landing a moment before this one
        // still has a tune in flight; without the bump that tune passes its own generation
        // check, moves the navigator out from under the rows captured below, and starts
        // playing with its banner drawn behind the open list. Reproduced on device before this
        // line existed, not theorised.
        deps.tune.supersede()

        val onAirNumber = deps.tune.onAir?.channel?.number ?: nav.currentNumber
        val seed = nav.channels.indexOfFirst { it.number == onAirNumber }
            .let { if (it >= 0) it else nav.currentIndex }

        // The list goes up with channel names ONLY, immediately. Working out what is on each
        // of a hundred channels means walking every clip list, and doing that before the first
        // frame of the picker is drawn puts a visible pause between pressing the button and
        // seeing anything - the one moment a guide has to feel instant. The titles arrive a
        // beat later and fill in underneath: structure now, detail when it exists.
        rows.value = nav.channels.map { ChannelLabels.listRow(it) }
        startIndex.value = seed
        visible.value = true
        deps.focus(true)
        fillTitles(nav)
        startMusic(nav)
    }

    /**
     * Deliberately does NOT bump the generation, unlike [open]. Closing happens either from
     * BACK - when nothing is queued, because the activity refuses every channel key while the
     * picker is up - or from [pick], which runs immediately AFTER queueing the tune the viewer
     * just asked for. A bump here would supersede that tune and selecting a channel would
     * quietly do nothing.
     */
    fun close() {
        visible.value = false
        stopMusic()
        deps.focus(false)
    }

    /**
     * BACK on the picker. Distinct from [close] because dismissal is the one close that must
     * also check for an abandoned tune: [open] bumps the generation, which kills any
     * error-recovery retune in flight, and if that recovery was what stood between the viewer
     * and a black screen, the channel behind the list is still black. [pick] keeps calling
     * [close] directly - it just queued a tune of its own, and a recovery bump would supersede
     * it.
     */
    fun dismiss() {
        close()
        deps.director.recoverIfAbandoned()
    }

    /**
     * OK on a row: resolves the row index back to a channel, moves the navigator exactly like
     * surfing does, tunes it, and closes.
     */
    fun pick(index: Int) {
        val nav = deps.navigator()
        val channel = nav?.channels?.getOrNull(index)
        if (nav != null && channel != null) {
            // jumpTo on this thread, like up() and down(): key handling is the navigator's
            // single writer, and the executor only reads it.
            nav.jumpTo(channel.number)

            // Selecting the channel already on air must NOT re-tune. A re-tune tears the
            // player down and restarts the same clip at a freshly computed offset, so the
            // picture visibly jumps for no reason - the viewer asked for the channel they are
            // already watching, and the correct answer is "you have it". The banner is still
            // re-shown, because pressing OK on a channel is a request to be told what it is.
            if (deps.tune.onAir?.channel?.number == channel.number) {
                deps.director.showBanner()
            } else {
                deps.tune.surfTo(channel)
            }
        }
        close()
    }

    /** Released on stop and destroy; see [stopMusic] for why released rather than paused. */
    fun releaseMusic() {
        musicPlayer?.release()
        musicPlayer = null
    }

    /**
     * Work out what is on each channel and fill the rows in behind the already-visible list.
     *
     * Off the UI thread, and discarded if the picker has closed by the time it finishes - a
     * viewer who opened and dismissed the guide in under a second should not have rows quietly
     * rewritten underneath the channel they went back to watching.
     */
    private fun fillTitles(nav: DialNavigator) {
        val channels = nav.channels
        deps.executor.execute {
            val started = deps.elapsedMillis()
            // One instant for the whole dial - see GuideRows. Walking a hundred channels while
            // reading the clock per channel would let the list straddle a programme boundary
            // and show two different moments at once.
            val filled = GuideRows.forChannels(channels, deps.nowSeconds()).toMutableList()
            // The on-air channel's row shows what is ACTUALLY playing. For every other channel
            // the clock's answer is the only one there is, but for this one the truth is in
            // hand, and it is the row the picker opens on - the first thing the viewer reads.
            deps.tune.onAir?.let { onAir ->
                val i = channels.indexOfFirst { it.number == onAir.channel.number }
                if (i >= 0) {
                    filled[i] = ChannelLabels.listRow(channels[i], onAir.stream.title)
                }
            }
            val took = deps.elapsedMillis() - started
            deps.runOnUi {
                if (deps.halted() || !visible.value) return@runOnUi
                Log.d("fs42", "guide titles for ${filled.size} channels in ${took}ms")
                rows.value = filled
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
    private fun startMusic(nav: DialNavigator) {
        val channel = PickerMusic.choose(nav.channels) ?: return
        deps.director.updateProgrammeVolume()
        deps.executor.execute {
            if (deps.halted()) return@execute
            val tuned = deps.tune.resolveForAudio(channel) ?: return@execute
            // Only the audio track is wanted, so the audio URL is handed over as the source
            // and the video URL is dropped entirely - no second decode, no second video fetch.
            val audioOnly = when (val playable = tuned.playable) {
                is Progressive -> playable.audioUrl?.let { Progressive(it, null) }
                is Hls -> playable
                else -> null
            } ?: return@execute
            // Always Media3, whatever plays the video. The guide music is an audio-only
            // stream under a translucent list; it has none of the frame-pacing problem that
            // put mpv on the video path, and giving it a second engine would mean a second set
            // of native libraries loaded to play 128kbps of bossa nova.
            val source = Media3Sources.sourceFor(
                Media3Sources.dataSourceFactory(), audioOnly) ?: return@execute

            deps.runOnUi {
                // The stopped check is what keeps bossa nova off the launcher: onStop releases
                // the player, but a resolve already in flight lands here afterwards and would
                // otherwise build a fresh ExoPlayer and play guide music behind the home
                // screen (the picker deliberately survives HOME, so visible stays true).
                if (deps.halted() || deps.stoppedNow() || !visible.value) return@runOnUi
                val music = musicPlayer
                    ?: androidx.media3.exoplayer.ExoPlayer.Builder(deps.context)
                        .build().also { musicPlayer = it }
                Log.i("fs42", "guide music: ${channel.name}")
                music.setMediaSource(source, (tuned.offsetSeconds * 1000).toLong())
                music.prepare()
                music.playWhenReady = true
            }
        }
    }

    private fun stopMusic() {
        // RELEASE, not stop(). stop() halts playback but keeps the instance, and with it a
        // hardware MediaCodec - a limited resource on this television, held idle alongside the
        // video decoder for as long as the app runs. Frame drops appeared across every channel
        // as soon as this player was introduced, which is what an extra codec instance looks
        // like from the outside. Recreating it on the next open costs a few hundred
        // milliseconds of music, against a picture that stays smooth.
        releaseMusic()
        deps.director.updateProgrammeVolume()
    }
}
