package com.cliftonia.fs42tv.sync

import java.io.File

/** What a successful sync fetched, so callers don't have to re-read and re-parse it from disk. */
data class SyncResult(val dial: Dial, val urls: UrlCache)

/**
 * Fetches the published dial and keeps the last good copy on disk.
 *
 * `fetch` is injected rather than hard-wired so this is testable on the JVM with no
 * network and no Android runtime.
 */
class DialRepository(
    private val fetch: (String) -> String,
    private val cacheDir: File,
) {
    private val dialFile get() = File(cacheDir, "channels.json")
    private val urlsFile get() = File(cacheDir, "urls.json")

    /** Fetch both files and cache them. Throws if the server cannot be reached. */
    fun sync(baseUrl: String): SyncResult {
        val dialText = fetch("$baseUrl/channels.json")
        val urlsText = fetch("$baseUrl/urls.json")
        // Parse BEFORE writing: caching a malformed response would poison the fallback
        // that exists precisely for when the server is unavailable.
        val dial = DialContract.parseDial(dialText)
        val urls = DialContract.parseUrls(urlsText)
        cacheDir.mkdirs()
        dialFile.writeText(dialText)
        urlsFile.writeText(urlsText)
        return SyncResult(dial, urls)
    }

    fun cachedDial(): Dial? =
        runCatching { DialContract.parseDial(dialFile.readText()) }.getOrNull()

    fun cachedUrls(): UrlCache? =
        runCatching { DialContract.parseUrls(urlsFile.readText()) }.getOrNull()
}
