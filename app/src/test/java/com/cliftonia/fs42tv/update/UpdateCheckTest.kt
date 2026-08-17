package com.cliftonia.fs42tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    private val published = UpdateCheck.Published(
        version = 262221037,
        apkUrl = "https://github.com/cliftonia/ytv/releases/download/v262221037/ytv.apk",
    )

    /** Trimmed to the fields that matter, in the shape GitHub actually returns them. */
    private val release = """
        {
          "tag_name": "v262221037",
          "name": "v262221037",
          "assets": [
            {
              "name": "ytv.apk",
              "browser_download_url":
                "https://github.com/cliftonia/ytv/releases/download/v262221037/ytv.apk"
            }
          ],
          "tarball_url": "https://api.github.com/repos/cliftonia/ytv/tarball/v262221037"
        }
    """.trimIndent()

    @Test
    fun `a newer published build is offered`() {
        assertTrue(UpdateCheck.isNewer(installed = 262220900, published = published))
    }

    @Test
    fun `the same build is not offered`() {
        // The normal case, on every launch, on both devices. It must be silent, or the viewer is
        // shown an update prompt every time they turn the television on.
        assertFalse(UpdateCheck.isNewer(installed = 262221037, published = published))
    }

    @Test
    fun `an older published build is not offered`() {
        // Possible if a device was updated by cable and the release has not caught up.
        // Downgrading silently would be worse than doing nothing.
        assertFalse(UpdateCheck.isNewer(installed = 262221200, published = published))
    }

    @Test
    fun `nothing published means nothing offered`() {
        assertFalse(UpdateCheck.isNewer(installed = 1, published = null))
    }

    @Test
    fun `a hand-built apk is always behind, so it never claims to be an update`() {
        // build.gradle.kts falls back to versionCode 1 without YTV_VERSION, which is what a plain
        // ./gradlew assembleDebug produces. It must be treated as the oldest possible build.
        assertTrue(UpdateCheck.isNewer(installed = 1, published = published))
    }

    @Test
    fun `a github release parses`() {
        val p = UpdateCheck.parse(release)
        assertEquals(262221037, p?.version)
        assertEquals(
            "https://github.com/cliftonia/ytv/releases/download/v262221037/ytv.apk", p?.apkUrl)
    }

    @Test
    fun `the apk is picked out from among the automatic assets`() {
        // GitHub attaches source archives to every release. Matching the first download url in
        // the response would hand the installer a zip, which fails in a way nobody would connect
        // back to this line.
        val withArchivesFirst = """
            {
              "tag_name": "v262221037",
              "assets": [
                {"browser_download_url": "https://github.com/cliftonia/ytv/archive/v1.zip"},
                {"browser_download_url": "https://github.com/cliftonia/ytv/archive/v1.tar.gz"},
                {"browser_download_url": "https://github.com/cliftonia/ytv/releases/x/ytv.apk"}
              ]
            }
        """.trimIndent()
        assertEquals("https://github.com/cliftonia/ytv/releases/x/ytv.apk",
            UpdateCheck.parse(withArchivesFirst)?.apkUrl)
    }

    @Test
    fun `a tag with no v prefix still parses`() {
        assertEquals(262221037, UpdateCheck.parse(release.replace("\"v262221037\"", "\"262221037\""))?.version)
    }

    @Test
    fun `anything unusable parses to null rather than to a default`() {
        // A repository with no releases answers 404, and a release exists for a few seconds
        // before its apk finishes uploading. Neither is an update, and neither should raise.
        assertNull(UpdateCheck.parse(null))
        assertNull(UpdateCheck.parse(""))
        assertNull(UpdateCheck.parse("not json at all"))
        assertNull("a release with no tag has no version",
            UpdateCheck.parse("""{"assets":[{"browser_download_url":"https://x/ytv.apk"}]}"""))
        assertNull("a release whose apk has not uploaded yet is not an offer",
            UpdateCheck.parse("""{"tag_name":"v262221037","assets":[]}"""))
        assertNull("a non-numeric tag is not a version",
            UpdateCheck.parse("""{"tag_name":"nightly","assets":[{"browser_download_url":"https://x/ytv.apk"}]}"""))
    }

    @Test
    fun `the release url is built from the repository name`() {
        assertEquals("https://api.github.com/repos/cliftonia/ytv/releases/latest",
            UpdateCheck.latestReleaseUrl("cliftonia/ytv"))
    }
}
