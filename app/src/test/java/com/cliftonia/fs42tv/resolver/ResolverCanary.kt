package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The daily breakage alert's check: resolve a handful of real clips on the JVM, exactly as a
 * television with no accelerator would, and fail if too few come back.
 *
 * The same idea as [DeviceResolverLiveCheck] - run that by hand when the dial goes quiet - made
 * runnable unattended. That class stays `@Ignore`d so the README's instructions for it stay true;
 * this one is skipped (an assumption, so it reports as skipped rather than passing) unless
 * `YTV_CANARY=1` is set, which only `.github/workflows/canary.yml` does. The ordinary suite never
 * touches the network.
 *
 * Ids come from `YTV_CANARY_IDS` (comma-separated; the workflow picks them from channels.json),
 * with Big Buck Bunny and Me at the zoo as the fallback - the two clips the live check already
 * trusts to exist forever.
 */
class ResolverCanary {

    @Test
    fun `enough of today's clips resolve on the device`() {
        assumeTrue("set YTV_CANARY=1 to run the live canary", System.getenv("YTV_CANARY") == "1")
        val ids = CanaryVerdict.ids(System.getenv("YTV_CANARY_IDS"))
        val resolver = DeviceResolver(decoders = { DecoderSupport.EVERYTHING })
        val now = System.currentTimeMillis() / 1000
        val resolved = ids.filter { id ->
            val result = runCatching { resolver.resolveDetailed(id, now, listOf("hd", "sd")) }
                .getOrNull()
            println("canary $id -> ${result?.tier ?: "FAILED"}")
            result != null
        }
        println("canary: ${resolved.size} of ${ids.size} resolved")
        assertTrue("only ${resolved.size} of ${ids.size} resolved: ${ids - resolved.toSet()}",
            CanaryVerdict.passes(resolved.size, ids.size))
    }
}

/**
 * What counts as broken. A single removed or private clip is ordinary churn - the lineup is
 * rebuilt nightly and videos vanish between then and now - so one failure is not an alarm. Most
 * failing is: that is the extractor, not the clips.
 */
object CanaryVerdict {

    private val FALLBACK = listOf("aqz-KE-bpKQ", "jNQXAC9IVRw")
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** The ids to try, keeping only well-formed ones - the variable is data, never trusted. */
    fun ids(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { ID.matches(it) }.distinct()
            .ifEmpty { FALLBACK }

    fun passes(resolved: Int, total: Int): Boolean = total > 0 && resolved * 2 > total
}

/** The verdict's own rules, which run in the ordinary suite. */
class CanaryVerdictTest {

    @Test
    fun `one dead clip among several is churn, not an outage`() {
        assertTrue(CanaryVerdict.passes(resolved = 5, total = 6))
    }

    @Test
    fun `half or fewer resolving is an outage`() {
        assertFalse(CanaryVerdict.passes(resolved = 3, total = 6))
        assertFalse(CanaryVerdict.passes(resolved = 0, total = 2))
        assertFalse(CanaryVerdict.passes(resolved = 0, total = 0))
    }

    @Test
    fun `ids are read defensively`() {
        assertEquals(listOf("aqz-KE-bpKQ", "jNQXAC9IVRw"),
            CanaryVerdict.ids(" aqz-KE-bpKQ ,jNQXAC9IVRw,aqz-KE-bpKQ, \$(rm -rf),x"))
        assertEquals("nothing usable falls back to the two clips that always exist",
            listOf("aqz-KE-bpKQ", "jNQXAC9IVRw"), CanaryVerdict.ids(null))
    }
}
