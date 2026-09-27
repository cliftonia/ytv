package com.cliftonia.fs42tv.ui

import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import com.cliftonia.fs42tv.player.MpvChannelPlayer

/**
 * [ScreenDirector]'s [RecoveryWatch], wired to the director's state and the activity's deps.
 *
 * Out of the director because the director is at the source-length ceiling and this is pure
 * wiring: the rules live in [RecoveryWatch], where they are tested. Its timers run on
 * [ScreenDirector.Deps.recoveryHandler] but cancel only their own runnables: the dial loader's
 * retry shares that handler, and a blanket clear would take the retry with it.
 */
internal fun ScreenDirector.Deps.recoveryWatch(
    tuning: State<Boolean>,
    standByReason: MutableState<String>,
): RecoveryWatch {
    // Where the viewer wants to be, not what last painted: a tune that never painted leaves
    // onAir on the channel before it.
    val retuneWanted = { reason: String ->
        (fallbackChannel() ?: tune().onAir?.channel)?.let {
            Log.i("fs42", "re-tuning ${it.number} ${it.name}: $reason")
            tune().tune(it)
        }
        Unit
    }
    return RecoveryWatch(
        schedule = cancellable(recoveryHandler),
        halted = halted,
        stillTuning = { tuning.value },
        deferred = { overlayOpen() || stoppedNow() },
        cardUp = { standByReason.value.isNotEmpty() },
        showCard = { reason ->
            // A silent direct Pluto stream gets a fresh session for whatever tunes next, without
            // counting toward the legacy fallback - see PlutoRoute.noPicture. A no-op otherwise.
            // onAir is the silent tune: it is committed when the tune paints, before any frame.
            if (reason == RecoveryWatch.NO_PICTURE) extras.plutoNoPicture(tune().onAir?.playable)
            standByReason.value = reason
        },
        retune = retuneWanted,
        retuneAfterError = { reason -> tune().retuneCurrent(reason) },
        // Only mpv: Media3 keeps no per-process load bookkeeping that a rebuild would clear.
        rebuildable = { player() is MpvChannelPlayer },
        rebuildAndRetune = { reason ->
            Log.w("fs42", "watchdog: $reason")
            rebuildEngine()
            // A fresh Pluto session too, so the retune into the new engine rules out the token.
            extras.plutoNoPicture(tune().onAir?.playable)
            retuneWanted(reason)
        },
        nowMillis = android.os.SystemClock::elapsedRealtime,
    )
}
