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
"""


def entry(pid, name, genre, alt=None):
    e = {"id": pid, "name": name, "genre": genre}
    if alt:
        e["alt"] = alt
    return e


class TestParse(unittest.TestCase):

    def test_reads_pluto_ids_and_urls(self):
        self.assertEqual({"aaa111": "https://jmp2.uk/plu-aaa111.m3u8",
                          "bbb222": "https://jmp2.uk/plu-bbb222.m3u8"},
                         build_pluto.parse_m3u(PLAYLIST))


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

    def test_numbers_by_genre_then_name(self):
        streams = {"a": "u-a", "b": "u-b", "c": "u-c"}
        channels, _ = build_pluto.build(
            [entry("a", "Zulu Kids", "Kids"), entry("b", "Beta Movies", "Movies"),
             entry("c", "Alpha Movies", "Movies")], streams)
        self.assertEqual([(1, "Alpha Movies"), (2, "Beta Movies"), (3, "Zulu Kids")],
                         [(c["number"], c["name"]) for c in channels])

    def test_an_unknown_genre_sorts_last(self):
        streams = {"a": "u-a", "b": "u-b"}
        channels, _ = build_pluto.build(
            [entry("a", "Aardvark", "Something New"), entry("b", "Zebra", "Kids")], streams)
        self.assertEqual(["Zebra", "Aardvark"], [c["name"] for c in channels])

    def test_every_channel_is_a_live_feed_the_app_plays_as_is(self):
        channels, _ = build_pluto.build([entry("aaa111", "Pluto TV Action", "Movies")], self.streams)
        channel = channels[0]
        self.assertEqual("live", channel["kind"])
        self.assertNotIn("rotation", channel)
        self.assertEqual([{"url": "https://jmp2.uk/plu-aaa111.m3u8", "duration": 600,
                           "title": "Pluto TV Action"}], channel["streams"])


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


if __name__ == "__main__":
    unittest.main()
