package com.cliftonia.fs42tv.player

/**
 * Which video engine plays the dial.
 *
 * Two exist because the judder is device-specific, not universal. androidx/media issue 2941
 * documents frame-rate transitions going wrong on BUILT-IN Android TVs while behaving correctly
 * on external sticks - Chromecast and Fire TV are named - and that matches what was measured
 * here: the TCL judders on roughly two tunes in five under Media3 and not at all under mpv.
 *
 * So this is not "mpv won". Media3 is better suited where it works: it is a fifth of the install
 * size, starts faster, and every other behaviour in this app was built and measured against it.
 * mpv is for panels that need it.
 */
enum class PlayerEngine {
    MEDIA3,
    MPV;

    companion object {

        /**
         * The engine to use when nobody has chosen one.
         *
         * [displayModeCount] is how many modes the panel offers, from
         * `Display.getSupportedModes().size`. It is the discriminator because it is the mechanism
         * rather than a proxy for it: the fault is a player being unable to present 24 or 25fps
         * evenly, and a display with more than one mode can switch to something that divides.
         * The TCL reports exactly ONE mode - 3840x2160 at 60.000004Hz with no alternates - so
         * there is nothing to switch to and the pacing has to be right in software. A Chromecast
         * driving a TV over HDMI can change output mode, which is why it was never affected.
         *
         * Chosen over checking the model name deliberately: a name has to be updated for every
         * new device, and would say nothing about WHY. This asks the question that matters.
         */
        fun default(displayModeCount: Int): PlayerEngine =
            if (displayModeCount <= 1) MPV else MEDIA3

        /** Parse an explicit override, or null when the value means nothing. */
        fun parse(name: String?): PlayerEngine? = when (name?.lowercase()) {
            "mpv" -> MPV
            "media3", "exo", "exoplayer" -> MEDIA3
            else -> null
        }
    }
}
