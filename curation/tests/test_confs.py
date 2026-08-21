#!/usr/bin/env python3
"""Reading and writing the confs every script in the pipeline shares.

The rotation cursor lives in here because it is a property of a conf, not of the refresher: the
whole nightly conveyor is `sorted(paths, key=last_refreshed)[:n]`, and the one bug that mattered
was in the key.
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


class TestRotation(unittest.TestCase):
    """The cursor has to survive a fresh clone, because CI always starts with one."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, slug, stamp=None):
        station = {"network_name": slug, "channel_number": 1, "search_query": "x", "streams": []}
        if stamp is not None:
            station["last_refreshed"] = stamp
        path = os.path.join(self.dir, "ytch_%s.json" % slug)
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": station}, handle)
        return path

    def test_the_cursor_is_read_from_the_file_not_the_filesystem(self):
        # The whole bug in one assertion. Under actions/checkout every conf carries the same
        # mtime, so an mtime-based rotation degenerated to alphabetical order and the same eight
        # channels refreshed every night for weeks while 82 never did.
        old = self.write("zebra", stamp=1000)
        new = self.write("alpha", stamp=9000)
        # `zebra` sorts last alphabetically and last by mtime, and must still be chosen first
        # because its stamp is the oldest.
        self.assertEqual([old, new], sorted([new, old], key=confs.last_refreshed))

    def test_a_channel_never_refreshed_goes_first(self):
        never = self.write("never")
        recent = self.write("recent", stamp=9000)
        self.assertEqual([never, recent], sorted([recent, never], key=confs.last_refreshed))

    def test_an_unreadable_conf_sorts_first_and_says_so(self):
        # A truncated conf - the leftovers of a save killed mid-write, before saves went through
        # a temporary file - must not raise here, because raising in a sort key takes the whole
        # rotation down. But it must not be silent either: quietly returning 0 made a corrupted
        # file look like a fresh channel waiting its turn, when it needs a human. It sorts first
        # and names itself on stderr, then fails loudly in refresh() where someone will see it.
        path = os.path.join(self.dir, "ytch_broken.json")
        with io.open(path, "w") as handle:
            handle.write("{ not json")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            stamp = confs.last_refreshed(path)
        self.assertEqual(0, stamp)
        self.assertIn("ytch_broken.json", err.getvalue())
        self.assertIn("warning", err.getvalue())


class TestPaths(unittest.TestCase):
    """The slug slice and the ytch_ glob, which used to be written out at five call sites."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def touch(self, name):
        path = os.path.join(self.dir, name)
        with io.open(path, "w", encoding="utf-8") as handle:
            handle.write("{}")
        return path

    def test_live_confs_are_not_youtube_channels(self):
        # The prefix is the only thing separating them, and build_lineup uses the same test to
        # decide whether a stream needs a video id.
        self.touch("ytch_blues.json")
        self.touch("abc_qld.json")
        self.assertEqual(["ytch_blues.json"],
                         [os.path.basename(p) for p in confs.youtube_paths(self.dir)])

    def test_the_order_is_stable(self):
        # The rotation re-sorts by stamp, but --only and the reporting both rely on this being
        # the same list every run.
        for name in ("ytch_zebra.json", "ytch_alpha.json", "ytch_middle.json"):
            self.touch(name)
        self.assertEqual(["alpha", "middle", "zebra"],
                         [confs.slug_for(p) for p in confs.youtube_paths(self.dir)])

    def test_the_slug_is_the_name_between_the_prefix_and_the_extension(self):
        self.assertEqual("music_1980s", confs.slug_for("/anywhere/confs/ytch_music_1980s.json"))


class TestSave(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_a_conf_is_written_so_a_human_can_read_the_diff(self):
        # Indented and with the original characters intact. A refresh that replaces a channel's
        # clips should show as a hundred changed lines in review, not as one unreadable line.
        path = os.path.join(self.dir, "ytch_x.json")
        confs.save(path, {"station_conf": {"network_name": "Café"}})
        with io.open(path, encoding="utf-8") as handle:
            text = handle.read()
        self.assertIn("\n    ", text)
        self.assertIn("Café", text)

    def test_what_was_saved_is_what_loads_back(self):
        path = os.path.join(self.dir, "ytch_x.json")
        conf = {"station_conf": {"network_name": "X", "streams": [{"url": "u", "duration": 1}]}}
        confs.save(path, conf)
        self.assertEqual(conf, confs.load(path))

    def test_a_save_leaves_no_temporary_file_behind(self):
        # The save goes through a sibling .tmp file so a killed process can only ever leave the
        # OLD conf in place, never a truncated one. The .tmp must then be moved, not copied and
        # forgotten - a directory slowly filling with stale temporaries would be its own bug.
        path = os.path.join(self.dir, "ytch_x.json")
        confs.save(path, {"station_conf": {"network_name": "X"}})
        self.assertEqual(["ytch_x.json"], sorted(os.listdir(self.dir)))


if __name__ == "__main__":
    unittest.main()
