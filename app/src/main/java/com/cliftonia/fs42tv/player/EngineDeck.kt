package com.cliftonia.fs42tv.player

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The one place allowed to create or destroy a playback engine.
 *
 * That exclusivity is the point: libmpv's Java binding is a process-global singleton holding
 * ONE mpv handle, whose native `create` is `if (handle != NULL) die("mpv is already
 * initialized")` - and `die` is a log line followed by exit(1). A clean process exit: no
 * signal, no tombstone, no Java exception, just the app quietly vanishing. Every rule in
 * [rebuild] exists because some ordering of these five lines has already killed the process.
 */
class EngineDeck(
    private val context: Context,
    private val engine: PlayerEngine,
    private val modeCount: Int,
    overlay: View,
    /** Re-applied to every fresh engine, so a rebuilt one reports first frames too. */
    private val wire: (ChannelPlayback) -> Unit,
) {

    // @Volatile: swapped on the UI thread, read from the executors when tuning.
    @Volatile var player: ChannelPlayback? = null
        private set

    /** The video surface with the compose overlay above it; becomes the activity's content. */
    val root: FrameLayout = FrameLayout(context)

    init {
        val first = newEngine()
        player = first
        root.addView(first.view, matchParent())
        root.addView(overlay, matchParent())
        wire(first)
    }

    /**
     * Builds a fresh engine and swaps it into the layout, releasing the old one.
     *
     * Only mpv needs this, and only for one reason: a dead URL makes an EDL yield no streams
     * at all, which mpv treats as FATAL and shuts its core down - `idle=yes` does not cover a
     * fatal. Without a rebuild, one 403 blacks out the dial until the app is restarted by hand.
     *
     * THE OLD ENGINE IS TORN DOWN COMPLETELY BEFORE THE NEW ONE IS BUILT. Order is not a style
     * choice here, it is the whole correctness of this method:
     *
     * Building the replacement first killed the process on the FIRST rebuild the app ever
     * attempted - see the class comment for `die`. And had it survived, releasing the old
     * engine afterwards would have destroyed the global handle the new one was already using,
     * so the next `setVolume` or `loadfile` would exit(1) on `die("mpv is not created")`
     * instead.
     *
     * Removing the dead view before releasing it was the third face of the same bug: it is
     * `removeView` that dispatches surfaceDestroyed, and that handler sets `vo=null` and
     * detaches the surface on the GLOBAL handle - which would by then have belonged to the new
     * engine. Release first, and the surface teardown lands on its own core.
     */
    fun rebuild() {
        val dead = player
        dead?.release()
        root.removeView(dead?.view)

        val fresh = newEngine()
        wire(fresh)
        player = fresh
        root.addView(fresh.view, 0, matchParent())
        Log.i("fs42", "engine rebuilt after shutdown")
    }

    fun release() {
        // Null'd as well as released, so work that outlives the activity finds nothing to
        // touch rather than a released player.
        player?.release()
        player = null
    }

    private fun newEngine(): ChannelPlayback = when (engine) {
        PlayerEngine.MPV -> MpvChannelPlayer(context)
        PlayerEngine.MEDIA3 -> ChannelPlayer(
            context, Media3Sources.dataSourceFactory(), canSwitchDisplayMode = modeCount > 1)
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
}
