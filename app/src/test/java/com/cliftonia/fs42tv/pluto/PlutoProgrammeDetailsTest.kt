package com.cliftonia.fs42tv.pluto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The description and picture the picker's details pane takes from Pluto's guide reply - the same
 * reply the titles come from. Shapes cut from real replies (`v2/channels/<id>`, 28 Sep 2026).
 */
class PlutoProgrammeDetailsTest {

    private val text = """
        {"timelines": [
          {"start": "2026-09-28T04:37:11.000Z", "stop": "2026-09-28T06:40:01.000Z", "title": "Raw Deal",
           "episode": {"name": "Raw Deal",
             "description": "A former FBI agent turned small-town sheriff infiltrates the Chicago mafia.",
             "poster": {"path": "https://images.pluto.tv/episodes/e1/poster.jpg?h=1000&w=694"},
             "featuredImage": {"path": "https://images.pluto.tv/episodes/e1/featured.jpg"},
             "series": {"name": "Raw Deal", "type": "live", "description": "Series words.",
                        "tile": {"path": "https://images.pluto.tv/series/s1/tile.jpg"}}}},
          {"start": "2026-09-28T06:40:01.000Z", "stop": "2026-09-28T07:10:00.000Z", "title": "Daria",
           "episode": {"name": "Ill",
             "series": {"name": "Daria", "type": "tv", "description": "A sardonic teen.",
                        "tile": {"path": "https://images.pluto.tv/series/s2/tile.jpg"}}}},
          {"start": "2026-09-28T07:10:00.000Z", "stop": "2026-09-28T07:40:00.000Z", "title": "Bare"}
        ]}
    """.trimIndent()

    private val programmes = PlutoApi.parse(text)!!.programmes

    @Test
    fun `the episode's description and portrait poster`() {
        val film = programmes[0]
        assertEquals("A former FBI agent turned small-town sheriff infiltrates the Chicago mafia.", film.description)
        assertEquals("https://images.pluto.tv/episodes/e1/poster.jpg?h=1000&w=694", film.imageUrl)
    }

    @Test
    fun `an episode without its own falls back to the series'`() {
        assertEquals("A sardonic teen.", programmes[1].description)
        assertEquals("https://images.pluto.tv/series/s2/tile.jpg", programmes[1].imageUrl)
    }

    @Test
    fun `a bare entry still parses, with blanks`() {
        assertEquals("", programmes[2].description)
        assertNull(programmes[2].imageUrl)
    }
}
