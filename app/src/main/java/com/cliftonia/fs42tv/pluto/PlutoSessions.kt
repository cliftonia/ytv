package com.cliftonia.fs42tv.pluto

import java.util.concurrent.ConcurrentHashMap

/**
 * The Pluto sessions this television holds, and which one a stream plays on.
 *
 * Three kinds, kept apart on purpose:
 *  - REGION sessions per country ("uk", "us"), from the home server - some channels show their
 *    programmes only to a session from home, and loop Pluto's logo bumper for anyone else;
 *  - the dial's LOCAL sessions, booted anonymously from this television (so, from Australia), for
 *    channels without a region and whenever the server cannot be reached - the car, always;
 *  - a separate local session for anything playing BESIDE the dial - the guide music can be a
 *    Pluto channel. Pluto allows one stream per session, so music on the dial's token would end
 *    the programme under the guide. The home server hands out one session per caller per region,
 *    so the player beside the dial can never share a region session either.
 *
 * The dial's region and local sessions are each a [SessionPool] of [poolSize] slots: slot 0 is
 * the dial's session exactly as it always was, and the others let the channels either side of the
 * screen have their masters read ahead on sessions of their own - see [SessionPool] for why one
 * session can never carry two channels, and for the rotation. A region's other slots ask the home
 * server under their own client name ([slotServer]); a pool the server hands one session twice
 * stops rotating rather than share it.
 *
 * Each session is kept until [REFRESH_MARGIN_MILLIS] before it expires; see [SessionSlot] for the
 * fetch locks and why [invalidate] takes none. [forDial], [forChannel], [lease] and [beside]
 * block; callers are the tune and prefetch threads, never the UI thread.
 */
class PlutoSessions(
    /** A fresh anonymous session from Pluto's boot service; may throw. */
    private val boot: () -> PlutoSession?,
    /**
     * The home server's session for a region. Null means the server answered without one; a
     * throw means it could not be reached, which retires the server for every region at once.
     */
    private val server: (region: String) -> PlutoSession?,
    /**
     * The same, asking the server for a NEW session rather than the one it cached for this
     * television (`fresh=1`). Asked after [invalidate]: the server keeps one session per caller,
     * so a plain ask after a failure handed back the very token that had just failed and the
     * rebuild changed nothing for any region channel.
     */
    private val freshServer: (region: String) -> PlutoSession? = server,
    /** Wall-clock milliseconds: expiries are stated in wall-clock time. */
    private val nowMillis: () -> Long,
    /**
     * Whether a failure is the network not being there YET - a television just woken, whose
     * DNS and Wi-Fi come back seconds later - rather than a server that is not there at all.
     */
    private val transient: (Throwable) -> Boolean = PlutoBoot::isTransient,
    /** Sessions per dial pool; 1 is the single dial session of before - see [SessionPool]. */
    private val poolSize: Int = 1,
    /** The home server's session for a region's slot 1 and up, under that slot's client name. */
    private val slotServer: (region: String, slot: Int, fresh: Boolean) -> PlutoSession? = { _, _, _ -> null },
) {

    /** A session for the dial, and whether the home server supplied it - for the diagnostics. */
    class Choice(val session: PlutoSession, val fromServer: Boolean)

    // A boot is always a new session; only the home server needs telling.
    private val local = pool("local", { _, _ -> boot() }) { BOOT_MISS_RETRY_MILLIS }
    private val besideLocal = SessionSlot({ boot() }, { BOOT_MISS_RETRY_MILLIS }, nowMillis)
    private val regions = ConcurrentHashMap<String, SessionPool>()

    /** The slot on screen - the one the last tune took - from whichever pool. */
    @Volatile private var current: SessionSlot? = null

    /**
     * Until when the home server is not asked for ANY region. One unreachable server is
     * unreachable for both regions, and paying its timeout once per region doubled the wait.
     */
    @Volatile private var serverDownUntil = 0L

    /**
     * The session a dial channel from [region] would play on: that region's, when the server
     * answers, else this television's own. Null only when neither can be had - the caller then
     * plays the channel's published url exactly as before sessions existed. Slot 0 of each pool,
     * and nothing claimed: for asking whether a session can be had at all.
     */
    fun forDial(region: String?): Choice? {
        if (region != null) {
            val slot = regionPool(region).slots[0]
            regionSession(slot)?.let { return Choice(it, fromServer = true) }
        }
        return local.slots[0].get()?.let { Choice(it, fromServer = false) }
    }

    /**
     * The session a tune of [channelId] from [region] plays on, as [forDial] chooses between the
     * region and the local pool - and within the pool, the slot [SessionPool.pick] names: the one
     * its master was read ahead on, when [rotate]. That slot is then on screen, claimed by the
     * channel. [rotate] false (Media3, nothing read ahead) is slot 0, the dial's session as before.
     */
    fun forChannel(region: String?, channelId: String, rotate: Boolean): Choice? {
        if (region != null) {
            val pool = regionPool(region)
            val slot = pool.pick(channelId, rotate, current)
            val session = regionSession(slot)
            if (session != null) return Choice(take(pool, slot, session, channelId), fromServer = true)
            pool.abandon(slot)
        }
        val slot = local.pick(channelId, rotate, current)
        val session = slot.get() ?: run {
            local.abandon(slot)
            return null
        }
        return Choice(take(local, slot, session, channelId), fromServer = false)
    }

    /**
     * A session to read [channelId]'s master ahead on, never the one on screen and never one
     * carrying a channel in [keep] - or null when none can be had cheaply: a region while the
     * server is away, a region slot being rebuilt (a fresh server session takes ~2s), a pool that
     * does not rotate. May fetch a free slot's session - a plain server ask, or a boot, which is
     * always new - so the prefetch threads only. Release the lease after the read.
     */
    fun lease(region: String?, channelId: String, keep: Set<String>): SessionPool.Lease? {
        val pool = if (region == null) local else {
            if (nowMillis() < serverDownUntil) return null
            regionPool(region)
        }
        return pool.lease(channelId, keep, { current }) { slot ->
            slot.cached() ?: if (region != null && (slot.rebuilding || nowMillis() < serverDownUntil)) null else slot.get()
        }
    }

    /**
     * The claim standing for [masterUrl] - the channel whose master was read last on its session,
     * if that is this url - or null. What [VariantCache] holds a pick against. Never blocks.
     */
    fun claimOf(masterUrl: String): Any? =
        local.claimOf(masterUrl) ?: regions.values.firstNotNullOfOrNull { it.claimOf(masterUrl) }

    /** A session for a player running at the same time as the dial - never the dial's own. */
    fun beside(): PlutoSession? = besideLocal.get()

    /**
     * [session] was refused by the stitcher, or a stream on it would not open: forget it, so the
     * next ask builds a new one - and whatever was claimed on it. A no-op when it has already been
     * replaced, so two failures reported for one bad token cannot throw away its healthy
     * successor. Never blocks.
     */
    fun invalidate(session: PlutoSession) {
        besideLocal.forget(session)
        local.slots.forEach { it.forget(session) }
        regions.values.forEach { pool -> pool.slots.forEach { it.forget(session) } }
    }

    private fun regionPool(region: String): SessionPool = regions.getOrPut(region) {
        pool(region, { slot, fresh ->
            when {
                slot > 0 -> slotServer(region, slot, fresh)
                fresh -> freshServer(region)
                else -> server(region)
            }
        }, ::serverMiss)
    }

    /** A region slot's session as the dial has always asked: never the server while it is away. */
    private fun regionSession(slot: SessionSlot): PlutoSession? =
        slot.cached() ?: if (nowMillis() >= serverDownUntil) slot.get() else slot.stillValid()

    private fun take(pool: SessionPool, slot: SessionSlot, session: PlutoSession, channelId: String): PlutoSession {
        val left = current
        // On screen before the pool lets go of it as pending, so no read ahead can take it between.
        current = slot
        if (left != null && left !== slot) poolOf(left)?.left(left)
        pool.take(slot, session, channelId)
        return session
    }

    private fun poolOf(slot: SessionSlot): SessionPool? =
        local.takeIf { slot in it.slots } ?: regions.values.firstOrNull { slot in it.slots }

    private fun pool(name: String, fetch: (slot: Int, fresh: Boolean) -> PlutoSession?, missFor: (Throwable?) -> Long): SessionPool {
        lateinit var pool: SessionPool
        val slots = List(poolSize.coerceAtLeast(1)) { index ->
            SessionSlot({ fresh -> fetch(index, fresh) }, missFor, nowMillis) { slot, session -> pool.accept(slot, session) }
        }
        pool = SessionPool(name, slots, nowMillis)
        return pool
    }

    /** How long a region fetch that failed is left alone - and the server with it, if it threw. */
    private fun serverMiss(failure: Throwable?): Long {
        if (failure == null) return SERVER_MISS_RETRY_MILLIS
        val wait = if (transient(failure)) NETWORK_MISS_RETRY_MILLIS else SERVER_MISS_RETRY_MILLIS
        serverDownUntil = nowMillis() + wait
        return wait
    }

    companion object {
        /** Refreshed this long before expiry, so no stream starts on a token about to lapse. */
        const val REFRESH_MARGIN_MILLIS = 30 * 60_000L

        /** A server that did not answer is asked again after this - long enough to spare the car. */
        const val SERVER_MISS_RETRY_MILLIS = 10 * 60_000L

        /**
         * A network not up yet - DNS failing, "network unreachable", a refused connection - is
         * looked at again this soon. A television woken from standby has no DNS for its first
         * seconds, and retiring the server for ten minutes over that parked the region channels
         * on this television's session for the whole first evening's viewing.
         */
        const val NETWORK_MISS_RETRY_MILLIS = 30_000L

        /** Pluto's boot failing usually means no network at all; look again soon. */
        const val BOOT_MISS_RETRY_MILLIS = 30_000L
    }
}
