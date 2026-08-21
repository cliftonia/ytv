#!/usr/bin/env python3
"""What the refresher itself does once the searching and the filtering are taken away.

The policy that decides which clips belong now lives in filters.py and the searching in search.py,
both with their own suites. What is left here is the nightly job's own behaviour, and in
particular the throttle guard - which is the only failure signal the whole conveyor has. A night
where every request was refused looks exactly like a night where nothing had changed: all confs
untouched, no diff, "no changes", green tick.
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
import confs
import refresh_channels as refresh


class TestThinSliceGuard(unittest.TestCase):
    """Half the slice coming back thin is yt-dlp being throttled, and it must fail the run."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_refresh = refresh.refresh

    def tearDown(self):
        refresh.refresh = self.real_refresh
        shutil.rmtree(self.dir, ignore_errors=True)

    def run_main(self, counts, target=10):
        """Run the nightly job over `counts`, a slug -> clip count for a channel that refreshed.

        `refresh` itself is replaced, so no yt-dlp is invoked and no network is touched: the
        guard is the only thing under test.
        """
        for slug in counts:
            path = os.path.join(self.dir, "ytch_%s.json" % slug)
            with io.open(path, "w", encoding="utf-8") as handle:
                json.dump({"station_conf": {"network_name": slug, "channel_number": 1,
                                            "search_query": "x", "streams": []}}, handle)
        refresh.refresh = lambda path, _target: (confs.slug_for(path),
                                                 counts[confs.slug_for(path)], False)
        argv = sys.argv
        sys.argv = ["refresh_channels.py", "--confs", self.dir, "--target", str(target)]
        out, err = io.StringIO(), io.StringIO()
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                status = refresh.main()
        finally:
            sys.argv = argv
        return status, out.getvalue(), err.getvalue()

    def test_exactly_half_the_slice_thin_does_not_fail(self):
        # `>` rather than `>=`. Two of four channels genuinely having little to offer is a
        # content outcome, and failing the run on it would mean the nightly job cries wolf often
        # enough that a real throttle stops being noticed.
        status, _, err = self.run_main({"a": 9, "b": 9, "c": 1, "d": 1})
        self.assertEqual(0, status)
        self.assertNotIn("FAILED", err)

    def test_more_than_half_the_slice_thin_fails(self):
        status, _, err = self.run_main({"a": 9, "b": 1, "c": 1, "d": 1})
        self.assertEqual(1, status)
        self.assertIn("almost certainly throttled", err)

    def test_a_slice_that_refreshed_nothing_does_not_fail(self):
        # `--only` naming a channel that no longer exists returns 2 before this point, but an
        # empty confs directory reaches the guard with no results at all, and `0 > 0` would be a
        # false alarm on a dial that had nothing to do.
        status, _, err = self.run_main({})
        self.assertEqual(0, status)
        self.assertEqual("", err)

    def test_a_healthy_slice_names_the_thin_channels_without_failing(self):
        # Thin channels are reported every night whether or not they fail the run, because one
        # channel that will not fill is a query to fix rather than a throttle.
        status, out, _ = self.run_main({"a": 9, "b": 9, "c": 9, "d": 1})
        self.assertEqual(0, status)
        self.assertIn("thin: d(1)", out)


class TestEmptySearchGuard(unittest.TestCase):
    """An empty search keeps yesterday's clips, which must not read as a healthy night.

    This is the failure shape the thin guard cannot see: a kept channel reports yesterday's
    count, which is usually a full one, so a night of total yt-dlp breakage arrives at main()
    looking like a hundred healthy channels that happened to need no changes.
    """

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_refresh = refresh.refresh

    def tearDown(self):
        refresh.refresh = self.real_refresh
        shutil.rmtree(self.dir, ignore_errors=True)

    def run_main(self, outcomes):
        """Run the nightly job over `outcomes`, a slug -> (clip count, kept) per channel."""
        for slug in outcomes:
            path = os.path.join(self.dir, "ytch_%s.json" % slug)
            with io.open(path, "w", encoding="utf-8") as handle:
                json.dump({"station_conf": {"network_name": slug, "channel_number": 1,
                                            "search_query": "x", "streams": []}}, handle)
        refresh.refresh = lambda path, _target: ((confs.slug_for(path),)
                                                 + outcomes[confs.slug_for(path)])
        argv = sys.argv
        sys.argv = ["refresh_channels.py", "--confs", self.dir, "--target", "10"]
        out, err = io.StringIO(), io.StringIO()
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                status = refresh.main()
        finally:
            sys.argv = argv
        return status, out.getvalue(), err.getvalue()

    def test_more_than_half_the_slice_kept_fails(self):
        # Every kept channel reports a full count, so the thin guard stays quiet. This guard is
        # the only thing standing between total breakage and a green tick.
        status, _, err = self.run_main({"a": (9, True), "b": (9, True),
                                        "c": (9, True), "d": (9, False)})
        self.assertEqual(1, status)
        self.assertIn("kept yesterday's clips", err)

    def test_exactly_half_the_slice_kept_does_not_fail(self):
        # `>` rather than `>=`, for the same reason as the thin guard: two queries genuinely
        # finding nothing on the same night is unlucky rather than broken, and a guard that
        # cries wolf stops being read.
        status, _, err = self.run_main({"a": (9, True), "b": (9, True),
                                        "c": (9, False), "d": (9, False)})
        self.assertEqual(0, status)
        self.assertNotIn("FAILED", err)


class TestEmptySearchKeepsYesterday(unittest.TestCase):
    """What refresh() itself does when the search finds nothing, run without a network."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_collect = refresh.search.collect

    def tearDown(self):
        refresh.search.collect = self.real_collect
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, stamp, streams):
        path = os.path.join(self.dir, "ytch_x.json")
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": {"network_name": "x", "channel_number": 1,
                                        "search_query": "x", "streams": streams,
                                        "last_refreshed": stamp}}, handle)
        return path

    def refresh_path(self, path):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            return refresh.refresh(path, 10)

    def test_an_empty_search_keeps_the_clips_and_does_not_advance_the_cursor(self):
        # Stamping `last_refreshed` on a failed search would push the channel to the back of the
        # rotation for a fortnight, so a query having a bad fortnight could go a month between
        # real attempts. The cursor only moves when something was actually written.
        yesterday = [{"url": "https://www.youtube.com/watch?v=AAAAAAAAAAA",
                      "duration": 300, "title": "Yesterday's clip"}]
        path = self.write(1234, yesterday)
        refresh.search.collect = lambda *args: 0
        name, count, kept = self.refresh_path(path)
        self.assertTrue(kept)
        self.assertEqual(1, count)
        station = confs.load(path)["station_conf"]
        self.assertEqual(yesterday, station["streams"])
        self.assertEqual(1234, station["last_refreshed"])

    def test_a_successful_search_is_not_kept_and_advances_the_cursor(self):
        def fake_collect(target, lo, hi, seen, keys, out, want):
            if not out:
                out.append({"url": "https://www.youtube.com/watch?v=BBBBBBBBBBB",
                            "duration": 300, "title": "Fresh clip"})
            return 1
        path = self.write(1234, [])
        refresh.search.collect = fake_collect
        name, count, kept = self.refresh_path(path)
        self.assertFalse(kept)
        self.assertEqual(1, count)
        self.assertGreater(confs.load(path)["station_conf"]["last_refreshed"], 1234)


class TestChannelSelection(unittest.TestCase):
    """Which channels a run touches, which is the conveyor's whole behaviour."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_refresh = refresh.refresh
        self.touched = []
        refresh.refresh = lambda path, _target: (self.touched.append(confs.slug_for(path))
                                                 or (confs.slug_for(path), 100, False))

    def tearDown(self):
        refresh.refresh = self.real_refresh
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, slug, stamp):
        path = os.path.join(self.dir, "ytch_%s.json" % slug)
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": {"network_name": slug, "channel_number": 1,
                                        "search_query": "x", "streams": [],
                                        "last_refreshed": stamp}}, handle)

    def run_main(self, *args):
        argv = sys.argv
        sys.argv = ["refresh_channels.py", "--confs", self.dir] + list(args)
        out, err = io.StringIO(), io.StringIO()
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                status = refresh.main()
        finally:
            sys.argv = argv
        return status

    def test_the_rotation_takes_the_least_recently_refreshed(self):
        self.write("alpha", 9000)
        self.write("zebra", 1000)
        self.write("middle", 5000)
        self.assertEqual(0, self.run_main("--rotate", "2"))
        self.assertEqual({"zebra", "middle"}, set(self.touched))

    def test_rotate_zero_touches_nothing(self):
        # `is not None` on the argument, so --rotate 0 means "none" rather than falling through
        # to all ninety and spending an hour getting the runner's egress address throttled.
        self.write("alpha", 9000)
        self.assertEqual(0, self.run_main("--rotate", "0"))
        self.assertEqual([], self.touched)

    def test_only_names_one_channel(self):
        self.write("blues", 1000)
        self.write("jazz", 1000)
        self.assertEqual(0, self.run_main("--only", "jazz"))
        self.assertEqual(["jazz"], self.touched)

    def test_only_naming_a_channel_that_does_not_exist_is_an_error(self):
        self.write("blues", 1000)
        self.assertEqual(2, self.run_main("--only", "nosuchthing"))
        self.assertEqual([], self.touched)


if __name__ == "__main__":
    unittest.main()
