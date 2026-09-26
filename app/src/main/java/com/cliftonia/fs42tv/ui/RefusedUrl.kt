package com.cliftonia.fs42tv.ui

import android.util.Log

/**
 * The one playback error worth reacting to specifically: a rejected url. Re-tuning without
 * forgetting it would resolve to the same dead link and fail the same way.
 *
 * Out of [ScreenDirector] so the rule is testable and the director stays one idea.
 */
object RefusedUrl {

    /**
     * Whether [code] reads as a refused url.
     *
     * Engine-agnostic on purpose. Media3 names the fault precisely; mpv reports only that the
     * file ended in error, and its commonest cause by far is exactly this - a signed URL the CDN
     * refused. Matching only Media3's spellings meant an mpv 403 re-tuned to the very same dead
     * URL, forever. Being wrong in the other direction costs one server resolve.
     */
    fun matches(code: String): Boolean =
        code.contains("BAD_HTTP_STATUS") || code.contains("FILE_NOT_FOUND") || code.startsWith("MPV_")

    /**
     * Tell the ledger clip [id] was refused, when [code] says so. Refuse the TIER, not the clip -
     * condemning the whole id forces a /resolve, which runs yt-dlp at seven to twelve measured
     * seconds, and nearly every clip carries a lower rung in a file the app already holds. Which
     * rung, and what to forget, is the ledger's decision ([condemn]).
     */
    fun report(code: String, id: String?, condemn: (String) -> String?) {
        if (id == null || !matches(code)) return
        val tier = condemn(id)
        if (tier != null) {
            Log.w("fs42", "tier $tier refused for $id; falling to the next rung")
        } else {
            Log.w("fs42", "all tiers refused for $id; skipping the clip")
        }
    }
}
