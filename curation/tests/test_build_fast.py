#!/usr/bin/env python3
"""fast_candidates.json: iptv-org's FAST playlists, filtered, probed and deduplicated.

Nothing here touches the network: the playlists are strings, the metadata is a few dicts, and the
probe results are the same maps a real run saves - which is what `--direct` and `--us` take.
"""
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import build_fast

PLAYLIST = """#EXTM3U
#EXTINF:-1 tvg-id="Buzzr.us@SD",Buzzr (1080p)
https://buzzr.example/playlist.m3u8
#EXTINF:-1 tvg-id="",21 Jump Street (720p) [Geo-blocked]
https://jump.example/master.m3u8
#EXTINF:-1 tvg-id="Needy.us@SD",Needs Headers
#EXTVLCOPT:http-referrer=https://needy.example/
#EXTVLCOPT:http-user-agent=Special
https://needy.example/live.m3u8
"""


def entry(name, source="us_samsung", tvg_id="", url=None, **extra):
    e = {"name": name, "raw_name": name, "source": source, "tvg_id": tvg_id,
         "url": url or "https://%s.example/%s.m3u8" % (source, name.replace(" ", "")),
         "categories": [], "languages": [], "nsfw": False, "closed": False, "logo": None,
         "iptv_channel": None}
    e.update(extra)
    return e


class TestParse(unittest.TestCase):

    def test_reads_names_ids_urls_and_headers(self):
        entries = build_fast.parse_m3u(PLAYLIST, "us_amagi")
        self.assertEqual(["Buzzr", "21 Jump Street", "Needs Headers"], [e["name"] for e in entries])
        self.assertEqual(["Buzzr.us@SD", "", "Needy.us@SD"], [e["tvg_id"] for e in entries])
        self.assertEqual({"us_amagi"}, {e["source"] for e in entries})
        self.assertNotIn("headers", entries[0])
        self.assertEqual({"Referer": "https://needy.example/", "User-Agent": "Special"},
                         entries[2]["headers"])

    def test_names_compare_without_tags_case_or_filler(self):
        self.assertEqual("buzzr", build_fast.norm_name("BUZZR"))
        self.assertEqual("buzzr", build_fast.norm_name("The Buzzr Channel (1080p)"))
        self.assertEqual("homeandgarden", build_fast.norm_name("Home & Garden"))
        self.assertEqual("tv", build_fast.norm_name("TV"))  # all filler: keep what there is

    def test_feeds_of_one_channel_are_one_channel(self):
        self.assertEqual("Buzzr.us", build_fast.channel_id("Buzzr.us@SD"))
        self.assertEqual("", build_fast.channel_id(""))


class TestMetadata(unittest.TestCase):

    meta = build_fast.Metadata(
        channels=[{"id": "Buzzr.us", "name": "Buzzr", "country": "US", "categories": ["entertainment"]},
                  {"id": "Jump.us", "name": "21 Jump Street", "country": "US", "categories": ["series"]},
                  {"id": "Twin.us", "name": "Twin", "country": "US"},
                  {"id": "Twin.uk", "name": "Twin", "country": "UK"},
                  {"id": "Tele.mx", "name": "Tele", "country": "MX"}],
        feeds=[{"channel": "Buzzr.us", "id": "SD", "is_main": True, "languages": ["eng"]},
               {"channel": "Buzzr.us", "id": "ES", "is_main": False, "languages": ["spa"]}],
        logos=[{"channel": "Buzzr.us", "feed": None, "in_use": True, "width": 10, "height": 10, "url": "a"},
               {"channel": "Buzzr.us", "feed": "ES", "in_use": True, "width": 99, "height": 99, "url": "b"}])

    def test_resolves_by_id_then_by_unique_name(self):
        self.assertEqual(("Buzzr.us", "id"), self.meta.resolve({"tvg_id": "Buzzr.us@SD", "name": "x"}))
        self.assertEqual(("Jump.us", "name"), self.meta.resolve({"tvg_id": "", "name": "21 Jump Street"}))
        # Two channels share the name: no guess.
        self.assertEqual((None, None), self.meta.resolve({"tvg_id": "", "name": "Twin"}))
        # Only English-speaking countries are guessed from.
        self.assertEqual((None, None), self.meta.resolve({"tvg_id": "", "name": "Tele"}))

    def test_languages_and_logo_follow_the_feed(self):
        self.assertEqual(["spa"], self.meta.languages("Buzzr.us", "Buzzr.us@ES"))
        self.assertEqual(["eng"], self.meta.languages("Buzzr.us", "Buzzr.us@SD"))
        self.assertEqual(["eng"], self.meta.languages("Buzzr.us", ""))
        self.assertEqual("b", self.meta.logo("Buzzr.us", "Buzzr.us@ES"))
        self.assertEqual("a", self.meta.logo("Buzzr.us", "Buzzr.us@SD"))
        self.assertIsNone(self.meta.logo("Jump.us", ""))


class TestFilters(unittest.TestCase):

    def test_english_only(self):
        self.assertIsNone(build_fast.language_problem(entry("A", languages=["eng", "spa"])))
        self.assertIsNone(build_fast.language_problem(entry("No Data At All")))
        self.assertIn("spa", build_fast.language_problem(entry("A", languages=["spa"])))
        self.assertIsNotNone(build_fast.language_problem(entry("Fox Sports en Español", languages=["eng"])))
        self.assertIsNotNone(build_fast.language_problem(entry("Top Barça")))  # decomposed ç

    def test_news_business_religious_and_shopping_go(self):
        drop = build_fast.category_problem
        self.assertEqual("news", drop(entry("A", categories=["documentary", "news"])))
        self.assertEqual("business news", drop(entry("Bloomberg Originals", categories=["business"])))
        self.assertEqual("religious", drop(entry("A", categories=["religious"])))
        self.assertEqual("shopping", drop(entry("A", categories=["shop"])))
        self.assertEqual("news (by name)", drop(entry("NBC Chicago News")))
        self.assertEqual("religious (by name)", drop(entry("Stingray Christian Hits")))
        self.assertEqual("news (local station)", drop(entry("ABC 7 Washington DC")))
        self.assertEqual("news (local station)", drop(entry("WSOC Charlotte")))
        self.assertIsNone(drop(entry("WBTV Watchlist")))
        self.assertIsNone(drop(entry("Newsies Classics")))  # whole words only
        self.assertIsNone(drop(entry("Tastemade", categories=["cooking"])))

    def test_what_either_dial_has_goes(self):
        existing = build_fast.Existing(pluto_names=["Buzzr", "Classic TV Comedy"],
                                       pluto_ids=["Crime.us@Pluto"], youtube_names=["Westerns"],
                                       urls=["https://live.example/cbs.m3u8"])
        self.assertEqual("on the Pluto dial (tvg-id)", existing.problem(entry("X", tvg_id="Crime.us@SD")))
        self.assertEqual("on the Pluto dial (name)", existing.problem(entry("BUZZR TV")))
        self.assertEqual("on the YouTube dial (name)", existing.problem(entry("The Westerns Channel")))
        self.assertEqual("Pluto stream", existing.problem(entry("Y", url="https://jmp2.uk/plu-abc.m3u8")))
        self.assertEqual("already on a dial (same url)",
                         existing.problem(entry("Z", url="https://live.example/cbs.m3u8")))
        self.assertIsNone(existing.problem(entry("Something Else", tvg_id="Other.us@SD")))

    def test_pluto_ids_come_from_the_allowlisted_pluto_streams(self):
        pluto = build_fast.parse_m3u(
            '#EXTINF:-1 tvg-id="Buzzr.us@Pluto",BUZZR\nhttps://jmp2.uk/plu-aaa.m3u8\n'
            '#EXTINF:-1 tvg-id="NotPicked.us@Pluto",Nope\nhttps://jmp2.uk/plu-bbb.m3u8\n', "us_pluto")
        self.assertEqual({"Buzzr.us@Pluto"},
                         build_fast.pluto_tvg_ids([{"id": "zzz", "alt": "aaa", "name": "BUZZR"}], pluto))

    def test_screen_keeps_survivors_and_says_why_the_rest_went(self):
        kept, dropped = build_fast.screen(
            [entry("Keep Me"), entry("A", languages=["fra"]), entry("B", categories=["news"]),
             entry("C", closed=True), entry("Buzzr")],
            build_fast.Existing(pluto_names=["Buzzr"]))
        self.assertEqual(["Keep Me"], [e["name"] for e in kept])
        self.assertEqual(["not English (fra)", "news", "channel closed", "on the Pluto dial (name)"],
                         [d["reason"] for d in dropped])


class TestGenre(unittest.TestCase):

    def test_name_beats_a_vague_category_and_order_settles_overlaps(self):
        genre = build_fast.genre_of
        self.assertEqual("Crime", genre("Crime 24/7", ["entertainment"]))
        self.assertEqual("Kids", genre("Cartoon Classics", []))
        self.assertEqual("Movies", genre("Drive In Movie Channel", []))
        self.assertEqual("Classic TV", genre("Murder She Wrote", []))
        self.assertEqual("Game Shows", genre("Family Feud Classic", []))
        self.assertEqual("Anime", genre("Hunter x Hunter", ["animation"]))

    def test_firm_categories_beat_names_and_loose_ones_only_fill_gaps(self):
        genre = build_fast.genre_of
        self.assertEqual("Music", genre("Stingray Crime Jazz", ["music"]))
        self.assertEqual("Crime", genre("Cold Case Files", ["series"]))
        self.assertEqual("Drama & Series", genre("Baywatch", ["series"]))
        self.assertEqual("Other", genre("4UV", []))
        self.assertTrue(all(g in build_fast.GENRES for g, _ in build_fast.NAME_GENRES))


class TestDedupe(unittest.TestCase):

    def test_the_copy_that_plays_from_australia_wins(self):
        us_copy = entry("Buzzr", source="au_samsung", tvg_id="Buzzr.us@SD")
        au_copy = entry("Buzzr", source="us_tubi", tvg_id="Buzzr.us@East")
        routes = {us_copy["url"]: "us", au_copy["url"]: "direct"}
        kept = build_fast.dedupe([us_copy, au_copy], routes)
        self.assertEqual([("us_tubi", "direct", ["au_samsung"])],
                         [(k["source"], k["route"], k["also"]) for k in kept])

    def test_ties_go_to_the_earlier_source(self):
        a = entry("Buzzr", source="us_tubi", tvg_id="Buzzr.us@SD")
        b = entry("Buzzr", source="au_samsung", tvg_id="Buzzr.us@SD")
        kept = build_fast.dedupe([a, b], {a["url"]: "direct", b["url"]: "direct"})
        self.assertEqual(["au_samsung"], [k["source"] for k in kept])

    def test_a_name_match_joins_an_entry_without_an_id_to_one_with(self):
        a = entry("Curiosity Now", source="us_samsung", tvg_id="CuriosityNow.us@SD")
        b = entry("Curiosity Now (1080p)", source="us_xumo")
        kept = build_fast.dedupe([a, b], {a["url"]: "us", b["url"]: "direct"})
        self.assertEqual([("us_xumo", ["us_samsung"])], [(k["source"], k["also"]) for k in kept])

    def test_different_channels_stay_apart(self):
        a, b = entry("One", tvg_id="One.us@SD"), entry("Two", tvg_id="Two.us@SD")
        self.assertEqual(2, len(build_fast.dedupe([a, b], {})))

    def test_route(self):
        direct = {"a": {"ok": True}, "b": {"ok": False}, "c": {"ok": False}}
        us = {"a": {"ok": False}, "b": {"ok": True}, "c": {"ok": False}}
        self.assertEqual(["direct", "us", "dead", "dead"],
                         [build_fast.route_of(u, direct, us) for u in ("a", "b", "c", "missing")])


class TestProbe(unittest.TestCase):
    """probe_one against a fake network: url -> (status, body)."""

    def probe(self, net, url="https://h.example/master.m3u8"):
        def get(u, headers, limit=None):
            if u not in net:
                raise OSError("no route to %s" % u)
            status, body = net[u]
            return status, u, body.encode()
        with mock.patch.object(build_fast, "_get", get):
            return build_fast.probe_one(url)

    MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n"
    MEDIA = "#EXTM3U\n#EXTINF:6,\nseg1.ts\n"

    def test_playlist_variant_and_segment_must_all_answer(self):
        net = {"https://h.example/master.m3u8": (200, self.MASTER),
               "https://h.example/low/index.m3u8": (200, self.MEDIA),
               "https://h.example/low/seg1.ts": (200, "x")}
        self.assertTrue(self.probe(net)["ok"])
        del net["https://h.example/low/seg1.ts"]
        self.assertFalse(self.probe(net)["ok"])

    def test_a_media_playlist_needs_no_variant(self):
        net = {"https://h.example/master.m3u8": (200, self.MEDIA), "https://h.example/seg1.ts": (200, "")}
        self.assertTrue(self.probe(net)["ok"])

    def test_not_a_playlist_fails(self):
        self.assertFalse(self.probe({"https://h.example/master.m3u8": (200, "<html>")})["ok"])


class TestMain(unittest.TestCase):
    """The whole run from saved probe results: no playlist fetch, no ssh."""

    def test_writes_live_candidates_and_a_summary(self):
        entries = [entry("Crime 24/7", source="au_samsung", url="https://a/1.m3u8"),
                   entry("Crime 24/7", source="us_tubi", url="https://a/2.m3u8"),
                   entry("Dead Air", url="https://a/3.m3u8"),
                   entry("Bloomberg", categories=["business"], url="https://a/4.m3u8")]
        with tempfile.TemporaryDirectory() as tmp:
            direct, us = os.path.join(tmp, "d.json"), os.path.join(tmp, "u.json")
            with open(direct, "w") as f:
                json.dump({"https://a/2.m3u8": {"ok": True}}, f)
            with open(us, "w") as f:
                json.dump({"https://a/1.m3u8": {"ok": True}}, f)
            out_json, out_md = os.path.join(tmp, "c.json"), os.path.join(tmp, "c.md")
            with mock.patch.object(build_fast, "gather", return_value=(entries, [], None)), \
                    mock.patch.object(build_fast, "enrich", side_effect=lambda e, m: dict(
                        e, category=build_fast.genre_of(e["name"], e["categories"]))), \
                    mock.patch.object(build_fast, "existing_dials", return_value=build_fast.Existing()), \
                    mock.patch.object(build_fast, "OUT_JSON", out_json), \
                    mock.patch.object(build_fast, "OUT_SUMMARY", out_md), \
                    mock.patch("sys.stdout"):
                self.assertEqual(0, build_fast.main(["--direct", direct, "--us", us]))
            with open(out_json) as f:
                candidates = json.load(f)["candidates"]
            with open(out_md) as f:
                md = f.read()
        self.assertEqual([("Crime 24/7", "us_tubi", "direct", "Crime", ["au_samsung"])],
                         [(c["name"], c["source"], c["route"], c["category"], c["also"]) for c in candidates])
        self.assertIn("| Crime | 1 | 1 | 0 |", md)
        self.assertIn("Dead Air", md)
        self.assertIn("**business news** (1 entries): Bloomberg", md)


if __name__ == "__main__":
    unittest.main()
