package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Resolves real videos against the real YouTube, to prove the extractor still works.
 *
 * `@Ignore` because it needs the network and because YouTube can break it on any given day
 * through no fault of this code - which is exactly the risk that came with dropping the server.
 * yt-dlp on a machine at home got fixed within hours of a YouTube change; a library baked into an
 * installed apk gets fixed when someone notices and ships a build.
 *
 * So this is the thing to run when the dial goes quiet everywhere at once:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*DeviceResolverLiveCheck*' \
 *       -Dtest.single.ignore=true --info
 *
 * or simply delete the `@Ignore` locally. A failure here means the extractor needs updating,
 * NOT that the lineup or the players are at fault - and knowing which of those it is saves the
 * hours that the first judder hunt cost.
 *
 * Runs on the JVM despite testing Android code because `unitTests.isReturnDefaultValues` stubs
 * `android.util.Log` to a no-op, and every other line here is plain Java.
 */
@Ignore("hits the live network; run by hand when resolution appears broken")
class DeviceResolverLiveCheck {

    private val resolver = DeviceResolver()
    private val now get() = System.currentTimeMillis() / 1000

    @Test
    fun `a 4k clip resolves at every rung of the ladder`() {
        // Big Buck Bunny, from Blender: Creative Commons, no age gate, no region block, and
        // published in every rendition up to 4K. If a clip resolves anywhere, it is this one.
        for (tier in listOf("uhd", "hd", "sd")) {
            val resolved = resolver.resolveDetailed("aqz-KE-bpKQ", now, listOf(tier))
            assertNotNull("$tier did not resolve", resolved)
            val playable = resolved!!.playable
            assertTrue("$tier video url is not googlevideo: ${playable.videoUrl.take(60)}",
                playable.videoUrl.contains("googlevideo.com"))
            assertNotNull("$tier came back with no audio", playable.audioUrl)
            assertTrue("$tier expiry is in the past", resolved.expiresAtSeconds > now)
            println("$tier -> expires in ${resolved.expiresAtSeconds - now}s")
        }
    }

    @Test
    fun `the ladder falls through to a rung a clip actually has`() {
        // Me at the zoo: 2005, 240p, and there is no 1080p rendition to be had. Asking for hd
        // first must not fail the clip - it must land on sd, which is the behaviour that keeps
        // the older material on the era channels playable.
        val resolved = resolver.resolveDetailed("jNQXAC9IVRw", now, listOf("uhd", "hd", "sd"))
        assertNotNull("a 240p clip should still resolve via the sd rung", resolved)
    }

    @Test
    fun `an id that does not exist returns null rather than throwing`() {
        // The caller's answer to "cannot resolve" is to skip the clip. An exception here would
        // take the player down instead, and dead ids are ordinary: the lineup is built nightly
        // and videos are removed between then and airtime.
        assertNull(resolver.resolveDetailed("aaaaaaaaaaa", now, listOf("hd", "sd")))
    }

    private fun assertNull(value: Any?) = assertTrue("expected null, got $value", value == null)
}
