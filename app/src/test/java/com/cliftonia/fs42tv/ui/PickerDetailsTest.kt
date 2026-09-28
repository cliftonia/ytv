package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.details.Enrichment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The details pane's words: the guide's and TMDB's weighed against each other, and every missing
 * field an empty line the pane leaves out rather than a label with nothing after it.
 */
class PickerDetailsTest {

    private val line = "CH 212  MOVIES"

    private val karate = Enrichment(
        title = "The Karate Kid", movie = true, year = 1984,
        posterUrl = "https://image.tmdb.org/t/p/w500/kk.jpg", backdropUrl = "https://image.tmdb.org/t/p/w780/b.jpg",
        overview = "Hounded by bullies.", genres = listOf("Action", "Drama", "Family", "Sport"), runtimeMinutes = 126,
        cast = listOf("Ralph Macchio", "Pat Morita", "Elisabeth Shue", "William Zabka", "Martin Kove"),
        makers = listOf("John G. Avildsen"), imdbRating = "7.3", rottenTomatoes = "90%",
    )

    @Test
    fun `a film is told in TMDB's words`() {
        val pane = PickerDetails.of(line, "Movie: The Karate Kid (1984)", "A guide blurb.", "https://guide/still.jpg", karate)
        assertEquals("The Karate Kid", pane.title)
        assertEquals("Hounded by bullies.", pane.description)
        assertEquals("https://image.tmdb.org/t/p/w500/kk.jpg", pane.imageUrl)
        assertEquals(listOf("IMDb 7.3", "RT 90%"), pane.ratings)
        assertEquals("1984 · 126 MIN · ACTION / DRAMA / FAMILY", pane.facts)
        assertEquals("WITH Ralph Macchio, Pat Morita, Elisabeth Shue, William Zabka", pane.cast)
        assertEquals("DIRECTED BY John G. Avildsen", pane.makers)
    }

    @Test
    fun `a series keeps the episode the guide names`() {
        val daria = Enrichment(title = "Daria", movie = false, overview = "A sardonic teen.",
            makers = listOf("Glenn Eichler"))
        val pane = PickerDetails.of(line, "Daria: Ill", "A rash sends Daria home.", null, daria)
        assertEquals("Daria: Ill", pane.title)
        assertEquals("A rash sends Daria home.", pane.description)
        assertEquals("CREATED BY Glenn Eichler", pane.makers)
        assertEquals("A sardonic teen.", PickerDetails.of(line, "Daria", "", null, daria).description)
    }

    @Test
    fun `without details the guide's own words stand, and nothing else is invented`() {
        val pane = PickerDetails.of(line, "Terry and June", "Terry is jealous.", "https://guide/still.jpg", null)
        assertEquals(PickerDetails(line, "Terry and June", "Terry is jealous.", "https://guide/still.jpg"), pane)
    }

    @Test
    fun `with nothing at all the channel line is the pane`() {
        assertEquals(PickerDetails(line), PickerDetails.of(line, null, null, null, null))
    }

    @Test
    fun `missing ratings and facts collapse cleanly`() {
        val bare = Enrichment(title = "Obscure", movie = true, rottenTomatoes = "61%")
        val pane = PickerDetails.of(line, "Obscure", "", null, bare)
        assertEquals(listOf("RT 61%"), pane.ratings)
        assertEquals("", pane.facts)
        assertEquals("", pane.cast)
        assertEquals("", pane.makers)
        assertEquals(null, pane.imageUrl)
    }

    @Test
    fun `the guide's still stands in for a missing poster, before the backdrop`() {
        val noPoster = karate.copy(posterUrl = null)
        assertEquals("https://guide/still.jpg", PickerDetails.of(line, "x", "", "https://guide/still.jpg", noPoster).imageUrl)
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", PickerDetails.of(line, "x", "", null, noPoster).imageUrl)
    }
}
