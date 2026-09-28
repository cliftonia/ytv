package com.cliftonia.fs42tv.sync

import kotlinx.serialization.SerialName
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
     * The LIVE TV dial's genre block and sub-block ("Movies", "Action"), published by
     * `curation/publish_live.py` - the guide's headings. Absent on the other dials, whose guide
     * has none.
     */
    val block: String? = null,
    val sub: String? = null,
    /**
     * How this channel's ad breaks are found: "cue" for a FAST feed whose breaks are marked in
     * the stream. Carried through the lineup only; absent means as before.
     */
    val breaks: String? = null,
    /**
     * Which service's guide lists this channel - samsung, plex, roku or xumo - and its id there:
     * the key into `fast_guide.json` (see `sync/FastGuide`). Absent for Pluto, which has its own.
     */
    val guide: String? = null,
    @SerialName("guide_id") val guideId: String? = null,
)

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
