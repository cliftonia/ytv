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
