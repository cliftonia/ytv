package com.cliftonia.fs42tv.pluto

import android.util.Log
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.sync.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * Which url a Pluto channel is played from.
 *
 * DIRECT (the default) is Pluto's own stitcher on a session from [PlutoSessions]; LEGACY is the
 * published jmp2 url, exactly as the dial played before sessions existed. LEGACY stays reachable
 * three ways: the Settings row, a channel for which no session can be had, and a channel whose
 * direct stream failed even on a rebuilt session - that one sits out [FALLBACK_MILLIS] on the old
 * url rather than failing, rebuilding and failing again for as long as it is watched.
 *
 * Blocking - a session may have to be fetched - so [forDial] runs on the tune thread and
 * [forBeside] on the prefetch thread. [playbackFailed] is the player's error callback, on the UI
 * thread; it only touches maps and a volatile, and never the network.
 */
class PlutoRoute(
    private val sessions: PlutoSessions,
    /** The PLUTO ROUTE row: true for DIRECT. Read per tune, so a flip lands on the next one. */
    private val direct: () -> Boolean,
    private val nowMillis: () -> Long,
    /** Where the dial's route is told to the diagnostics - RESOLVED BY in Settings. */
    private val report: (String) -> Unit,
) {

    /** The last direct stream handed to the dial, so a failure can be traced to its session. */
    private class Pick(val url: String, val channelId: String, val session: PlutoSession)

    @Volatile private var lastDial: Pick? = null

    /** When each channel's session was last rebuilt after a failure. */
    private val rebuiltAt = ConcurrentHashMap<String, Long>()

    /** Channels sitting out on the legacy url, and until when. */
    private val legacyUntil = ConcurrentHashMap<String, Long>()

    /**
     * What the dial plays for live [channel], whose published playable is [legacy]. Anything that
     * is not a Pluto channel - Euronews on the YouTube dial is, by its url; a plain news feed is
     * not - comes back as [legacy] itself.
     */
    fun forDial(channel: Channel, legacy: Playable): Playable {
        val id = PlutoIds.of(channel) ?: return legacy
        if (!direct()) {
            lastDial = null
            report("PLUTO LEGACY - SETTING")
            return legacy
        }
        if (legacy !is Hls) return legacy
        val now = nowMillis()
        val until = legacyUntil[id]
        if (until != null && now < until) {
            lastDial = null
            return legacyBecause(channel, "DIRECT FAILED", legacy)
        }
        legacyUntil.remove(id)
        val choice = sessions.forDial(channel.pluto?.region)
            ?: return legacyBecause(channel, "NO SESSION", legacy).also { lastDial = null }
        val url = choice.session.masterUrl(id)
        lastDial = Pick(url, id, choice.session)
        val where = if (choice.fromServer) "HOME SERVER" else "THIS TV"
        report("PLUTO ${choice.session.region} - $where")
        Log.i("fs42", "pluto: ${channel.number} ${channel.name} direct on the " +
            "${choice.session.region} session from ${where.lowercase()}")
        return Hls(url)
    }

    /**
     * What a player running beside the dial - the guide music - plays for [channel]: the direct
     * route on a session of its own, so it can never end the dial's stream. Not reported: the
     * diagnostics row describes the dial.
     */
    fun forBeside(channel: Channel, legacy: Playable): Playable {
        if (!direct() || legacy !is Hls) return legacy
        val id = PlutoIds.of(channel) ?: return legacy
        val session = sessions.beside() ?: return legacy
        return Hls(session.masterUrl(id))
    }

    /**
     * The dial's player failed on [playable]. If it was the last direct stream, the first failure
     * rebuilds its session - a refused or retired token is the likely cause, and a new one costs
     * one boot - and a second within [REBUILD_WINDOW_MILLIS] puts the channel on its legacy url
     * for [FALLBACK_MILLIS]. The re-tune that follows every playback error does the rest.
     */
    fun playbackFailed(playable: Playable?) {
        val pick = lastDial ?: return
        if ((playable as? Hls)?.url != pick.url) return
        lastDial = null
        val now = nowMillis()
        val rebuilt = rebuiltAt[pick.channelId]
        if (rebuilt != null && now - rebuilt < REBUILD_WINDOW_MILLIS) {
            rebuiltAt.remove(pick.channelId)
            legacyUntil[pick.channelId] = now + FALLBACK_MILLIS
            Log.w("fs42", "pluto: ${pick.channelId} failed on a rebuilt session; legacy url for a while")
        } else {
            rebuiltAt[pick.channelId] = now
            sessions.invalidate(pick.session)
            Log.w("fs42", "pluto: ${pick.channelId} failed; rebuilding the ${pick.session.region} session")
        }
    }

    private fun legacyBecause(channel: Channel, why: String, legacy: Playable): Playable {
        report("PLUTO LEGACY - $why")
        Log.i("fs42", "pluto: ${channel.number} ${channel.name} on the legacy url ($why)")
        return legacy
    }

    companion object {
        /** A second failure this soon after a rebuild is the channel, not the token. */
        const val REBUILD_WINDOW_MILLIS = 10 * 60_000L

        /** How long a channel that failed direct twice plays its legacy url before trying again. */
        const val FALLBACK_MILLIS = 30 * 60_000L
    }
}
