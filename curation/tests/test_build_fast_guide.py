#!/usr/bin/env python3
"""fast_guide.json: what is on the LIVE TV dial's FAST channels, cut down from whole-service guides.

The app looks a title up by `<guide>:<guide_id>` and the minute; everything here is about that
lookup giving the programme actually on air, or nothing - never the last title of a stale list.
"""
import os
import sys
import unittest
import urllib.parse
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import build_fast_guide as fg
import fast_guide_fetch as fetch

# 2026-09-28 00:00:00 UTC
T0 = 1790553600

XMLTV = b"""<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="US1"><display-name>One</display-name></channel>
  <programme channel="US1" start="20260927230000 +0000" stop="20260928003000 +0000"><title>Before  And
  Now</title></programme>
  <programme channel="US1" start="20260928003000 +0000" stop="20260928010000 +0000"><title>Next</title></programme>
  <programme channel="US1" start="20260928020000 +0000" stop="20260928030000 +0000"><title>After A Gap</title></programme>
  <programme channel="US1" start="20260930000000 +0000" stop="20260930010000 +0000"><title>Too Late</title></programme>
  <programme channel="US1" start="20260927200000 +0000" stop="20260927210000 +0000"><title>Over</title></programme>
  <programme channel="OTHER" start="20260928000000 +0000" stop="20260928010000 +0000"><title>Not Ours</title></programme>
</tv>
"""


def lookup(guide, name, at):
    """The app's lookup, in Python: the title on air at epoch second [at], or None."""
    flat = guide["channels"].get(name)
    if not flat:
        return None
    minute = (at - guide["base"]) // 60
    title = None
    for i in range(0, len(flat), 2):
        if flat[i] <= minute:
            title = flat[i + 1]
    return guide["titles"][title] or None if title is not None else None


class TestProgrammes(unittest.TestCase):

    def test_only_wanted_channels_inside_the_window_in_order(self):
        got = fg.programmes(XMLTV, {"US1"}, T0)
        self.assertEqual(["Before And Now", "Next", "After A Gap"], [p[2] for p in got["US1"]])
        self.assertNotIn("OTHER", got)

    def test_xmltv_times(self):
        self.assertEqual(T0, fg.xmltv_seconds("20260928000000 +0000"))
        self.assertEqual(T0, fg.xmltv_seconds("20260928100000 +1000"))
        self.assertIsNone(fg.xmltv_seconds("soon"))


class TestEncode(unittest.TestCase):

    def setUp(self):
        self.guide = fg.encode({"samsung:US1": fg.programmes(XMLTV, {"US1"}, T0 + 30)["US1"]}, T0 + 30)

    def test_the_title_on_air_is_found_at_any_minute(self):
        self.assertEqual("Before And Now", lookup(self.guide, "samsung:US1", T0 + 60))
        self.assertEqual("Next", lookup(self.guide, "samsung:US1", T0 + 45 * 60))
        self.assertEqual("After A Gap", lookup(self.guide, "samsung:US1", T0 + 150 * 60))

    def test_a_hole_in_the_listings_and_the_end_of_them_say_nothing(self):
        self.assertIsNone(lookup(self.guide, "samsung:US1", T0 + 90 * 60))
        self.assertIsNone(lookup(self.guide, "samsung:US1", T0 + 5 * 3600))

    def test_base_is_the_build_minute_and_a_running_programme_starts_before_it(self):
        self.assertEqual(T0, self.guide["base"])
        self.assertEqual(-60, self.guide["channels"]["samsung:US1"][0])

    def test_titles_are_shared_and_zero_is_empty(self):
        guide = fg.encode({"a:1": [(T0, T0 + 60, "Same")], "a:2": [(T0, T0 + 60, "Same")]}, T0)
        self.assertEqual(["", "Same"], guide["titles"])
        self.assertEqual([0, 1, 1, 0], guide["channels"]["a:1"])

    def test_overlapping_listings_never_run_backwards(self):
        guide = fg.encode({"a:1": [(T0, T0 + 600, "First"), (T0 + 300, T0 + 900, "Second")]}, T0)
        starts = guide["channels"]["a:1"][0::2]
        self.assertEqual(sorted(starts), starts)
        self.assertEqual("Second", lookup(guide, "a:1", T0 + 700))


DETAILED = b"""<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <programme channel="US1" start="20260928000000 +0000" stop="20260928003000 +0000">
    <title>Terry and June</title>
    <desc>Terry experiences pangs of
      jealousy.</desc>
    <icon src="https://img.example/a.jpg" />
  </programme>
  <programme channel="US1" start="20260928010000 +0000" stop="20260928013000 +0000">
    <title>Bare</title>
    <icon src="http://insecure.example/b.jpg" />
  </programme>
</tv>
"""


class TestDescriptionsAndPictures(unittest.TestCase):

    def test_desc_and_icon_ride_along_with_each_programme(self):
        got = fg.programmes(DETAILED, {"US1"}, T0)["US1"]
        self.assertEqual(("Terry and June", "Terry experiences pangs of jealousy.",
                          "https://img.example/a.jpg"), got[0][2:])
        self.assertEqual(("Bare", "", ""), got[1][2:], "no desc, and no plain-http picture")

    def test_info_runs_alongside_the_channel_pairs_holes_included(self):
        guide = fg.encode({"samsung:US1": fg.programmes(DETAILED, {"US1"}, T0)["US1"]}, T0)
        flat, info = guide["channels"]["samsung:US1"], guide["info"]["samsung:US1"]
        self.assertEqual(len(flat), len(info))
        self.assertEqual("Terry experiences pangs of jealousy.", guide["descs"][info[0]])
        self.assertEqual("https://img.example/a.jpg", guide["icons"][info[1]])
        self.assertEqual([0, 0], info[2:4], "the hole between the two says nothing")
        self.assertEqual([0, 0], info[-2:])

    def test_title_only_programmes_add_no_tables(self):
        guide = fg.encode({"a:1": [(T0, T0 + 60, "Same")]}, T0)
        self.assertNotIn("info", guide)
        self.assertNotIn("descs", guide)

    def test_long_descriptions_are_cut_on_a_word(self):
        cut = fg.trimmed("word " * 100, limit=40)
        self.assertLessEqual(len(cut), 40)
        self.assertTrue(cut.endswith("word…"))
        self.assertEqual("short one", fg.trimmed("  short \n one "))


class TestBuild(unittest.TestCase):

    LINEUP = [
        {"name": "One", "guide": "samsung", "guide_id": "US1"},
        {"name": "Plain", "guide": None},
        {"name": "Pluto", "pluto": {"id": "x"}},
        {"name": "X & Y", "guide": "xumo", "guide_id": "999"},
    ]

    def test_only_lineup_channels_with_a_carried_guide_are_wanted(self):
        self.assertEqual({"samsung": {"US1": "samsung:US1"}, "xumo": {"999": "xumo:999"}},
                         fg.wanted(self.LINEUP))

    def test_a_missing_service_leaves_its_channels_out(self):
        guide = fg.build(self.LINEUP, {"samsung": XMLTV}, T0)
        self.assertEqual(["samsung:US1"], list(guide["channels"]))

    def test_publishing_is_refused_when_most_guide_channels_came_back_empty(self):
        guide = fg.build(self.LINEUP, {}, T0)
        self.assertIsNotNone(fg.refusal(guide, self.LINEUP))
        self.assertIsNone(fg.refusal(fg.build(self.LINEUP, {"samsung": XMLTV}, T0), self.LINEUP))

    def test_a_service_already_parsed_is_used_as_it_is(self):
        guide = fg.build(self.LINEUP, {"xumo": {"999": [(T0, T0 + 600, "Film")], "111": [(T0, T0 + 60, "No")]}}, T0)
        self.assertEqual(["xumo:999"], list(guide["channels"]))
        self.assertEqual("Film", lookup(guide, "xumo:999", T0 + 60))


class TestTimes(unittest.TestCase):

    def test_iso_times_with_any_offset_form(self):
        for text in ("2026-09-28T00:00:00Z", "2026-09-28T00:00:00+0000", "2026-09-28T01:00:00.000+01:00",
                     "2026-09-28T00:00:00+00:00"):
            self.assertEqual(T0, fg.iso_seconds(text), text)

    def test_an_iso_time_without_an_offset_is_no_time(self):
        self.assertIsNone(fg.iso_seconds("2026-09-28T00:00:00"))
        self.assertIsNone(fg.iso_seconds(None))
        self.assertIsNone(fg.iso_seconds("later"))

    def test_stirr_epoch_seconds(self):
        self.assertEqual(T0, fg.stirr_seconds(T0, "2026-09-28 00:00:00", T0))

    def test_stirr_minutes_in_the_seconds_field_beside_a_nonsense_date(self):
        self.assertEqual(T0 + 14 * 60, fg.stirr_seconds(202609280014, "8390-06-07 19:33:34", T0))

    def test_stirr_year_zero_is_the_year_nearest_now(self):
        self.assertEqual(T0, fg.stirr_seconds(-62143804800, "0000-09-28 00:00:00", T0 + 3600))
        # 2027-01-01 00:00 UTC, seen from late December
        self.assertEqual(1798761600, fg.stirr_seconds(-1, "0000-01-01 00:00:00", 1798761600 - 86400))

    def test_stirr_nothing_usable_is_no_time(self):
        self.assertIsNone(fg.stirr_seconds(None, None, T0))
        self.assertIsNone(fg.stirr_seconds(-5, "garbage", T0))


class TestServiceGuides(unittest.TestCase):

    def test_windowed_drops_what_is_over_or_too_late_and_repeats(self):
        items = [(T0 - 7200, T0 - 3600, "Over"), (T0 - 60, T0 + 60, "On  now"), (T0 - 60, T0 + 60, "On now"),
                 (T0 + 3600, T0 + 3600, "Empty"), (T0 + 40 * 3600, T0 + 41 * 3600, "Too late"),
                 (None, T0 + 60, "No start")]
        self.assertEqual([(T0 - 60, T0 + 60, "On now", "", "")], fg.windowed(items, T0))

    def test_xumo_titles_come_from_the_page_assets_and_overlapping_pages_merge(self):
        page = {"channels": [{"channelId": 99991333, "schedule": [
                    {"assetId": "A", "start": "2026-09-28T00:00:00+0000", "end": "2026-09-28T01:00:00+0000"}]},
                             {"channelId": 1, "schedule": [
                    {"assetId": "A", "start": "2026-09-28T00:00:00+0000", "end": "2026-09-28T01:00:00+0000"}]}],
                "assets": {"A": {"title": "Film"}}}
        got = fg.xumo_programmes([page, page], {"99991333"}, T0)
        self.assertEqual({"99991333": [(T0, T0 + 3600, "Film", "", "")]}, got)

    def test_xumo_segments_cover_the_window_from_the_one_on_air(self):
        segments = fetch.xumo_segments(T0 + 7 * 3600)
        self.assertEqual(("20260928", 1), segments[0])
        self.assertEqual(("20260929", 2), segments[-1])
        self.assertEqual(6, len(segments))

    def test_tubi_rows(self):
        rows = [{"content_id": 400000012, "title": "ACCDN", "programs": [
            {"title": "Game", "start_time": "2026-09-28T00:00:00Z", "end_time": "2026-09-28T02:00:00Z"}]}]
        self.assertEqual({"400000012": [(T0, T0 + 7200, "Game", "", "")]}, fg.tubi_programmes(rows, {"400000012"}, T0))
        self.assertEqual({}, fg.tubi_programmes([{"content_id": 1, "programs": []}], {"1"}, T0))

    def test_rakuten_pages(self):
        pages = [{"data": [{"id": "sci-fi-rakuten-tv", "live_programs": [
            {"title": "Native", "starts_at": "2026-09-28T01:00:00.000+01:00", "ends_at": "2026-09-28T02:30:00.000+01:00"}]},
                          {"id": "other", "live_programs": [
            {"title": "No", "starts_at": "2026-09-28T01:00:00.000+01:00", "ends_at": "2026-09-28T02:30:00.000+01:00"}]}]}]
        self.assertEqual({"sci-fi-rakuten-tv": [(T0, T0 + 5400, "Native", "", "")]},
                         fg.rakuten_programmes(pages, {"sci-fi-rakuten-tv"}, T0))

    def test_stirr_channels_in_every_time_form(self):
        data = {"data": {"channels": [
            {"channel_id": 5294, "programs": [{"title": "Match", "start": "2026-09-28 00:00:00",
                                               "end": "2026-09-28 01:00:00", "start_time": T0,
                                               "end_time": T0 + 3600}]},
            {"channel_id": 7041, "programs": [{"title": "Geared Up", "start": "8390-06-07 19:33:34",
                                               "end": "8390-06-07 19:34:03", "start_time": 202609280014,
                                               "end_time": 202609280043}]},
            {"channel_id": 6424, "programs": [{"title": "Karting", "start": "0000-09-28 00:00:00",
                                               "end": "0000-09-28 01:00:00", "start_time": -62143804800,
                                               "end_time": -62143801200}]}]}}
        got = fg.stirr_programmes(data, {"5294", "7041", "6424"}, T0)
        self.assertEqual([(T0, T0 + 3600, "Match")], [p[:3] for p in got["5294"]])
        self.assertEqual([(T0 + 14 * 60, T0 + 43 * 60, "Geared Up")], [p[:3] for p in got["7041"]])
        self.assertEqual([(T0, T0 + 3600, "Karting")], [p[:3] for p in got["6424"]])


class TestServiceDetails(unittest.TestCase):
    """Each service's description and picture, in the shapes their apis really return."""

    def test_xumo_takes_the_largest_description_that_fits_whole(self):
        page = {"channels": [{"channelId": 7, "schedule": [
                    {"assetId": "EP1", "start": "2026-09-28T00:00:00+0000", "end": "2026-09-28T01:00:00+0000"},
                    {"assetId": "EP2", "start": "2026-09-28T01:00:00+0000", "end": "2026-09-28T02:00:00+0000"},
                    {"assetId": "EP3", "start": "2026-09-28T02:00:00+0000", "end": "2026-09-28T03:00:00+0000"}]}],
                "assets": {"EP1": {"title": "Dateline NBC", "episodeTitle": "Twisted in Texas", "descriptions": {
                               "tiny": "An alleged abuser's behavior devolves.",
                               "small": "An alleged abuser and stalker moves on to worse behavior.",
                               "medium": "word " * 60}},
                           "EP2": {"title": "News", "descriptions": {"tiny": "The news."}},
                           "EP3": {"title": "Bare"}}}
        got = fg.xumo_programmes([page], {"7"}, T0)["7"]
        self.assertEqual("An alleged abuser and stalker moves on to worse behavior.", got[0][3])
        self.assertEqual(("News", "The news.", ""), got[1][2:])
        self.assertEqual(("Bare", "", ""), got[2][2:])

    def test_tubi_description_and_its_wide_picture(self):
        rows = [{"content_id": 692051, "programs": [
            {"title": "Game", "description": "Two teams.", "start_time": "2026-09-28T00:00:00Z",
             "end_time": "2026-09-28T02:00:00Z",
             "images": {"poster": ["https://tubi.example/p.jpg"], "landscape": ["https://tubi.example/l.jpg"]}},
            {"title": "Plain", "start_time": "2026-09-28T02:00:00Z", "end_time": "2026-09-28T03:00:00Z"}]}]
        got = fg.tubi_programmes(rows, {"692051"}, T0)["692051"]
        self.assertEqual(("Game", "Two teams.", "https://tubi.example/l.jpg"), got[0][2:])
        self.assertEqual(("Plain", "", ""), got[1][2:])

    def test_rakuten_description_and_snapshot_when_there_is_one(self):
        pages = [{"data": [{"id": "france-24-en", "live_programs": [
            {"title": "Focus", "description": "Exclusive reports.", "starts_at": "2026-09-28T01:00:00.000+01:00",
             "ends_at": "2026-09-28T01:30:00.000+01:00", "images": {"snapshot": None, "snapshot_webp": None}},
            {"title": "News", "description": None, "starts_at": "2026-09-28T01:30:00.000+01:00",
             "ends_at": "2026-09-28T02:00:00.000+01:00",
             "images": {"snapshot": "https://images-0.rakuten.tv/storage/snapshot/shot/a.jpeg"}}]}]}]
        got = fg.rakuten_programmes(pages, {"france-24-en"}, T0)["france-24-en"]
        self.assertEqual(("Focus", "Exclusive reports.", ""), got[0][2:])
        self.assertEqual(("News", "", "https://images-0.rakuten.tv/storage/snapshot/shot/a.jpeg"), got[1][2:])

    def test_stirr_description_cut_to_the_pane(self):
        data = {"data": {"channels": [{"channel_id": 3353, "programs": [
            {"title": "Love Your Body", "description": "Come back into alignment. " * 20,
             "start_time": T0, "end_time": T0 + 1678, "start": "2026-09-28 00:00:00", "end": "2026-09-28 00:27:58"}]}]}}
        (item,) = fg.stirr_programmes(data, {"3353"}, T0)["3353"]
        self.assertTrue(item[3].startswith("Come back into alignment."))
        self.assertLessEqual(len(item[3]), fg.DESC_CHARS)
        self.assertEqual("", item[4])

    def test_picture_is_the_first_https_url_in_any_shape(self):
        self.assertEqual("https://a/1.jpg", fg.picture("https://a/1.jpg"))
        self.assertEqual("", fg.picture("http://a/1.jpg"))
        self.assertEqual("", fg.picture(None))
        self.assertEqual("https://a/2.jpg", fg.picture(["", "http://a/1.jpg", "https://a/2.jpg"]))
        self.assertEqual("https://a/w.jpg", fg.picture({"poster": "https://a/p.jpg", "wide": ["https://a/w.jpg"]},
                                                       ("wide",)))

    def test_every_service_s_details_reach_the_tables(self):
        lineup = [{"guide": "xumo", "guide_id": "7"}, {"guide": "stirr", "guide_id": "3353"},
                  {"guide": "samsung", "guide_id": "US1"}]
        guides = {"xumo": {"7": [(T0, T0 + 600, "News", "The news.", "")]},
                  "stirr": {"3353": [(T0, T0 + 600, "Yoga", "Breathe.", "https://s/y.jpg")]},
                  "samsung": DETAILED}
        guide = fg.build(lineup, guides, T0)
        for name, desc in (("xumo:7", "The news."), ("stirr:3353", "Breathe."),
                           ("samsung:US1", "Terry experiences pangs of jealousy.")):
            self.assertEqual(len(guide["channels"][name]), len(guide["info"][name]), name)
            self.assertEqual(desc, guide["descs"][guide["info"][name][0]], name)
        self.assertEqual("https://s/y.jpg", guide["icons"][guide["info"]["stirr:3353"][1]])


class TestTubiThroughTheRelay(unittest.TestCase):
    """Tubi answers only inside the US: the home server asks it through its US relay."""

    RELAY = "http://127.0.0.1:4247/hls?u="

    def asked(self, via, ids=("692051", "400000012")):
        urls = []

        def fake(url, timeout=60):
            urls.append(url)
            return {"rows": [{"content_id": "692051"}]}
        with mock.patch.object(fetch, "fetch_json", fake):
            rows = fetch.fetch_tubi(set(ids), via)
        return urls, rows

    def test_without_a_relay_tubi_is_asked_directly(self):
        urls, rows = self.asked(None)
        self.assertEqual(["https://tubitv.com/oz/epg/programming?content_id=400000012,692051"], urls)
        self.assertEqual([{"content_id": "692051"}], rows)

    def test_through_the_relay_the_whole_url_is_its_u_parameter(self):
        urls, _ = self.asked(self.RELAY)
        self.assertTrue(urls[0].startswith(self.RELAY))
        u = urllib.parse.parse_qs(urllib.parse.urlsplit(urls[0]).query)["u"][0]
        self.assertEqual("https://tubitv.com/oz/epg/programming?content_id=400000012,692051", u)

    def test_batches_each_go_through_the_relay(self):
        ids = ["%06d" % i for i in range(fetch.TUBI_BATCH + 1)]
        urls, rows = self.asked(self.RELAY, ids)
        self.assertEqual(2, len(urls))
        self.assertTrue(all(u.startswith(self.RELAY) for u in urls))
        self.assertEqual(2, len(rows))

    def test_the_builder_passes_its_option_and_environment_on(self):
        seen = []
        with mock.patch.object(fg, "fetch_tubi", lambda ids, via=None: seen.append(via) or []):
            fg.load_all([{"guide": "tubi", "guide_id": "1"}], None, T0, self.RELAY)
        self.assertEqual([self.RELAY], seen)
        with mock.patch.dict(os.environ, {"YTV_TUBI_VIA": self.RELAY}), \
                mock.patch.object(fg, "load_all", side_effect=RuntimeError("stop")) as load:
            with self.assertRaises(RuntimeError):
                fg.main(["--out", os.devnull])
        self.assertEqual(self.RELAY, load.call_args[0][3])


if __name__ == "__main__":
    unittest.main()
