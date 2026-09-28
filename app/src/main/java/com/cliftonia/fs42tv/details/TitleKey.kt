package com.cliftonia.fs42tv.details

import java.text.Normalizer
import java.util.Locale

/**
 * The key `details.json` is written under, for a programme title exactly as a guide has it.
 *
 * The same function as `curation/titles.py`'s `key`, and deliberately as dumb: lower case, accents
 * off, apostrophes dropped, "&" as "and", anything else not a letter or digit a single space. All
 * the clever parsing of messy titles ("Movie: X (1984)", "S2 E5") happens once, server side, which
 * then publishes the result under this key of the raw title - so the only thing the two languages
 * must agree on is this, and `curation/tests/title_keys.json` holds the cases both test suites run.
 */
object TitleKey {

    private val COMBINING = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("['‘’`´]")
    private val NOT_ALNUM = Regex("[^0-9a-z]+")

    /** Empty for a title with nothing left - one written in another script, say. */
    fun of(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val stripped = COMBINING.replace(Normalizer.normalize(raw, Normalizer.Form.NFKD), "")
        val lower = stripped.lowercase(Locale.ROOT)
        val spaced = APOSTROPHES.replace(lower, "").replace("&", " and ")
        return NOT_ALNUM.replace(spaced, " ").trim()
    }
}
