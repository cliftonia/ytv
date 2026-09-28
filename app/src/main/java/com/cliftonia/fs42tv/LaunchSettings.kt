package com.cliftonia.fs42tv

import android.content.SharedPreferences
import android.util.Log
import androidx.activity.ComponentActivity
import com.cliftonia.fs42tv.player.FrameCadence
import com.cliftonia.fs42tv.player.PlayerEngine
import com.cliftonia.fs42tv.ui.SettingsCatalog

/**
 * What [MainActivity] reads before it builds anything: the panel's display modes, the remembered
 * pacing, audio hold and quality ladder, the measurement clock, and the engine they choose.
 *
 * Out of the activity because that file is the Android glue - lifecycle, keys, construction - and
 * this is one idea of its own: the launch's settings, read once in onCreate before the engine is
 * built (mpv applies some during init) and then only read, bar the quality ladder, which the
 * settings row writes. The fields keep the threading they had as activity fields: the mode count
 * is main-thread only, the ladder and the pinned clock are `@Volatile` for the executors.
 */
internal class LaunchSettings {

    /**
     * How many modes the panel reports, kept because the settings screen shows it and the
     * engine default is derived from it. One mode means a television that cannot change its
     * refresh rate, which is the whole reason two engines exist.
     */
    var displayModeCount: Int = 0
        private set

    /**
     * Which quality tiers to ask for. `@Volatile`: written by the settings row on the UI
     * thread, read by every resolve on the executors.
     */
    @Volatile var ladder: List<String> = listOf("hd", "sd")

    /**
     * Wall-clock seconds, or a frozen instant when one was supplied at launch.
     *
     * Every channel derives its clip and offset from the current time, so two measurement runs
     * minutes apart are watching entirely different content - a larger source of variance than
     * any setting worth tuning, and the cause of three separate false results. Freezing the
     * clock pins clip selection and offset; a launch without the extra behaves exactly as the
     * remote does. tools/measure-switch.sh passes it as `--el fs42.now`.
     */
    @Volatile private var fixedNowSeconds: Long = -1L

    fun nowSeconds(): Long =
        if (fixedNowSeconds > 0) fixedNowSeconds else System.currentTimeMillis() / 1000

    /** Every remembered preference, read before the engine is built - mpv applies some during init. */
    fun read(activity: ComponentActivity, prefs: SharedPreferences) {
        displayModeCount =
            (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                activity.display else activity.windowManager.defaultDisplay)?.supportedModes?.size ?: 0
        com.cliftonia.fs42tv.player.videoSyncMode =
            prefs.getString(SettingsCatalog.VIDEO_SYNC_KEY, null)
                ?: FrameCadence.SYNC_MODES.first()
        // Re-read on every engine build rather than only the first: mpv is rebuilt whenever
        // its core shuts down, and a trim that reset itself on that path would look exactly
        // like the audio fault coming back.
        com.cliftonia.fs42tv.player.audioHoldMillis =
            prefs.getInt(SettingsCatalog.AUDIO_HOLD_KEY, 0)
        ladder = SettingsCatalog.QUALITY_LADDERS
            .firstOrNull { it.first == prefs.getString(SettingsCatalog.QUALITY_KEY, null) }
            ?.second ?: SettingsCatalog.QUALITY_LADDERS.first().second
        // The measurement seam - see [fixedNowSeconds]. Disconnected once during a refactor,
        // after which a sweep silently measured rotating content.
        fixedNowSeconds = activity.intent?.getLongExtra("fs42.now", -1L) ?: -1L
        if (fixedNowSeconds > 0) Log.i("fs42", "clock pinned to $fixedNowSeconds")
    }

    /**
     * Which engine plays the dial, and why it is not simply "the newer one".
     *
     * Media3 judders on this television - roughly two tunes in five come back with the picture
     * running fast then slow - and mpv does not, measured on the same clips at the same
     * offsets. androidx/media issue 2941 documents the same fault on BUILT-IN Android TVs and
     * explicitly NOT on Chromecast or Fire TV, which matches: a stick can change its HDMI
     * output mode, a panel with one mode cannot. So the choice is made from the number of
     * display modes rather than from a device name, and Media3 stays the default wherever it
     * works - it is a fifth of the install size and starts faster.
     *
     * Override with:  adb shell am start -S -n com.cliftonia.fs42tv/.MainActivity --es engine mpv
     * (-S because launchMode is singleTask: without it a launch while the app is running
     * re-delivers the intent to the EXISTING activity and onCreate never runs.)
     */
    fun chooseEngine(
        activity: ComponentActivity,
        prefs: SharedPreferences,
        audioRoute: () -> String,
    ): PlayerEngine {
        val engine = PlayerEngine.parse(activity.intent?.getStringExtra("engine"))
            ?: PlayerEngine.parse(prefs.getString(SettingsCatalog.ENGINE_KEY, null))
            ?: PlayerEngine.default(displayModeCount)
        prefs.edit().putString(SettingsCatalog.ENGINE_KEY, engine.name.lowercase()).apply()
        Log.i("fs42", "player engine $engine ($displayModeCount display mode(s)), " +
            "audio out ${audioRoute()}, " +
            "hold ${com.cliftonia.fs42tv.player.audioHoldMillis}ms")
        return engine
    }
}
