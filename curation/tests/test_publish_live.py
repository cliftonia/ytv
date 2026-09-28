#!/usr/bin/env python3
"""live.json: the LIVE TV dial, published from the draft and the owner's ticks.

The app reads it with the same Channel model as the other two dials, so every channel must be a
live feed it already knows how to play - and the optional fields only this dial adds (block, sub,
breaks, guide) must never be anything an older app would trip over.
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import publish_live

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def pluto(number, name, block="Movies", sub="Action", pid="a" * 24, default=True):
    return {"number": number, "name": name, "block": block, "sub": sub, "source": "pluto",
            "pluto": {"id": pid, "region": "us"}, "default": default,
            "streams": [{"url": "https://jmp2.uk/plu-%s.m3u8" % pid, "duration": 600, "title": name}],
            "guide": "pluto", "guide_id": pid}


def fast(number, name, block="Movies", sub="Action", source="us_samsung", route="direct",
         guide="samsung", gid="US1", default=True, url=None, tvg=None):
    return {"number": number, "name": name, "block": block, "sub": sub, "source": source,
            "url": url or "https://cdn.example.com/%s.m3u8" % number, "route": route,
            "guide": guide, "guide_id": gid, "default": default, "tvg_id": tvg}


BLOCKS = [
    {"name": "Movies", "first_number": 100, "sub": [
        {"name": "Action", "first_number": 100}, {"name": "Comedy", "first_number": 110}]},
    {"name": "Music", "first_number": 300, "sub": [{"name": "Pop", "first_number": 300}]},
    {"name": "Relax", "first_number": 400, "sub": [{"name": "Relax", "first_number": 400}]},
]


class TestPicks(unittest.TestCase):

    def setUp(self):
        self.channels = [fast(100, "A"), fast(101, "B"), fast(400, "Calm", "Relax", "Relax", default=False)]

    def test_no_ticks_means_every_default_on_channel_and_nothing_else(self):
        self.assertEqual(["A", "B"], [c["name"] for c in publish_live.picked(self.channels, set(), set())])

    def test_off_unticks_a_default_channel_and_on_ticks_a_default_off_one(self):
        chosen = publish_live.picked(self.channels, {"A"}, {"Calm"})
        self.assertEqual(["B", "Calm"], [c["name"] for c in chosen])

    def test_a_missing_picks_file_is_no_changes(self):
        self.assertEqual((set(), set()), publish_live.load_picks("/nonexistent/live_picks.json"))

    def test_picks_are_read_by_name(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({"off": ["A"], "on": ["Calm"]}, f)
        try:
            self.assertEqual(({"A"}, {"Calm"}), publish_live.load_picks(f.name))
        finally:
            os.unlink(f.name)

    def test_a_name_on_two_draft_channels_is_refused(self):
        problem = publish_live.picks_problem(self.channels + [fast(102, "A")], set(), set())
        self.assertIn("'A'", problem)

    def test_a_tick_naming_no_channel_is_refused(self):
        self.assertIn("Gone", publish_live.picks_problem(self.channels, {"Gone"}, set()))
        with self.assertRaises(ValueError):
            publish_live.build({"blocks": BLOCKS, "channels": self.channels}, set(), {"Gone"})

    def test_the_committed_draft_has_unique_names_and_the_picks_fit_it(self):
        with open(publish_live.DRAFT) as f:
            draft = json.load(f)
        off, on = publish_live.load_picks(publish_live.PICKS)
        self.assertIsNone(publish_live.picks_problem(draft["channels"], off, on))


class TestRenumber(unittest.TestCase):

    def numbers(self, channels):
        return [(n, c["name"]) for n, c in publish_live.renumber(BLOCKS, channels)]

    def test_gaps_close_with_blocks_on_hundreds_and_subs_on_tens(self):
        channels = [fast(100, "A1"), fast(104, "A2"), fast(113, "C1", sub="Comedy"),
                    fast(305, "P1", "Music", "Pop")]
        self.assertEqual([(100, "A1"), (101, "A2"), (110, "C1"), (200, "P1")], self.numbers(channels))

    def test_order_within_a_sub_block_is_the_drafts(self):
        channels = [fast(103, "Late"), fast(101, "Early")]
        self.assertEqual([(100, "Early"), (101, "Late")], self.numbers(channels))

    def test_an_empty_sub_block_and_an_empty_block_take_no_numbers(self):
        channels = [fast(110, "C1", sub="Comedy"), fast(400, "Calm", "Relax", "Relax")]
        self.assertEqual([(100, "C1"), (200, "Calm")], self.numbers(channels))

    def test_a_full_sub_block_is_followed_by_the_next_ten(self):
        channels = [fast(100 + i, "A%d" % i) for i in range(10)] + [fast(110, "C1", sub="Comedy")]
        self.assertEqual((110, "C1"), self.numbers(channels)[-1])

    def test_a_block_over_a_hundred_runs_on_and_moves_the_next_block_up(self):
        channels = [fast(i, "A%d" % i) for i in range(100, 205)] + [fast(305, "P1", "Music", "Pop")]
        self.assertEqual((300, "P1"), self.numbers(channels)[-1])

    def test_a_channel_outside_the_drafts_blocks_is_an_error_not_a_loss(self):
        with self.assertRaises(ValueError):
            publish_live.renumber(BLOCKS, [fast(900, "Lost", "Nowhere", "Nothing")])


class TestRecord(unittest.TestCase):

    def test_a_pluto_channel_keeps_its_ref_and_jmp2_stream_and_takes_no_cue(self):
        out = publish_live.lineup_record(100, pluto(5, "Action"))
        self.assertEqual({"id": "a" * 24, "region": "us"}, out["pluto"])
        self.assertEqual("https://jmp2.uk/plu-%s.m3u8" % ("a" * 24), out["streams"][0]["url"])
        self.assertEqual(("live", "Movies", "Action"), (out["kind"], out["block"], out["sub"]))
        self.assertNotIn("breaks", out)
        self.assertNotIn("guide", out)

    def test_a_fast_channel_is_a_live_feed_with_cue_breaks_and_its_guide(self):
        out = publish_live.lineup_record(101, fast(7, "Film Four"))
        self.assertEqual([{"url": "https://cdn.example.com/7.m3u8", "duration": 600, "title": "Film Four"}],
                         out["streams"])
        self.assertEqual("cue", out["breaks"])
        self.assertEqual(("samsung", "US1"), (out["guide"], out["guide_id"]))
        self.assertNotIn("pluto", out)

    def test_a_us_only_feed_carries_the_route_the_relay_reads(self):
        out = publish_live.lineup_record(101, fast(7, "Far", route="us"))
        self.assertEqual("us", out["streams"][0]["route"])

    def test_music_takes_no_cue_by_block_or_by_stingray(self):
        self.assertNotIn("breaks", publish_live.lineup_record(1, fast(1, "M", "Music", "Pop")))
        self.assertNotIn("breaks", publish_live.lineup_record(1, fast(1, "S", source="ca_stingray")))

    def test_every_fast_guide_service_is_published(self):
        for guide, gid in (("tubi", "400000012"), ("rakuten", "sci-fi-rakuten-tv"), ("stirr", "5294"),
                           ("xumo", "99991333")):
            out = publish_live.lineup_record(1, fast(1, "N", guide=guide, gid=gid))
            self.assertEqual((guide, gid), (out["guide"], out["guide_id"]))

    def test_no_guide_or_a_pluto_guide_is_left_out(self):
        self.assertNotIn("guide", publish_live.lineup_record(1, fast(1, "N", guide="none", gid=None)))
        self.assertNotIn("guide", publish_live.lineup_record(1, fast(1, "X", guide="xumo", gid=None)))


class TestGuideRule(unittest.TestCase):
    """LIVE TV carries only channels that can show what is playing."""

    def build(self, channels, on=()):
        built, unguided = publish_live.build({"blocks": BLOCKS, "channels": channels}, set(), set(on))
        return [(c["number"], c["name"]) for c in built], unguided

    def test_a_fast_channel_without_a_guide_is_left_out_and_named(self):
        got, unguided = self.build([fast(100, "Seen"), fast(101, "Blind", guide="none", gid=None),
                                    fast(102, "Absent", guide=None, gid=None)])
        self.assertEqual([(100, "Seen")], got)
        self.assertEqual(["Blind", "Absent"], unguided)

    def test_a_carried_guide_with_no_id_is_no_guide(self):
        self.assertEqual(["Idless"], self.build([fast(100, "Idless", guide="xumo", gid=None)])[1])

    def test_pluto_is_always_kept(self):
        channel = dict(pluto(100, "Pluto Action"), guide=None, guide_id=None)
        self.assertEqual(([(100, "Pluto Action")], []), self.build([channel]))

    def test_stingray_music_stays_when_it_has_a_guide(self):
        got, unguided = self.build([fast(300, "Hits", "Music", "Pop", source="ca_stingray", guide="plex", gid="5f"),
                                    fast(301, "Hush", "Music", "Pop", source="ca_stingray", guide="none", gid=None)])
        self.assertEqual([(100, "Hits")], got, "Music is the first block with a channel")
        self.assertEqual(["Hush"], unguided)

    def test_the_rest_close_up_as_numbering_always_does(self):
        got, _ = self.build([fast(100, "A1"), fast(101, "Gap", guide="none", gid=None), fast(102, "A2"),
                             fast(110, "C1", sub="Comedy"), fast(300, "P1", "Music", "Pop")])
        self.assertEqual([(100, "A1"), (101, "A2"), (110, "C1"), (200, "P1")], got)

    def test_a_sub_block_and_a_block_left_empty_disappear(self):
        got, _ = self.build([fast(100, "Gone", guide="none", gid=None), fast(110, "C1", sub="Comedy"),
                             fast(300, "Quiet", "Music", "Pop", guide="none", gid=None),
                             fast(400, "Calm", "Relax", "Relax")])
        self.assertEqual([(100, "C1"), (200, "Calm")], got)

    def test_a_ticked_channel_without_a_guide_is_still_left_out(self):
        got, unguided = self.build([fast(400, "Calm", "Relax", "Relax", guide="none", gid=None, default=False)],
                                   on={"Calm"})
        self.assertEqual(([], ["Calm"]), (got, unguided))

    def test_the_same_draft_numbers_the_same_every_night(self):
        channels = [fast(100 + i, "A%d" % i, guide="none" if i % 3 else "samsung") for i in range(12)]
        self.assertEqual(self.build(channels), self.build(list(channels)))


class TestDeadLinks(unittest.TestCase):

    def run_alive(self, channels, pluto_streams=None, fast_entries=(), allowlist=()):
        draft = {"blocks": BLOCKS, "channels": channels}
        built, _ = publish_live.build(draft, set(), set())
        streams = pluto_streams or {}
        regions = {pid: "uk" for pid in streams}
        kept, dropped = publish_live.alive(built, streams, regions, list(fast_entries), list(allowlist))
        return [publish_live.public(c) for c in kept], dropped

    def test_a_listed_fast_url_stays_and_an_unlisted_one_is_dropped(self):
        entries = [{"source": "us_samsung", "url": "https://cdn.example.com/100.m3u8", "tvg_id": "x"}]
        kept, dropped = self.run_alive([fast(100, "Live"), fast(101, "Dead")], fast_entries=entries)
        self.assertEqual(["Live"], [c["name"] for c in kept])
        self.assertEqual(["Dead"], dropped)

    def test_a_dropped_channel_leaves_a_gap_rather_than_renumbering(self):
        entries = [{"source": "us_samsung", "url": "https://cdn.example.com/101.m3u8", "tvg_id": "x"}]
        kept, _ = self.run_alive([fast(100, "Dead"), fast(101, "Live")], fast_entries=entries)
        self.assertEqual([101], [c["number"] for c in kept])

    def test_a_repointed_url_on_the_same_host_is_followed_and_another_host_is_not(self):
        entries = [{"source": "us_samsung", "tvg_id": "Moved.us", "url": "https://cdn.example.com/new.m3u8"},
                   {"source": "us_samsung", "tvg_id": "Hijack.us", "url": "https://evil.example.org/x.m3u8"}]
        kept, dropped = self.run_alive([fast(100, "Moved", tvg="Moved.us"), fast(101, "Hijack", tvg="Hijack.us")],
                                       fast_entries=entries)
        self.assertEqual("https://cdn.example.com/new.m3u8", kept[0]["streams"][0]["url"])
        self.assertEqual(["Hijack"], dropped)

    def test_a_pluto_channel_follows_its_allowlist_alternative_like_build_pluto(self):
        uk, us = "b" * 24, "a" * 24
        streams = {uk: "https://jmp2.uk/plu-%s.m3u8" % uk}
        kept, _ = self.run_alive([pluto(100, "Action", pid=us)], pluto_streams=streams,
                                 allowlist=[{"id": uk, "alt": us, "name": "Action"}])
        self.assertEqual({"id": uk, "region": "uk"}, kept[0]["pluto"])
        self.assertEqual(streams[uk], kept[0]["streams"][0]["url"])

    def test_a_pluto_channel_no_playlist_carries_is_dropped(self):
        kept, dropped = self.run_alive([pluto(100, "Gone")], pluto_streams={"c" * 24: "u"})
        self.assertEqual(([], ["Gone"]), (kept, dropped))

    def test_losing_more_than_half_is_refused(self):
        self.assertIsNone(publish_live.refusal(6, 10))
        self.assertIsNotNone(publish_live.refusal(4, 10))
        self.assertIsNotNone(publish_live.refusal(0, 0))


class TestCommittedLineup(unittest.TestCase):
    """The real live.json, as the televisions will read it."""

    @classmethod
    def setUpClass(cls):
        path = os.path.join(REPO, "live.json")
        if not os.path.exists(path):
            raise unittest.SkipTest("no live.json yet")
        with open(path) as f:
            cls.channels = json.load(f)["channels"]

    def test_numbers_are_unique_and_ascending(self):
        numbers = [c["number"] for c in self.channels]
        self.assertEqual(sorted(set(numbers)), numbers)

    def test_every_channel_is_a_live_feed_the_app_plays(self):
        for c in self.channels:
            self.assertEqual("live", c["kind"], c["name"])
            self.assertTrue(c["streams"] and all("id" not in s for s in c["streams"]), c["name"])
            self.assertTrue(c["block"] and c["sub"], c["name"])

    def test_breaks_are_cue_or_absent_and_never_on_pluto(self):
        for c in self.channels:
            self.assertIn(c.get("breaks"), (None, "cue"), c["name"])
            if "pluto" in c:
                self.assertNotIn("breaks", c)

    def test_every_fast_channel_can_show_what_is_playing(self):
        for c in self.channels:
            if "pluto" not in c:
                self.assertIn(c.get("guide"), publish_live.FAST_GUIDES, c["name"])
                self.assertTrue(c.get("guide_id"), c["name"])

    def test_blocks_start_on_hundreds(self):
        firsts = {}
        for c in self.channels:
            firsts.setdefault(c["block"], c["number"])
        self.assertTrue(all(n % 100 == 0 for n in firsts.values()), firsts)


if __name__ == "__main__":
    unittest.main()


class OwnerOrderTest(unittest.TestCase):
    def test_horror_and_thriller_end_movies_and_crime_ends_series(self):
        blocks = [{"name": "Movies", "sub": [{"name": n} for n in ("Action", "Horror", "Thriller", "Drama", "Movies – Mixed")]},
                  {"name": "Series", "sub": [{"name": n} for n in ("Comedy", "Crime", "Reality")]},
                  {"name": "Music", "sub": [{"name": "80s"}]}]
        got = [[s["name"] for s in b["sub"]] for b in publish_live.owner_order(blocks)]
        self.assertEqual([["Action", "Drama", "Movies – Mixed", "Horror", "Thriller"],
                          ["Comedy", "Reality", "Crime"], ["80s"]], got)
