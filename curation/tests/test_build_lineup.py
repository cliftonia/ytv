#!/usr/bin/env python3
"""The channels.json contract.

Every field checked here is one the app reads. `Stream.id` in particular decides how a clip is
played: present means "resolve this YouTube id", absent means "this is a live HLS url, play it
as-is". Getting that backwards does not fail loudly - it hands a watch page to a video player and
shows a black screen.
"""
import contextlib
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

    def test_a_file_channel_plays_its_urls_as_is_and_rotates_by_clock(self):
        # The url IS the playable: minting an id from it (or dropping it for lacking one)
        # would send the app looking for a resolve that can never succeed. And it must
        # rotate - the whole point of the kind is a film joined partway through.
        path = self.write("file_movies.json", {
            "network_name": "Movies", "channel_number": 91,
            "streams": [{"url": "http://192.168.4.58:4244/Movies/A%20Film%20(2024).mkv",
                         "duration": 5412, "title": "A Film"}]})
        channel = build_lineup.channel_from(path)
        self.assertEqual("file", channel["kind"])
        self.assertEqual("clock", channel["rotation"])
        stream = channel["streams"][0]
        self.assertEqual("http://192.168.4.58:4244/Movies/A%20Film%20(2024).mkv",
                         stream["url"])
        self.assertEqual(5412, stream["duration"])
        self.assertNotIn("id", stream)

    def test_a_youtube_stream_with_an_unreadable_url_is_dropped(self):
        # Keeping it would hand a non-watch url to the resolver, which cannot do anything with it.
        path = self.write("ytch_x.json", {
            "network_name": "X", "channel_number": 5,
            "streams": [{"url": "https://example.com/not-a-video", "duration": 10, "title": "?"},
                        {"url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                         "duration": 212, "title": "ok"}]})
        channel = build_lineup.channel_from(path)
        self.assertEqual(1, len(channel["streams"]))

    def test_a_stream_with_no_duration_is_dropped_while_its_siblings_survive(self):
        # The rotation skips a zero-length clip, so leaving it in the list gives the channel a
        # slot that can never be on air: the cycle is shorter than the clip count says and there
        # is nothing on the screen to diagnose it from. Only the channel total was asserted on
        # the published lineup, which a single phantom among a hundred good clips sails past.
        path = self.write("ytch_x.json", {
            "network_name": "X", "channel_number": 5,
            "streams": [{"url": "https://www.youtube.com/watch?v=aaaaaaaaaaa",
                         "duration": 212, "title": "first"},
                        {"url": "https://www.youtube.com/watch?v=bbbbbbbbbbb",
                         "duration": 0, "title": "phantom"},
                        {"url": "https://www.youtube.com/watch?v=ccccccccccc",
                         "title": "no duration at all"},
                        {"url": "https://www.youtube.com/watch?v=ddddddddddd",
                         "duration": -1, "title": "negative"},
                        {"url": "https://www.youtube.com/watch?v=eeeeeeeeeee",
                         "duration": 300, "title": "last"}]})
        channel = build_lineup.channel_from(path)
        self.assertEqual(["first", "last"], [s["title"] for s in channel["streams"]])

    def test_a_channel_of_nothing_but_zero_length_clips_is_not_published(self):
        # Every clip dropped leaves an empty list, which is a dead number on the dial rather than
        # a channel that plays nothing.
        path = self.write("ytch_x.json", {
            "network_name": "X", "channel_number": 5,
            "streams": [{"url": "https://www.youtube.com/watch?v=aaaaaaaaaaa",
                         "duration": 0, "title": "phantom"}]})
        self.assertIsNone(build_lineup.channel_from(path))

    def test_an_empty_channel_is_not_published(self):
        # A channel with nothing on it is a dead number: it tunes to black and the viewer has to
        # press twice to get past it.
        path = self.write("ytch_x.json",
                          {"network_name": "X", "channel_number": 5, "streams": []})
        self.assertIsNone(build_lineup.channel_from(path))

    def test_a_youtube_stream_carries_its_skips_only_when_there_are_some(self):
        # An empty list is a cached "SponsorBlock had nothing", which is conf bookkeeping; the
        # televisions fetch channels.json over mobile data and do not need nine thousand `[]`s.
        # skip_checked is bookkeeping too and never leaves the conf. duration stays raw: the app
        # derives the watch time from the two.
        path = self.write("ytch_x.json", {
            "network_name": "X", "channel_number": 5, "stream_rotation": "clock",
            "streams": [{"url": "https://www.youtube.com/watch?v=aaaaaaaaaaa", "duration": 812,
                         "title": "ad", "skip": [[31.2, 74.9]], "skip_checked": 1},
                        {"url": "https://www.youtube.com/watch?v=bbbbbbbbbbb", "duration": 300,
                         "title": "clean", "skip": [], "skip_checked": 1},
                        {"url": "https://www.youtube.com/watch?v=ccccccccccc", "duration": 300,
                         "title": "never asked"}]})
        first, clean, unasked = build_lineup.channel_from(path)["streams"]
        self.assertEqual([[31.2, 74.9]], first["skip"])
        self.assertEqual(812, first["duration"])
        self.assertNotIn("skip_checked", first)
        self.assertNotIn("skip", clean)
        self.assertNotIn("skip", unasked)

    def test_skips_that_would_leave_almost_nothing_to_watch_are_not_published(self):
        # The rotation divides by the watch time, so a clip skipped down to a few seconds is a
        # near-phantom (see the zero-duration rule). It plays in full instead - the same outcome
        # as SponsorBlock having no information - rather than dropping out and moving the gate's
        # clip counts.
        path = self.write("ytch_x.json", {
            "network_name": "X", "channel_number": 5, "stream_rotation": "clock",
            "streams": [{"url": "https://www.youtube.com/watch?v=aaaaaaaaaaa", "duration": 300,
                         "title": "all ad", "skip": [[0.0, 280.0]], "skip_checked": 1}]})
        stream = build_lineup.channel_from(path)["streams"][0]
        self.assertNotIn("skip", stream)
        self.assertEqual(300, stream["duration"])

    def test_a_live_or_file_stream_never_carries_skips(self):
        # SponsorBlock timings belong to a YouTube video; on anything else they are nonsense.
        path = self.write("file_x.json", {
            "network_name": "X", "channel_number": 91,
            "streams": [{"url": "http://h/a.mkv", "duration": 600, "title": "a",
                         "skip": [[1.0, 5.0]]}]})
        self.assertNotIn("skip", build_lineup.channel_from(path)["streams"][0])

    def test_a_youtube_stream_carries_its_parts_only_when_it_has_some(self):
        # Untagged clips are the all-day pool, and absent is how the contract says so: an empty
        # list on every one of nine thousand streams would say the same thing over mobile data.
        # Published in the day's order and only the parts the app knows, so a hand-edited conf
        # can neither churn the file by reordering nor publish a part nothing ever draws from.
        path = self.write("ytch_x.json", {
            "network_name": "X", "channel_number": 5, "stream_rotation": "clock",
            "streams": [{"url": "https://www.youtube.com/watch?v=aaaaaaaaaaa", "duration": 300,
                         "title": "a", "parts": ["late", "brunch", "prime"]},
                        {"url": "https://www.youtube.com/watch?v=bbbbbbbbbbb", "duration": 300,
                         "title": "b", "parts": []},
                        {"url": "https://www.youtube.com/watch?v=ccccccccccc", "duration": 300,
                         "title": "c"}]})
        tagged, empty, untagged = build_lineup.channel_from(path)["streams"]
        self.assertEqual(["prime", "late"], tagged["parts"])
        self.assertNotIn("parts", empty)
        self.assertNotIn("parts", untagged)

    def test_a_live_or_file_stream_never_carries_parts(self):
        # The contract gives parts to YouTube streams only; files and live feeds are unmixed.
        path = self.write("file_x.json", {
            "network_name": "X", "channel_number": 91,
            "streams": [{"url": "http://h/a.mkv", "duration": 600, "title": "a",
                         "parts": ["prime"]}]})
        self.assertNotIn("parts", build_lineup.channel_from(path)["streams"][0])

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


class TestGenerated(unittest.TestCase):
    """`generated` must not change unless the lineup did.

    A fresh timestamp on every build meant channels.json differed every night whatever else
    happened, so the workflow's "no changes" branch could never run and every night committed.
    """

    CHANNELS = [{"number": 1, "name": "a", "kind": "live", "rotation": None,
                 "streams": [{"url": "u", "duration": 600, "title": "t"}]}]

    def test_an_unchanged_lineup_keeps_its_stamp(self):
        previous = {"generated": 1234, "channels": self.CHANNELS}
        self.assertEqual(1234, build_lineup.generated_for(self.CHANNELS, previous, now=9999))

    def test_a_changed_lineup_is_stamped_now(self):
        previous = {"generated": 1234, "channels": []}
        self.assertEqual(9999, build_lineup.generated_for(self.CHANNELS, previous, now=9999))

    def test_no_previous_lineup_is_stamped_now(self):
        self.assertEqual(9999, build_lineup.generated_for(self.CHANNELS, None, now=9999))
        self.assertEqual(9999, build_lineup.generated_for(self.CHANNELS, {}, now=9999))

    def test_a_previous_lineup_without_a_stamp_is_stamped_now(self):
        previous = {"channels": self.CHANNELS}
        self.assertEqual(9999, build_lineup.generated_for(self.CHANNELS, previous, now=9999))

    def test_two_builds_of_the_same_confs_are_byte_identical(self):
        confs_dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, confs_dir, True)
        with io.open(os.path.join(confs_dir, "news.json"), "w", encoding="utf-8") as handle:
            json.dump({"station_conf": {"network_name": "News", "channel_number": 101,
                                        "streams": [{"url": "https://x/a.m3u8", "duration": 600,
                                                     "title": "News"}]}}, handle)
        out = os.path.join(confs_dir, "channels.json")

        def build():
            argv = sys.argv
            sys.argv = ["build_lineup.py", "--confs", confs_dir, "--out", out]
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, build_lineup.main())
            finally:
                sys.argv = argv
            with open(out, "rb") as handle:
                return handle.read()

        first = build()
        real_time = build_lineup.time.time
        build_lineup.time.time = lambda: real_time() + 86400
        try:
            self.assertEqual(first, build())
        finally:
            build_lineup.time.time = real_time


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

    def test_published_skips_are_sorted_disjoint_ranges_inside_the_clip(self):
        # The app steps over skip ranges in order to map watch time to media time; an unsorted,
        # overlapping or out-of-bounds list would send it seeking somewhere the clip is not.
        for channel in self.dial["channels"]:
            for stream in channel["streams"]:
                skip = stream.get("skip")
                if skip is None:
                    continue
                self.assertEqual("youtube", channel["kind"])
                self.assertTrue(skip, "%s publishes an empty skip list" % channel["name"])
                end = 0
                for start, stop in skip:
                    self.assertGreaterEqual(start, end, "%s: unsorted or overlapping skips %r"
                                            % (channel["name"], skip))
                    self.assertGreaterEqual(stop - start, 1)
                    end = stop
                self.assertLessEqual(end, stream["duration"])

    def test_published_parts_are_known_parts_of_the_day(self):
        import confs
        for channel in self.dial["channels"]:
            for stream in channel["streams"]:
                parts = stream.get("parts")
                if parts is None:
                    continue
                self.assertEqual("youtube", channel["kind"])
                self.assertTrue(parts, "%s publishes an empty parts list" % channel["name"])
                self.assertEqual([p for p in confs.DAY_PARTS if p in parts], parts)


if __name__ == "__main__":
    unittest.main()
