package com.cliftonia.fs42tv.pluto

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One Pluto session: where its streams are stitched, and the token that admits them.
 *
 * Why the dial needs one at all: the Pluto dial's urls are iptv-org's `jmp2.uk/plu-<id>.m3u8`,
 * which redirect to Samsung TV Plus's partner stitcher - and for many channels, in every region,
 * that route serves nothing but Pluto's logo bumper on a loop (iptv-org issue #52645). Pluto's own
 * stitcher, reached with a session from its boot service, plays the same channels. It is plain
 * AES-128 HLS, no DRM, so both engines play it as they play any live feed.
 *
 * Pluto allows ONE stream per session; a second stream on the same token ends the first. Hence
 * [PlutoSessions] keeping a separate session for anything playing beside the dial.
 */
data class PlutoSession(
    val stitcher: String,
    /** A ready-made query string - device, app and session ids - passed through untouched. */
    val stitcherParams: String,
    val jwt: String,
    /** Pluto's name for the session's country - "AU", "GB", "US" - for the diagnostics row. */
    val region: String,
    /** Wall-clock epoch milliseconds. The home server states it; a local boot is given three hours. */
    val expiresAtMillis: Long,
) {
    /** The master playlist for [channelId] on this session - the url the player is handed. */
    fun masterUrl(channelId: String): String =
        "$stitcher/v2/stitch/hls/channel/$channelId/master.m3u8?$stitcherParams&jwt=$jwt" +
            "&masterJWTPassthrough=true&includeExtendedEvents=true"
}

/**
 * Getting a [PlutoSession]: anonymously from Pluto's boot service, the way its own web player
 * does, or from the home server, which holds one per country for channels that only show their
 * programmes to a session from home.
 *
 * Parsing is pure and never throws, and the two fetches are plain blocking calls - the caller
 * decides the thread. Parsed with kotlinx.serialization for the reason [PlutoApi] gives: org.json
 * is stubbed in JVM tests, and a parser written with it passes tests without ever running.
 */
object PlutoBoot {

    /**
     * The home server's two addresses for one machine, LAN first - the same order, and the same
     * reasons, as `AcceleratedResolver.RESOLVE_SERVERS`: at home the LAN answers in milliseconds;
     * the tailnet is the same box for anything that can reach it; the car reaches neither and
     * must not care. `network_security_config.xml` already allows cleartext to both hosts.
     */
    val SERVERS = listOf(
        "http://192.168.4.58:4246",
        "http://100.74.3.68:4246",
    )

    /**
     * How long a local session is trusted. Maintained tools cache Pluto's for four hours and the
     * JWT itself runs longer; three leaves margin against a token Pluto retires early.
     */
    const val LOCAL_LIFETIME_MILLIS = 3 * 3_600_000L

    /**
     * Pluto's boot service, asked as the desktop web player - the identity FastChannels and the
     * other maintained tools use, and the one whose stitcher answers are plain HLS. A fresh
     * random [clientId] per session: an anonymous device, as the web player is on a first visit.
     */
    fun bootUrl(clientId: String): String =
        "https://boot.pluto.tv/v4/start?appName=web&appVersion=9.1.0&deviceVersion=122.0.0" +
            "&deviceModel=web&deviceMake=chrome&deviceType=web&clientModelNumber=1.0.0" +
            "&serverSideAds=false&drmCapabilities=&clientID=$clientId"

    fun serverUrl(base: String, region: String): String = "$base/pluto/session?region=$region"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A boot reply as a local session issued at [nowMillis], or null when it is not one. */
    fun parseBoot(text: String, nowMillis: Long): PlutoSession? {
        val wire = runCatching { json.decodeFromString(WireBoot.serializer(), text) }.getOrNull()
            ?: return null
        return session(
            stitcher = wire.servers?.stitcher,
            params = wire.stitcherParams,
            jwt = wire.sessionToken,
            region = wire.session?.activeRegion ?: "LOCAL",
            expiresAtMillis = nowMillis + LOCAL_LIFETIME_MILLIS,
        )
    }

    /** The home server's reply, with the expiry it states, or null when it is not one. */
    fun parseServer(text: String): PlutoSession? {
        val wire = runCatching { json.decodeFromString(WireServer.serializer(), text) }.getOrNull()
            ?: return null
        val expires = wire.expiresAt ?: return null
        return session(wire.stitcher, wire.stitcherParams, wire.jwt, wire.region ?: "?", expires * 1000)
    }

    private fun session(
        stitcher: String?,
        params: String?,
        jwt: String?,
        region: String,
        expiresAtMillis: Long,
    ): PlutoSession? {
        if (stitcher == null || params.isNullOrEmpty() || jwt.isNullOrEmpty()) return null
        if (!trustedStitcher(stitcher)) return null
        return PlutoSession(stitcher.trimEnd('/'), params, jwt, region.uppercase(), expiresAtMillis)
    }

    /**
     * Https on Pluto's own domain, exact or a real subdomain - curation's rule for playlist urls,
     * for the same reason: whatever this names is handed straight to the player on every Pluto
     * channel, and `fakepluto.tv` ends in the right letters.
     */
    private fun trustedStitcher(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase() ?: return false
        return parsed.scheme == "https" && (host == "pluto.tv" || host.endsWith(".pluto.tv"))
    }

    /**
     * The connection itself could not be made - nothing was said, as opposed to a slow or
     * unhelpful answer. Only this moves [fetchFromServer] on to the next address.
     */
    class Unreachable(cause: Throwable) : java.io.IOException(cause)

    /**
     * Whether [failure] is a network that is not up YET: no DNS, "network unreachable", no
     * route, a refused connection - what a television just woken from standby sees for its first
     * seconds, all fast. A connect TIMEOUT is not: that is an address with nothing behind it,
     * which is the car, and it stays that way.
     */
    fun isTransient(failure: Throwable): Boolean {
        val cause = if (failure is Unreachable) failure.cause ?: failure else failure
        return when (cause) {
            is java.net.UnknownHostException -> true
            // ConnectException and NoRouteToHostException are SocketExceptions, as is ENETUNREACH.
            is java.net.SocketException -> true
            else -> false
        }
    }

    /** A fresh anonymous session from Pluto itself. Blocking; throws when Pluto cannot be asked. */
    fun fetchBoot(nowMillis: Long): PlutoSession? = parseBoot(
        httpGet(bootUrl(java.util.UUID.randomUUID().toString()), BOOT_CONNECT_MILLIS, BOOT_READ_MILLIS),
        nowMillis)

    /**
     * The home server's session for [region]; null when it answered without one. Blocking, and
     * on the tune thread, so it is kept short: the second address is the SAME machine, so it is
     * only tried when the first could not even be connected to - a slow or refusing answer would
     * be no better from the other network. Throws [Unreachable] when neither address connected,
     * which [PlutoSessions] takes as the server being away for every region at once.
     */
    fun fetchFromServer(
        region: String,
        get: (String) -> String = { httpGet(it, SERVER_CONNECT_MILLIS, SERVER_READ_MILLIS) },
    ): PlutoSession? {
        var unreachable: Unreachable? = null
        for (base in SERVERS) {
            val body = try {
                get(serverUrl(base, region))
            } catch (e: Unreachable) {
                unreachable = e
                continue
            } catch (e: java.io.IOException) {
                return null
            }
            return parseServer(body)
        }
        throw unreachable ?: Unreachable(java.io.IOException("no pluto session server"))
    }

    private fun httpGet(url: String, connectMillis: Int, readMillis: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMillis
            readTimeout = readMillis
            setRequestProperty("Accept", "application/json")
            // A desktop Chrome, as the web player is: the boot service's answer depends on who
            // it believes is asking. Harmless to the home server.
            setRequestProperty("User-Agent", DESKTOP_CHROME)
        }
        try {
            // Connected explicitly, so a failure to connect is told apart from a bad answer.
            try {
                connection.connect()
            } catch (e: java.io.IOException) {
                throw Unreachable(e)
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("pluto session HTTP $code")
            // Capped like the guide fetch, so a misbehaving endpoint cannot fill the heap of a
            // 2.3GB television; a real boot reply is about 8KB.
            return connection.inputStream.use { input ->
                String(input.readNBytesCapped(MAX_BYTES), Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCapped(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private const val DESKTOP_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /**
     * Pluto over the internet. A tune - and every surf queued behind it - waits on this, though
     * only once per three hours, so it is held to seven seconds at the very worst.
     */
    private const val BOOT_CONNECT_MILLIS = 3_000
    private const val BOOT_READ_MILLIS = 4_000

    /** A machine on the LAN or the tailnet answers in well under this, or is not there. */
    private const val SERVER_CONNECT_MILLIS = 1_000

    /**
     * The server answered well inside a second when measured (26 Sep 2026); two seconds is a
     * server in trouble, and the channel is better off on this television's own session.
     */
    private const val SERVER_READ_MILLIS = 2_000

    private const val MAX_BYTES = 256 * 1024

    @Serializable
    private data class WireBoot(
        val servers: WireServers? = null,
        val session: WireBootSession? = null,
        val stitcherParams: String? = null,
        val sessionToken: String? = null,
    )

    @Serializable
    private data class WireServers(val stitcher: String? = null)

    @Serializable
    private data class WireBootSession(val activeRegion: String? = null)

    @Serializable
    private data class WireServer(
        val stitcher: String? = null,
        val stitcherParams: String? = null,
        val jwt: String? = null,
        val region: String? = null,
        val expiresAt: Long? = null,
    )
}
