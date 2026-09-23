#!/usr/bin/env python3
"""Sponsor skips: what SponsorBlock's answer becomes in a conf, and when it is asked at all.

No test here touches the network. The lookup is handed a fake `fetch`, so what is under test is
our side of the contract: which segments count, how ranges are cleaned, which clips are due, the
per-run cap, and - the rule everything else leans on - that a failed lookup is "no information"
and never a failed nightly.
"""
import contextlib
import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import confs
import sponsor

DAY = 86400
NOW = 1_800_000_000


def url(identifier):
    return "https://www.youtube.com/watch?v=" + identifier


def segment(a, b, category="sponsor", votes=0, locked=0, **extra):
    """One segment shaped like the live API's (checked against a real response, 23 Sep 2026)."""
    body = {"category": category, "actionType": "skip", "segment": [a, b],
            "UUID": "x", "videoDuration": 0, "locked": locked, "votes": votes,
            "description": ""}
    body.update(extra)
    return body


class TestPrefix(unittest.TestCase):

    def test_the_prefix_is_the_first_four_hex_of_the_ids_sha256(self):
        self.assertEqual(hashlib.sha256(b"dQw4w9WgXcQ").hexdigest()[:4],
                         sponsor.prefix("dQw4w9WgXcQ"))

    def test_the_url_asks_for_only_the_three_categories_url_encoded(self):
        address = sponsor.url_for("5f6b")
        self.assertTrue(address.startswith("https://sponsor.ajay.app/api/skipSegments/5f6b?"))
        self.assertIn("categories=%5B%22sponsor%22%2C%22selfpromo%22%2C%22interaction%22%5D",
                      address)
        # The id itself never leaves the machine - that is the point of the prefix endpoint.
        self.assertNotIn("dQw4w9WgXcQ", address)


class TestRangesFor(unittest.TestCase):
    """Which of a prefix's segments belong to a clip."""

    def body(self, *segments, video="aaaaaaaaaaa"):
        return [{"videoID": "zzzzzzzzzzz", "segments": [segment(1, 50)]},
                {"videoID": video, "segments": list(segments)}]

    def test_only_the_wanted_videos_segments_count(self):
        # A prefix answers for every video sharing it; the neighbours are someone else's clips.
        self.assertEqual([[10, 20]],
                         sponsor.ranges_for(self.body(segment(10, 20)), "aaaaaaaaaaa", 600))

    def test_a_video_the_prefix_does_not_mention_has_nothing_to_skip(self):
        self.assertEqual([], sponsor.ranges_for(self.body(segment(10, 20)), "bbbbbbbbbbb", 600))

    def test_other_categories_are_ignored(self):
        body = self.body(segment(0, 30, category="intro"), segment(40, 50, category="outro"),
                         segment(60, 70, category="selfpromo"),
                         segment(80, 90, category="interaction"))
        self.assertEqual([[60, 70], [80, 90]], sponsor.ranges_for(body, "aaaaaaaaaaa", 600))

    def test_downvoted_segments_are_ignored_unless_locked(self):
        body = self.body(segment(10, 20, votes=-1), segment(30, 40, votes=-1, locked=1),
                         segment(50, 60, votes=0), segment(70, 80, votes=12))
        self.assertEqual([[30, 40], [50, 60], [70, 80]],
                         sponsor.ranges_for(body, "aaaaaaaaaaa", 600))

    def test_hidden_segments_are_ignored(self):
        body = self.body(segment(10, 20, hidden=1), segment(30, 40, shadowHidden=1),
                         segment(50, 60))
        self.assertEqual([[50, 60]], sponsor.ranges_for(body, "aaaaaaaaaaa", 600))

    def test_non_skip_actions_are_ignored(self):
        # A mute segment skipped would cut picture the viewer was meant to see.
        body = self.body(segment(10, 20, actionType="mute"), segment(30, 40))
        self.assertEqual([[30, 40]], sponsor.ranges_for(body, "aaaaaaaaaaa", 600))

    def test_segments_timed_against_a_different_cut_are_ignored(self):
        body = self.body(segment(10, 20, videoDuration=900), segment(30, 40, videoDuration=601.4),
                         segment(50, 60, videoDuration=0))
        self.assertEqual([[30, 40], [50, 60]], sponsor.ranges_for(body, "aaaaaaaaaaa", 600))

    def test_garbage_in_the_body_is_ignored_rather_than_raised(self):
        body = [None, "x", {"videoID": "aaaaaaaaaaa", "segments": [
            {"segment": "nope", "category": "sponsor"}, {"category": "sponsor"},
            segment(10, 20), {"segment": [1, 2, 3], "category": "sponsor"}]}]
        self.assertEqual([[10, 20]], sponsor.ranges_for(body, "aaaaaaaaaaa", 600))


class TestClean(unittest.TestCase):

    def test_overlapping_and_touching_ranges_merge_and_sort(self):
        self.assertEqual([[5.0, 30.0], [100.0, 120.0]],
                         sponsor.clean([[100, 120], [20, 30], [5, 25], [25, 26]], 600))

    def test_ranges_are_clamped_to_the_clip(self):
        self.assertEqual([[0.0, 10.0], [590.0, 600.0]],
                         sponsor.clean([[-3, 10], [590, 640]], 600))

    def test_ranges_under_a_second_are_dropped_after_merging(self):
        # Two half-second slivers that meet are one second, which is kept.
        self.assertEqual([[10.0, 11.0]], sponsor.clean([[10, 10.5], [10.5, 11], [50, 50.9]], 600))

    def test_a_range_entirely_outside_the_clip_disappears(self):
        self.assertEqual([], sponsor.clean([[700, 720]], 600))

    def test_times_are_rounded_to_a_tenth(self):
        self.assertEqual([[31.2, 74.9]], sponsor.clean([[31.2349, 74.8761]], 812))

    def test_a_clip_with_no_duration_skips_nothing(self):
        self.assertEqual([], sponsor.clean([[1, 20]], 0))


class TestDue(unittest.TestCase):

    def test_a_never_checked_clip_is_due(self):
        self.assertTrue(sponsor.due({"url": url("aaaaaaaaaaa")}, NOW))

    def test_a_clip_checked_within_thirty_days_is_not(self):
        self.assertFalse(sponsor.due({"skip": [], "skip_checked": NOW - 29 * DAY}, NOW))

    def test_a_clip_checked_over_thirty_days_ago_is_again(self):
        # Segments are submitted after upload, so an early "nothing" goes stale.
        self.assertTrue(sponsor.due({"skip": [], "skip_checked": NOW - 31 * DAY}, NOW))


class SweepCase(unittest.TestCase):
    """A confs directory and a fake SponsorBlock."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.asked = []
        self.answers = {}   # prefix -> body, or None for a failed request

    def write(self, slug, streams):
        path = os.path.join(self.dir, "ytch_%s.json" % slug)
        confs.save(path, {"station_conf": {"network_name": slug, "channel_number": 1,
                                           "streams": streams}})
        return path

    def fetch(self, prefix):
        self.asked.append(prefix)
        return self.answers.get(prefix, [])

    def sweep(self, **kwargs):
        kwargs.setdefault("max_requests", 100)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return sponsor.sweep(self.dir, NOW, self.fetch, sleep=lambda s: None,
                                 clock=lambda: 0, **kwargs)

    def streams(self, path):
        return confs.load(path)["station_conf"]["streams"]


def ids_sharing_a_prefix():
    """Two distinct valid ids with the same sha256 prefix, found by brute force (fast: 65536
    buckets, so a collision turns up within a few hundred tries)."""
    seen = {}
    for n in range(100000):
        identifier = ("v%010d" % n)
        p = sponsor.prefix(identifier)
        if p in seen:
            return seen[p], identifier
        seen[p] = identifier
    raise AssertionError("no collision")


class TestSweep(SweepCase):

    def test_clips_sharing_a_prefix_cost_one_request(self):
        first, second = ids_sharing_a_prefix()
        self.write("a", [{"url": url(first), "duration": 600, "title": "1"}])
        self.write("b", [{"url": url(second), "duration": 600, "title": "2"}])
        self.sweep()
        self.assertEqual([sponsor.prefix(first)], self.asked)

    def test_found_segments_are_cleaned_and_stored_with_the_time_checked(self):
        path = self.write("a", [{"url": url("aaaaaaaaaaa"), "duration": 600, "title": "t"},
                                {"url": url("bbbbbbbbbbb"), "duration": 600, "title": "u"}])
        self.answers[sponsor.prefix("aaaaaaaaaaa")] = [
            {"videoID": "aaaaaaaaaaa", "segments": [segment(10, 20), segment(15, 30.04)]}]
        self.sweep()
        a, b = self.streams(path)
        self.assertEqual([[10.0, 30.0]], a["skip"])
        self.assertEqual(NOW, a["skip_checked"])
        # A clean answer of "nothing" is information too: it is cached so it is not asked again.
        self.assertEqual([], b["skip"])
        self.assertEqual(NOW, b["skip_checked"])

    def test_a_fresh_clip_is_not_asked_about(self):
        self.write("a", [{"url": url("aaaaaaaaaaa"), "duration": 600, "title": "t",
                          "skip": [], "skip_checked": NOW - DAY}])
        self.sweep()
        self.assertEqual([], self.asked)

    def test_a_stale_clip_is_asked_about_again(self):
        self.write("a", [{"url": url("aaaaaaaaaaa"), "duration": 600, "title": "t",
                          "skip": [], "skip_checked": NOW - 31 * DAY}])
        self.sweep()
        self.assertEqual([sponsor.prefix("aaaaaaaaaaa")], self.asked)

    def test_the_cap_limits_requests_and_never_checked_clips_go_first(self):
        ids = ["%s%08d" % ("abc", n) for n in range(6)]
        streams = [{"url": url(i), "duration": 600, "title": i} for i in ids]
        # Two stale ones that were checked long ago; the cap must spend itself on the never-checked.
        streams[0].update(skip=[], skip_checked=NOW - 90 * DAY)
        streams[1].update(skip=[], skip_checked=NOW - 60 * DAY)
        path = self.write("a", streams)
        self.sweep(max_requests=3)
        self.assertEqual(3, len(self.asked))
        self.assertEqual(sorted(sponsor.prefix(i) for i in ids[2:])[:3], self.asked)
        checked = [s for s in self.streams(path) if s.get("skip_checked") == NOW]
        self.assertEqual(3, len(checked))

    def test_the_next_run_picks_up_where_the_cap_stopped(self):
        ids = ["%s%08d" % ("abc", n) for n in range(5)]
        path = self.write("a", [{"url": url(i), "duration": 600, "title": i} for i in ids])
        self.sweep(max_requests=3)
        self.sweep(max_requests=3)
        self.assertEqual(5, len(set(self.asked)))
        self.assertTrue(all(s.get("skip_checked") == NOW for s in self.streams(path)))

    def test_a_failed_request_is_no_information_and_the_clip_stays_due(self):
        path = self.write("a", [{"url": url("aaaaaaaaaaa"), "duration": 600, "title": "t",
                                 "skip": [[1, 5]], "skip_checked": NOW - 40 * DAY}])
        self.answers[sponsor.prefix("aaaaaaaaaaa")] = None
        self.sweep()
        stream = self.streams(path)[0]
        # Neither wiped nor re-stamped: yesterday's answer stands and tomorrow asks again.
        self.assertEqual([[1, 5]], stream["skip"])
        self.assertEqual(NOW - 40 * DAY, stream["skip_checked"])

    def test_a_run_of_failures_stops_asking(self):
        # SponsorBlock down: a handful of failures, not a thousand timeouts against a dead host.
        self.write("a", [{"url": url("%s%08d" % ("abc", n)), "duration": 600, "title": "t"}
                         for n in range(20)])
        self.fetch = lambda prefix: self.asked.append(prefix)   # always None
        self.sweep()
        self.assertEqual(sponsor.MAX_FAILURES_IN_A_ROW, len(self.asked))

    def test_the_time_budget_stops_the_run(self):
        self.write("a", [{"url": url("%s%08d" % ("abc", n)), "duration": 600, "title": "t"}
                         for n in range(10)])
        ticks = iter(range(0, 10000, 100))
        with contextlib.redirect_stdout(io.StringIO()):
            sponsor.sweep(self.dir, NOW, self.fetch, sleep=lambda s: None,
                          clock=lambda: next(ticks), max_requests=100, budget_seconds=250)
        # The clock is read once at the start (0) and once before each request (100, 200, 300):
        # the third reading is past the budget, so two requests were made.
        self.assertEqual(2, len(self.asked))

    def test_non_youtube_confs_and_unreadable_urls_are_left_alone(self):
        live = os.path.join(self.dir, "iptv_x.json")
        confs.save(live, {"station_conf": {"streams": [{"url": "https://x/a.m3u8"}]}})
        path = self.write("a", [{"url": "https://example.com/nope", "duration": 60}])
        self.sweep()
        self.assertEqual([], self.asked)
        self.assertNotIn("skip", self.streams(path)[0])
        self.assertNotIn("skip", confs.load(live)["station_conf"]["streams"][0])

    def test_an_exploding_fetch_never_escapes(self):
        path = self.write("a", [{"url": url("aaaaaaaaaaa"), "duration": 600, "title": "t"}])

        def boom(prefix):
            raise RuntimeError("the fake exploded")
        self.fetch = boom
        self.sweep()
        self.assertNotIn("skip_checked", self.streams(path)[0])


class TestFetch(unittest.TestCase):
    """The one function that talks to SponsorBlock, with the network replaced."""

    class Response(io.BytesIO):
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    def test_a_body_is_parsed(self):
        body = [{"videoID": "aaaaaaaaaaa", "segments": []}]
        seen = {}

        def opener(request, timeout):
            seen["ua"] = request.get_header("User-agent")
            seen["timeout"] = timeout
            return self.Response(json.dumps(body).encode())
        self.assertEqual(body, sponsor.fetch("abcd", opener=opener))
        self.assertTrue(seen["ua"])
        self.assertTrue(seen["timeout"])

    def test_a_404_is_a_real_answer_of_nothing(self):
        # The API's way of saying no video under this prefix has a segment.
        def opener(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 404, "Not Found", {}, None)
        self.assertEqual([], sponsor.fetch("abcd", opener=opener))

    def test_every_other_failure_is_none(self):
        failures = [urllib.error.HTTPError("u", 429, "Too Many", {}, None),
                    urllib.error.HTTPError("u", 500, "Oops", {}, None),
                    urllib.error.URLError("down"), TimeoutError(), OSError("reset")]
        for failure in failures:
            def opener(request, timeout, failure=failure):
                raise failure
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertIsNone(sponsor.fetch("abcd", opener=opener), failure)

    def test_a_body_that_is_not_a_list_is_none(self):
        for text in (b"not json", b'{"error": 1}'):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertIsNone(sponsor.fetch(
                    "abcd", opener=lambda request, timeout, t=text: self.Response(t)))


class TestCarry(unittest.TestCase):
    """A refresh rebuilds a channel's list from search results, which know nothing of skips."""

    def test_a_clip_that_survives_a_refresh_keeps_its_lookup(self):
        old = [{"url": url("aaaaaaaaaaa"), "skip": [[1, 5]], "skip_checked": NOW},
               {"url": url("bbbbbbbbbbb"), "skip": [], "skip_checked": NOW}]
        new = [{"url": url("aaaaaaaaaaa"), "duration": 600},
               {"url": url("ccccccccccc"), "duration": 600}]
        sponsor.carry(old, new)
        self.assertEqual([[1, 5]], new[0]["skip"])
        self.assertEqual(NOW, new[0]["skip_checked"])
        self.assertNotIn("skip", new[1])
        self.assertNotIn("skip_checked", new[1])


if __name__ == "__main__":
    unittest.main()
