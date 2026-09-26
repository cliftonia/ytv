package com.cliftonia.fs42tv.pluto

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pluto's own route, read request by request the way Media3's data source stack reads it on the
 * television - for when Media3 fails on it and mpv does not.
 *
 * Opt-in, like [com.cliftonia.fs42tv.resolver.ResolverCanary]: skipped unless `YTV_PLUTO_LIVE=1`.
 * Run: `YTV_PLUTO_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*PlutoRouteLiveCheck*' -i`.
 * `YTV_PLUTO_CHANNEL` picks the channel (default 70s Cinema), `YTV_PLUTO_SESSION` the session
 * source (default the home server's us session).
 *
 * Why not Media3 itself: DefaultHttpDataSource builds its URL from android.net.Uri, which is a stub
 * on the JVM. So each request is made as DefaultHttpDataSource makes it - HttpURLConnection,
 * same-protocol redirects followed, `Accept-Encoding: identity`, and the bounded
 * `Range: bytes=0-50331647` ChunkedDataSource asks for - and every answer is printed: status,
 * where it landed (host and path only - the query carries the jwt), Content-Range, length.
 *
 * Measured from a Mac on 26 Sep 2026: every request answers (200 from the stitcher, which ignores
 * Range; 206 from the CDN) - NOT reproduced on the JVM. The TLS difference is recorded in
 * ChannelPlayer's source-failure line on the television instead; see SourceFailure.
 */
class PlutoRouteLiveCheck {

    private fun get(url: String, maxBytes: Int = 64 * 1024): Pair<String, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=0-50331647")
        }
        try {
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.use { input ->
                val bytes = input.readNBytes(maxBytes)
                String(bytes, Charsets.ISO_8859_1)
            }.orEmpty()
            println("  $code ${redact(c.url.toString())} range=${c.getHeaderField("Content-Range")} " +
                "type=${c.contentType} len=${c.getHeaderField("Content-Length")} read=${body.length}")
            assertTrue("HTTP $code for ${redact(url)}", code in 200..299)
            return c.url.toString() to body
        } catch (e: Exception) {
            println("  FAILED ${redact(url)}: ${generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") {
                "${it.javaClass.simpleName}(${it.message})" }}")
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun redact(url: String) = url.substringBefore('?')

    private fun absolute(base: String, uri: String) = URL(URL(base), uri).toString()

    @Test
    fun `every request of a direct Pluto tune answers`() {
        assumeTrue("set YTV_PLUTO_LIVE=1 to read Pluto's own route", System.getenv("YTV_PLUTO_LIVE") == "1")
        val channel = System.getenv("YTV_PLUTO_CHANNEL") ?: "5f4d878d3d19b30007d2e782"
        val sessionUrl = System.getenv("YTV_PLUTO_SESSION") ?: PlutoBoot.serverUrl(PlutoBoot.SERVERS[1], "us")
        val session = PlutoBoot.parseServer(URL(sessionUrl).readText()) ?: error("no session from $sessionUrl")

        println("master:")
        val (masterAt, master) = get(session.masterUrl(channel))
        val lines = master.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val media = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map(HlsMaster::attributes)
        val variantUri = lines[lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") } + 1]

        println("variant:")
        val (variantAt, variant) = get(absolute(masterAt, variantUri))
        media.filter { it["URI"] != null }.forEach { rendition ->
            println("${rendition["TYPE"]} ${rendition["NAME"]} (default ${rendition["DEFAULT"] ?: "NO"}):")
            get(absolute(masterAt, rendition["URI"]!!))
        }
        val segment = variant.lines().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("#") }
        println("segment:")
        get(absolute(variantAt, segment), maxBytes = 4096)
        Regex("""#EXT-X-KEY:[^\n]*URI="([^"]+)"""").find(variant)?.let { key ->
            println("key:")
            get(absolute(variantAt, key.groupValues[1]))
        }
    }
}
