package com.cliftonia.fs42tv.ui

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * The on/off switches for the extras - each one a Settings row, each one ON by default.
 *
 * They exist because these features ship without having been watched on either television. A
 * feature that misbehaves on the TCL but not the Chromecast (the two engines are two code paths;
 * see HANDOVER) must be removable with the remote, from the sofa, without an adb session or a
 * release. So every flag's OFF is exactly the behaviour from before the feature existed, and
 * every caller reads its flag at the moment it acts rather than once at launch - a row switched
 * off takes effect on the next tune at the latest, usually immediately.
 *
 * Pure Kotlin behind two closures rather than a SharedPreferences field, so the rules - default
 * on, one row per flag, the flip persists - are testable on the JVM.
 */
class Features(
    private val read: (key: String, default: Boolean) -> Boolean,
    private val write: (key: String, value: Boolean) -> Unit,
    /** For the one choice with more than two answers, [tuningScreen]. */
    private val readText: (key: String) -> String? = { null },
    private val writeText: (key: String, value: String) -> Unit = { _, _ -> },
) {

    /**
     * What fills the screen between channels. Always silent: a hiss under the snow shipped, was
     * heard, and was called annoying - so there is no sound option to come back to.
     */
    enum class TuningScreen { STATIC, BLUE, NONE }

    /**
     * The TUNING SCREEN row. Read once; a television that had switched the old STATIC row off
     * chose plain black, so it starts at NONE rather than having the snow come back.
     */
    @Volatile
    var tuningScreen: TuningScreen = TuningScreen.values()
        .firstOrNull { it.name == readText(TUNING_KEY) }
        ?: if (read(OLD_STATIC_KEY, true)) TuningScreen.STATIC else TuningScreen.NONE
        private set

    /**
     * One entry per feature. [label] is the Settings row, so it is written the way the owner will
     * look for it on screen; [key] is the remembered preference and must never be reused.
     */
    enum class Flag(
        val label: String,
        val key: String,
        /** What the row says when on and off - ON/OFF unless the choice has better words. */
        val onValue: String = "ON",
        val offValue: String = "OFF",
    ) {
        /** Now/next programme titles on Pluto channels, from Pluto's own guide. */
        PLUTO_GUIDE("PLUTO GUIDE", "feature.plutoguide"),

        /** YouTube clips turned down to YouTube's reference loudness, never up. */
        LEVEL_VOLUME("LEVEL VOLUME", "feature.levelvolume"),

        /**
         * Sponsor reads, self-promotion and "like and subscribe" jumped over, from SponsorBlock's
         * community data. OFF ignores the lineup's `skip` field everywhere - the clock walks raw
         * durations again, exactly as before the field existed.
         */
        SKIP_SPONSORS("SKIP SPONSORS", "feature.skipsponsors"),

        /**
         * Clock channels on the half hour - programmes at :00 and :30, short clips in the gaps, an
         * "up next" card for the rest. CONTINUOUS is the rotation exactly as it was before.
         */
        SCHEDULE("SCHEDULE", "feature.schedule", onValue = "HALF-HOUR", offValue = "CONTINUOUS"),

        /**
         * Pluto channels through Pluto's own stitcher on a session (see `pluto/PlutoRoute`),
         * instead of the published jmp2 url, which for many channels loops Pluto's logo bumper.
         * LEGACY plays the jmp2 url exactly as before, from the next tune.
         */
        PLUTO_ROUTE("PLUTO ROUTE", "feature.plutoroute", onValue = "DIRECT", offValue = "LEGACY"),

        /**
         * A "we'll be right back" card over Pluto's logo bumper during an ad break, with the
         * guide's music (see `ui/PlutoBreak`). OFF reads no playlists and shows the bumper, as
         * before.
         */
        BREAK_CARD("BREAK CARD", "feature.breakcard"),
    }

    // Read once, then served from memory: flags are consulted on the UI thread and the executors,
    // and SharedPreferences.getBoolean takes a lock per call. Concurrent because the settings row
    // writes on the UI thread while an executor may be reading.
    private val on = ConcurrentHashMap<Flag, Boolean>().apply {
        Flag.values().forEach { put(it, read(it.key, true)) }
    }

    fun isOn(flag: Flag): Boolean = on[flag] ?: true

    /**
     * The Settings rows, in [Flag] order.
     *
     * [onToggled] is how a flag acts on what is ALREADY on screen - restoring full volume, ending
     * a card - so switching one off is visible the moment OK lands rather
     * than on the next channel change.
     */
    fun rows(onToggled: (Flag, Boolean) -> Unit, refresh: () -> Unit): List<SettingRow> {
        val flags = Flag.values().map { flag ->
            SettingRow(
                label = flag.label,
                value = if (isOn(flag)) flag.onValue else flag.offValue,
                action = {
                    val next = !isOn(flag)
                    on[flag] = next
                    write(flag.key, next)
                    onToggled(flag, next)
                    refresh()
                },
            )
        }
        // Where the old STATIC row was, straight after PLUTO GUIDE.
        val tuning = SettingRow(
            label = "TUNING SCREEN",
            value = tuningScreen.name,
            action = {
                val all = TuningScreen.values()
                tuningScreen = all[(tuningScreen.ordinal + 1) % all.size]
                writeText(TUNING_KEY, tuningScreen.name)
                refresh()
            },
        )
        val at = flags.indexOfFirst { it.label == Flag.PLUTO_GUIDE.label } + 1
        return flags.take(at) + tuning + flags.drop(at)
    }

    companion object {
        private const val TUNING_KEY = "feature.tuningscreen"

        /**
         * The retired ON/OFF row, read only to carry its answer over. Neither it nor
         * `feature.logo` - the corner bug, removed on the owner's word - may be reused.
         */
        private const val OLD_STATIC_KEY = "feature.static"

        fun from(prefs: SharedPreferences) = Features(
            read = { key, default -> prefs.getBoolean(key, default) },
            write = { key, value -> prefs.edit().putBoolean(key, value).apply() },
            readText = { key -> prefs.getString(key, null) },
            writeText = { key, value -> prefs.edit().putString(key, value).apply() },
        )
    }
}
