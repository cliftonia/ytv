package com.cliftonia.fs42tv.sync

import java.io.File

/** What a successful sync fetched, so callers don't have to re-read and re-parse it from disk. */
data class SyncResult(val dial: Dial)

/**
 * Fetches the published dial and keeps the last good copy on disk.
 *
 * `fetch` is injected rather than hard-wired so this is testable on the JVM with no
 * network and no Android runtime.
 *
 * Only the lineup travels now. There used to be a second file, `urls.json`, holding signed stream
 * urls the server had resolved ahead of time - it covered about 46% of the dial and saved a round
 * trip on those clips. It cannot survive the server going away: googlevideo signs urls for roughly
 * six hours, so a lineup published nightly would carry urls that were already dead by breakfast.
 * Resolution moved onto the device instead ([com.cliftonia.fs42tv.resolver.DeviceResolver]), which
 * is slower on the first visit to a channel and free on every one after, because the result is
 * held in memory for as long as the signature lasts.
 */
class DialRepository(
    private val fetch: (String) -> String,
    private val cacheDir: File,
) {
    private val dialFile get() = File(cacheDir, "channels.json")

    /**
     * Fetch the lineup from [dialUrl] and cache it. Throws if it cannot be reached.
     *
     * A full url rather than a base and a path: the lineup is a file in a git repository now, not
     * an endpoint on a server that also served three other things.
     */
    fun sync(dialUrl: String): SyncResult {
        val dialText = fetch(dialUrl)
        // Parse BEFORE writing: caching a malformed response would poison the fallback
        // that exists precisely for when the lineup cannot be fetched.
        val dial = DialContract.parseDial(dialText)
        // A body of `{}` parses perfectly - `channels` defaults to empty - and caching it would
        // overwrite the last good lineup with nothing, leaving both televisions with no dial and
        // no way back except a good fetch. Parsing is not the same as being usable.
        require(dial.channels.isNotEmpty()) { "the lineup parsed but has no channels" }
        cacheDir.mkdirs()
        dialFile.writeText(dialText)
        // Left by the version that synced two files. Harmless if read - every tier in it expired
        // months ago and freshness checks would reject them - but it is dead weight on a device
        // with 2.3GB of storage, so it goes on the first successful sync after upgrading.
        File(cacheDir, "urls.json").delete()
        return SyncResult(dial)
    }

    fun cachedDial(): Dial? =
        runCatching { DialContract.parseDial(dialFile.readText()) }.getOrNull()
}
