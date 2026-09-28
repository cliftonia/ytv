package com.cliftonia.fs42tv.sync

import java.io.File
import java.util.concurrent.Executor

/**
 * The [FastGuide] the banner and the guide read, kept on disk and refreshed while the app runs.
 *
 * The rules, each one a courtesy to a 2.3 GB television on a shared link:
 *
 * - The YouTube dial never asks. The LIVE TV dial asks when it comes up ([lineupSeen]) and when a
 *   FAST channel is looked at.
 * - The cached copy answers first, straight off disk, so the first banner after a launch has a
 *   title without waiting on the network - the file holds thirty hours.
 * - At launch a copy older than [LAUNCH_STALE_MILLIS] is downloaded again; while running, a
 *   download at most every [REFRESH_MILLIS], counted from the cached file's age, so a relaunch
 *   does not fetch again; after a failure, not again for [RETRY_MILLIS].
 * - A lineup refreshed to one other than the guide was downloaded under is downloaded for at once:
 *   the server builds the guide from the lineup, and a new channel has no titles until then.
 * - One load at a time, on the executor - the prefetch thread, like Pluto's guide.
 * - Parse before writing: a captive portal's page is never cached over a good guide.
 *
 * Failure answers null, and the banner and the guide keep the channel name - exactly the
 * behaviour from before this existed. The loading itself is [PublishedFile]'s, shared with the
 * picker's `details.json`.
 */
class FastGuideStore(
    file: File,
    fetch: (String) -> String,
    executor: Executor,
    /** Wall clock: the file's age is its modification time against this. */
    private val nowMillis: () -> Long,
    /** The Settings row. Read per call: switched off, nothing is loaded or served. */
    private val enabled: () -> Boolean,
    url: String = FastGuide.URL,
) {

    private val published = PublishedFile(
        file = file, url = url, fetch = fetch, parse = FastGuide::parse, executor = executor,
        nowMillis = nowMillis, refreshMillis = REFRESH_MILLIS, retryMillis = RETRY_MILLIS,
        label = "fast guide", describe = { "${it.size} channels" }, launchStaleMillis = LAUNCH_STALE_MILLIS,
    )

    /**
     * The LIVE TV dial came up on, or refreshed to, the lineup [stamp] - see
     * [DialRepository.stampOf]. Loads when the copy held is stale or was taken under another
     * lineup; with the row switched off, nothing is loaded.
     */
    fun lineupSeen(stamp: String) = published.lineupSeen(stamp, load = enabled())

    /**
     * What is on [channel] now, or null. When nothing is held yet, or the copy held is due for a
     * refresh, a load starts; [onLoaded] - on the executor - runs once it lands, when nothing
     * was held, so the caller can read again. Never called when the load fails.
     */
    fun titleOn(channel: Channel, onLoaded: (() -> Unit)? = null): String? =
        programmeOn(channel, onLoaded)?.title

    /** As [titleOn], with the programme's description and picture where the guide has them. */
    fun programmeOn(channel: Channel, onLoaded: (() -> Unit)? = null): FastGuide.Programme? {
        if (!enabled()) return null
        FastGuide.keyOf(channel) ?: return null
        return published.get(onLoaded)?.programmeAt(channel, nowMillis())
    }

    companion object {
        /** Hourly: the server publishes every three hours, and the file holds thirty. */
        const val REFRESH_MILLIS = PublishedFile.REFRESH_MILLIS
        const val LAUNCH_STALE_MILLIS = PublishedFile.LAUNCH_STALE_MILLIS
        const val RETRY_MILLIS = PublishedFile.RETRY_MILLIS
        const val FILE_NAME = "fast_guide.json"

        /** The download, with timeouts, as the lineup's: the default is none at all. */
        fun httpFetch(url: String): String = PublishedFile.httpFetch(url)
    }
}
