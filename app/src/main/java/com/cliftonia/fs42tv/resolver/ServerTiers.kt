package com.cliftonia.fs42tv.resolver

/** Whether a health response means the server is worth asking. */
object Health {

    /**
     * True only when the server says both that it is up AND that its extractor works.
     *
     * The second half matters: the copy of yt-dlp on that machine sat broken for six months
     * without anything noticing, returning storyboards and no streams. A server in that state
     * answers every request with a 404, so a television that trusted mere liveness would wait for
     * it, fail, and resolve on its own anyway - strictly slower than never asking.
     */
    fun isUsable(body: String): Boolean =
        Regex("\"ok\"\\s*:\\s*true").containsMatchIn(body)
}

/**
 * The tiers in a server resolve response.
 *
 * Read by hand rather than with a json library, for the same reason [com.cliftonia.fs42tv.update
 * .UpdateCheck] is: Android STUBS org.json in JVM unit tests, so a parser written with it passes
 * its tests without ever having run. This is three fields and a regex is honest about that.
 */
object ServerTiers {

    private val TIER = { name: String ->
        Regex("\"$name\"\\s*:\\s*\\{([^}]*)\\}")
    }

    fun parse(
        body: String,
        ladder: List<String>,
        refused: Set<String>,
        videoId: String,
        nowSeconds: Long,
        wantCaptions: Boolean = false,
    ): ClipResolver.Resolved? {
        // Read once, outside the ladder loop: the caption belongs to the video, not to a
        // rendition of it.
        //
        // This field is why captions did nothing on the television at home. The accelerator
        // answers every resolve when it is reachable, so the on-device caption picking never ran
        // and the fast path had nowhere to carry a track - captions could only ever have worked
        // in the car, where the server is out of reach.
        val caption = if (wantCaptions) field(body, "caption") else null
        PlaybackDiagnostics.recordCaptions(when {
            !wantCaptions -> "OFF"
            caption == null -> "NONE ON THIS CLIP (server)"
            else -> "FOUND (server)"
        })
        for (name in ladder) {
            // A rung the CDN already refused this session will be refused again, whoever resolved
            // it. The server has no idea which urls this particular television has been turned
            // away from, so the skipping has to happen here.
            if (StreamResolver.refusedKey(videoId, name) in refused) continue
            val block = TIER(name).find(body)?.groupValues?.get(1) ?: continue
            val video = field(block, "video") ?: continue
            val audio = field(block, "audio")
            val expires = field(block, "expires")?.toLongOrNull() ?: 0L
            // The same margin the device applies to its own resolves, so a url is retired at the
            // same moment whichever path produced it.
            if (expires - SAFETY_MARGIN_SECONDS <= nowSeconds) continue
            return ClipResolver.Resolved(Progressive(video, audio, caption), expires)
        }
        return null
    }

    private fun field(block: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"?([^\",}]+)\"?").find(block)?.groupValues?.get(1)
}
