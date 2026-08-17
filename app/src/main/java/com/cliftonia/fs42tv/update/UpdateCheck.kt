package com.cliftonia.fs42tv.update

/**
 * Whether a published build is newer than the one running, and where to get it.
 *
 * Pure and I/O free: the fetch, the download and the install intent all live in [Updater]. The
 * decision is separated because it is the part that can be wrong in ways nobody notices - an
 * update that never offers itself looks exactly like no update being available, and one that
 * offers itself repeatedly is worse than none at all.
 *
 * Reads a GitHub release rather than a hand-written manifest on a server at home. The release is
 * created by the same workflow that builds the apk, so there is no step between "a build exists"
 * and "the televisions can see it" for anyone to forget.
 */
object UpdateCheck {

    /** What the newest release offers. */
    data class Published(val version: Int, val apkUrl: String)

    /**
     * Parse a GitHub `releases/latest` response, or null if it says nothing usable.
     *
     * Null rather than an exception, and null rather than a default: a repository with no
     * releases yet answers 404, and a release can exist for a few seconds before its apk finishes
     * uploading. Neither is a reason to bother the viewer, and neither should look like an update.
     *
     * Read by hand rather than with org.json, which Android STUBS in JVM unit tests: with
     * `unitTests.isReturnDefaultValues = true` a JSONObject silently answers 0 and "" for
     * everything, so the tests for this passed against a parser that never ran.
     */
    fun parse(json: String?): Published? {
        if (json.isNullOrBlank()) return null
        // The tag IS the version: the release workflow tags `v<versionCode>`, and versionCode is
        // the yyDDDHHmm stamp, so the tag sorts and compares the same way the installed build does.
        val version = Regex("\"tag_name\"\\s*:\\s*\"v?(\\d+)\"").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        // Anchored on the .apk suffix because a release carries other assets - source tarballs are
        // attached automatically - and matching the first download url would hand back a zip.
        val apk = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(json)
            ?.groupValues?.get(1) ?: return null
        return if (version <= 0) null else Published(version, apk)
    }

    /**
     * Whether [published] is worth offering to someone running [installed].
     *
     * Strictly greater, so a device already on the newest build is never nagged. Equal is the
     * normal case and must be silent.
     */
    fun isNewer(installed: Int, published: Published?): Boolean =
        published != null && published.version > installed

    /** Where to ask about the newest release of [repo], as `owner/name`. */
    fun latestReleaseUrl(repo: String): String =
        "https://api.github.com/repos/$repo/releases/latest"
}
