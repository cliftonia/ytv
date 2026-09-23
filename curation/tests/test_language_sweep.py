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
import json
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
        # The accelerator's verdict as the home server publishes it: one video id it has seen
        # declare a language other than English. Everything under test is what the sweep does
        # with that answer.
        self.verdicts = os.path.join(self.dir, "foreign.json")
        with open(self.verdicts, "w") as handle:
            json.dump({"generated": 1790000000, "foreign": [self.FOREIGN_ID]}, handle)

    def tearDown(self):
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
        sys.argv = ["language_sweep.py", "--confs", self.dir, "--verdicts", self.verdicts] + list(args)
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

    def test_no_verdict_file_means_no_information(self):
        # Not "nothing is foreign": a missing file must leave the dial exactly as it was and say
        # so, just as an unreachable server used to.
        os.remove(self.verdicts)
        path = self.write([self.clip(self.FOREIGN_ID)])
        status, out = self.run_main()
        self.assertEqual(0, status)
        self.assertEqual(1, len(confs.load(path)["station_conf"]["streams"]))
        self.assertIn("no language data available", out)

    def test_an_unreadable_verdict_file_is_no_information_too(self):
        with open(self.verdicts, "w") as handle:
            handle.write("{ half a file")
        path = self.write([self.clip(self.FOREIGN_ID)])
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            status, out = self.run_main()
        self.assertEqual(0, status)
        self.assertEqual(1, len(confs.load(path)["station_conf"]["streams"]))
        self.assertIn("no language data available", out)
        self.assertIn("foreign.json", err.getvalue())

    def test_the_server_is_only_asked_when_told_to(self):
        asked = []
        real = language_sweep.foreign_ids
        language_sweep.foreign_ids = lambda server, timeout=30: asked.append(server) or {}
        try:
            self.write([self.clip("BBBBBBBBBBB")])
            self.run_main()
            self.assertEqual([], asked)
            self.run_main("--from-server")
            self.assertEqual([language_sweep.SERVER], asked)
        finally:
            language_sweep.foreign_ids = real


class TestVerdictFile(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "foreign.json")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, body):
        with open(self.path, "w") as handle:
            json.dump(body, handle)

    def test_reads_the_ids(self):
        self.write({"generated": 1, "foreign": ["AAAAAAAAAAA", "BBBBBBBBBBB"]})
        self.assertEqual({"AAAAAAAAAAA", "BBBBBBBBBBB"},
                         set(language_sweep.foreign_from_file(self.path)))

    def test_an_empty_list_is_an_answer_not_silence(self):
        # The server looked and found nothing foreign: that is information, unlike no file.
        self.write({"generated": 1, "foreign": []})
        self.assertEqual({}, language_sweep.foreign_from_file(self.path))

    def test_missing_is_none(self):
        self.assertIsNone(language_sweep.foreign_from_file(self.path))

    def test_the_wrong_shape_is_none(self):
        for body in ([], {"generated": 1}, {"foreign": "AAAAAAAAAAA"}, {"foreign": [1, 2]}):
            self.write(body)
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertIsNone(language_sweep.foreign_from_file(self.path), body)

    def test_the_committed_file_if_present_is_well_formed(self):
        if not os.path.exists(language_sweep.VERDICTS):
            self.skipTest("no foreign.json committed yet")
        self.assertIsNotNone(language_sweep.foreign_from_file(language_sweep.VERDICTS))


if __name__ == "__main__":
    unittest.main()
