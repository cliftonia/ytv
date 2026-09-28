package com.cliftonia.fs42tv.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Stream(
    val id: String? = null,
    val url: String,
    val duration: Int,
    val title: String = "",
    /**
     * Stretches of the file to jump over - sponsor reads, self-promotion, "like and subscribe" -
     * as `[startSeconds, endSeconds]` pairs from SponsorBlock, looked up by the nightly job.
     * Absent on every clip SponsorBlock knows nothing about. [duration] stays the raw length;
     * what is actually watched is worked out in `schedule/Skips`.
     */
    val skip: List<List<Double>> = emptyList(),
    /**
     * The parts of the day this clip belongs to - "breakfast", "afternoon", "prime", "late" -
     * for channels curated with time-of-day mixes. Absent on every clip of every other channel,
     * which then draws from all its clips at every hour. See `schedule/HalfHourSchedule`.
     */
    val parts: List<String> = emptyList(),
    /**
     * Where a live feed must be fetched from - "us" for a FAST channel that answers only a US
     * address, played through the home server's relay (see `relay/FastRelay`). Absent on every
     * stream that plays as published.
     */
    val route: String? = null,
)

@Serializable
data class Channel(
    val number: Int,
    val name: String,
    val kind: String,
    val rotation: String? = null,
    val streams: List<Stream> = emptyList(),
    /**
     * Episodes must air in list order - sitcoms, anime, cartoons, the Series files. On the
     * half-hour schedule their gaps are filled with the next episodes, never with others.
     */
    val ordered: Boolean = false,
    /**
     * Which Pluto channel this is, and which country's playlist it was found in - published by
     * `curation/build_pluto.py` for every channel on the Pluto dial, absent everywhere else. The
     * app plays it through Pluto's own session route; the stream url stays as the fallback.
     */
    val pluto: PlutoRef? = null,
    /**
     * Whether a FAST channel's ad breaks get the station's own commercials or card (see
     * `fast/CueBreaks`). `false` opts a channel out - a music channel (Stingray) whose cues mark
     * no break worth covering. Absent, or anything that is not a clear no, is on: see
     * [LenientFlag].
     */
    @Serializable(with = LenientFlag::class)
    val breaks: Boolean = true,
)

/**
 * A flag read the way a hand-edited lineup writes it: `false`, `"false"`, `"no"`, `"off"` and `0`
 * are off; everything else - true, null, a typo - is on. Never throws: one odd value must not
 * cost every channel on the dial its parse.
 */
object LenientFlag : kotlinx.serialization.KSerializer<Boolean> {

    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "LenientFlag", kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN)

    private val OFF = setOf("false", "no", "off", "0")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Boolean {
        val element = (decoder as? kotlinx.serialization.json.JsonDecoder)?.decodeJsonElement()
            ?: return runCatching { decoder.decodeBoolean() }.getOrDefault(true)
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive ?: return true
        if (primitive is kotlinx.serialization.json.JsonNull) return true
        return primitive.content.trim().lowercase() !in OFF
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Boolean) =
        encoder.encodeBoolean(value)
}

/**
 * A Pluto channel id and its home region, "uk" or "us".
 *
 * The region matters because some channels only show programmes to a session from their own
 * country - to anyone else they loop Pluto's logo bumper. Nullable so an entry without one still
 * parses: it simply gets the television's own session.
 */
@Serializable
data class PlutoRef(val id: String, val region: String? = null)

@Serializable
data class Dial(val generated: Long = 0, val channels: List<Channel> = emptyList())

@Serializable
data class Tier(val video: String, val audio: String? = null, val expires: Long = 0)

@Serializable
data class UrlCache(
    val generated: Long = 0,
    val urls: Map<String, Map<String, Tier>> = emptyMap(),
)

/**
 * The wire format published by the server.
 *
 * `ignoreUnknownKeys` is deliberate: the server must be able to add a field without
 * breaking every app already installed on a television, where updating means sideloading
 * an APK by hand.
 */
object DialContract {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseDial(text: String): Dial = json.decodeFromString(Dial.serializer(), text)

}
