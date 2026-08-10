package com.cliftonia.fs42tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    private val published = UpdateCheck.Published(version = 262221037, apkPath = "/ytv.apk")

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
        // Possible while deploy.sh is mid-copy, or if a device was updated by cable and the
        // publisher has not caught up. Downgrading silently would be worse than doing nothing.
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
    fun `a well formed manifest parses`() {
        val p = UpdateCheck.parse("""{"version": 262221037, "apk": "/ytv.apk"}""")
        assertEquals(262221037, p?.version)
        assertEquals("/ytv.apk", p?.apkPath)
    }

    @Test
    fun `anything unusable parses to null rather than to a default`() {
        // A publisher that has never been deployed to answers 503; a manifest can be half-written
        // while deploy.sh copies it. Neither is an update, and neither should raise.
        assertNull(UpdateCheck.parse(null))
        assertNull(UpdateCheck.parse(""))
        assertNull(UpdateCheck.parse("not json at all"))
        assertNull("a manifest with no version is not an offer",
            UpdateCheck.parse("""{"apk": "/ytv.apk"}"""))
        assertNull("a manifest with no apk has nowhere to send the device",
            UpdateCheck.parse("""{"version": 262221037}"""))
        assertNull("version zero is the absence of a version",
            UpdateCheck.parse("""{"version": 0, "apk": "/ytv.apk"}"""))
    }

    @Test
    fun `the download url is built from the publisher that was asked`() {
        assertEquals("http://192.168.4.203:4243/ytv.apk",
            UpdateCheck.downloadUrl("http://192.168.4.203:4243", published))
    }

    @Test
    fun `a trailing slash or a leading slash does not double up`() {
        assertEquals("http://box:4243/ytv.apk",
            UpdateCheck.downloadUrl("http://box:4243/", published))
        assertEquals("http://box:4243/ytv.apk",
            UpdateCheck.downloadUrl("http://box:4243", UpdateCheck.Published(2, "ytv.apk")))
    }
}
