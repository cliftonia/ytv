package com.cliftonia.fs42tv.ads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One compilation reel of vintage Australian TV commercials on the Internet Archive, as the
 * nightly job publishes it in `ads.json`: a progressive file and the second at which each
 * commercial in it starts ([cuts]).
 *
 * The cuts are what make a reel usable in a break at all: joined at a random second the viewer
 * gets half a jingle, joined at a cut they get a whole commercial and then the next, as a station
 * would have run them.
 */
@Serializable
data class AdReel(
    val id: String,
    val title: String = "",
    /** "70s", "80s" or "90s" - for the log; the app does not choose by it. */
    val era: String = "",
    /** https://archive.org/download/<id>/<file>.mp4 - public, token-free, safe to log. */
    val url: String,
    /** Seconds. */
    val duration: Double,
    /** Seconds from the start of the file at which a commercial starts, in any order. */
    val cuts: List<Double> = emptyList(),
) {

    /**
     * The cuts a break may start at: inside the file, and far enough from its end that a break
     * does not start on the reel's last commercial and roll straight off it. Sorted, distinct.
     */
    fun usableCuts(): List<Double> = cuts
        .filter { it.isFinite() && it >= 0.0 && it <= duration - MIN_RUN_SECONDS }
        .distinct()
        .sorted()

    /**
     * Playable at all: an id to remember it by, an https url (the app refuses cleartext off the
     * two home-server addresses - see network_security_config.xml), a length, and a cut to start at.
     */
    val usable: Boolean
        get() = id.isNotBlank() && url.startsWith("https://") && duration.isFinite() &&
            duration > 0.0 && usableCuts().isNotEmpty()

    companion object {
        /** A break is one to three minutes; a reel should have at least a minute left at the cut. */
        const val MIN_RUN_SECONDS = 60.0
    }
}

/** The whole of `ads.json`. [generated] is unix seconds. */
@Serializable
data class AdCatalog(val generated: Long = 0, val reels: List<AdReel> = emptyList()) {

    /** Only the reels a break can play - see [AdReel.usable]. */
    val usableReels: List<AdReel> get() = reels.filter { it.usable }

    companion object {
        // kotlinx, not org.json: org.json is stubbed in the JVM tests (see HANDOVER), and a parser
        // written with it passes every test without ever running. Unknown keys are ignored so the
        // server job can add fields without breaking televisions that have not updated.
        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        /** The catalog in [text], or null when it is not one - never throws. */
        fun parse(text: String?): AdCatalog? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}
