package com.cliftonia.fs42tv.update

/**
 * Whether a published build is newer than the one running, and where to get it.
 *
 * Pure and I/O free: the fetch, the download and the install intent all live in [Updater]. The
 * decision is separated because it is the part that can be wrong in ways nobody notices - an
 * update that never offers itself looks exactly like no update being available, and one that
 * offers itself repeatedly is worse than none at all.
 */
object UpdateCheck {

    /** What the publisher says is available. */
    data class Published(val version: Int, val apkPath: String)

    /**
     * Parse `/app.json`, or null if it says nothing usable.
     *
     * Null rather than an exception, and null rather than a default: a publisher that has never
     * been deployed to answers 503, and a half-written manifest is possible while deploy.sh is
     * mid-copy. Neither is a reason to bother the viewer, and neither should look like an update.
     */
    fun parse(json: String?): Published? {
        if (json.isNullOrBlank()) return null
        // Read by hand rather than with org.json, which Android STUBS in JVM unit tests: with
        // `unitTests.isReturnDefaultValues = true` a JSONObject silently answers 0 and "" for
        // everything, so the tests for this passed against a parser that never ran. The manifest
        // is two fields written by one script; a regex is honest about that and actually testable.
        val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val apk = Regex("\"apk\"\\s*:\\s*\"([^\"]+)\"").find(json)
            ?.groupValues?.get(1) ?: return null
        return if (version <= 0 || apk.isBlank()) null else Published(version, apk)
    }

    /**
     * Whether [published] is worth offering to someone running [installed].
     *
     * Strictly greater, so a device already on the newest build is never nagged. Equal is the
     * normal case and must be silent.
     */
    fun isNewer(installed: Int, published: Published?): Boolean =
        published != null && published.version > installed

    /**
     * The URL to download from, given the publisher's base URL.
     *
     * The manifest carries a path rather than a full URL so the same published file works
     * whether the box is reached by name or address, and so a manifest can never point a device
     * at somewhere other than the publisher it just asked.
     */
    fun downloadUrl(baseUrl: String, published: Published): String =
        baseUrl.trimEnd('/') + "/" + published.apkPath.trimStart('/')
}
