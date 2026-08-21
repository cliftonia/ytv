package com.cliftonia.fs42tv.resolver

/**
 * Turns a YouTube id into something the player can open.
 *
 * The published lineup cannot carry playable urls: YouTube signs them and they die within hours,
 * so a clip that airs at nine in the evening has to be resolved at nine in the evening. That one
 * fact is why this app used to need a machine at home, and why replacing the machine meant
 * replacing this and nothing else.
 *
 * An interface rather than a class because the implementation moved - from a server endpoint
 * running yt-dlp to [DeviceResolver] running on the television itself - and the call sites were
 * unchanged by the move. Anything satisfying this can be dropped in.
 */
interface ClipResolver {

    /**
     * A resolved clip together with the moment its signed urls die.
     *
     * The expiry is what lets a caller cache the result without guessing a lifetime. It is
     * carried here rather than folded into [Progressive] because [Progressive] is a `Playable`
     * that flows all the way to the player, and the player has no business knowing about it.
     */
    data class Resolved(
        val playable: Progressive,
        val expiresAtSeconds: Long,
        /**
         * Which rung of the ladder this url came from. Carried so the 403 handler can refuse
         * the tier that actually played rather than recomputing "first rung not yet refused" -
         * a guess that condemns the wrong rung whenever the clip lacked the top tier or a
         * prefetch raced the refusal.
         */
        val tier: String,
    )

    /**
     * Resolve [videoId], preferring the first rung of [ladder] that this clip actually offers.
     *
     * [refused] holds "<id>/<tier>" pairs the CDN has already rejected this session, and skipping
     * them is the entire point of refusing a tier. Without it the ladder was recomputed, a rung
     * was marked refused, and then the resolver walked the full ladder again and handed back the
     * same rejected url - so a single 403 cost three or four identical failures before the clip
     * was finally condemned.
     *
     * Returns null on any failure rather than throwing: the caller's correct response to "cannot
     * resolve" is to skip the clip, and an exception here would take the player down instead.
     *
     * Blocking. Every caller is already on the background executor.
     */
    fun resolveDetailed(
        videoId: String,
        nowSeconds: Long,
        ladder: List<String> = DEFAULT_LADDER,
        refused: Set<String> = emptySet(),
    ): Resolved?

    companion object {
        /** The ladder for a panel whose height is unknown - conservative, never 4K. */
        val DEFAULT_LADDER = listOf("hd", "sd")
    }
}
