package com.cliftonia.fs42tv.player

import android.view.View
import com.cliftonia.fs42tv.resolver.Playable

/**
 * What the dial needs from a video player, so the engine underneath can be swapped.
 *
 * Two implementations exist deliberately. Media3 is what the app shipped on and what every
 * other behaviour was built and measured against; libmpv is here because Media3's frame pacing
 * judders on this television and eight separate attempts to fix it on the Media3 side each
 * failed, while mpv played the same clips at the same offsets on the same panel without it.
 *
 * The engine stays behind this interface rather than being replaced outright: mpv is unproven
 * against everything else the app does - recovery from dead URLs, clip roll-over, the guide, the
 * stand-by card - and a single flag that puts Media3 back is worth far more than the code it
 * costs.
 *
 * Deliberately narrow. Only eight things in MainActivity ever touched the player, and widening
 * this to expose an engine-specific handle would put the switch back where it started.
 */
interface ChannelPlayback {

    /** The view showing the picture. Added to the layout by whoever owns it; never re-parented. */
    val view: View

    /** The clip ran out. The dial re-tunes, and the clock picks whatever is on now. */
    var onClipEnded: (() -> Unit)?

    /** Playback failed outright, with an engine-specific code for the log and the stand-by card. */
    var onPlaybackError: ((String) -> Unit)?

    /** A picture actually appeared - not merely that a tune was dispatched. */
    var onFirstFrame: (() -> Unit)?

    /** True when playback stalls to buffer, false when it recovers. */
    var onBuffering: ((Boolean) -> Unit)?

    /**
     * Start [playable] at [startAtSeconds], joining it partway through as the clock dictates.
     *
     * [requestedAtMillis] is the moment the VIEWER asked, not the moment the URL was ready, so
     * the first-frame timing an implementation logs covers the resolve as well as the load.
     */
    fun play(playable: Playable, startAtSeconds: Double, requestedAtMillis: Long)

    /** Stop rendering immediately, so the previous channel is not left under a new banner. */
    fun stop()

    /** 0f while tuning or while the guide music plays, 1f otherwise. */
    fun setVolume(volume: Float)

    /** Release everything. The instance is dead afterwards. */
    fun release()
}
