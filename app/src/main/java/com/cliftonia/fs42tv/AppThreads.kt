package com.cliftonia.fs42tv

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The app's three background threads, and why there are three rather than one.
 *
 * Out of [MainActivity] so the reasons sit beside the threads rather than among the lifecycle.
 * Each is single-threaded on purpose; ordering within each is part of its contract.
 */
class AppThreads {

    // Single-threaded so a rapid burst of channel presses queues in order rather than racing
    // each other over the shared navigator and player.
    val tune: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * A second thread, for resolving channels nobody has asked for yet. Separate from [tune] on
     * purpose: that one serves the channel the viewer is actually waiting for, and a speculative
     * resolve queued ahead of a real keypress would make surfing slower.
     */
    val prefetch: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * A third thread, for downloading the subtitle file of the clip that just started. Its own
     * thread for the same reason [prefetch] has one, in both directions: on [tune] it would delay
     * the next channel change, and on [prefetch] the captions for the programme being watched
     * would queue behind neighbours nobody asked for.
     */
    val caption: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Run [block] on its own low-priority daemon thread: work nobody waits for, like building the
     * schedules ahead of the first guide open. Not the prefetch thread, where it would hold up
     * the first neighbour resolves; not an executor, so there is nothing to have been shut down
     * under it - and a failure is logged, never thrown into whoever started it.
     */
    fun inBackground(name: String, block: () -> Unit) {
        Thread({
            runCatching(block).onFailure { android.util.Log.w("fs42", "$name failed: $it") }
        }, name).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    /** On destroy: nothing queued may outlive the activity. */
    fun shutdown() {
        tune.shutdownNow()
        prefetch.shutdownNow()
        caption.shutdownNow()
    }
}
