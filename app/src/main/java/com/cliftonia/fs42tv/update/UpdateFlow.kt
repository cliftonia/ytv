package com.cliftonia.fs42tv.update

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The self-update loop: notice a newer build, fetch it, and offer or perform the install.
 *
 * On its own thread, never the tuning executor: that executor is what makes a channel change
 * feel instant, and a 66MB download queued in front of a tune would undo the whole point of it.
 * Nothing is shown until the file is on disk, so an unreachable publisher - the normal state of
 * the set in the car - is completely silent on the passive path.
 */
class UpdateFlow(
    private val context: Context,
    private val repo: String,
    private val installedVersion: Int,
    private val halted: () -> Boolean,
    private val runOnUi: (() -> Unit) -> Unit,
) {

    /** True once a newer build has been downloaded and is sitting ready to install. */
    val ready = mutableStateOf(false)

    /** What the update row says right now, so a slow download does not look like a dead button. */
    val status = mutableStateOf("")

    /** Guards against a second check while one is already running. */
    private val running = AtomicBoolean(false)

    /**
     * Ask the publisher whether there is a newer build, and fetch it if so.
     *
     * [installWhenReady] is what separates the two callers. On launch the check is a background
     * courtesy - it finds a build, says so on the dial, and waits for OK, because interrupting
     * someone who just turned the television on with an installer is rude. Asked for explicitly
     * from settings it should simply do the thing: a button called CHECK FOR UPDATE that finds
     * an update and then requires you to leave the screen and press a different button is a
     * button that has not finished its job.
     *
     * [onStatus] reports progress in words for the settings row.
     */
    fun check(installWhenReady: Boolean = false, onStatus: (String) -> Unit = {}) {
        if (!running.compareAndSet(false, true)) return
        report("CHECKING...", onStatus)
        Thread {
            var outcome = "UP TO DATE"
            try {
                val updater = Updater(context, repo)
                if (updater.downloadIfNewer(installedVersion)) {
                    runOnUi { if (!halted()) ready.value = true }
                    if (installWhenReady) {
                        outcome = "INSTALLING..."
                        runOnUi {
                            if (halted()) return@runOnUi
                            // Cleared before handing over, exactly as the dial's OK path does:
                            // whether the viewer accepts Android's dialog or dismisses it, the
                            // prompt has done its job and must not sit there afterwards.
                            ready.value = false
                            updater.install()
                        }
                    } else {
                        outcome = "READY - PRESS OK"
                    }
                }
            } catch (e: Exception) {
                // The publisher being unreachable is the normal state of a television in a car.
                Log.w("fs42", "update check failed: $e")
                outcome = "COULD NOT CHECK"
            } finally {
                running.set(false)
                val finalOutcome = outcome
                runOnUi { if (!halted()) report(finalOutcome, onStatus) }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * OK pressed while the prompt is up: hand the downloaded build to Android's installer.
     *
     * The prompt is cleared either way - accepted or dismissed, it has done its job and must not
     * sit there hijacking the guide button afterwards.
     */
    fun installNow() {
        ready.value = false
        Updater(context, repo).install()
    }

    private fun report(text: String, onStatus: (String) -> Unit) {
        status.value = text
        onStatus(text)
    }
}
