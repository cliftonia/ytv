#!/usr/bin/env python3
"""scan_media.py: the media folders on the homelab server become the file channels' streams.

The order collect() returns IS the broadcast order - the app's clock rotation walks the list
from the top and cycles, so a sort bug here is mis-scheduled television, not untidy output.
Every case below runs against a temporary tree with ffprobe stubbed, because real probing
needs real video bytes and the suite must not.
"""
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
import contextlib
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import confs
import scan_media


class ScanCase(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.root = os.path.join(self.dir, "files")
        os.makedirs(self.root)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def video(self, relpath):
        path = os.path.join(self.root, relpath)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as handle:
            handle.write("not really video; probing is stubbed")
        return path

    def collect(self, media_dir, durations=None):
        """run collect() with a probe that answers by filename, defaulting to 100 seconds."""
        durations = durations or {}

        def fake_probe(path):
            return durations.get(os.path.basename(path), 100)

        with mock.patch.object(scan_media, "probe_duration", fake_probe):
            return scan_media.collect(self.root, os.path.join(self.root, media_dir),
                                      "http://192.168.4.58:4244")


class TestTitles(ScanCase):

    def test_a_spaced_release_name_keeps_its_words_and_loses_its_tags(self):
        self.video("Movies/The Mandalorian and Grogu (2026) 2160p HDR10+.mp4")
        (stream,) = self.collect("Movies")
        self.assertEqual("The Mandalorian and Grogu (2026)", stream["title"])

    def test_a_dot_style_release_name_is_unpacked_and_its_group_dropped(self):
        self.video("Movies/Pioneer.One.S01E01.REDUX.720p.x264-VODO.mkv")
        (stream,) = self.collect("Movies")
        self.assertEqual("Pioneer One S01E01 REDUX", stream["title"])

    def test_a_spaced_name_ending_in_a_dash_word_keeps_it(self):
        # The release-group strip only runs on dot-style names; applied to a spaced one it
        # would eat a real title word.
        self.video("Movies/S01E01 - Earthfall Pilot.mp4")
        (stream,) = self.collect("Movies")
        self.assertEqual("S01E01 - Earthfall Pilot", stream["title"])


class TestUrls(ScanCase):

    def test_spaces_and_brackets_are_quoted_but_slashes_survive(self):
        # urllib's quote encodes brackets; any HTTP server answers both forms, and the
        # encoded form is what shows in logcat when a file is being chased.
        self.video("Movies/A Film (2024).mkv")
        (stream,) = self.collect("Movies")
        self.assertEqual("http://192.168.4.58:4244/Movies/A%20Film%20%282024%29.mkv",
                         stream["url"])

    def test_the_base_url_trailing_slash_does_not_double(self):
        self.video("Movies/a.mp4")
        with mock.patch.object(scan_media, "probe_duration", lambda p: 100):
            (stream,) = scan_media.collect(self.root, os.path.join(self.root, "Movies"),
                                           "http://192.168.4.58:4244/")
        self.assertEqual("http://192.168.4.58:4244/Movies/a.mp4", stream["url"])


class TestBroadcastOrder(ScanCase):

    def test_episodes_play_in_show_then_season_then_episode_runs(self):
        for ep in ("S02E01 - New Season.mkv", "S01E11 - Late First.mkv",
                   "S01E02 - Second.mkv", "S01E01 - First.mkv"):
            self.video(os.path.join("Series", "Pioneer One", ep))
        self.video(os.path.join("Series", "Alpha Show", "S01E01 - Pilot.mkv"))
        titles = [s["title"] for s in self.collect("Series")]
        self.assertEqual([
            "Alpha Show S01E01 - Pilot",
            "Pioneer One S01E01 - First",
            "Pioneer One S01E02 - Second",
            "Pioneer One S01E11 - Late First",
            "Pioneer One S02E01 - New Season",
        ], titles)

    def test_a_dot_style_scene_release_inside_a_show_dir_titles_and_sorts(self):
        # The shape files actually arrive in: a Show dir, a dotted stem, a scene group
        # suffix - and no episode-name detail at all, which must NOT repeat the show name
        # (that was the "Cowboy Bebop S01E01 - Cowboy Bebop S01E01" bug).
        self.video("Series/Cowboy Bebop/Cowboy.Bebop.S01E02.1080p.BluRay.x264-V0ldemort.mkv")
        self.video("Series/Cowboy Bebop/Cowboy.Bebop.S01E01.1080p.BluRay.x264-V0ldemort.mkv")
        self.video("Series/Black Lagoon/Black.Lagoon.S02E01.720p.BluRay.x264-DON.mkv")
        self.assertEqual(
            ["Black Lagoon S02E01", "Cowboy Bebop S01E01", "Cowboy Bebop S01E02"],
            [s["title"] for s in self.collect("Series")])

    def test_files_outside_a_show_directory_sort_after_the_runs(self):
        # A loose special is still content; it just cannot sit inside a season run.
        self.video(os.path.join("Series", "Christmas Special.mp4"))
        self.video(os.path.join("Series", "Pioneer One", "S01E01 - Pilot.mkv"))
        titles = [s["title"] for s in self.collect("Series")]
        self.assertEqual(["Pioneer One S01E01 - Pilot", "Christmas Special"], titles)


class TestNonContent(ScanCase):

    def test_partial_downloads_samples_and_hidden_files_are_not_content(self):
        self.video("Movies/download-tmp/Incomplete Film.mp4")
        self.video("Movies/Great Film.Sample.2024.mkv")
        self.video("Movies/.DS_Store.mp4")
        self.video("Movies/Great Film (2024).mkv")
        streams = self.collect("Movies")
        self.assertEqual(["Great Film (2024)"], [s["title"] for s in streams])

    def test_a_file_with_no_readable_duration_is_skipped_but_its_siblings_survive(self):
        # Shipping it with a guessed duration mis-schedules every programme after it in the
        # cycle - the phantom-clip failure build_lineup refuses at the publish gate.
        self.video("Movies/Broken.mp4")
        self.video("Movies/Fine.mp4")
        streams = self.collect("Movies", durations={"Broken.mp4": None, "Fine.mp4": 500})
        self.assertEqual(["Fine"], [s["title"] for s in streams])
        self.assertEqual(500, streams[0]["duration"])


class TestRemoteUrls(ScanCase):

    def test_remote_urls_are_probed_and_keep_authored_order(self):
        with mock.patch.object(scan_media, "probe_duration", lambda u: 4321):
            streams = scan_media.collect_remote([
                "https://archive.org/download/x/The_Red_House_1947.mp4",
                "https://example.org/film.mp4|Detour (1945)",
            ])
        self.assertEqual(["The Red House 1947", "Detour (1945)"],
                         [s["title"] for s in streams])
        self.assertEqual(4321, streams[0]["duration"])
        self.assertTrue(all(s["url"].startswith("https://") for s in streams))

    def test_a_remote_with_no_readable_duration_is_skipped_loudly(self):
        # Same rule as local files: a guessed duration mis-schedules everything after it
        # in the cycle, so the stream drops and the operator is told.
        with mock.patch.object(scan_media, "probe_duration", lambda u: None), \
                mock.patch.object(sys, "stderr", io.StringIO()) as err:
            self.assertEqual([], scan_media.collect_remote(["https://example.org/x.mp4"]))
        self.assertIn("no readable duration", err.getvalue())


class TestMain(ScanCase):

    def setUp(self):
        super().setUp()
        self.confs_dir = os.path.join(self.dir, "confs")
        os.makedirs(self.confs_dir)
        conf = {"station_conf": {"network_name": "Movies", "channel_number": 91,
                                 "media_dir": "Movies", "streams": []}}
        with io.open(os.path.join(self.confs_dir, "file_movies.json"), "w",
                     encoding="utf-8") as handle:
            json.dump(conf, handle)

    def main(self, *extra):
        argv = ["scan_media.py", "--root", self.root, "--base",
                "http://192.168.4.58:4244", "--confs", self.confs_dir] + list(extra)
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(scan_media, "probe_duration", lambda p: 100), \
                mock.patch.object(scan_media.subprocess, "run") as run, \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            run.return_value.returncode = 0  # the ffprobe -version presence check
            status = scan_media.main()
        return status, out.getvalue(), err.getvalue()

    def test_a_dry_run_reports_but_writes_nothing(self):
        self.video("Movies/A Film.mp4")
        status, out, _ = self.main("--dry")
        self.assertEqual(0, status)
        self.assertIn("movies: 1 stream", out)
        station = confs.load(os.path.join(self.confs_dir, "file_movies.json"))["station_conf"]
        self.assertEqual([], station["streams"])

    def test_a_scan_writes_the_streams_and_a_second_scan_changes_nothing(self):
        # The streams are recomputed from disk each run, so a nightly cron re-running this
        # (or nobody running it for a month) cannot drift from what is actually there.
        self.video("Movies/A Film.mp4")
        status, out, _ = self.main()
        self.assertEqual(0, status)
        station = confs.load(os.path.join(self.confs_dir, "file_movies.json"))["station_conf"]
        self.assertEqual("A Film", station["streams"][0]["title"])

    def test_a_media_dir_that_does_not_exist_is_named_not_silently_skipped(self):
        # Pointing at a folder that is not mounted (the HDD stayed unmounted once before)
        # must read as an error, because the silent version publishes an empty channel.
        os.makedirs(os.path.join(self.root, "Movies"))
        os.rename(os.path.join(self.root, "Movies"), os.path.join(self.root, "Gone"))
        _, _, err = self.main()
        self.assertIn("is not a directory", err)

    def test_a_remote_only_channel_scans_without_any_media_dir(self):
        os.makedirs(os.path.join(self.root, "Movies"))  # the sibling conf from setUp
        confs.save(os.path.join(self.confs_dir, "file_cinema_stream.json"),
                   {"station_conf": {"network_name": "Cinema Stream", "channel_number": 93,
                                     "media_dir": "",
                                     "remote_urls": ["https://example.org/a.mp4|A Stream"],
                                     "streams": []}})
        status, out, err = self.main()
        self.assertEqual(0, status)
        self.assertNotIn("not a directory", err)
        station = confs.load(os.path.join(self.confs_dir, "file_cinema_stream.json"))[
            "station_conf"]
        self.assertEqual("A Stream", station["streams"][0]["title"])

    def test_an_empty_conf_is_named_not_silently_passed(self):
        confs.save(os.path.join(self.confs_dir, "file_bare.json"),
                   {"station_conf": {"network_name": "Bare", "channel_number": 94,
                                     "streams": []}})
        _, _, err = self.main()
        self.assertIn("nothing to scan", err)


if __name__ == "__main__":
    unittest.main()
