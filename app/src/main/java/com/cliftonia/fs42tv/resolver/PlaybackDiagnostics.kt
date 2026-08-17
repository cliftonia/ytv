package com.cliftonia.fs42tv.resolver

/**
 * What the resolver last handed the player, in words, for the settings screen.
 *
 * Exists because "the picture is not right" and "the picture is wrong in this specific way" are
 * hours apart in diagnosis, and nothing on screen said which rendition was playing. A whole
 * afternoon went into deciding whether a television was being given 4K VP9 - a question the
 * resolver knew the answer to the entire time and had no way to say.
 *
 * A single mutable field rather than a log: only the current answer matters, it is written once
 * per tune and read only when somebody opens settings.
 */
object PlaybackDiagnostics {

    @Volatile
    var lastStream: String = "NOTHING YET"
        private set

    /** How the last tune spent its time, and whether the prefetch had already done the work. */
    @Volatile
    var lastTiming: String = "NOTHING YET"
        private set

    private val prefetchHits = java.util.concurrent.atomic.AtomicInteger(0)
    private val prefetchMisses = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Record where a tune's time went.
     *
     * Split deliberately. "Channel switching is slow" has two completely different causes with
     * two completely different fixes: resolving the url, which prefetching removes, and getting
     * the first frame out of the player, which it cannot touch. Without the split there is no way
     * to tell which one is being complained about.
     */
    fun recordTune(resolveMillis: Long, firstFrameMillis: Long, fromCache: Boolean) {
        if (fromCache) prefetchHits.incrementAndGet() else prefetchMisses.incrementAndGet()
        val hits = prefetchHits.get()
        val total = hits + prefetchMisses.get()
        lastTiming = "RESOLVE %dms + PLAY %dms, READY %d/%d".format(
            resolveMillis, firstFrameMillis - resolveMillis, hits, total)
    }

    fun record(tier: String, resolution: String?, codec: String?) {
        lastStream = "%s %s %s".format(
            tier.uppercase(), resolution ?: "?", DecoderSupport.family(codec))
    }
}
