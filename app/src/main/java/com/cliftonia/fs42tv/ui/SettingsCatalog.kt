package com.cliftonia.fs42tv.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cliftonia.fs42tv.BuildConfig
import com.cliftonia.fs42tv.CrashLog
import com.cliftonia.fs42tv.ExitReason
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.player.AudioSync
import com.cliftonia.fs42tv.player.FrameCadence
import com.cliftonia.fs42tv.player.MpvLog
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics

/**
 * What the settings screen shows and what each row does when OK lands on it.
 *
 * Separate from the activity so the screen's dependency surface is visible in one place: every
 * piece of state a setting can read or change is a named member of [Deps], and anything not in
 * there is out of a setting's reach. When this lived inside the activity the same list read
 * fourteen private fields scattered across a two-thousand-line file, and working out what a
 * toggle could possibly affect meant reading all of it.
 *
 * Closures rather than an interface back onto the activity, so each row names exactly the
 * capability it uses instead of receiving the whole host.
 */
class SettingsCatalog(private val context: Context, private val deps: Deps) {

    class Deps(
        val prefs: SharedPreferences,
        val displayModeCount: () -> Int,
        val channels: () -> List<Channel>,
        /** The current quality ladder, and the way to change it for the NEXT tune. */
        val ladder: () -> List<String>,
        val setLadder: (List<String>) -> Unit,
        /** Resolves cached at the old ceiling are wrong for the new one and must go. */
        val clearResolved: () -> Unit,
        val captionsOn: () -> Boolean,
        /** Flips the flag AND acts on the clip already playing - see the row's comment. */
        val toggleCaptions: () -> Unit,
        /** Applies the trim to whatever is playing right now, which is the point of the row. */
        val applyAudioHold: (Int) -> Unit,
        val checkForUpdate: ((String) -> Unit) -> Unit,
        val updateStatus: () -> String,
        /** Rebuilds and republishes the rows, so a change is visible the moment OK lands. */
        val refresh: () -> Unit,
    )

    fun rows(): List<SettingRow> {
        val engine = com.cliftonia.fs42tv.player.PlayerEngine.parse(
            deps.prefs.getString(ENGINE_KEY, null))
            ?: com.cliftonia.fs42tv.player.PlayerEngine.default(deps.displayModeCount())
        val channels = deps.channels()
        val clips = channels.sumOf { it.streams.size }
        val crash = ExitReason.lastAbnormal(context) ?: CrashLog.summary(context.filesDir)
        return listOfNotNull(
            crash?.let {
                SettingRow(
                    label = "LAST CRASH",
                    value = it,
                    // OK clears it, so the next crash is unambiguously new rather than possibly
                    // the same one being read twice.
                    action = {
                        CrashLog.clear(context.filesDir)
                        deps.refresh()
                    },
                )
            },
            SettingRow(
                label = "VIDEO ENGINE",
                value = engine.name,
                // Takes effect on the next launch rather than swapping the player under a running
                // channel. Rebuilding the engine live is possible - the recovery path does it -
                // but doing it from a settings screen would mean re-resolving and re-seeking the
                // current clip, and the one moment this setting is reached for is when playback
                // is already misbehaving.
                action = {
                    val next =
                        if (engine == com.cliftonia.fs42tv.player.PlayerEngine.MPV) {
                            com.cliftonia.fs42tv.player.PlayerEngine.MEDIA3
                        } else {
                            com.cliftonia.fs42tv.player.PlayerEngine.MPV
                        }
                    deps.prefs.edit().putString(ENGINE_KEY, next.name.lowercase()).apply()
                    deps.refresh()
                    Log.i("fs42", "engine set to $next; takes effect on next launch")
                },
            ),
            SettingRow(
                label = "MAX QUALITY",
                value = QUALITY_LADDERS.firstOrNull { it.second == deps.ladder() }?.first
                    ?: "1080p",
                // Applies to the NEXT tune rather than the current one, which is why the row does
                // not restart playback: flipping channel is how you see the effect, and that is
                // the thing you were already doing when you noticed the problem.
                action = {
                    val current = QUALITY_LADDERS.indexOfFirst { it.second == deps.ladder() }
                    val next = QUALITY_LADDERS[(current + 1).mod(QUALITY_LADDERS.size)]
                    deps.setLadder(next.second)
                    deps.prefs.edit().putString(QUALITY_KEY, next.first).apply()
                    deps.clearResolved()
                    deps.refresh()
                    Log.i("fs42", "quality ceiling now ${next.first} -> ${next.second}")
                },
            ),
            SettingRow(
                label = "FRAME PACING",
                value = com.cliftonia.fs42tv.player.videoSyncMode ?: "DISPLAY",
                // Takes effect on the next launch: mpv reads it while the core initialises, and
                // rebuilding the engine to change it would restart whatever is playing.
                action = {
                    val modes = FrameCadence.SYNC_MODES
                    val next = modes[(modes.indexOf(
                        com.cliftonia.fs42tv.player.videoSyncMode) + 1).mod(modes.size)]
                    com.cliftonia.fs42tv.player.videoSyncMode = next
                    deps.prefs.edit().putString(VIDEO_SYNC_KEY, next).apply()
                    deps.refresh()
                    Log.i("fs42", "frame pacing $next; takes effect on next launch")
                },
            ),
            SettingRow(
                label = "AV DELAY",
                value = AudioSync.label(com.cliftonia.fs42tv.player.audioHoldMillis),
                // Applies to whatever is playing RIGHT NOW, unlike every other row here, and that
                // is deliberate. The right value cannot be derived: it is however long the sound
                // takes to get from this app to the speaker, and on this television that is a
                // Bluetooth link whose sink reports its own buffering as zero. The only way to
                // find it is to press this until the mouths match, which needs the picture and the
                // sound to keep running while you do.
                action = {
                    val next = AudioSync.next(com.cliftonia.fs42tv.player.audioHoldMillis)
                    deps.prefs.edit().putInt(AUDIO_HOLD_KEY, next).apply()
                    deps.applyAudioHold(next)
                    deps.refresh()
                },
            ),
            SettingRow(
                label = "CAPTIONS",
                value = if (deps.captionsOn()) "ON" else "OFF",
                // Acts on the clip already playing, not only the next one. Every resolve carries
                // its caption url whether or not captions are on, so the current clip's track is
                // waiting in the cache and turning captions on is a fetch of the track alone -
                // no re-resolve, and nothing that could be lost to a generation change on the
                // way back.
                action = {
                    deps.toggleCaptions()
                    deps.refresh()
                },
            ),
            SettingRow(
                label = "CHECK FOR UPDATE",
                value = deps.updateStatus().ifEmpty { "CHECK NOW" },
                action = {
                    deps.checkForUpdate { deps.refresh() }
                    deps.refresh()
                },
            ),
            // Everything below is read-only. Controls come FIRST because the screen is
            // finite: when this was a plain unscrolling column, adding diagnostics
            // silently pushed the captions toggle and the update check off the bottom,
            // and a control you cannot see is a control that does not exist.
            SettingRow("--- DIAGNOSTICS ---", ""),
            // FIRST of the readings, deliberately. After five builds spent looking for an audio
            // fault inside the player while the sound was leaving over Bluetooth, this is the
            // reading that has to be seen.
            SettingRow("AUDIO OUT", audioRoute()),
            SettingRow("LAST STREAM", PlaybackDiagnostics.lastStream),
            SettingRow("DECODERS", PlaybackDiagnostics.decoders),
            SettingRow("CAPTION STATE", PlaybackDiagnostics.captions),
            SettingRow("RESOLVED BY", PlaybackDiagnostics.lastSource),
            SettingRow("MPV SAID", MpvLog.lastReason() ?: "nothing"),
            SettingRow("LAST TUNE", PlaybackDiagnostics.lastTiming),
            SettingRow("AV SYNC", PlaybackDiagnostics.lastSync),
            SettingRow("VERSION", BuildConfig.VERSION_CODE.toString()),
            SettingRow("DISPLAY MODES", deps.displayModeCount().toString()),
            SettingRow("CHANNELS", "${channels.size} / $clips CLIPS"),
            SettingRow("LINEUP", lineupAge()),
        )
    }

    /**
     * Which physical output will carry the sound, and whether the player can do anything about
     * its latency.
     *
     * The Android half is here and the choosing is in [AudioSync] because `getDevices` needs a
     * real AudioManager and the routing rules do not - and the routing rules are the part that
     * can be wrong. Asked afresh every time the rows are built rather than cached: a Bluetooth
     * speaker switched off halfway through an evening changes the answer, and a diagnostic that
     * goes stale is worse than no diagnostic, because it is believed.
     */
    fun audioRoute(): String {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
            ?: return "NOT ASKED"
        val outputs = runCatching {
            audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .map { it.type to it.productName.toString() }
        }.getOrElse {
            Log.w("fs42", "could not enumerate audio outputs: $it")
            return "NOT ASKED"
        }
        val described = AudioSync.describeRoute(outputs)
        // Spelled out rather than left to be inferred: a route the player cannot compensate for
        // is the single most useful thing this screen can say when somebody is looking at it
        // because the audio sounds late.
        return if (AudioSync.needsManualTrim(outputs)) "$described - USE AV DELAY" else described
    }

    /**
     * How stale the lineup is, in words.
     *
     * The single most useful reading on this screen. When the dial misbehaves the first question
     * is whether the content is old or the extractor has broken, and those have opposite fixes -
     * a lineup fetched today with nothing playing means the extractor; a lineup from three weeks
     * ago means the nightly workflow has been failing and nobody noticed.
     */
    private fun lineupAge(): String {
        val file = java.io.File(context.cacheDir, "channels.json")
        if (!file.exists()) return "NOT FETCHED"
        val days = (System.currentTimeMillis() - file.lastModified()) / 86_400_000L
        return when {
            days <= 0L -> "FETCHED TODAY"
            days == 1L -> "1 DAY OLD"
            else -> "$days DAYS OLD"
        }
    }

    companion object {
        /**
         * The quality ceiling as a remembered preference, and every ladder it can take.
         *
         * A choice rather than an automatic upgrade: on a 2.34GB 32-bit panel a smooth 720p
         * H.264 beats a 1080p60 VP9 that stalls, and the only way to know which is happening is
         * to be able to switch between them and watch.
         */
        val QUALITY_LADDERS = listOf(
            "1080p" to listOf("hd", "sd"),
            "720p" to listOf("sd"),
            "4K" to listOf("uhd", "hd", "sd"),
        )

        /**
         * Remembers the chosen video engine so an override survives a relaunch. Persisted
         * rather than decided fresh each start because the point of the flag is to put Media3
         * back in a hurry when mpv misbehaves - and a setting that evaporates on the next
         * launch is no use at all in that moment.
         */
        const val ENGINE_KEY = "engine"

        /** Remembered quality ceiling; see [QUALITY_LADDERS]. */
        const val QUALITY_KEY = "quality"

        /** Whether to show English subtitles when a clip offers them. */
        const val CAPTIONS_KEY = "captions"

        /** Remembered frame pacing mode for mpv; see FrameCadence.SYNC_MODES. */
        const val VIDEO_SYNC_KEY = "videosync"

        /** Remembered A/V trim in milliseconds of picture hold-back; see AudioSync. */
        const val AUDIO_HOLD_KEY = "audiohold"
    }
}
