package com.cliftonia.fs42tv.resolver

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

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
 *
 * Every failure is printed with the reason the extractor gave, and the run ends with one
 * `canary-verdict: ok|blocked|broken` line, which is what the workflow acts on (see
 * [CanaryVerdict.verdict]). For two months the canary said only "FAILED" while the runner's IP was
 * being bot-checked and the televisions played on; the reason is the difference.
 */
class ResolverCanary {

    @Test
    fun `enough of today's clips resolve on the device`() {
        assumeTrue("set YTV_CANARY=1 to run the live canary", System.getenv("YTV_CANARY") == "1")
        val ids = CanaryVerdict.ids(System.getenv("YTV_CANARY_IDS"))
        val errors = mutableMapOf<String, Throwable>()
        val resolver = DeviceResolver(
            decoders = { DecoderSupport.EVERYTHING },
            onExtractionFailure = { id, error -> errors[id] = error },
        )
        val now = System.currentTimeMillis() / 1000
        val outcomes = ids.map { id ->
            val result = runCatching { resolver.resolveDetailed(id, now, listOf("hd", "sd")) }
                .onFailure { errors[id] = it }
                .getOrNull()
            if (result != null) {
                println("canary $id -> ${result.tier}")
                null
            } else {
                val error = errors[id]
                val kind = CanaryVerdict.classify(error)
                println("canary $id -> FAILED [${kind.label}] ${CanaryVerdict.describe(error)}")
                kind
            }
        }
        val failures = outcomes.filterNotNull()
        val resolved = ids.size - failures.size
        val verdict = CanaryVerdict.verdict(resolved, ids.size, failures)
        println("canary: $resolved of ${ids.size} resolved (" +
            CanaryVerdict.Failure.entries.joinToString(", ") { kind ->
                "${failures.count { it == kind }} ${kind.label}"
            } + ")")
        println("canary-verdict: ${verdict.label}")
        assertTrue("only $resolved of ${ids.size} resolved; verdict ${verdict.label}",
            verdict == CanaryVerdict.Verdict.OK)
    }
}

/**
 * What counts as broken. A single removed or private clip is ordinary churn - the lineup is
 * rebuilt nightly and videos vanish between then and now - so one failure is not an alarm. Most
 * failing is: that is the extractor, not the clips - unless YouTube said why, and the why was
 * "prove you are not a bot", which is about the machine asking rather than the code.
 */
object CanaryVerdict {

    private val FALLBACK = listOf("aqz-KE-bpKQ", "jNQXAC9IVRw")
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Why one clip did not resolve. */
    enum class Failure(val label: String) {
        /** YouTube refused the asker: a bot check, a captcha, a 429. Says nothing about the code. */
        BLOCKED("blocked"),

        /** The clip itself is gone or fenced off: private, removed, age or region restricted. */
        UNAVAILABLE("unavailable"),

        /** Anything else - a parse error, a signature that will not decipher, no usable stream. */
        BROKEN("broken"),
    }

    enum class Verdict(val label: String) { OK("ok"), BLOCKED("blocked"), BROKEN("broken") }

    /** The ids to try, keeping only well-formed ones - the variable is data, never trusted. */
    fun ids(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { ID.matches(it) }.distinct()
            .ifEmpty { FALLBACK }

    fun passes(resolved: Int, total: Int): Boolean = total > 0 && resolved * 2 > total

    /**
     * Which kind of failure [error] is, looking down the cause chain. No exception at all means
     * the extraction "succeeded" with nothing usable in it (no audio, no stream in any tier),
     * which is the extractor misreading YouTube - broken.
     *
     * The bot check is matched by its message as well as its class, because the extractor does
     * not always get to throw the dedicated exception: YouTube's wording sometimes arrives inside
     * an ordinary unavailable or parsing error.
     */
    fun classify(error: Throwable?): Failure {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        if (chain.isEmpty()) return Failure.BROKEN
        if (chain.any { it is SignInConfirmNotBotException || it is ReCaptchaException }) {
            return Failure.BLOCKED
        }
        if (chain.any { BOT_CHECK.containsMatchIn(it.message.orEmpty()) }) return Failure.BLOCKED
        if (chain.any { it is ContentNotAvailableException }) return Failure.UNAVAILABLE
        return Failure.BROKEN
    }

    /**
     * The day's verdict. Enough resolving is ok whatever else happened. Otherwise it is blocked
     * only when nothing failed for a reason that points at the code - a single broken clip among
     * the refusals means the extractor cannot be cleared, and an unclear morning is reported as
     * the outage rather than excused as the IP. That is the safe direction: one false alarm is a
     * nuisance, a quiet dial nobody hears about is the thing this exists to prevent.
     */
    fun verdict(resolved: Int, total: Int, failures: List<Failure>): Verdict = when {
        passes(resolved, total) -> Verdict.OK
        Failure.BLOCKED in failures && Failure.BROKEN !in failures -> Verdict.BLOCKED
        else -> Verdict.BROKEN
    }

    /**
     * One printable line for the log: the exception's class and message. Urls lose their query
     * (signed stream urls carry tokens), "::" is broken up so a message cannot pose as a GitHub
     * workflow command, backticks go so it cannot break out of the job summary's code block, and
     * it is kept short.
     */
    fun describe(error: Throwable?): String {
        if (error == null) return "no exception: extraction returned nothing playable"
        val root = generateSequence(error) { it.cause }.take(8).last()
        val text = buildString {
            append(error::class.java.simpleName).append(": ").append(error.message.orEmpty())
            if (root !== error) append(" <- ").append(root::class.java.simpleName)
                .append(": ").append(root.message.orEmpty())
        }
        return text.replace(URL_QUERY, "$1")
            .replace(Regex("\\s+"), " ")
            .replace("::", ": :")
            .replace("`", "'")
            .take(MAX_DESCRIPTION)
    }

    private val BOT_CHECK = Regex("not a bot|sign in to confirm|recaptcha", RegexOption.IGNORE_CASE)
    private val URL_QUERY = Regex("(https?://[^\\s?#]+)[?#]\\S*")
    private const val MAX_DESCRIPTION = 240
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

    @Test
    fun `a bot check or captcha is the asker being refused`() {
        val blocked = CanaryVerdict.Failure.BLOCKED
        assertEquals(blocked, CanaryVerdict.classify(SignInConfirmNotBotException("no")))
        assertEquals(blocked, CanaryVerdict.classify(ReCaptchaException("429", "https://x")))
        assertEquals("found down the cause chain",
            blocked, CanaryVerdict.classify(IOException("wrapped", ReCaptchaException("c", "u"))))
        assertEquals("found by YouTube's wording inside an ordinary error", blocked,
            CanaryVerdict.classify(ContentNotAvailableException(
                "Sign in to confirm you're not a bot")))
    }

    @Test
    fun `a fenced-off clip is churn and anything else is the extractor`() {
        assertEquals(CanaryVerdict.Failure.UNAVAILABLE,
            CanaryVerdict.classify(AgeRestrictedContentException("age")))
        assertEquals(CanaryVerdict.Failure.BROKEN,
            CanaryVerdict.classify(ParsingException("Could not find deobfuscation function")))
        assertEquals("nothing thrown but nothing playable is the extractor misreading",
            CanaryVerdict.Failure.BROKEN, CanaryVerdict.classify(null))
    }

    @Test
    fun `a failing morning is excused as the IP only when nothing points at the code`() {
        val b = CanaryVerdict.Failure.BLOCKED
        val u = CanaryVerdict.Failure.UNAVAILABLE
        val x = CanaryVerdict.Failure.BROKEN
        assertEquals(CanaryVerdict.Verdict.OK, CanaryVerdict.verdict(5, 6, listOf(x)))
        assertEquals(CanaryVerdict.Verdict.BLOCKED, CanaryVerdict.verdict(0, 6, List(6) { b }))
        assertEquals(CanaryVerdict.Verdict.BLOCKED,
            CanaryVerdict.verdict(1, 6, listOf(b, b, b, b, u)))
        assertEquals(CanaryVerdict.Verdict.BROKEN,
            CanaryVerdict.verdict(0, 6, listOf(b, b, b, b, b, x)))
        assertEquals(CanaryVerdict.Verdict.BROKEN, CanaryVerdict.verdict(0, 6, List(6) { u }))
        assertEquals(CanaryVerdict.Verdict.BROKEN, CanaryVerdict.verdict(0, 0, emptyList()))
    }

    @Test
    fun `descriptions keep the reason and drop what should not be printed`() {
        val line = CanaryVerdict.describe(IOException(
            "request failed: https://rr1.googlevideo.com/videoplayback?sig=SECRET&n=x",
            ReCaptchaException("reCaptcha challenge requested", "u")))
        assertTrue(line, line.startsWith("IOException: request failed: " +
            "https://rr1.googlevideo.com/videoplayback <- ReCaptchaException"))
        assertFalse(line, line.contains("SECRET"))
        assertFalse(CanaryVerdict.describe(ParsingException("::error::x `y`")).contains("::"))
        assertTrue(CanaryVerdict.describe(ParsingException("z".repeat(1000))).length <= 240)
    }
}
