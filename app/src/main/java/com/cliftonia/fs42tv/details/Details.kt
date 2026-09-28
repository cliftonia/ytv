package com.cliftonia.fs42tv.details

import com.cliftonia.fs42tv.sync.REPO_RAW
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One film or series from `details.json`: what TMDB and OMDb say about it, as the picker shows it.
 * Every field but [title] may be missing, and the pane collapses around whatever is.
 */
data class Enrichment(
    val title: String,
    val movie: Boolean,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String = "",
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val cast: List<String> = emptyList(),
    /** The director for a film, the creators for a series. */
    val makers: List<String> = emptyList(),
    val imdbRating: String? = null,
    val rottenTomatoes: String? = null,
)

/**
 * `details.json`, built six-hourly by `curation/build_details.py`: enrichment for the titles airing
 * on the LIVE TV dial in the next thirty hours, keyed by [TitleKey] of the raw guide title.
 *
 * Pure and immutable: parsed once per download, then read from the UI thread and the executors.
 */
class Details private constructor(
    val generatedSeconds: Long,
    private val byKey: Map<String, Enrichment>,
) {

    val size: Int get() = byKey.size

    /** What is known about the programme a guide calls [rawTitle], or null. */
    fun forTitle(rawTitle: String?): Enrichment? =
        TitleKey.of(rawTitle).takeIf { it.isNotEmpty() }?.let(byKey::get)

    @Serializable
    private class Wire(
        val generated: Long = 0,
        val img: String = "",
        val titles: Map<String, Int> = emptyMap(),
        val items: List<Item> = emptyList(),
    )

    @Serializable
    private class Item(
        val k: String = "",
        val t: String = "",
        val y: Int? = null,
        val p: String? = null,
        val b: String? = null,
        val o: String = "",
        val g: List<String> = emptyList(),
        val r: Int? = null,
        val c: List<String> = emptyList(),
        val d: List<String> = emptyList(),
        val i: String? = null,
        val rt: String? = null,
    )

    companion object {
        const val URL = "$REPO_RAW/details.json"

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /**
         * The file's text as details. Throws on anything that is not one, so a truncated download
         * or a captive portal's page is never cached; an item without a title is left out.
         */
        fun parse(text: String): Details {
            val wire = json.decodeFromString(Wire.serializer(), text)
            require(wire.generated > 0) { "not a details file" }
            // Images only ever from https, whatever the file says.
            val base = wire.img.takeIf { it.startsWith("https://") }
            val items = wire.items.map { item ->
                item.t.takeIf { it.isNotBlank() }?.let {
                    Enrichment(
                        title = it,
                        movie = item.k == "m",
                        year = item.y,
                        posterUrl = item.p?.let { p -> base?.plus(p) },
                        backdropUrl = item.b?.let { b -> base?.plus(b) },
                        overview = item.o,
                        genres = item.g,
                        runtimeMinutes = item.r?.takeIf { r -> r > 0 },
                        cast = item.c,
                        makers = item.d,
                        imdbRating = item.i,
                        rottenTomatoes = item.rt,
                    )
                }
            }
            val byKey = HashMap<String, Enrichment>(wire.titles.size * 2)
            for ((key, index) in wire.titles) {
                items.getOrNull(index)?.let { byKey[key] = it }
            }
            return Details(wire.generated, byKey)
        }
    }
}
