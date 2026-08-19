#!/usr/bin/env python3
"""What may go on a channel, tested without a network anywhere near it.

Both of the worst bugs found in this pipeline lived in this policy, and both were invisible: the
nightly job went green every night while deleting every series from the episodic channels. Neither
had a test, and neither produced a failure anywhere.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import filters


class TestTitleKey(unittest.TestCase):
    """Deduplication must not delete the thing that distinguishes two episodes."""

    def test_episodes_of_one_series_are_distinct(self):
        # This is the bug. The key used to strip standalone digits, so every episode of every
        # series collapsed to one entry - which emptied season playlists before search even ran,
        # and made the sequencing feature permanently useless.
        self.assertNotEqual(
            filters.title_key("Dr. STONE Episode 1 English Dub | Stone World"),
            filters.title_key("Dr. STONE Episode 2 English Dub | Stone World"))
        self.assertNotEqual(
            filters.title_key('I Married Joan S1-15 "Uncle Edgar"'),
            filters.title_key('I Married Joan S1-16 "Uncle Edgar"'))
        self.assertNotEqual(
            filters.title_key("Lock Up 50s TV Crime Series episode 24 of 26"),
            filters.title_key("Lock Up 50s TV Crime Series episode 25 of 26"))

    def test_the_same_clip_from_two_uploaders_still_collapses(self):
        # The reason the key is loose at all: punctuation and case vary between uploaders of the
        # identical thing, and a channel should not carry it twice.
        self.assertEqual(
            filters.title_key("Queen - Bohemian Rhapsody (Official Video)"),
            filters.title_key("Queen — Bohemian Rhapsody [Official Video]"))

    def test_an_empty_or_missing_title_does_not_raise(self):
        self.assertEqual("", filters.title_key(""))
        self.assertEqual("", filters.title_key(None))


class TestWindows(unittest.TestCase):

    def test_songs_get_a_song_length_window(self):
        low, high = filters.window("music_1980s")
        self.assertLessEqual(low, 180)
        self.assertLessEqual(high, 600)

    def test_documentaries_get_a_long_window(self):
        low, high = filters.window("documentaries")
        self.assertGreaterEqual(low, 600)

    def test_era_channels_are_exempt_from_the_current_year(self):
        # Asking a 1950s channel for this year's uploads returns retrospectives about the era
        # rather than anything from it.
        self.assertTrue(filters.is_period("music_1950s"))
        self.assertTrue(filters.is_period("westerns"))
        self.assertFalse(filters.is_period("ufc"))


class TestUsable(unittest.TestCase):

    def test_commentary_is_rejected(self):
        self.assertFalse(filters.usable("Top 20 Greatest Anime of All Time"))
        self.assertFalse(filters.usable("I Watched 30 Anime So You Don't Have To"))

    def test_actual_content_is_kept(self):
        self.assertTrue(filters.usable("Dr. STONE Episode 1 English Dub"))
        self.assertTrue(filters.usable("QI Full Episode - Series S, EP 6"))

    def test_an_empty_title_is_not_usable(self):
        self.assertFalse(filters.usable(""))
        self.assertFalse(filters.usable(None))


class TestEnglishSpeech(unittest.TestCase):
    """Telling the language OF the content from the language AS its subject.

    Every title here is real. A blunter filter - non-Latin characters, or simply the word "Hindi"
    anywhere - gets the first three wrong, and those are exactly the clips worth keeping.
    """

    def test_an_explicit_english_marker_wins_outright(self):
        # A Japanese OVA labelled Eng Dub is precisely what Classic Anime wants, original title
        # and all. Rejecting it for the characters in its own name would empty the channel.
        self.assertTrue(filters.english_speech("Angel Cop [Eng Dub] (OVA - 1989) エンゼル コップ"))
        self.assertTrue(filters.english_speech(
            "【My Brother, the Mafia Godfather】Full episode丨【ENG DUB】"))

    def test_a_language_naming_the_subject_is_kept(self):
        # "Filipino" describes the food and "Japanese" the style; both are English-language clips.
        self.assertTrue(filters.english_speech(
            "Philippines Street Food!! 14 Hour FILIPINO STREET FOOD Tour in Cagayan de Oro"))
        self.assertTrue(filters.english_speech(
            "Flow Of Time Japanese LoFi HipHop Mix - Collection 時間の流れ"))
        # A pipe is a segment boundary, so the language and the media noun are not related.
        self.assertTrue(filters.english_speech("Japanese Garden Design | Documentary"))

    def test_a_language_naming_the_audio_is_rejected(self):
        self.assertFalse(filters.english_speech("HARIMIYA LOVE STORY FULL SEASON 1 HINDI DUBBED"))
        self.assertFalse(filters.english_speech("Tamil Christian Short Film | Dowry Awareness"))
        self.assertFalse(filters.english_speech(
            "India v Pakistan | Urdu Highlights | Men's T20 World Cup"))

    def test_a_feed_that_names_its_language_is_rejected(self):
        # The Hindi feed of an otherwise English documentary strand, and an Indian regional
        # broadcaster. Both end a pipe segment with the language, which is how feeds label
        # themselves.
        self.assertFalse(filters.english_speech(
            "Cute to Killer: The Journey of a Lion | National Geographic Hindi | Wildlife"))
        self.assertFalse(filters.english_speech(
            "Jabardasth | 2nd August 2026 | Full Episode | ETV Telugu"))

    def test_a_title_mostly_in_another_script_is_rejected(self):
        self.assertFalse(filters.english_speech(
            "New Season | KBC S17 | Ep. 75 | Full Episode | "
            "KBC के मंच पर पहुंची "
            "दिग्गजों की टोली"))
        self.assertFalse(filters.english_speech(
            "COMEDY CLUB новое: 21 сезон"))

    def test_ordinary_english_titles_are_untouched(self):
        for title in ("QI Full Episode - Series S, EP 6 Featuring Aisling Bea",
                      "Queen - Bohemian Rhapsody (Official Video Remastered)",
                      "Sarah McLachlan: Tiny Desk Concert"):
            self.assertTrue(filters.english_speech(title), title)

    def test_an_empty_title_is_not_english(self):
        self.assertFalse(filters.english_speech(""))
        self.assertFalse(filters.english_speech(None))

    def test_a_single_indic_or_cyrillic_character_is_enough(self):
        # Unlike Japanese, these scripts never appear as decoration in an English title. A
        # programme whose title is mostly English but carries a few Devanagari words is a Hindi
        # programme, and a share-based threshold let every one of those through.
        self.assertFalse(filters.english_speech(
            "New Season | KBC S18 | Ep. 1 | Full Episode | Sunny "
            "के Dhai Kilo"))
        self.assertFalse(filters.english_speech(
            "Motu और Patlu Japan Martial Arts Adventure | Kung-Fu Kings"))

    def test_japanese_and_korean_may_still_decorate_an_english_title(self):
        # The distinction that makes the rule above safe: an English-dubbed OVA keeps its original
        # title, and rejecting those would empty the Classic Anime channel.
        self.assertTrue(filters.english_speech(
            "Flow Of Time Japanese LoFi HipHop Mix - Collection 時間の流れ"))


if __name__ == "__main__":
    unittest.main()


class TestLatinScriptForeign(unittest.TestCase):
    """Another language written in our own alphabet, which no script check can see.

    Every title here is real, and the English ones are the point: `kung` appears in "Kung Fu",
    `ng` in "Andrew Ng", `yang` in "Jimmy Yang", `de la` in "De La Soul". Matching a short function
    word at a word boundary looks precise and deletes all of them, which is why the rule needs two
    tiers rather than one list.
    """

    def test_a_marker_no_english_title_carries_convicts_alone(self):
        self.assertFalse(filters.english_speech(
            "LATEST 2026! UPSET Victory ng Pinoy sa JAPAN! PINOY BAGONG WBO Champion"))
        self.assertFalse(filters.english_speech(
            "Payapang Probinsya (Live At Hidden Grove) - Pinoy Reggae Republic"))

    def test_two_common_words_from_one_language_convict_together(self):
        self.assertFalse(filters.english_speech(
            "Kung Wala Na Ang Mga Mata (Live At Hidden Grove)"))
        self.assertFalse(filters.english_speech("DI DEPAN TERAS PART 14 - Animasi Sekolah"))

    def test_one_common_word_is_a_loanword_or_a_surname(self):
        # Each of these carries exactly one marker, and every one is an English title. Convicting
        # on a single word would have deleted the whole Martial Arts channel.
        for title in ("Kung Fu Chaos (Original Xbox) - Playthrough | Retro Gaming 2026",
                      "The Future of AI Agents with Andrew Ng | Interrupt 26",
                      "Jimmy O. Yang: Guess How Much (Full Show) | Stand-Up Comedy Special",
                      "De La Soul: Tiny Desk Concert",
                      "Snake in the Eagle's Shadow (1978) | Full Martial Arts Movie"):
            self.assertTrue(filters.english_speech(title), title)

    def test_a_place_name_is_not_a_language(self):
        # Diacritics in a proper noun are not evidence of anything; these are English broadcasts.
        for title in ("Race Highlights | Rolex 6 Hours of Sao Paulo 2026 | FIA WEC",
                      "2026 SoCal Tuna Fishing (El Nino)",
                      "IZMIR TURKEY 2026 4K WALKING TOUR | Kemeralti Bazaar"):
            self.assertTrue(filters.english_speech(title), title)

    def test_the_markers_must_come_from_one_language(self):
        # A global music channel's titles borrow a word each from several languages. Counting
        # across languages would read that as foreign when it is nothing of the kind.
        self.assertTrue(filters.english_speech("Una Mas | Dan Auerbach | Live Sessions"))
