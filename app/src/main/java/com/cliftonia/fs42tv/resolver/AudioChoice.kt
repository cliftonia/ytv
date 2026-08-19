package com.cliftonia.fs42tv.resolver

/**
 * Which audio track to play, when a video carries several.
 *
 * YouTube now ships multi-language audio on one video: an English original with German, Spanish
 * and Hindi dubs alongside it, all the same picture. Choosing by bitrate alone - which is what
 * this app did - picks whichever dub happens to be fattest, so a creator who is always in English
 * would suddenly be in German with no change to the dial at all.
 *
 * That is very likely most of what has been reported as "foreign content". It is not the content:
 * it is the same content with the wrong track selected, and no amount of filtering the LINEUP
 * could ever have fixed it. Title heuristics, script ranges and YouTube's declared language all
 * describe the video, and the video was right.
 *
 * Pure so the rule can be tested. Which track gets picked is invisible until somebody is watching,
 * and by then it presents as the channel being wrong rather than the player being wrong.
 */
object AudioChoice {

    /** One audio track, reduced to what choosing between them needs. */
    data class Track(
        val bitrate: Int,
        val languageTag: String?,
        val kind: Kind,
        val container: String?,
    )

    enum class Kind { ORIGINAL, DUBBED, DESCRIPTIVE, SECONDARY, UNKNOWN }

    /**
     * The best track to play, or null when there is nothing usable.
     *
     * Ordered by what actually ruins a programme, worst first:
     *
     * 1. DESCRIPTIVE is refused outright. It is the audio-description track for blind viewers - a
     *    narrator talking over the action - and it is unmistakably wrong on a television nobody
     *    asked for it on.
     * 2. English wins over any other language, whatever its bitrate.
     * 3. An original track beats a dub of the same language, because a dub of an English video
     *    into English is a machine translation of itself.
     * 4. Then bitrate, then m4a, which is what this used to do on its own.
     *
     * A track with NO language at all is treated as acceptable rather than rejected: single-track
     * videos - the overwhelming majority - carry no tag, and refusing them would silence the dial.
     */
    fun pick(tracks: List<Track>): Track? =
        tracks
            .filter { it.kind != Kind.DESCRIPTIVE }
            .maxWithOrNull(
                compareBy<Track> { if (isEnglishOrUntagged(it.languageTag)) 1 else 0 }
                    .thenBy { if (it.kind == Kind.ORIGINAL) 1 else 0 }
                    .thenBy { it.bitrate }
                    .thenBy { if (it.container == "m4a") 1 else 0 }
            )

    /**
     * Whether a track is English, or does not say.
     *
     * Untagged counts, because a video with one audio track does not label it and that is most
     * videos. Only a track that explicitly names another language is demoted - the absence of a
     * claim is not a claim of absence.
     */
    fun isEnglishOrUntagged(tag: String?): Boolean {
        val lowered = tag?.trim()?.lowercase().orEmpty()
        return lowered.isEmpty() || lowered == "en" || lowered.startsWith("en-") ||
            lowered.startsWith("en_")
    }
}
