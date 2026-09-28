package com.cliftonia.fs42tv.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** details.json read back: found by the guide's raw title, whatever its dressing. */
class DetailsTest {

    private val text = """
        {"generated": 1790553600, "img": "https://image.tmdb.org/t/p/",
         "titles": {"movie the karate kid 1984": 0, "the karate kid": 0, "daria": 1, "broken": 7},
         "items": [
           {"k": "m", "t": "The Karate Kid", "y": 1984, "p": "w500/kk.jpg", "b": "w780/kkb.jpg",
            "o": "Hounded by bullies.", "g": ["Action", "Drama"], "r": 126,
            "c": ["Ralph Macchio", "Pat Morita"], "d": ["John G. Avildsen"], "i": "7.3", "rt": "90%"},
           {"k": "t", "t": "Daria", "future": true}
         ]}
    """.trimIndent()

    private val details = Details.parse(text)

    @Test
    fun `a messy guide title finds its film`() {
        val found = details.forTitle("Movie: The Karate Kid (1984)")!!
        assertEquals("The Karate Kid", found.title)
        assertEquals(true, found.movie)
        assertEquals("https://image.tmdb.org/t/p/w500/kk.jpg", found.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w780/kkb.jpg", found.backdropUrl)
        assertEquals(listOf("John G. Avildsen"), found.makers)
        assertEquals("7.3" to "90%", found.imdbRating to found.rottenTomatoes)
    }

    @Test
    fun `case and punctuation do not matter, and a bare item has blanks not errors`() {
        val daria = details.forTitle("DARIA!")!!
        assertEquals(false, daria.movie)
        assertNull(daria.posterUrl)
        assertEquals("", daria.overview)
        assertEquals(emptyList<String>(), daria.cast)
    }

    @Test
    fun `unknown titles and dangling indexes answer nothing`() {
        assertNull(details.forTitle("Righteous Kill"))
        assertNull(details.forTitle("Broken"))
        assertNull(details.forTitle(""))
        assertNull(details.forTitle(null))
        assertEquals(3, details.size)
    }

    @Test
    fun `pictures come only from an https base`() {
        val plain = Details.parse(text.replace("https://image.tmdb.org", "http://image.tmdb.org"))
        assertNull(plain.forTitle("The Karate Kid")!!.posterUrl)
    }

    @Test(expected = Exception::class)
    fun `something that is not a details file is refused`() {
        Details.parse("<html>portal</html>")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty object is refused too`() {
        Details.parse("{}")
    }

    @Test
    fun `a series channel gets the series and a movie channel the film of one title`() {
        val both = Details.parse("""{"generated":1,"img":"https://image.tmdb.org/t/p/",
            "titles":{"tv:21 jump street":0,"movie:21 jump street":1},
            "items":[{"k":"t","t":"21 Jump Street","y":1987},{"k":"m","t":"21 Jump Street","y":2012}]}""")!!
        assertEquals(1987, both.forTitle("21 Jump Street", Details.kindOfBlock("Series"))?.year)
        assertEquals(2012, both.forTitle("21 Jump Street", Details.kindOfBlock("Movies"))?.year)
        assertEquals("no bare key, so no guess", null, both.forTitle("21 Jump Street", null))
    }
}
