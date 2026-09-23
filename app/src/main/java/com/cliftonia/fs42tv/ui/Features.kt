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
) {

    /**
     * One entry per feature. [label] is the Settings row, so it is written the way the owner will
     * look for it on screen; [key] is the remembered preference and must never be reused.
     */
    enum class Flag(val label: String, val key: String) {
        /** Now/next programme titles on Pluto channels, from Pluto's own guide. */
        PLUTO_GUIDE("PLUTO GUIDE", "feature.plutoguide"),
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
     * [onToggled] is how a flag acts on what is ALREADY on screen - silencing a hiss, hiding a
     * logo, restoring full volume - so switching one off is visible the moment OK lands rather
     * than on the next channel change.
     */
    fun rows(onToggled: (Flag, Boolean) -> Unit, refresh: () -> Unit): List<SettingRow> =
        Flag.values().map { flag ->
            SettingRow(
                label = flag.label,
                value = if (isOn(flag)) "ON" else "OFF",
                action = {
                    val next = !isOn(flag)
                    on[flag] = next
                    write(flag.key, next)
                    onToggled(flag, next)
                    refresh()
                },
            )
        }

    companion object {
        fun from(prefs: SharedPreferences) = Features(
            read = { key, default -> prefs.getBoolean(key, default) },
            write = { key, value -> prefs.edit().putBoolean(key, value).apply() },
        )
    }
}
