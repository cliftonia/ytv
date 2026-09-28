#!/usr/bin/env python3
"""fast_guide.json: what is on the LIVE TV dial's FAST channels, cut down from whole-service guides.

The app looks a title up by `<guide>:<guide_id>` and the minute; everything here is about that
lookup giving the programme actually on air, or nothing - never the last title of a stale list.
"""
import os
import sys
import unittest
import xml.etree.ElementTree as ET

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
        {"name": "X & Y", "guide": "xumo", "guide_id": "300#999"},
    ]

    def test_only_lineup_channels_with_a_carried_guide_are_wanted(self):
        self.assertEqual({"samsung": {"US1": "samsung:US1"}, "xumo": {"300#999": "xumo:300#999"}},
                         fg.wanted(self.LINEUP))

    def test_a_missing_service_leaves_its_channels_out(self):
        guide = fg.build(self.LINEUP, {"samsung": XMLTV}, T0)
        self.assertEqual(["samsung:US1"], list(guide["channels"]))

    def test_publishing_is_refused_when_most_guide_channels_came_back_empty(self):
        guide = fg.build(self.LINEUP, {}, T0)
        self.assertIsNotNone(fg.refusal(guide, self.LINEUP))
        self.assertIsNone(fg.refusal(fg.build(self.LINEUP, {"samsung": XMLTV}, T0), self.LINEUP))

    def test_the_xumo_channel_list_names_channels_by_site_id(self):
        root = ET.fromstring(fg.xumo_channels(self.LINEUP).encode())
        channel = root.find("channel")
        self.assertEqual(("xumo.tv", "300#999", ""), (channel.get("site"), channel.get("site_id"),
                                                      channel.get("xmltv_id")))
        self.assertEqual("X & Y", channel.text)
        self.assertEqual(1, len(root))


if __name__ == "__main__":
    unittest.main()
