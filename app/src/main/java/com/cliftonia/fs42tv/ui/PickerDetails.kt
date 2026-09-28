package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.details.Enrichment

/**
 * What the LIVE TV picker's details pane draws for the highlighted channel: display-ready strings,
 * each empty or null when there is nothing to say, so the pane collapses around the gap.
 *
 * Pure: [of] is where the guide's own words and TMDB's are weighed against each other, and the
 * only part of the pane with decisions in it.
 */
data class PickerDetails(
    /** "CH 212  COMEDY CENTRAL" - always there, so the pane is never blank. */
    val channelLine: String,
    val title: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    /** "IMDb 7.4", "RT 88%" - chips, in that order. */
    val ratings: List<String> = emptyList(),
    /** "1984 · 126 MIN · ACTION / DRAMA". */
    val facts: String = "",
    /** "WITH Ralph Macchio, Pat Morita". */
    val cast: String = "",
    /** "DIRECTED BY John G. Avildsen" or "CREATED BY Glenn Eichler". */
    val makers: String = "",
) {
    companion object {

        /**
         * The pane for a channel: [guideTitle], [guideDescription] and [guideImage] as the guide has
         * them, [enrichment] as `details.json` has it for that title, either or both missing.
         *
         * A film is TMDB's: its title without the guide's "Movie: ... (1984)" dressing, and its
         * overview. A series keeps the guide's title and description, which name the episode
         * actually on - TMDB's describe the whole series, the fallback. The picture is the poster
         * where there is one, else the guide's still, else TMDB's backdrop.
         */
        fun of(
            channelLine: String,
            guideTitle: String?,
            guideDescription: String?,
            guideImage: String?,
            enrichment: Enrichment?,
        ): PickerDetails {
            val title = guideTitle?.trim().orEmpty()
            val desc = guideDescription?.trim().orEmpty()
            if (enrichment == null) {
                return PickerDetails(channelLine, title, desc, guideImage)
            }
            val movie = enrichment.movie
            return PickerDetails(
                channelLine = channelLine,
                title = if (movie || title.isEmpty()) enrichment.title else title,
                description = if (movie) enrichment.overview.ifEmpty { desc } else desc.ifEmpty { enrichment.overview },
                imageUrl = enrichment.posterUrl ?: guideImage ?: enrichment.backdropUrl,
                ratings = listOfNotNull(
                    enrichment.imdbRating?.takeIf { it.isNotBlank() }?.let { "IMDb $it" },
                    enrichment.rottenTomatoes?.takeIf { it.isNotBlank() }?.let { "RT $it" },
                ),
                facts = listOfNotNull(
                    enrichment.year?.toString(),
                    enrichment.runtimeMinutes?.let { "$it MIN" },
                    enrichment.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(" / ") { it.uppercase() },
                ).joinToString(" · "),
                cast = enrichment.cast.take(4).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "WITH ").orEmpty(),
                makers = enrichment.makers.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ", prefix = if (movie) "DIRECTED BY " else "CREATED BY ").orEmpty(),
            )
        }
    }
}
