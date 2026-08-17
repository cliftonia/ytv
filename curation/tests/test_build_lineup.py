#!/usr/bin/env python3
"""The channels.json contract.

Every field checked here is one the app reads. `Stream.id` in particular decides how a clip is
played: present means "resolve this YouTube id", absent means "this is a live HLS url, play it
as-is". Getting that backwards does not fail loudly - it hands a watch page to a video player and
shows a black screen.
"""
import io
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import build_lineup


class TestVideoId(unittest.TestCase):

    def test_reads_a_watch_url(self):
        self.assertEqual("dQw4w9WgXcQ",
                         build_lineup.video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))

    def test_reads_an_id_that_is_not_the_first_parameter(self):
        self.assertEqual("dQw4w9WgXcQ",
                         build_lineup.video_id("https://www.youtube.com/watch?t=30&v=dQw4w9WgXcQ"))

    def test_ids_with_underscores_and_hyphens_survive(self):
        # Both are legal in a YouTube id, and a character class that omitted them would truncate
        # roughly one id in eight into something unresolvable.
        self.assertEqual("a_b-c_d-e_f", build_lineup.video_id("https://youtube.com/watch?v=a_b-c_d-e_f"))

    def test_a_url_with_no_id_is_none(self):
        self.assertIsNone(build_lineup.video_id("https://c.mjh.nz/abc-qld.m3u8"))
        self.assertIsNone(build_lineup.video_id(""))
        self.assertIsNone(build_lineup.video_id(None))


class TestChannelFrom(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, name, station):
        path = os.path.join(self.dir, name)
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": station}, handle)
        return path

    def test_a_youtube_channel_carries_an_id_on_every_stream(self):
        path = self.write("ytch_x.json", {
            "network_name": "X", "channel_number": 5, "stream_rotation": "clock",
            "streams": [{"url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                         "duration": 212, "title": "A song"}]})
        channel = build_lineup.channel_from(path)
        self.assertEqual("youtube", channel["kind"])
        self.assertEqual("clock", channel["rotation"])
        self.assertEqual("dQw4w9WgXcQ", channel["streams"][0]["id"])

    def test_a_live_channel_carries_no_id(self):
        # If it did, the app would try to resolve an m3u8 as a YouTube video.
        path = self.write("iptv_x.json", {
            "network_name": "X", "channel_number": 101,
            "streams": [{"url": "https://c.mjh.nz/abc-qld.m3u8",
                         "duration": 600, "title": "ABC"}]})
        channel = build_lineup.channel_from(path)
        self.assertEqual("live", channel["kind"])
        self.assertIsNone(channel["rotation"])
        self.assertNotIn("id", channel["streams"][0])

    def test_a_youtube_stream_with_an_unreadable_url_is_dropped(self):
        # Keeping it would hand a non-watch url to the resolver, which cannot do anything with it.
        path = self.write("ytch_x.json", {
            "network_name": "X", "channel_number": 5,
            "streams": [{"url": "https://example.com/not-a-video", "duration": 10, "title": "?"},
                        {"url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                         "duration": 212, "title": "ok"}]})
        channel = build_lineup.channel_from(path)
        self.assertEqual(1, len(channel["streams"]))

    def test_an_empty_channel_is_not_published(self):
        # A channel with nothing on it is a dead number: it tunes to black and the viewer has to
        # press twice to get past it.
        path = self.write("ytch_x.json",
                          {"network_name": "X", "channel_number": 5, "streams": []})
        self.assertIsNone(build_lineup.channel_from(path))

    def test_a_web_channel_is_not_published(self):
        # WeatherStar and friends were rendered by a browser on a machine that no longer exists.
        path = self.write("weatherstar.json", {
            "network_name": "WeatherStar", "channel_number": 1, "network_type": "web",
            "web_url": "http://localhost:9090/index.html"})
        self.assertIsNone(build_lineup.channel_from(path))

    def test_a_conf_with_no_station_block_is_not_published(self):
        path = os.path.join(self.dir, "main_config.json")
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"something_else": True}, handle)
        self.assertIsNone(build_lineup.channel_from(path))


class TestPublishedLineup(unittest.TestCase):
    """The real channels.json, as the televisions will read it."""

    @classmethod
    def setUpClass(cls):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        path = os.path.join(root, "channels.json")
        if not os.path.exists(path):
            raise unittest.SkipTest("channels.json has not been built")
        with io.open(path, encoding="utf-8") as handle:
            cls.dial = json.load(handle)

    def test_it_has_the_fields_the_app_requires(self):
        self.assertIn("generated", self.dial)
        for channel in self.dial["channels"]:
            for field in ("number", "name", "kind", "streams"):
                self.assertIn(field, channel, "a channel is missing %r" % field)

    def test_youtube_streams_all_resolve_to_an_id(self):
        for channel in self.dial["channels"]:
            if channel["kind"] != "youtube":
                continue
            for stream in channel["streams"]:
                self.assertIn("id", stream,
                              "%s has a stream with no id" % channel["name"])
                self.assertEqual(11, len(stream["id"]),
                                 "%s has a malformed id %r" % (channel["name"], stream["id"]))

    def test_live_streams_never_carry_an_id(self):
        for channel in self.dial["channels"]:
            if channel["kind"] == "live":
                for stream in channel["streams"]:
                    self.assertNotIn("id", stream,
                                     "%s would be resolved as a video" % channel["name"])

    def test_no_duplicate_channel_numbers(self):
        numbers = [c["number"] for c in self.dial["channels"]]
        self.assertEqual(len(numbers), len(set(numbers)))

    def test_every_clip_has_a_positive_duration(self):
        # ClockRotation divides by the total cycle length; a channel of zero-length clips has no
        # position to compute and returns null, which shows as a channel that never plays.
        for channel in self.dial["channels"]:
            self.assertGreater(sum(s["duration"] for s in channel["streams"]), 0,
                               "%s has no playable duration" % channel["name"])


if __name__ == "__main__":
    unittest.main()
