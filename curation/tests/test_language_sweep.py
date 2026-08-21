#!/usr/bin/env python3
"""The language sweep, run over conf-shaped streams with the accelerator stubbed out.

The shape of the data is the whole point of this suite. The sweep once matched on s.get("id"),
and conf streams do not carry an id - the field is minted later, by build_lineup, when the
lineup is published. Every stream therefore compared None against the foreign map, and the sweep
reported a healthy-looking run every night while never removing anything. These tests pin the
sweep to what a conf actually contains: a url and nothing else.
"""
import contextlib
import io
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import confs
import language_sweep


class TestSweep(unittest.TestCase):

    FOREIGN_ID = "AAAAAAAAAAA"

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_foreign_ids = language_sweep.foreign_ids
        # The accelerator's verdict, without the accelerator: one video id it has seen declare
        # Hindi. Everything under test is what the sweep does with that answer.
        language_sweep.foreign_ids = lambda server, timeout=30: {self.FOREIGN_ID: "hi"}

    def tearDown(self):
        language_sweep.foreign_ids = self.real_foreign_ids
        shutil.rmtree(self.dir, ignore_errors=True)

    def clip(self, video):
        # A stream exactly as a conf carries it: url, duration, title. No id - that is the bug
        # this suite exists to keep dead.
        return {"url": "https://www.youtube.com/watch?v=%s" % video,
                "duration": 300, "title": "Clip %s" % video}

    def write(self, streams):
        path = os.path.join(self.dir, "ytch_test.json")
        confs.save(path, {"station_conf": {"network_name": "Test", "channel_number": 1,
                                           "streams": streams}})
        return path

    def run_main(self, *args):
        argv = sys.argv
        sys.argv = ["language_sweep.py", "--confs", self.dir] + list(args)
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                status = language_sweep.main()
        finally:
            sys.argv = argv
        return status, out.getvalue()

    def test_a_stream_whose_video_id_is_foreign_is_removed(self):
        path = self.write([self.clip(self.FOREIGN_ID), self.clip("BBBBBBBBBBB")])
        status, out = self.run_main()
        self.assertEqual(0, status)
        remaining = confs.load(path)["station_conf"]["streams"]
        self.assertEqual(["https://www.youtube.com/watch?v=BBBBBBBBBBB"],
                         [s["url"] for s in remaining])
        self.assertIn("1 clips removed", out)

    def test_a_stream_the_accelerator_has_not_condemned_is_kept(self):
        path = self.write([self.clip("BBBBBBBBBBB")])
        status, out = self.run_main()
        self.assertEqual(0, status)
        self.assertEqual(1, len(confs.load(path)["station_conf"]["streams"]))
        self.assertIn("0 clips removed", out)

    def test_a_stream_with_no_watch_url_is_kept(self):
        # A url that is not a watch url has no video id, so video_id returns None for it - and
        # None must never accidentally match the foreign map the way a missing "id" once did.
        path = self.write([{"url": "https://www.youtube.com/playlist?list=PL12345",
                            "duration": 300, "title": "Not a watch url"}])
        status, _ = self.run_main()
        self.assertEqual(0, status)
        self.assertEqual(1, len(confs.load(path)["station_conf"]["streams"]))

    def test_a_dry_run_reports_but_removes_nothing(self):
        path = self.write([self.clip(self.FOREIGN_ID)])
        status, out = self.run_main("--dry")
        self.assertEqual(0, status)
        self.assertEqual(1, len(confs.load(path)["station_conf"]["streams"]))
        self.assertIn("(dry run)", out)


if __name__ == "__main__":
    unittest.main()
