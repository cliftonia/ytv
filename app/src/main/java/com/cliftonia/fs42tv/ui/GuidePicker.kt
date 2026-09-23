package com.cliftonia.fs42tv.ui

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.schedule.ScheduleLines
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.TuneController
import java.util.concurrent.Executor

/**
 * The channel guide: the list, the music underneath it, and the rules for opening and closing.
 *
 * Owns its own states; the music is [GuideMusic]'s, shared with the "up next" card. The
 * activity's only involvement is focus - which genuinely belongs to it, since focus lives on the
 * activity's ComposeView.
 */
class GuidePicker(private val deps: Deps) {

    class Deps(
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
        /** Pluto's what-is-on, for the rows the viewer is actually looking at. */
        val extras: ScreenExtras,
        /** The music under the list - shared with the card, see [GuideMusic]. */
        val music: GuideMusic,
    )

    // Captured once, at the moment the picker opens, rather than derived live from the
    // navigator - the whole point of the picker is that surfing is frozen while it is up, so
    // nothing should move these under it.
    val visible = mutableStateOf(false)
    val rows = mutableStateOf<List<Pair<String, String>>>(emptyList())
    val startIndex = mutableStateOf(0)

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
        // The guide covers the blank, so the snow stops animating behind it.
        deps.director.syncCovered()
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
        // The guide opened over an "up next" card took the music over; hand it back.
        deps.director.resumeBreakMusic()
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

    /**
     * The highlight has come to rest on [index]: fill in Pluto's NOW and NEXT for the rows on
     * screen around it.
     *
     * Only those rows, and only once the highlight stops - the picker debounces - so holding DOWN
     * through the dial asks for nothing until it lets go. A row whose answer arrives after the
     * guide has closed, or after the list was rebuilt under it, is dropped.
     */
    fun settled(index: Int) {
        val channels = deps.navigator()?.channels ?: return
        for (i in (index - SETTLED_ROWS_ABOVE)..(index + SETTLED_ROWS_BELOW)) {
            val channel = channels.getOrNull(i) ?: continue
            val cached = deps.extras.guideRow(channel) { line -> setRow(i, channel, line) }
            if (cached != null) setRow(i, channel, cached)
        }
    }

    private fun setRow(index: Int, channel: com.cliftonia.fs42tv.sync.Channel, line: String) {
        if (!visible.value) return
        val current = rows.value
        val row = ChannelLabels.listRow(channel, line)
        if (current.getOrNull(index) == null || current[index] == row) return
        rows.value = current.toMutableList().also { it[index] = row }
    }

    /** Released on stop and destroy; see [GuideMusic.release] for why released, not paused. */
    fun releaseMusic() = deps.music.release()

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
            val now = deps.nowSeconds()
            val timetable = deps.extras.timetable
            val filled = GuideRows.forChannels(channels, now, timetable).toMutableList()
            // The on-air channel's row shows what is ACTUALLY playing. For every other channel
            // the clock's answer is the only one there is, but for this one the truth is in
            // hand, and it is the row the picker opens on - the first thing the viewer reads.
            // On the half-hour schedule the row keeps its times, naming the clip on air; a card
            // has no clip on air, so the schedule's own "up next" line stands.
            deps.tune.onAir?.takeIf { it.card == null }?.let { onAir ->
                val i = channels.indexOfFirst { it.number == onAir.channel.number }
                if (i >= 0) {
                    val line = ScheduleLines.guideRow(channels[i], timetable, now, onAir.streamIndex)
                        ?: onAir.stream.title
                    filled[i] = ChannelLabels.listRow(channels[i], line)
                }
            }
            val took = deps.elapsedMillis() - started
            deps.runOnUi {
                if (deps.halted() || !visible.value) return@runOnUi
                Log.d("fs42", "guide titles for ${filled.size} channels in ${took}ms")
                // Pluto lines already in hand go in with the rest - cache only, never a request
                // per channel. The rows around the highlight ask for theirs in [settled].
                channels.forEachIndexed { i, channel ->
                    deps.extras.guideRow(channel, null)?.let {
                        filled[i] = ChannelLabels.listRow(channel, it)
                    }
                }
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
        if (PickerMusic.choose(nav.channels) == null) return
        deps.director.updateProgrammeVolume()
        deps.music.play(nav.channels) { visible.value }
    }

    private fun stopMusic() {
        deps.music.release()
        deps.director.updateProgrammeVolume()
    }

    private companion object {
        /** About half a screen of rows either side of the highlight, which sits mid-list. */
        const val SETTLED_ROWS_ABOVE = 4
        const val SETTLED_ROWS_BELOW = 4
    }
}
