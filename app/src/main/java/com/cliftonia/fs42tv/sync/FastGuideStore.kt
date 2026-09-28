package com.cliftonia.fs42tv.sync

import java.io.File
import java.util.concurrent.Executor

/**
 * The [FastGuide] the banner and the guide read, kept on disk and refreshed a few times a day.
 *
 * The rules, each one a courtesy to a 2.3 GB television on a shared link:
 *
 * - Nothing happens until a FAST channel is looked at. The YouTube dial never asks, and neither
 *   does a LIVE TV evening spent on Pluto channels.
 * - The cached copy answers first, straight off disk, so the first banner after a launch has a
 *   title without waiting on the network - the file holds thirty hours, so last night's copy is
 *   still right this morning.
 * - A download at most every [REFRESH_MILLIS], counted from the cached file's age, so a relaunch
 *   does not fetch again; after a failure, not again for [RETRY_MILLIS].
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
        label = "fast guide", describe = { "${it.size} channels" },
    )

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
        /** Four hours: the job publishes every six, and the file holds thirty. */
        const val REFRESH_MILLIS = 4L * 60 * 60 * 1000
        const val RETRY_MILLIS = 10L * 60 * 1000
        const val FILE_NAME = "fast_guide.json"

        /** The download, with timeouts, as the lineup's: the default is none at all. */
        fun httpFetch(url: String): String = PublishedFile.httpFetch(url)
    }
}
