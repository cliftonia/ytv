#!/usr/bin/env python3
"""pluto.json: the hand-picked allowlist matched against iptv-org's playlists.

The output is read by the same app code as channels.json, so every channel must look exactly like
the live channels already on the YouTube dial - `kind: "live"`, no rotation, and a stream with no
`id`. A stream carrying an id would be sent to the YouTube resolver and never play.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import build_pluto

PLAYLIST = """#EXTM3U
#EXTINF:-1 tvg-id="Action.us@US",Pluto TV Action (720p)
https://jmp2.uk/plu-aaa111.m3u8
#EXTINF:-1 tvg-id="Kids.uk@UK",Pluto TV Kids
https://jmp2.uk/plu-bbb222.m3u8
#EXTINF:-1 tvg-id="",Not a pluto stream
https://example.com/other.m3u8
#EXTINF:-1 tvg-id="",Pluto-shaped path on a stranger's host
https://evil.example.com/plu-ccc333.m3u8
#EXTINF:-1 tvg-id="",Pluto's own cdn
https://service-stitcher.clusters.pluto.tv/v1/stitch/embed/hls/channel/plu-ddd444.m3u8
#EXTINF:-1 tvg-id="",A host that merely ends in the letters
https://notpluto.tv.evil.com/plu-eee555.m3u8
#EXTINF:-1 tvg-id="",Suffix without the dot
https://fakepluto.tv/plu-fff666.m3u8
"""


def entry(pid, name, genre, alt=None, number=1):
    e = {"number": number, "id": pid, "name": name, "genre": genre}
    if alt:
        e["alt"] = alt
    return e


class TestParse(unittest.TestCase):

    def test_reads_pluto_ids_and_urls_from_trusted_hosts_only(self):
        self.assertEqual({"aaa111": "https://jmp2.uk/plu-aaa111.m3u8",
                          "bbb222": "https://jmp2.uk/plu-bbb222.m3u8",
                          "ddd444": "https://service-stitcher.clusters.pluto.tv/v1/stitch/embed/hls/"
                                    "channel/plu-ddd444.m3u8"},
                         build_pluto.parse_m3u(PLAYLIST))

    def test_trusted_hosts(self):
        self.assertTrue(build_pluto.trusted_host("https://jmp2.uk/plu-a.m3u8"))
        self.assertTrue(build_pluto.trusted_host("https://pluto.tv/plu-a.m3u8"))
        self.assertTrue(build_pluto.trusted_host("https://x.pluto.tv/plu-a.m3u8"))
        self.assertFalse(build_pluto.trusted_host("https://fakepluto.tv/plu-a.m3u8"))
        self.assertFalse(build_pluto.trusted_host("https://jmp2.uk.evil.com/plu-a.m3u8"))
        self.assertFalse(build_pluto.trusted_host("not a url"))


class TestBuild(unittest.TestCase):

    streams = build_pluto.parse_m3u(PLAYLIST)

    def test_matches_by_id(self):
        channels, missing = build_pluto.build([entry("aaa111", "Pluto TV Action", "Movies")], self.streams)
        self.assertEqual([], missing)
        self.assertEqual("https://jmp2.uk/plu-aaa111.m3u8", channels[0]["streams"][0]["url"])

    def test_falls_back_to_the_other_playlist_id(self):
        channels, missing = build_pluto.build(
            [entry("gone999", "Pluto TV Action", "Movies", alt="aaa111")], self.streams)
        self.assertEqual([], missing)
        self.assertEqual("https://jmp2.uk/plu-aaa111.m3u8", channels[0]["streams"][0]["url"])

    def test_a_retired_channel_is_reported_and_skipped(self):
        channels, missing = build_pluto.build(
            [entry("aaa111", "Pluto TV Action", "Movies"), entry("gone999", "Retired", "Movies")],
            self.streams)
        self.assertEqual(["Retired"], missing)
        self.assertEqual(["Pluto TV Action"], [c["name"] for c in channels])

    def test_numbers_come_from_the_allowlist_in_dial_order(self):
        streams = {"a": "u-a", "b": "u-b", "c": "u-c"}
        channels, _ = build_pluto.build(
            [entry("a", "Zulu Kids", "Kids", number=3), entry("b", "Beta Movies", "Movies", number=2),
             entry("c", "Alpha Movies", "Movies", number=1)], streams)
        self.assertEqual([(1, "Alpha Movies"), (2, "Beta Movies"), (3, "Zulu Kids")],
                         [(c["number"], c["name"]) for c in channels])

    def test_a_retired_channel_leaves_a_gap_and_nothing_else_moves(self):
        streams = {"a": "u-a", "c": "u-c"}
        channels, missing = build_pluto.build(
            [entry("a", "Alpha", "Movies", number=1), entry("b", "Beta", "Movies", number=2),
             entry("c", "Gamma", "Movies", number=3)], streams)
        self.assertEqual(["Beta"], missing)
        self.assertEqual([(1, "Alpha"), (3, "Gamma")], [(c["number"], c["name"]) for c in channels])

    def test_every_channel_is_a_live_feed_the_app_plays_as_is(self):
        channels, _ = build_pluto.build([entry("aaa111", "Pluto TV Action", "Movies")], self.streams)
        channel = channels[0]
        self.assertEqual("live", channel["kind"])
        self.assertNotIn("rotation", channel)
        self.assertEqual([{"url": "https://jmp2.uk/plu-aaa111.m3u8", "duration": 600,
                           "title": "Pluto TV Action"}], channel["streams"])


class TestNumbers(unittest.TestCase):

    def test_well_numbered_allowlist_passes(self):
        self.assertIsNone(build_pluto.numbering_problem(
            [entry("a", "A", "Kids", number=1), entry("b", "B", "Kids", number=5)]))

    def test_duplicate_numbers_are_refused(self):
        problem = build_pluto.numbering_problem(
            [entry("a", "A", "Kids", number=4), entry("b", "B", "Kids", number=4)])
        self.assertIn("4", problem)
        self.assertIn("A", problem)
        self.assertIn("B", problem)

    def test_a_missing_number_is_refused_by_name(self):
        e = entry("b", "Brand New", "Kids")
        del e["number"]
        problem = build_pluto.numbering_problem([entry("a", "A", "Kids", number=1), e])
        self.assertIn("Brand New", problem)
        self.assertIn("number", problem)

    def test_a_non_positive_or_non_integer_number_is_refused(self):
        for bad in (0, -1, "7", 2.5, True):
            self.assertIsNotNone(build_pluto.numbering_problem([entry("a", "A", "Kids", number=bad)]), bad)


class TestGuard(unittest.TestCase):

    def test_passes_when_most_resolved(self):
        self.assertIsNone(build_pluto.refusal(resolved=150, wanted=225))

    def test_refuses_when_fewer_than_half_resolved(self):
        self.assertIn("112", build_pluto.refusal(resolved=112, wanted=225))

    def test_refuses_an_empty_result(self):
        self.assertIsNotNone(build_pluto.refusal(resolved=0, wanted=0))


class TestAllowlist(unittest.TestCase):
    """The committed file itself: a malformed entry would silently drop a channel."""

    def test_the_committed_allowlist_is_well_formed(self):
        allow = build_pluto.load_allowlist()
        self.assertGreater(len(allow), 100)
        self.assertEqual(len(allow), len({e["id"] for e in allow}), "duplicate ids")
        self.assertEqual(len(allow), len({e["name"].lower() for e in allow}), "duplicate names")
        for e in allow:
            self.assertIn(e["genre"], build_pluto.GENRE_ORDER, e["name"])
        self.assertIsNone(build_pluto.numbering_problem(allow))

    def test_the_committed_numbers_follow_genre_then_name(self):
        """Numbers were assigned once, from the dial order build_pluto used to compute (genre
        block, then name). New channels may take any free number, but the original 219 must never
        move - a viewer's remembered channel is a number."""
        allow = sorted(build_pluto.load_allowlist(), key=lambda e: e["number"])

        def rank(e):
            return build_pluto.GENRE_ORDER.index(e["genre"]), e["name"].lower()

        original = [e for e in allow if e["number"] <= 219]
        self.assertEqual(sorted(original, key=rank), original)


if __name__ == "__main__":
    unittest.main()
