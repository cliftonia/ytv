#!/usr/bin/env python3
"""Episode parsing and ordering.

Every title below is a real one taken off the dial. That matters more than invented cases here:
the whole difficulty is that uploaders write episode numbers a dozen different ways, and a parser
tested only against tidy input will meet none of them.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import sequence


class TestParse(unittest.TestCase):

    def test_season_and_episode_run_together(self):
        show, season, episode = sequence.parse(
            "George Burns & Gracie Allen Show S2E22 Gracie confuses a desk")
        self.assertEqual("2", season)
        self.assertEqual(22, episode)
        self.assertIn("burns", show)

    def test_season_and_episode_split_by_a_hyphen(self):
        _, season, episode = sequence.parse('I Married Joan S1-15 "Uncle Edgar" 01/21/1953')
        self.assertEqual("1", season)
        self.assertEqual(15, episode)

    def test_spelled_out_season_and_episode(self):
        _, season, episode = sequence.parse(
            'Full Anime | "Attack on Titan" Season 1 Ep.12 (English Dub)')
        self.assertEqual("1", season)
        self.assertEqual(12, episode)

    def test_qi_counts_its_series_in_letters(self):
        # This is why season is a string. Comparing "D" against 2 would raise, and QI is the
        # single biggest run on the whole dial.
        show, season, episode = sequence.parse(
            "QI FULL EPISODE! 'Dogs' Episode 3, Series D Jeremy Clarkson")
        self.assertEqual("D", season)
        self.assertEqual(3, episode)
        self.assertEqual("qi", show)

    def test_series_before_episode_also_parses(self):
        _, season, episode = sequence.parse(
            "QI Full Episode - Series S, EP 6 Featuring Aisling Bea")
        self.assertEqual("S", season)
        self.assertEqual(6, episode)

    def test_a_bare_episode_number_assumes_one_season(self):
        show, season, episode = sequence.parse(
            "Dr. STONE Episode 1 English Dub | Stone World")
        self.assertEqual("1", season)
        self.assertEqual(1, episode)
        self.assertIn("dr", show)

    def test_titles_with_no_episode_are_not_parsed(self):
        self.assertIsNone(sequence.parse("A Silent Voice Full Movie [English dub]"))
        self.assertIsNone(sequence.parse("Sarah McLachlan: Tiny Desk Concert"))
        self.assertIsNone(sequence.parse(""))

    def test_a_bare_series_letter_is_read_as_the_season(self):
        # QI uploaders leave the word "Series" off about a third of the time. Parsed as season 1,
        # those sorted ahead of every genuine lettered series - so the largest run on the whole
        # dial opened in the middle of itself.
        show, season, episode = sequence.parse(
            "QI FULL EPISODE! 'Death' Episode 5 D Sean Lock, Jeremy Hardy")
        self.assertEqual("D", season)
        self.assertEqual(5, episode)
        self.assertEqual("qi", show)

    def test_a_bare_letter_sorts_with_its_own_series(self):
        self.assertLess(sequence.sort_key("D", 1), sequence.sort_key("D", 5))
        self.assertLess(sequence.sort_key("D", 5), sequence.sort_key("E", 1))

    def test_qi_xl_is_the_same_show_as_qi(self):
        # The old test was named for this case and its body did not contain an XL title, so it
        # passed while asserting nothing about it.
        self.assertEqual(sequence.show_name("QI XL Full Episode"),
                         sequence.show_name("QI Full Episode"))

    def test_the_qi_variants_land_in_one_bucket(self):
        # "QI Full Episode", "QI XL Full Episode" and "QI FULL EPISODE!" are the same programme.
        # Before the noise words were stripped these were three shows and none of them sorted.
        names = {sequence.parse(t)[0] for t in (
            "QI Full Episode - Series S, EP 6 Featuring Aisling Bea",
            "QI FULL EPISODE! 'Dogs' Episode 3, Series D Jeremy Clarkson",
        )}
        self.assertEqual({"qi"}, names)


class TestSortKey(unittest.TestCase):

    def test_episodes_order_within_a_season(self):
        self.assertLess(sequence.sort_key("1", 2), sequence.sort_key("1", 10))

    def test_seasons_order_before_episodes(self):
        self.assertLess(sequence.sort_key("1", 99), sequence.sort_key("2", 1))

    def test_numeric_and_lettered_seasons_can_be_compared(self):
        # A channel carrying both must produce an order, not a TypeError.
        keys = sorted([sequence.sort_key("D", 1), sequence.sort_key("2", 1)])
        self.assertEqual(2, len(keys))


class TestOrdered(unittest.TestCase):

    def clip(self, title, duration=1400):
        return {"url": "https://www.youtube.com/watch?v=x", "duration": duration, "title": title}

    def test_a_series_is_put_in_episode_order(self):
        streams = [self.clip("Show Episode 3"), self.clip("Show Episode 1"),
                   self.clip("Show Episode 2")]
        titles = [s["title"] for s in sequence.ordered(streams)]
        self.assertEqual(["Show Episode 1", "Show Episode 2", "Show Episode 3"], titles)

    def test_nothing_is_lost_or_duplicated(self):
        # The one guarantee that matters: reordering must never change what is on the channel.
        streams = [self.clip("Show Episode 2"), self.clip("A documentary"),
                   self.clip("Show Episode 1"), self.clip("Another one-off")]
        out = sequence.ordered(streams)
        self.assertEqual(len(streams), len(out))
        self.assertEqual(sorted(s["title"] for s in streams),
                         sorted(s["title"] for s in out))

    def test_unparsed_clips_keep_their_order_at_the_end(self):
        streams = [self.clip("Loose one"), self.clip("Show Episode 1"),
                   self.clip("Loose two"), self.clip("Show Episode 2")]
        titles = [s["title"] for s in sequence.ordered(streams)]
        self.assertEqual(["Show Episode 1", "Show Episode 2", "Loose one", "Loose two"], titles)

    def test_a_lone_episode_is_not_treated_as_a_series(self):
        # One episode is not a sequence. Hoisting it to the front would shuffle a channel that had
        # nothing to gain, which is exactly what this must not do.
        streams = [self.clip("Loose"), self.clip("Solo Show Episode 4")]
        titles = [s["title"] for s in sequence.ordered(streams)]
        self.assertEqual(["Solo Show Episode 4", "Loose"], titles)
        self.assertEqual([], sequence.runs(streams))

    def test_the_longest_series_leads(self):
        streams = ([self.clip("Big Episode %d" % i) for i in range(1, 5)] +
                   [self.clip("Small Episode %d" % i) for i in range(1, 3)])
        titles = [s["title"] for s in sequence.ordered(streams)]
        self.assertTrue(titles[0].startswith("Big"))
        self.assertEqual("Big Episode 1", titles[0])

    def test_ordering_is_idempotent(self):
        streams = [self.clip("Show Episode 2"), self.clip("Show Episode 1"), self.clip("Loose")]
        once = sequence.ordered(streams)
        self.assertEqual([s["title"] for s in once],
                         [s["title"] for s in sequence.ordered(once)])

    def test_an_empty_channel_survives(self):
        self.assertEqual([], sequence.ordered([]))


if __name__ == "__main__":
    unittest.main()
