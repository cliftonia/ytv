package com.cliftonia.fs42tv.pluto

import android.util.Log

/**
 * The dial's sessions for one source - a region from the home server, or this television's own
 * boots - as a small pool, so the channels either side of the one on screen can have their
 * masters read ahead ([MasterPrefetch]) without touching the stream being watched.
 *
 * WHY A POOL - measured Sep 2026 against live Pluto: on ONE session, reading channel B's master
 * ends channel A's media playlist at once (A's next read is an empty `#EXT-X-ENDLIST`). The
 * stitcher keeps one channel per session, and the master read is what moves it. On two sessions,
 * A played on and B's variant was still good 90s later. So: never two channels on one session.
 *
 * THE RULE, modelled explicitly: each slot's [SessionSlot.claim] is the channel whose master was
 * read LAST on its session. [take] (a tune) and [lease] (a read ahead) set it BEFORE the master is
 * read, so the claim is never behind the stitcher. A pick is good only while its claim stands -
 * [claimOf] is what [VariantCache] checks - so reading X then Y on one session retires X's pick,
 * and X read again later is a new claim that the old pick does not match.
 *
 * THE ROTATION - three slots: the one on screen (current), and the two a surf would land on.
 *  - A tune takes the slot already claimed by its channel, if any: a surf onto a neighbour read
 *    ahead plays that slot's pick, and that slot becomes current.
 *  - The slot it leaves still carries the channel just left - which, after a surf by one, is the
 *    new channel's neighbour the other way. Its claim stands; nothing is read on it.
 *  - The read ahead re-points only a slot that is not current, not being read, not left in the
 *    last [QUIET_MILLIS], and not carrying a channel still wanted - the remaining one.
 *  - A tune to a channel nobody read ahead (a jump) takes a free slot that holds a session and
 *    is out of its quiet time, else the current one - exactly the one-session dial of before.
 *
 * Without [rotating] - Media3, where nothing is read ahead, or a server that hands every slot the
 * same session - a tune always takes slot 0, the dial's session exactly as before the pool.
 *
 * Every choice is made under [lock], and a slot being read ahead is [busy]: a tune never takes a
 * slot whose master read is still in flight for another channel, since whichever read the
 * stitcher saw last would win. Nothing here fetches; the callers do, outside the lock.
 */
class SessionPool internal constructor(
    val name: String,
    val slots: List<SessionSlot>,
    private val nowMillis: () -> Long,
) {

    /** Who last read a master on a session: [channelId]'s, on [session]. Identity is the token. */
    class Claim(val session: PlutoSession, val channelId: String) {
        val masterUrl: String = session.masterUrl(channelId)
    }

    /** A slot lent to a read ahead; [release] when the read is done, whatever it found. */
    class Lease(val session: PlutoSession, val masterUrl: String, val release: () -> Unit)

    val lock = Any()

    /**
     * False once two slots were handed the same session - a home server that keys sessions by
     * television alone. Reading ahead on a twin would end the programme on screen, so from then
     * on this pool is one session, as before it existed.
     */
    @Volatile var rotating: Boolean = slots.size > 1
        private set

    private val busy = HashMap<SessionSlot, Int>()
    private val leftAt = HashMap<SessionSlot, Long>()
    private val claimedAt = HashMap<SessionSlot, Long>()

    /** The slot a tune has picked and not yet taken - see [pick]. Under the lock. */
    private var pending: SessionSlot? = null

    /** A session a slot just fetched: refused, and the pool stops rotating, if another holds it. */
    fun accept(slot: SessionSlot, session: PlutoSession): Boolean {
        val twin = slots.any { it !== slot && it.holding()?.jwt == session.jwt }
        if (!twin) return true
        if (rotating) Log.w("fs42", "pluto: $name sessions came back identical; no reading ahead on them")
        rotating = false
        // Whatever a twin claimed stands no longer: the session it names is about to be slot 0's.
        synchronized(lock) { slots.forEach { if (it !== slots[0] && it.holding()?.jwt == session.jwt) it.claim = null } }
        // Slot 0 is the dial's own and keeps what it fetched; a twin in any other slot is refused.
        return slot === slots[0]
    }

    /** The claim standing for [masterUrl] on one of these slots, or null - see [VariantCache]. */
    fun claimOf(masterUrl: String): Claim? = slots.firstNotNullOfOrNull { slot ->
        slot.claim?.takeIf { it.masterUrl == masterUrl && it.session === slot.holding() }
    }

    /**
     * The slot a tune of [channelId] reads its master on - see the class comment. [current] is the
     * slot on screen now, from whichever pool; [rotate] false is slot 0, always.
     */
    fun pick(channelId: String, rotate: Boolean, current: SessionSlot?): SessionSlot = synchronized(lock) {
        // Pending until [take]: a read ahead must not choose it while the tune fetches its session.
        choose(channelId, rotate, current).also { pending = it }
    }

    private fun choose(channelId: String, rotate: Boolean, current: SessionSlot?): SessionSlot {
        if (!rotate || !rotating) return slots[0]
        slots.firstOrNull { claimed(it, channelId) && it.cached() != null }?.let { return it }
        val here = current?.takeIf { it in slots }
        // Not one in its quiet time either: a read of the channel it carried may still land.
        val now = nowMillis()
        slots.filter { it !== here && idle(it) && it.cached() != null && !quiet(it, now) }
            .minByOrNull { claimedAt[it] ?: 0L }?.let { return it }
        return here?.takeIf(::idle) ?: slots.firstOrNull(::idle) ?: slots[0]
    }

    private fun quiet(slot: SessionSlot, now: Long): Boolean =
        leftAt[slot]?.let { now - it < QUIET_MILLIS } == true

    /**
     * [slot] now plays [channelId] on [session]: its claim, kept when it already names that
     * channel on that session so the pick read ahead stays good.
     */
    fun take(slot: SessionSlot, session: PlutoSession, channelId: String) = synchronized(lock) {
        pending = null
        claim(slot, session, channelId)
    }

    /** [slot] is no longer on screen: its quiet time starts - see [QUIET_MILLIS]. */
    fun left(slot: SessionSlot) = synchronized(lock) { leftAt[slot] = nowMillis() }

    /** A tune picked a slot here and then found no session on it: nothing is pending any more. */
    fun abandon(slot: SessionSlot) = synchronized(lock) { if (pending === slot) pending = null }

    /**
     * A slot to read [channelId]'s master ahead on, marked busy - or null: none is free. [keep] are
     * the channels whose claims must stand - the one on screen, and the other neighbour.
     * [session] fetches or finds the slot's session outside the lock (null: none to be had
     * cheaply). [current] is re-read after it: a tune that took the slot meanwhile wins.
     */
    fun lease(
        channelId: String,
        keep: Set<String>,
        current: () -> SessionSlot?,
        session: (SessionSlot) -> PlutoSession?,
    ): Lease? {
        if (!rotating) return null
        val slot = synchronized(lock) {
            val onScreen = current()
            val same = slots.firstOrNull { claimed(it, channelId) }
            val chosen = if (same != null) {
                same.takeIf { it !== onScreen && it !== pending && idle(it) }
            } else {
                val now = nowMillis()
                slots.filter { slot ->
                    slot !== onScreen && slot !== pending && idle(slot) && !quiet(slot, now) &&
                        slot.claim?.channelId?.let { it !in keep } != false
                }.minByOrNull { claimedAt[it] ?: 0L }
            }
            chosen?.also { busy[it] = (busy[it] ?: 0) + 1 }
        } ?: return null
        val release = { synchronized(lock) { busy[slot]?.let { if (it <= 1) busy.remove(slot) else busy[slot] = it - 1 } } }
        val held = runCatching { session(slot) }.getOrNull()
        val claim = held?.let {
            synchronized(lock) {
                if (current() === slot || pending === slot || !rotating || slot.holding() !== it) null
                else claim(slot, it, channelId)
            }
        }
        if (claim == null) {
            release()
            return null
        }
        return Lease(claim.session, claim.masterUrl) { release() }
    }

    private fun claimed(slot: SessionSlot, channelId: String): Boolean =
        slot.claim?.let { it.channelId == channelId && it.session === slot.holding() } == true

    private fun idle(slot: SessionSlot): Boolean = (busy[slot] ?: 0) == 0

    // Under the lock.
    private fun claim(slot: SessionSlot, session: PlutoSession, channelId: String): Claim {
        val standing = slot.claim?.takeIf { it.session === session && it.channelId == channelId }
        claimedAt[slot] = nowMillis()
        return standing ?: Claim(session, channelId).also { slot.claim = it }
    }

    companion object {
        /** The slots a pool holds: the channel on screen and one each side of it. */
        const val SIZE = 3

        /**
         * A slot left by the screen is not re-pointed for this long, by a tune or a read ahead:
         * the break poller's read of the channel it carried may still be in flight (2s connect,
         * 3s read), and that master landing after another channel's would end the other one.
         */
        const val QUIET_MILLIS = 10_000L
    }
}
