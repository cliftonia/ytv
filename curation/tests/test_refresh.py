#!/usr/bin/env python3
"""The refresher's pure logic, which had no tests at all.

Both of the worst bugs found in this pipeline lived here, and both were invisible: the nightly
job went green every night while refreshing the same eight channels and deleting every series
from the episodic ones. Neither had a test, and neither produced a failure anywhere.
"""
import io
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import refresh_channels as refresh


class TestTitleKey(unittest.TestCase):
    """Deduplication must not delete the thing that distinguishes two episodes."""

    def test_episodes_of_one_series_are_distinct(self):
        # This is the bug. The key used to strip standalone digits, so every episode of every
        # series collapsed to one entry - which emptied season playlists before search even ran,
        # and made the sequencing feature permanently useless.
        self.assertNotEqual(
            refresh.title_key("Dr. STONE Episode 1 English Dub | Stone World"),
            refresh.title_key("Dr. STONE Episode 2 English Dub | Stone World"))
        self.assertNotEqual(
            refresh.title_key('I Married Joan S1-15 "Uncle Edgar"'),
            refresh.title_key('I Married Joan S1-16 "Uncle Edgar"'))
        self.assertNotEqual(
            refresh.title_key("Lock Up 50s TV Crime Series episode 24 of 26"),
            refresh.title_key("Lock Up 50s TV Crime Series episode 25 of 26"))

    def test_the_same_clip_from_two_uploaders_still_collapses(self):
        # The reason the key is loose at all: punctuation and case vary between uploaders of the
        # identical thing, and a channel should not carry it twice.
        self.assertEqual(
            refresh.title_key("Queen - Bohemian Rhapsody (Official Video)"),
            refresh.title_key("Queen — Bohemian Rhapsody [Official Video]"))

    def test_an_empty_or_missing_title_does_not_raise(self):
        self.assertEqual("", refresh.title_key(""))
        self.assertEqual("", refresh.title_key(None))


class TestRotation(unittest.TestCase):
    """The cursor has to survive a fresh clone, because CI always starts with one."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, slug, stamp=None):
        station = {"network_name": slug, "channel_number": 1, "search_query": "x", "streams": []}
        if stamp is not None:
            station["last_refreshed"] = stamp
        path = os.path.join(self.dir, "ytch_%s.json" % slug)
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": station}, handle)
        return path

    def test_the_cursor_is_read_from_the_file_not_the_filesystem(self):
        # The whole bug in one assertion. Under actions/checkout every conf carries the same
        # mtime, so an mtime-based rotation degenerated to alphabetical order and the same eight
        # channels refreshed every night for weeks while 82 never did.
        old = self.write("zebra", stamp=1000)
        new = self.write("alpha", stamp=9000)
        # `zebra` sorts last alphabetically and last by mtime, and must still be chosen first
        # because its stamp is the oldest.
        self.assertEqual([old, new], sorted([new, old], key=refresh.last_refreshed))

    def test_a_channel_never_refreshed_goes_first(self):
        never = self.write("never")
        recent = self.write("recent", stamp=9000)
        self.assertEqual([never, recent], sorted([recent, never], key=refresh.last_refreshed))

    def test_an_unreadable_conf_sorts_first_rather_than_raising(self):
        # It will then fail loudly in refresh() instead of silently skewing the rotation.
        path = os.path.join(self.dir, "ytch_broken.json")
        with io.open(path, "w") as handle:
            handle.write("{ not json")
        self.assertEqual(0, refresh.last_refreshed(path))


class TestWindows(unittest.TestCase):

    def test_songs_get_a_song_length_window(self):
        low, high = refresh.window("music_1980s")
        self.assertLessEqual(low, 180)
        self.assertLessEqual(high, 600)

    def test_documentaries_get_a_long_window(self):
        low, high = refresh.window("documentaries")
        self.assertGreaterEqual(low, 600)

    def test_era_channels_are_exempt_from_the_current_year(self):
        # Asking a 1950s channel for this year's uploads returns retrospectives about the era
        # rather than anything from it.
        self.assertTrue(refresh.is_period("music_1950s"))
        self.assertTrue(refresh.is_period("westerns"))
        self.assertFalse(refresh.is_period("ufc"))


class TestUsable(unittest.TestCase):

    def test_commentary_is_rejected(self):
        self.assertFalse(refresh.usable("Top 20 Greatest Anime of All Time"))
        self.assertFalse(refresh.usable("I Watched 30 Anime So You Don't Have To"))

    def test_actual_content_is_kept(self):
        self.assertTrue(refresh.usable("Dr. STONE Episode 1 English Dub"))
        self.assertTrue(refresh.usable("QI Full Episode - Series S, EP 6"))

    def test_an_empty_title_is_not_usable(self):
        self.assertFalse(refresh.usable(""))
        self.assertFalse(refresh.usable(None))


if __name__ == "__main__":
    unittest.main()


class TestEnglishSpeech(unittest.TestCase):
    """Telling the language OF the content from the language AS its subject.

    Every title here is real. A blunter filter - non-Latin characters, or simply the word "Hindi"
    anywhere - gets the first three wrong, and those are exactly the clips worth keeping.
    """

    def test_an_explicit_english_marker_wins_outright(self):
        # A Japanese OVA labelled Eng Dub is precisely what Classic Anime wants, original title
        # and all. Rejecting it for the characters in its own name would empty the channel.
        self.assertTrue(refresh.english_speech("Angel Cop [Eng Dub] (OVA - 1989) エンゼル コップ"))
        self.assertTrue(refresh.english_speech(
            "【My Brother, the Mafia Godfather】Full episode丨【ENG DUB】"))

    def test_a_language_naming_the_subject_is_kept(self):
        # "Filipino" describes the food and "Japanese" the style; both are English-language clips.
        self.assertTrue(refresh.english_speech(
            "Philippines Street Food!! 14 Hour FILIPINO STREET FOOD Tour in Cagayan de Oro"))
        self.assertTrue(refresh.english_speech(
            "Flow Of Time Japanese LoFi HipHop Mix - Collection 時間の流れ"))
        # A pipe is a segment boundary, so the language and the media noun are not related.
        self.assertTrue(refresh.english_speech("Japanese Garden Design | Documentary"))

    def test_a_language_naming_the_audio_is_rejected(self):
        self.assertFalse(refresh.english_speech("HARIMIYA LOVE STORY FULL SEASON 1 HINDI DUBBED"))
        self.assertFalse(refresh.english_speech("Tamil Christian Short Film | Dowry Awareness"))
        self.assertFalse(refresh.english_speech(
            "India v Pakistan | Urdu Highlights | Men's T20 World Cup"))

    def test_a_feed_that_names_its_language_is_rejected(self):
        # The Hindi feed of an otherwise English documentary strand, and an Indian regional
        # broadcaster. Both end a pipe segment with the language, which is how feeds label
        # themselves.
        self.assertFalse(refresh.english_speech(
            "Cute to Killer: The Journey of a Lion | National Geographic Hindi | Wildlife"))
        self.assertFalse(refresh.english_speech(
            "Jabardasth | 2nd August 2026 | Full Episode | ETV Telugu"))

    def test_a_title_mostly_in_another_script_is_rejected(self):
        self.assertFalse(refresh.english_speech(
            "New Season | KBC S17 | Ep. 75 | Full Episode | "
            "KBC के मंच पर पहुंची "
            "दिग्गजों की टोली"))
        self.assertFalse(refresh.english_speech(
            "COMEDY CLUB новое: 21 сезон"))

    def test_ordinary_english_titles_are_untouched(self):
        for title in ("QI Full Episode - Series S, EP 6 Featuring Aisling Bea",
                      "Queen - Bohemian Rhapsody (Official Video Remastered)",
                      "Sarah McLachlan: Tiny Desk Concert"):
            self.assertTrue(refresh.english_speech(title), title)

    def test_an_empty_title_is_not_english(self):
        self.assertFalse(refresh.english_speech(""))
        self.assertFalse(refresh.english_speech(None))

    def test_a_single_indic_or_cyrillic_character_is_enough(self):
        # Unlike Japanese, these scripts never appear as decoration in an English title. A
        # programme whose title is mostly English but carries a few Devanagari words is a Hindi
        # programme, and a share-based threshold let every one of those through.
        self.assertFalse(refresh.english_speech(
            "New Season | KBC S18 | Ep. 1 | Full Episode | Sunny "
            "के Dhai Kilo"))
        self.assertFalse(refresh.english_speech(
            "Motu और Patlu Japan Martial Arts Adventure | Kung-Fu Kings"))

    def test_japanese_and_korean_may_still_decorate_an_english_title(self):
        # The distinction that makes the rule above safe: an English-dubbed OVA keeps its original
        # title, and rejecting those would empty the Classic Anime channel.
        self.assertTrue(refresh.english_speech(
            "Flow Of Time Japanese LoFi HipHop Mix - Collection 時間の流れ"))
