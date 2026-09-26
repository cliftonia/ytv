package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.sync.Channel
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which Pluto channel a dial channel is.
 *
 * Read from the stream url rather than from [com.cliftonia.fs42tv.sync.LineupSource]: the Pluto
 * dial's every url is `https://jmp2.uk/plu-<24 hex>.m3u8` (built by `curation/build_pluto.py`),
 * and the id inside it is the one thing Pluto's guide is keyed on. Deciding by url also means a
 * channel is only ever treated as Pluto when there is an id to ask about - no id, no request.
 */
object PlutoIds {
    private val ID = Regex("""/plu-([0-9a-f]{24})\.m3u8""")

    fun idFrom(url: String): String? = ID.find(url)?.groupValues?.get(1)

    /**
     * The published `pluto.id` when the lineup carries one - it names the id curation actually
     * chose, UK or US - and otherwise the id inside the jmp2 url, which is how a dial published
     * before the field, and the Pluto-fed news channels on the YouTube dial, are recognised.
     */
    fun of(channel: Channel): String? =
        channel.pluto?.id ?: channel.streams.firstOrNull()?.url?.let(::idFrom)
}

/** One programme on a Pluto channel. Epoch milliseconds, so comparisons need no parsing. */
data class Programme(val title: String, val startMillis: Long, val stopMillis: Long)

/**
 * A channel's next six hours, and its logo.
 *
 * Immutable and cached whole: the guide is asked for once per programme at most, and everything
 * drawn - the banner's NOW, the guide row's NOW and NEXT - is read from this.
 */
data class PlutoSchedule(val programmes: List<Programme>, val logoUrl: String?) {

    /** The programme on air at [nowMillis]; a boundary belongs to the one starting. */
    fun onAt(nowMillis: Long): Programme? =
        programmes.firstOrNull { nowMillis >= it.startMillis && nowMillis < it.stopMillis }

    /** The first programme starting at or after the one on air ends. */
    fun nextAfter(nowMillis: Long): Programme? {
        val from = onAt(nowMillis)?.stopMillis ?: nowMillis
        return programmes.filter { it.startMillis >= from }.minByOrNull { it.startMillis }
    }

    /**
     * How long this answer stays true: until the programme on air ends, when NOW becomes the old
     * NEXT. Null when nothing is on air, so the caller applies its own short retry instead.
     */
    fun freshUntil(nowMillis: Long): Long? = onAt(nowMillis)?.stopMillis
}

/**
 * Pluto's public per-channel guide: `GET api.pluto.tv/v2/channels/<id>?start=&stop=`.
 *
 * Verified per-id from Australia on 23 Sep 2026: HTTP 200, about 10KB, a `timelines` array of
 * `{start, stop, title, episode: {name, description, ...}}` plus the channel's logos. Parsed with
 * kotlinx.serialization and `ignoreUnknownKeys`, which is the right tool here in a way `org.json`
 * is not (it is stubbed in JVM tests - see HANDOVER) and a hand-rolled regex would not be: the
 * reply nests series objects with their own `name` and `title`-like fields, so a regex would
 * match the wrong one.
 */
object PlutoApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Six hours: enough for NOW and NEXT on anything short of a film marathon. */
    private const val WINDOW_HOURS = 6L

    fun url(id: String, nowMillis: Long): String {
        // Whole seconds: the endpoint was verified with exactly this form, and Instant.toString
        // on a millisecond clock writes fractional seconds nobody has tested it with.
        val start = Instant.ofEpochMilli(nowMillis).truncatedTo(ChronoUnit.SECONDS)
        val stop = start.plus(WINDOW_HOURS, ChronoUnit.HOURS)
        return "https://api.pluto.tv/v2/channels/$id?start=$start&stop=$stop"
    }

    /**
     * The schedule in [text], or null when it is not one. Never throws: a guide is a courtesy,
     * and every failure here must degrade to the banner as it was before the guide existed.
     */
    fun parse(text: String): PlutoSchedule? {
        val wire = runCatching { json.decodeFromString(WireChannel.serializer(), text) }
            .getOrNull() ?: return null
        val programmes = wire.timelines.mapNotNull { entry ->
            val start = millisOf(entry.start) ?: return@mapNotNull null
            val stop = millisOf(entry.stop) ?: return@mapNotNull null
            val title = entry.title.trim().ifEmpty { entry.episode?.name?.trim().orEmpty() }
            if (title.isEmpty() || stop <= start) null else Programme(title, start, stop)
        }.sortedBy { it.startMillis }
        if (programmes.isEmpty()) return null
        // logo.path is served at 280x80 - small enough to hold a dozen in memory. The colour and
        // solid PNGs are full size and only a fallback.
        val logo = listOfNotNull(wire.logo, wire.colorLogoPNG, wire.solidLogoPNG)
            .firstNotNullOfOrNull { it.path?.takeIf { p -> p.startsWith("https://") } }
        return PlutoSchedule(programmes, logo)
    }

    /**
     * GET [url] with short timeouts, for the prefetch thread.
     *
     * Four seconds each way, not the resolver's ten and twenty: nothing waits on this. A slow
     * answer is worth less than none, since the banner it would decorate is gone after eight
     * seconds anyway. Capped at 512KB so a misbehaving endpoint cannot fill the heap of a 2.3GB
     * television; the real reply is about 10KB.
     */
    fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("pluto guide HTTP $code")
            return connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_BYTES)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun millisOf(iso: String?): Long? =
        iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private const val TIMEOUT_MILLIS = 4_000
    private const val MAX_BYTES = 512 * 1024

    @Serializable
    private data class WireChannel(
        val timelines: List<WireTimeline> = emptyList(),
        val logo: WireImage? = null,
        val colorLogoPNG: WireImage? = null,
        val solidLogoPNG: WireImage? = null,
    )

    @Serializable
    private data class WireTimeline(
        val start: String? = null,
        val stop: String? = null,
        val title: String = "",
        val episode: WireEpisode? = null,
    )

    @Serializable
    private data class WireEpisode(val name: String? = null)

    @Serializable
    private data class WireImage(val path: String? = null)
}
