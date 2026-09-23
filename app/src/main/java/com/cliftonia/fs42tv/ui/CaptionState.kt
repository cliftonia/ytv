package com.cliftonia.fs42tv.ui

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.VttCues
import java.util.concurrent.Executor

/**
 * The captions the overlay draws, the viewer's on/off for them, and the fetch that fills them.
 *
 * Out of [ScreenDirector] so the director keeps to when things appear; what the captions say and
 * where they come from is this unit's. Main thread only, except [on], which is read wherever a
 * clip is painted.
 */
class CaptionState(
    executor: Executor,
    runOnUi: (() -> Unit) -> Unit,
    private val generationNow: () -> Int,
    halted: () -> Boolean,
    /** The id of the clip on air, when it is one. */
    private val onAirId: () -> String?,
    /** The cached resolve for a clip, so the toggle can find the current track. */
    private val recallResolved: (String) -> Progressive?,
    private val persistOn: (Boolean) -> Unit,
) {

    /**
     * The cues of the clip currently playing, as the overlay draws them.
     *
     * The app parses and draws subtitles itself rather than handing the track to the player -
     * see [CaptionLine] for why. Written only on the UI thread: cleared where the clip starts,
     * filled by the loader once the file has come down.
     */
    val cues = mutableStateOf<List<VttCues.Cue>>(emptyList())

    /**
     * Whether the viewer wants English subtitles.
     *
     * Off by default. Most of the dial is in English and captions on a channel nobody needed
     * them for is a worse default than absence. `@Volatile` because the toggle is flipped on
     * the UI thread and read wherever a clip is painted.
     */
    @Volatile var on: Boolean = false

    private val loader = CaptionLoader(
        executor = executor,
        runOnUi = runOnUi,
        generationNow = generationNow,
        halted = halted,
        show = { cues.value = it },
    )

    /**
     * A clip is starting: clear the last one's cues, and fetch this one's if wanted. Cleared on
     * the same thread that starts the clip, so the outgoing programme's dialogue cannot be left
     * sitting over the incoming one.
     */
    fun clipStarting(playable: Playable, generation: Int) {
        clear()
        if (on) loader.load(playable, generation)
    }

    /** Nothing to caption - a channel change, a card. */
    fun clear() {
        cues.value = emptyList()
    }

    /**
     * The captions flag, applied to the clip already playing.
     *
     * Every resolve carries its caption url whether or not captions are on, so turning them on
     * is a fetch of the current clip's track out of the cache - no re-resolve. Off clears the
     * overlay immediately.
     */
    fun toggle() {
        on = !on
        persistOn(on)
        if (on) loadForCurrentClip() else clear()
        Log.i("fs42", "captions ${if (on) "on" else "off"}")
    }

    private fun loadForCurrentClip() {
        val id = onAirId() ?: run {
            Log.i("fs42", "captions: nothing on air to caption")
            return
        }
        val playable = recallResolved(id) ?: run {
            // Only reachable if the clip's urls expired while it was still playing, which the
            // tune path handles by re-resolving anyway.
            Log.i("fs42", "captions: $id is not in the resolved cache")
            PlaybackDiagnostics.recordCaptions("NOT CACHED")
            return
        }
        loader.load(playable, generationNow())
    }
}
