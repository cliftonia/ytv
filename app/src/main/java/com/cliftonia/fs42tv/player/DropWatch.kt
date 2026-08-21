package com.cliftonia.fs42tv.player

/**
 * Whether the picture is actually holding together at the quality being asked for.
 *
 * The decoder capability API is not trustworthy on this television. `areSizeAndRateSupported` and
 * `getAchievableFrameRatesFor` both report that it sustains VP9 at 2160p, and in practice a 4K
 * clip drops frames badly enough that the viewer switches back to 1080p by hand. The vendor's
 * claim is what the device will ACCEPT; this is what it can actually hold.
 *
 * So the ceiling COULD be decided by observation. mpv counts dropped and late frames, and a rate
 * above a frame or so a second is visible as stutter - measured on this panel, `display-resample`
 * at 1080p produced late frames climbing 9, 27, 45 over fifty seconds and looked wrong, while the
 * same clip under `video-sync=audio` produced zero.
 *
 * Deliberately DORMANT: nothing calls [shouldDemote] yet. The quality ceiling is a manual,
 * observable preference (MAX QUALITY in settings), and auto-demotion would change the picture
 * with nothing on screen to say why. This is the policy held ready - and pinned by tests - for
 * the day observation shows the manual ceiling is not enough.
 *
 * Pure, because the judgement is the part worth getting right and it is invisible from the sofa:
 * demote too eagerly and a capable panel is capped at 1080p forever; too reluctantly and the
 * viewer keeps seeing stutter the app could have prevented.
 */
object DropWatch {

    /**
     * Frames lost per second above which a resolution is not worth attempting again.
     *
     * One a second is roughly where it stops reading as an occasional hiccup and starts reading as
     * a fault. Below that, leaving the higher resolution in place is the better trade - a rare
     * dropped frame is less visible than a permanently softer picture.
     */
    const val LOST_PER_SECOND = 1.0

    /**
     * How long to watch before judging.
     *
     * A tune is a deep seek into a file and the first seconds are always ragged - the decoder is
     * filling, the proxy is ramping its window. Judging on that would demote every channel.
     */
    const val SETTLE_SECONDS = 8.0

    /**
     * Whether what has been observed justifies dropping to a lower ceiling.
     *
     * [dropped] is the decoder failing to keep up and [late] is the output missing its vsync
     * deadline. Both are counted because both are visible as stutter and the viewer cannot tell
     * them apart - only the cure differs, and neither cure is available to this app.
     */
    fun shouldDemote(dropped: Int, late: Int, elapsedSeconds: Double): Boolean {
        if (elapsedSeconds < SETTLE_SECONDS) return false
        return (dropped + late) / elapsedSeconds > LOST_PER_SECOND
    }
}
