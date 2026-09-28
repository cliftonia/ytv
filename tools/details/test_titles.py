#!/usr/bin/env python3
"""Guide titles to lookup keys and search terms, on titles copied from the real guides (Samsung,
Roku, Plex and Pluto, 28 Sep 2026). A wrong poster is worse than none, so most of these are about
refusing to guess.

Run: python3 -m unittest discover -s tools/details"""
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import titles as tt  # noqa: E402
from titles import MOVIE, TV, Parsed  # noqa: E402


class TestKey(unittest.TestCase):

    def test_the_cases_the_app_shares(self):
        with open(os.path.join(HERE, "title_keys.json")) as f:
            cases = json.load(f)["cases"]
        for raw, want in cases:
            self.assertEqual(want, tt.key(raw), raw)

    def test_a_leading_the_is_the_only_difference_forgiven(self):
        self.assertTrue(tt.same("karate kid", "the karate kid"))
        self.assertFalse(tt.same("karate kid", "karate kid part ii"))
        self.assertFalse(tt.same("", ""))


class TestParse(unittest.TestCase):

    def test_a_movie_label_is_dropped_and_says_movie(self):
        self.assertEqual(Parsed("Fast Atlanta", MOVIE, None), tt.parse("Movie: Fast Atlanta"))
        self.assertEqual(Parsed("The Karate Kid", MOVIE, 1984), tt.parse("Movie: The Karate Kid (1984)"))

    def test_a_year_in_brackets_and_a_trailing_year(self):
        self.assertEqual(Parsed("The Jim Rome Podcast", TV, 2024), tt.parse("The Jim Rome Podcast(series, 2024)"))
        self.assertEqual(Parsed("Righteous Kill", None, 2008), tt.parse("Righteous Kill - 2008"))

    def test_an_episode_marker_ends_the_title(self):
        self.assertEqual(Parsed("My Secret,Terrius", TV, None), tt.parse("My Secret,Terrius Ep.16 (English Dub)"))
        self.assertEqual(Parsed("Cheers", TV, None), tt.parse("Cheers S2 E5 The Coach's Daughter"))
        self.assertEqual(Parsed("Daria", TV, None), tt.parse("Daria Season 2 Episode 9"))

    def test_nothing_before_the_episode_marker_is_nothing_to_search(self):
        for raw in ("S2 E3 Apocalyptic Visions", "S5 E99 Neighborhood Wars: Top 10 Moments of 2023",
                    "Series 21 Ep 9 with Tom Cruise", "S1 E8 Episode 08"):
            self.assertIsNone(tt.parse(raw), raw)

    def test_dub_and_format_tags_go(self):
        self.assertEqual(Parsed("KEY THE METAL IDOL"), tt.parse("KEY THE METAL IDOL (Dubbed)"))
        self.assertEqual(Parsed("Speed Racer"), tt.parse("Speed Racer [English-Language Version]"))
        self.assertEqual(Parsed("My Deer Friend Nokotan"), tt.parse("My Deer Friend Nokotan (English Dub)"))

    def test_sport_paid_programming_and_live_are_skipped(self):
        for raw in ("Cincinnati vs. Arizona (11.15.25) Football Full Game Replay",
                    "Arkansas vs. Utah Condensed Game (9.12.26)", "UCL Extended Highlights",
                    "Live: Football Weekend Round Up", "Paid Programming", "日本", ""):
            self.assertIsNone(tt.parse(raw), raw)

    def test_titles_that_merely_start_like_a_skip_are_kept(self):
        self.assertEqual(Parsed("Live and Let Die"), tt.parse("Live and Let Die"))
        self.assertEqual(Parsed("New Girl"), tt.parse("New Girl"))

    def test_colons_inside_real_titles_survive(self):
        self.assertEqual(Parsed("Seal Team Six: The Raid on Osama Bin Laden"),
                         tt.parse("Seal Team Six: The Raid on Osama Bin Laden"))


class TestPrefixes(unittest.TestCase):

    def test_a_prefix_over_two_different_titles_is_a_series(self):
        raws = ["Cosmic Vistas: Night Fire", "Cosmic Vistas: Fermi Paradox", "Mission: Impossible",
                "The Wedding Planners: Champagne Dream", "The Wedding Planners: Feuding Families",
                "Cosmic Vistas: Night Fire"]
        self.assertEqual({"cosmic vistas", "the wedding planners"}, tt.recurring_prefixes(raws))

    def test_the_first_separator_wins(self):
        self.assertEqual("The Super Simple Show", tt.prefix("The Super Simple Show - Counting And Numbers"))
        self.assertIsNone(tt.prefix("Righteous Kill"))


class TestHints(unittest.TestCase):

    def test_slot_length(self):
        self.assertEqual(MOVIE, tt.kind_from_minutes(112))
        self.assertEqual(TV, tt.kind_from_minutes(30))
        self.assertIsNone(tt.kind_from_minutes(60))
        self.assertIsNone(tt.kind_from_minutes(None))

    def test_year_of_a_tmdb_date(self):
        self.assertEqual(1984, tt.year_of("1984-06-22"))
        self.assertIsNone(tt.year_of(""))
        self.assertIsNone(tt.year_of(None))


if __name__ == "__main__":
    unittest.main()
