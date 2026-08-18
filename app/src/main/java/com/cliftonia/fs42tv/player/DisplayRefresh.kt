package com.cliftonia.fs42tv.player

import android.content.Context
import android.view.View

/**
 * The panel's real refresh rate, which is what mpv resamples the audio against.
 *
 * `video-sync=display-resample` locks video to the display's real refresh and RESAMPLES THE AUDIO
 * to follow, so mpv's idea of the refresh rate IS the rate the audio is resampled at. Without an
 * override mpv falls back to its own detection, which is unreliable on Android - and any error
 * accumulates as audio sliding steadily ahead of or behind the picture. Lip sync drifting on a
 * talking head is exactly the shape of that fault.
 */
object DisplayRefresh {

    /**
     * The refresh rate to hand mpv, or null when nothing trustworthy could be read.
     *
     * Asked of the system rather than of the view. `View.getDisplay()` returns NULL until the
     * view is attached to a window, and options are set from the constructor - long before that.
     * So the previous implementation always returned null, the caller's `?.let` never ran, and
     * `display-fps-override` was never applied once. DisplayManager answers without a window,
     * which is the whole reason for using it here.
     */
    fun of(context: Context, view: View?): Float? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager
        val fromManager = manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        // The attached view first if there is one - it names the display this surface is actually
        // on - and the default display otherwise.
        val chosen = view?.display ?: fromManager
        return plausible(chosen?.mode?.refreshRate ?: chosen?.refreshRate)
    }

    /**
     * [hz] if a television could plausibly run at it, null otherwise.
     *
     * A rate outside anything a television does means something answered with a placeholder, and
     * resampling audio to a placeholder is worse than not resampling at all - the caller's
     * fallback is `video-sync=audio`, which gives up frame pacing but cannot drift.
     */
    fun plausible(hz: Float?): Float? = hz?.takeIf { it > 20f && it < 250f }
}
