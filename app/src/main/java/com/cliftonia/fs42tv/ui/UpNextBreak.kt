package com.cliftonia.fs42tv.ui

import android.os.Handler
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.schedule.ScheduleLines
import com.cliftonia.fs42tv.schedule.Timetable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.tune.Tuned

/**
 * The "up next" card's life: up with its music when the schedule says, down exactly when its
 * time is up, and gone the moment anything else happens to the screen.
 *
 * The card is scheduled time, not a fault and not a tune in progress. So it is timed by the
 * wall clock against the schedule's own end, never by the player - there is no player running -
 * and when it ends it hands the channel back to be tuned like any other programme boundary.
 *
 * Main thread only; [handler] is on the main looper.
 */
class UpNextBreak(
    private val handler: Handler,
    private val music: GuideMusic,
    /** The dial, for choosing the music channel. */
    private val channels: () -> List<Channel>,
    private val timetable: Timetable,
    private val stoppedNow: () -> Boolean,
    /** The guide is up: the music is the guide's while it is, and must not be released here. */
    private val guideOpen: () -> Boolean,
    /** The card's time is up: tune [Channel] for what the schedule has next. */
    private val ended: (Channel) -> Unit,
) {

    /** What the overlay draws; null when no card is up. Compose state. */
    val state = mutableStateOf<UpNextState?>(null)

    val showing: Boolean get() = state.value != null

    private var channel: Channel? = null

    private val timeUp = Runnable { endNow() }

    /** The channel whose programme the schedule cuts short, while that cut is pending. */
    private var cutChannel: Channel? = null

    private val cutNow = Runnable {
        val channel = cutChannel ?: return@Runnable
        cutChannel = null
        Log.i("fs42", "the part ends here; the schedule moves on")
        ended(channel)
    }

    /**
     * [tuned] just started playing: if the schedule cuts it short - a programme longer than its
     * part, stopped at the part's end - move on at that instant. Nothing else would: the player
     * plays to the end of the file, hours past the part.
     */
    fun watchCut(tuned: Tuned?) {
        stopCut()
        val at = tuned?.cutAt ?: return
        cutChannel = tuned.channel
        handler.postDelayed(cutNow, maxOf(at * 1000 - System.currentTimeMillis(), MIN_DELAY_MILLIS))
    }

    fun stopCut() {
        handler.removeCallbacks(cutNow)
        cutChannel = null
    }

    /** On destroy: no timer may fire into a dead activity's shut-down executors. */
    fun release() {
        cancel()
        stopCut()
    }

    /** Put [tuned]'s card up until its scheduled end. */
    fun show(tuned: Tuned) {
        val card = tuned.card ?: return
        handler.removeCallbacks(timeUp)
        channel = tuned.channel
        state.value = UpNextState(
            channelLine = ChannelLabels.bannerLines(tuned).first,
            time = ScheduleLines.clock(card.nextAt, timetable.zone(), timetable.use24Hour()),
            title = tuned.stream.title.trim(),
        )
        // Against the wall clock in milliseconds, so the card ends on the boundary itself rather
        // than up to a second late. Floored, so a clock pinned for measurement (MainActivity's
        // fs42.now), which keeps answering "card", re-checks once a second rather than spinning.
        val delay = maxOf(card.until * 1000 - System.currentTimeMillis(), MIN_DELAY_MILLIS)
        Log.i("fs42", "up next card on ${tuned.channel.number} for ${delay / 1000}s")
        handler.postDelayed(timeUp, delay)
        resumeMusic()
    }

    /**
     * Start the card's music if a card is up and nothing else owns the music - after the card
     * goes up, after the guide closes over it, and when the app comes back into view.
     */
    fun resumeMusic() {
        if (!showing || guideOpen() || stoppedNow()) return
        music.play(channels()) { showing && !guideOpen() && !stoppedNow() }
    }

    /** Take the card down without tuning - something else is taking over the screen. */
    fun cancel() {
        handler.removeCallbacks(timeUp)
        if (!showing) return
        state.value = null
        channel = null
        if (!guideOpen()) music.release()
    }

    /** End the card now and hand its channel back to be tuned, as its time running out does. */
    fun endNow() {
        val onCard = channel ?: return
        cancel()
        ended(onCard)
    }

    private companion object {
        const val MIN_DELAY_MILLIS = 1_000L
    }
}
