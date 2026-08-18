#!/usr/bin/env python3
"""Reconciling the confs on disk to the dial declared in dial.py.

This is the only script in the pipeline that DELETES a channel conf, and it had no tests at all.
Every case below is written against a temporary confs directory and a stub dial, so none of it
can reach a real channel.

These are characterisation tests: they pin what the script does today, including the ordering
that the 9978c00 defect turned on. They are not a specification of what it ought to do.
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
import apply_dial
import confs


class Dial(object):
    """A dial with nothing on it, for a test to fill in the one field it cares about."""

    RENAMED_SLUGS = {}
    ABSORBED = {}
    DROPPED = {}
    PLAYLISTS = {}
    EXTRA_QUERIES = {}
    YOUTUBE = ()
    LIVE = ()


class ApplyDialCase(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_confs = confs.CONFS
        self.real_dial = apply_dial.DIAL
        confs.CONFS = self.dir
        self.dial = Dial()
        apply_dial.DIAL = self.dial

    def tearDown(self):
        confs.CONFS = self.real_confs
        apply_dial.DIAL = self.real_dial
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, name, station):
        path = os.path.join(self.dir, name)
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": station}, handle)
        return path

    def read(self, name):
        return confs.load(os.path.join(self.dir, name))["station_conf"]

    def run_apply(self, *args):
        argv = sys.argv
        sys.argv = ["apply_dial.py"] + list(args)
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                status = apply_dial.main()
        finally:
            sys.argv = argv
        return status, out.getvalue()

    def names(self):
        return sorted(os.listdir(self.dir))


class TestDefaults(ApplyDialCase):
    """The defect from 9978c00: defaults must be applied before the no-changes exit."""

    def test_a_conf_that_already_matches_still_acquires_a_missing_rotation(self):
        # The whole bug. `stream_rotation` used to be filled in AFTER the early exit, so a conf
        # whose name, number and query already matched could never acquire it - and a youtube
        # channel without it is published with `rotation: null`, which makes the tuner replay
        # clip 0 from the start on every single visit.
        self.dial.YOUTUBE = ((5, "blues", "Blues", "blues music"),)
        self.write("ytch_blues.json", {"network_name": "Blues", "channel_number": 5,
                                       "search_query": "blues music", "streams": []})
        _, out = self.run_apply()
        self.assertIn("added stream_rotation", out)
        self.assertEqual("clock", self.read("ytch_blues.json")["stream_rotation"])

    def test_the_other_two_defaults_arrive_the_same_way(self):
        self.dial.YOUTUBE = ((5, "blues", "Blues", "blues music"),)
        self.write("ytch_blues.json", {"network_name": "Blues", "channel_number": 5,
                                       "search_query": "blues music"})
        self.run_apply()
        station = self.read("ytch_blues.json")
        self.assertEqual("streaming", station["network_type"])
        self.assertEqual([], station["streams"])

    def test_a_conf_with_nothing_missing_is_left_entirely_alone(self):
        # Idempotence is what makes this safe to run after editing dial.py rather than something
        # to be careful with. A second run must report nothing at all.
        self.dial.YOUTUBE = ((5, "blues", "Blues", "blues music"),)
        self.write("ytch_blues.json", {"network_name": "Blues", "network_long_name": "Blues",
                                       "channel_number": 5, "search_query": "blues music",
                                       "network_type": "streaming", "stream_rotation": "clock",
                                       "streams": []})
        _, out = self.run_apply()
        self.assertIn("0 actions", out)

    def test_a_refresh_of_the_clips_is_never_undone(self):
        # Content is never invented or removed here: a channel that has just been filled by
        # refresh_channels must come through a renumbering with its clips intact.
        self.dial.YOUTUBE = ((7, "blues", "Blues", "blues music"),)
        streams = [{"url": "https://www.youtube.com/watch?v=aaaaaaaaaaa",
                    "duration": 212, "title": "A song"}]
        self.write("ytch_blues.json", {"network_name": "Blues", "channel_number": 5,
                                       "search_query": "blues music", "streams": streams,
                                       "network_type": "streaming", "stream_rotation": "clock"})
        self.run_apply()
        station = self.read("ytch_blues.json")
        self.assertEqual(7, station["channel_number"])
        self.assertEqual(streams, station["streams"])


class TestCreationAndRemoval(ApplyDialCase):

    def test_a_new_channel_is_created_empty(self):
        # Content is filled in by refresh_channels, so this can be re-run at any time without
        # spending an hour of searching.
        self.dial.YOUTUBE = ((5, "blues", "Blues", "blues music"),)
        self.run_apply()
        station = self.read("ytch_blues.json")
        self.assertEqual([], station["streams"])
        self.assertEqual("clock", station["stream_rotation"])

    def test_a_dropped_channel_is_deleted(self):
        self.dial.DROPPED = {"gone": "nothing worth watching"}
        self.write("ytch_gone.json", {"network_name": "Gone", "channel_number": 1})
        self.run_apply()
        self.assertEqual([], self.names())

    def test_an_absorbed_channel_is_deleted_and_the_survivor_kept(self):
        self.dial.ABSORBED = {"soul": "motown"}
        self.dial.YOUTUBE = ((1, "motown", "Motown", "motown"),)
        self.write("ytch_soul.json", {"network_name": "Soul", "channel_number": 2})
        self.write("ytch_motown.json", {"network_name": "Motown", "channel_number": 1,
                                        "search_query": "motown", "network_type": "streaming",
                                        "stream_rotation": "clock", "streams": []})
        self.run_apply()
        self.assertEqual(["ytch_motown.json"], self.names())

    def test_a_rename_happens_before_anything_else_looks_for_the_slug(self):
        # Order matters: rename first, so step 4 finds the conf under its new name and updates it
        # rather than creating a second empty one alongside.
        self.dial.RENAMED_SLUGS = {"old": "new"}
        self.dial.YOUTUBE = ((3, "new", "New", "new query"),)
        self.write("ytch_old.json", {"network_name": "Old", "channel_number": 3,
                                     "search_query": "new query", "network_type": "streaming",
                                     "stream_rotation": "clock",
                                     "streams": [{"url": "u", "duration": 1, "title": "t"}]})
        self.run_apply()
        self.assertEqual(["ytch_new.json"], self.names())
        self.assertEqual(1, len(self.read("ytch_new.json")["streams"]))

    def test_an_unknown_conf_is_named_rather_than_deleted(self):
        # Guessing whether an orphan is something to add to dial.py or something to remove from
        # it would eventually throw away a channel somebody wanted.
        self.write("ytch_mystery.json", {"network_name": "Mystery", "channel_number": 9})
        _, out = self.run_apply()
        self.assertIn("ORPHAN  ytch_mystery.json", out)
        self.assertEqual(["ytch_mystery.json"], self.names())

    def test_a_live_channel_with_no_conf_and_no_urls_is_reported_not_invented(self):
        self.dial.LIVE = ((101, "abc_qld", "ABC QLD", None),)
        _, out = self.run_apply()
        self.assertIn("MISSING", out)
        self.assertEqual([], self.names())


class TestDryRun(ApplyDialCase):

    def test_a_dry_run_writes_nothing_but_says_everything(self):
        self.dial.YOUTUBE = ((5, "blues", "Blues", "blues music"),)
        self.dial.DROPPED = {"gone": "nothing worth watching"}
        self.write("ytch_gone.json", {"network_name": "Gone", "channel_number": 1})
        _, out = self.run_apply("--dry")
        self.assertIn("create  blues", out)
        self.assertIn("drop    gone", out)
        self.assertIn("(dry run, nothing written)", out)
        self.assertEqual(["ytch_gone.json"], self.names())


if __name__ == "__main__":
    unittest.main()
