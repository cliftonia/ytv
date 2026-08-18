#!/usr/bin/env python3
"""The order a channel is filled in.

Nothing here touches the network. `queries_for` decides what every channel on the dial gets asked
for and in which order, which makes it the single largest influence on what a viewer finds when
they tune - and while it was an inline block inside refresh() it could not be reached at all
without running yt-dlp ninety times.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import search


class TestQueriesFor(unittest.TestCase):

    def test_this_year_is_asked_first_then_the_bare_query(self):
        # A bias rather than a filter: the list is walked until the channel is full, so a channel
        # with nothing from this year falls through to the unqualified query instead of emptying.
        self.assertEqual(["ufc 2026", "ufc", "ufc full"],
                         search.queries_for("ufc", "ufc", None, 2026))

    def test_a_period_channel_is_never_asked_for_this_year(self):
        # Asking a 1950s channel for this year's uploads returns reaction videos and
        # retrospectives about the era rather than anything from it.
        self.assertEqual(["western movies", "western movies full"],
                         search.queries_for("western movies", "westerns", None, 2026))

    def test_named_programmes_come_last(self):
        # They beat a genre word every time, but the channel still leads with the broad sweep so
        # its identity is the query rather than whichever five shows are listed in dial.py.
        attempts = search.queries_for("panel show", "panel", ["QI full episode", "Taskmaster"],
                                      2026)
        self.assertEqual(["panel show 2026", "panel show", "panel show full",
                          "QI full episode", "Taskmaster"], attempts)

    def test_a_period_channel_still_gets_its_extras(self):
        self.assertEqual(["gunsmoke", "gunsmoke full", "Bonanza"],
                         search.queries_for("gunsmoke", "westerns", ["Bonanza"], 2026))

    def test_the_year_is_a_parameter_so_the_answer_does_not_move(self):
        # Read from the clock inside the function this would need rewriting every January, and
        # the caller is the only place that should know what today is.
        self.assertEqual("blues 1999", search.queries_for("blues", "blues", None, 1999)[0])

    def test_no_extras_is_the_same_as_an_empty_list(self):
        self.assertEqual(search.queries_for("jazz", "jazz", None, 2026),
                         search.queries_for("jazz", "jazz", [], 2026))


class TestCollect(unittest.TestCase):
    """The sift, with yt-dlp replaced by a list of rows."""

    def setUp(self):
        self.real = search.ytdlp

    def tearDown(self):
        search.ytdlp = self.real

    def rows(self, *rows):
        search.ytdlp = lambda target, timeout=300: list(rows)

    def test_a_clip_outside_the_duration_window_is_not_taken(self):
        # A song is three minutes and a documentary is fifty; the wrong window is what once left
        # a music channel with seventeen clips on it.
        self.rows(("aaaaaaaaaaa", 30, "Short thing"), ("bbbbbbbbbbb", 200, "A song"))
        out = []
        self.assertEqual(1, search.collect("x", 60, 420, set(), set(), out, 10))
        self.assertEqual("A song", out[0]["title"])

    def test_two_uploads_of_one_song_land_once(self):
        self.rows(("aaaaaaaaaaa", 200, "Queen - Bohemian Rhapsody (Official Video)"),
                  ("bbbbbbbbbbb", 201, "Queen - Bohemian Rhapsody [Official Video]"))
        out = []
        self.assertEqual(1, search.collect("x", 60, 420, set(), set(), out, 10))

    def test_two_episodes_of_one_series_both_survive(self):
        # The bug that emptied every episodic channel: a key that collapsed "Episode 1" and
        # "Episode 2" reduced a season playlist to one clip before search had even begun.
        self.rows(("aaaaaaaaaaa", 1400, "Dr. STONE Episode 1 English Dub"),
                  ("bbbbbbbbbbb", 1400, "Dr. STONE Episode 2 English Dub"))
        out = []
        self.assertEqual(2, search.collect("x", 1200, 9000, set(), set(), out, 10))

    def test_it_stops_at_the_target(self):
        self.rows(*[("id%08d" % n, 200, "Song number %d" % n) for n in range(10)])
        out = []
        search.collect("x", 60, 420, set(), set(), out, 3)
        self.assertEqual(3, len(out))

    def test_a_url_already_taken_from_an_earlier_query_is_skipped(self):
        # `seen` and `keys` carry across every playlist and query for one channel, which is what
        # stops the broad sweep and the named-programme extras duplicating each other.
        self.rows(("aaaaaaaaaaa", 200, "A song"))
        seen = {"https://www.youtube.com/watch?v=aaaaaaaaaaa"}
        out = []
        self.assertEqual(0, search.collect("x", 60, 420, seen, set(), out, 10))


if __name__ == "__main__":
    unittest.main()
