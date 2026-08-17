package com.cliftonia.fs42tv.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Stream(
    val id: String? = null,
    val url: String,
    val duration: Int,
    val title: String = "",
)

@Serializable
data class Channel(
    val number: Int,
    val name: String,
    val kind: String,
    val rotation: String? = null,
    val streams: List<Stream> = emptyList(),
)

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
