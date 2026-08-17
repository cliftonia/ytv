#!/usr/bin/env python3
"""Invariants of the declared dial.

These are the faults that reach a television and cannot be taken back: the app caches whatever
parses, so a duplicate channel number or a channel pointing at the wrong conf is live until
somebody notices and pushes a fix. Checking here costs a second.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import dial


class TestDial(unittest.TestCase):

    def test_channel_numbers_are_unique(self):
        # Two channels on one number means one is unreachable from the remote, and which one wins
        # depends on sort order - the kind of fault that surfaces months later as "that channel
        # just vanished".
        numbers = [c[0] for c in dial.YOUTUBE] + [c[0] for c in dial.LIVE]
        duplicates = sorted({n for n in numbers if numbers.count(n) > 1})
        self.assertEqual([], duplicates, "duplicate channel numbers: %s" % duplicates)

    def test_slugs_are_unique(self):
        # Two entries sharing a slug means one silently overwrites the other's conf.
        slugs = [c[1] for c in dial.YOUTUBE]
        duplicates = sorted({s for s in slugs if slugs.count(s) > 1})
        self.assertEqual([], duplicates, "duplicate slugs: %s" % duplicates)

    def test_youtube_block_is_contiguous_from_one(self):
        # A gap is a channel that tunes to nothing when someone flips past it.
        numbers = sorted(c[0] for c in dial.YOUTUBE)
        self.assertEqual(1, numbers[0], "the dial must start at 1")
        self.assertEqual(list(range(1, len(numbers) + 1)), numbers,
                         "the youtube block must be contiguous")

    def test_youtube_and_live_blocks_do_not_overlap(self):
        # The gap between them is deliberate: "the clip channels end here" is a place on the
        # remote rather than a number to remember.
        highest_youtube = max(c[0] for c in dial.YOUTUBE)
        lowest_live = min(c[0] for c in dial.LIVE)
        self.assertLess(highest_youtube, lowest_live)
        self.assertGreaterEqual(lowest_live, 101,
                                "the live block starts at 101 by convention")

    def test_every_channel_has_a_name_and_a_query(self):
        for number, slug, name, query in dial.YOUTUBE:
            self.assertTrue(name.strip(), "channel %d has no name" % number)
            self.assertTrue(query.strip(), "%s has no search query" % slug)

    def test_names_are_unique(self):
        # Duplicated names are indistinguishable in the picker.
        names = [c[2] for c in dial.YOUTUBE] + [c[2] for c in dial.LIVE]
        duplicates = sorted({n for n in names if names.count(n) > 1})
        self.assertEqual([], duplicates, "duplicate channel names: %s" % duplicates)

    def test_extras_reference_real_channels(self):
        # A playlist or extra query attached to a slug that no longer exists does nothing, and
        # does it silently - the channel simply stays thin and nobody connects the two.
        slugs = {c[1] for c in dial.YOUTUBE}
        for name, mapping in (("PLAYLISTS", dial.PLAYLISTS),
                              ("EXTRA_QUERIES", dial.EXTRA_QUERIES)):
            for slug in mapping:
                self.assertIn(slug, slugs, "%s names %r, which is not a channel" % (name, slug))
        for slug in dial.SEQUENCED:
            self.assertIn(slug, slugs, "SEQUENCED names %r, which is not a channel" % slug)

    def test_absorbed_channels_point_at_something_that_exists(self):
        slugs = {c[1] for c in dial.YOUTUBE}
        for gone, survivor in dial.ABSORBED.items():
            self.assertIn(survivor, slugs,
                          "%s was merged into %s, which is not on the dial" % (gone, survivor))
            self.assertNotIn(gone, slugs, "%s is both absorbed and on the dial" % gone)

    def test_dropped_channels_are_not_also_on_the_dial(self):
        slugs = {c[1] for c in dial.YOUTUBE}
        for gone in dial.DROPPED:
            self.assertNotIn(gone, slugs, "%s is both dropped and on the dial" % gone)

    def test_live_channels_declare_urls_or_already_have_a_conf(self):
        confs = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "confs")
        for number, slug, name, streams in dial.LIVE:
            if streams is None:
                self.assertTrue(os.path.exists(os.path.join(confs, "%s.json" % slug)),
                                "%s declares no urls and has no conf to inherit them from" % slug)
            else:
                for title, url in streams:
                    self.assertTrue(url.startswith("http"), "%s has a malformed url" % slug)


if __name__ == "__main__":
    unittest.main()
