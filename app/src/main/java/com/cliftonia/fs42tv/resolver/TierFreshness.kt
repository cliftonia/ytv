package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.sync.Tier

/**
 * One freshness rule for [Tier], shared by [StreamResolver] (reading the published cache) and
 * [ClipResolver] (extracting on the device). Both ultimately hand back the same signed URLs, so
 * both must retire them on the same schedule - otherwise a freshly resolved URL could be trusted
 * for longer than an identical one the cached path had already rejected as too close to expiry.
 */

/** Treat a tier as dead slightly before its stated expiry, since signing is not exact. */
internal const val SAFETY_MARGIN_SECONDS = 300L

/** Whether this tier's signed URL is still safely playable at [nowSeconds]. */
internal fun Tier.isFresh(nowSeconds: Long): Boolean = expires - SAFETY_MARGIN_SECONDS > nowSeconds
