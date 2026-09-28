#!/usr/bin/env python3
"""fast_guide.json: what is on the LIVE TV dial's FAST channels, cut down from whole-service guides.

The app looks a title up by `<guide>:<guide_id>` and the minute; everything here is about that
lookup giving the programme actually on air, or nothing - never the last title of a stale list.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import build_fast_guide as fg

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
        self.assertEqual([(T0 - 60, T0 + 60, "On now")], fg.windowed(items, T0))

    def test_xumo_titles_come_from_the_page_assets_and_overlapping_pages_merge(self):
        page = {"channels": [{"channelId": 99991333, "schedule": [
                    {"assetId": "A", "start": "2026-09-28T00:00:00+0000", "end": "2026-09-28T01:00:00+0000"}]},
                             {"channelId": 1, "schedule": [
                    {"assetId": "A", "start": "2026-09-28T00:00:00+0000", "end": "2026-09-28T01:00:00+0000"}]}],
                "assets": {"A": {"title": "Film"}}}
        got = fg.xumo_programmes([page, page], {"99991333"}, T0)
        self.assertEqual({"99991333": [(T0, T0 + 3600, "Film")]}, got)

    def test_xumo_segments_cover_the_window_from_the_one_on_air(self):
        segments = fg.xumo_segments(T0 + 7 * 3600)
        self.assertEqual(("20260928", 1), segments[0])
        self.assertEqual(("20260929", 2), segments[-1])
        self.assertEqual(6, len(segments))

    def test_tubi_rows(self):
        rows = [{"content_id": 400000012, "title": "ACCDN", "programs": [
            {"title": "Game", "start_time": "2026-09-28T00:00:00Z", "end_time": "2026-09-28T02:00:00Z"}]}]
        self.assertEqual({"400000012": [(T0, T0 + 7200, "Game")]}, fg.tubi_programmes(rows, {"400000012"}, T0))
        self.assertEqual({}, fg.tubi_programmes([{"content_id": 1, "programs": []}], {"1"}, T0))

    def test_rakuten_pages(self):
        pages = [{"data": [{"id": "sci-fi-rakuten-tv", "live_programs": [
            {"title": "Native", "starts_at": "2026-09-28T01:00:00.000+01:00", "ends_at": "2026-09-28T02:30:00.000+01:00"}]},
                          {"id": "other", "live_programs": [
            {"title": "No", "starts_at": "2026-09-28T01:00:00.000+01:00", "ends_at": "2026-09-28T02:30:00.000+01:00"}]}]}]
        self.assertEqual({"sci-fi-rakuten-tv": [(T0, T0 + 5400, "Native")]},
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
        self.assertEqual([(T0, T0 + 3600, "Match")], got["5294"])
        self.assertEqual([(T0 + 14 * 60, T0 + 43 * 60, "Geared Up")], got["7041"])
        self.assertEqual([(T0, T0 + 3600, "Karting")], got["6424"])


if __name__ == "__main__":
    unittest.main()
