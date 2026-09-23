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
        refresh.search.collect = lambda *args, **kwargs: 0
        name, count, kept = self.refresh_path(path)
        self.assertTrue(kept)
        self.assertEqual(1, count)
        station = confs.load(path)["station_conf"]
        self.assertEqual(yesterday, station["streams"])
        self.assertEqual(1234, station["last_refreshed"])
        # Counted, though, so a query that is broken for good eventually yields its slot.
        self.assertEqual(1, station["refresh_misses"])

    def test_a_successful_search_is_not_kept_and_advances_the_cursor(self):
        def fake_collect(target, lo, hi, seen, keys, out, want, exclude=()):
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

    def test_a_clip_found_again_keeps_its_sponsor_lookup(self):
        # Search results know nothing of SponsorBlock; without carrying the answer over, every
        # refresh would send the clips it found again back to the lookup queue.
        found = [{"url": "https://www.youtube.com/watch?v=BBBBBBBBBBB",
                  "duration": 300, "title": "Same clip"},
                 {"url": "https://www.youtube.com/watch?v=CCCCCCCCCCC",
                  "duration": 300, "title": "New clip"}]

        def fake_collect(target, lo, hi, seen, keys, out, want, exclude=()):
            if not out:
                out.extend(dict(s) for s in found)
            return len(found)
        path = self.write(1234, [dict(found[0], skip=[[1.0, 9.0]], skip_checked=77)])
        refresh.search.collect = fake_collect
        self.refresh_path(path)
        same, new = confs.load(path)["station_conf"]["streams"]
        self.assertEqual([[1.0, 9.0]], same["skip"])
        self.assertEqual(77, same["skip_checked"])
        self.assertNotIn("skip_checked", new)


def clips(n, prefix="A"):
    """`n` distinct stream entries, shaped like a conf's."""
    return [{"url": "https://www.youtube.com/watch?v=%s%010d" % (prefix, i),
             "duration": 300, "title": "%s clip %d" % (prefix, i)} for i in range(n)]


class TestCollapseKeepsYesterday(unittest.TestCase):
    """A search that comes back with a fraction of yesterday's clips is not published.

    The lineup workflow's publish gate refuses the WHOLE dial when any channel of 20+ clips falls
    below half. Before refresh() applied the same rule itself, one query having a bad night
    produced a conf the gate then refused, and every nightly after it failed on the same
    channel until someone intervened.
    """

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_collect = refresh.search.collect

    def tearDown(self):
        refresh.search.collect = self.real_collect
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, streams, stamp=1234, misses=None):
        path = os.path.join(self.dir, "ytch_x.json")
        station = {"network_name": "x", "channel_number": 1, "search_query": "x",
                   "streams": streams, "last_refreshed": stamp}
        if misses is not None:
            station["refresh_misses"] = misses
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": station}, handle)
        return path

    def search_finds(self, n):
        fresh = clips(n, prefix="B")

        def fake_collect(target, lo, hi, seen, keys, out, want, exclude=()):
            if not out:
                out.extend(fresh)
            return len(fresh)
        refresh.search.collect = fake_collect

    def refresh_path(self, path):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            result = refresh.refresh(path, 100)
        return result, out.getvalue()

    def test_a_collapse_keeps_yesterdays_list_and_says_so(self):
        yesterday = clips(40)
        path = self.write(yesterday)
        self.search_finds(19)
        (name, count, kept), out = self.refresh_path(path)
        self.assertTrue(kept)
        self.assertEqual(40, count)
        self.assertIn("fell from 40 to 19", out)
        station = confs.load(path)["station_conf"]
        self.assertEqual(yesterday, station["streams"])
        self.assertEqual(1234, station["last_refreshed"])

    def test_exactly_half_is_published(self):
        # The gate's own comparison is `after < before // 2`; refresh() must agree with it
        # exactly, or it keeps lists the gate would have passed (or publishes ones it refuses).
        path = self.write(clips(40))
        self.search_finds(20)
        (_, count, kept), _ = self.refresh_path(path)
        self.assertFalse(kept)
        self.assertEqual(20, count)

    def test_a_small_channel_is_not_held_to_the_rule(self):
        # Below 20 clips the gate does not compare, so neither does this.
        path = self.write(clips(19))
        self.search_finds(3)
        (_, count, kept), _ = self.refresh_path(path)
        self.assertFalse(kept)
        self.assertEqual(3, count)


class TestMissesStillAdvanceTheCursor(unittest.TestCase):
    """A channel that keeps yesterday's clips night after night must not hog the rotation.

    Kept channels do not stamp `last_refreshed`, so they stay at the front of the queue. That is
    right for one bad night - try again tomorrow - and wrong for a query that is simply broken:
    it would take a slot every night forever and starve the rest of the dial. After MAX_MISSES
    consecutive kept nights the cursor advances anyway and the channel waits its turn.
    """

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_collect = refresh.search.collect
        refresh.search.collect = lambda *args, **kwargs: 0

    def tearDown(self):
        refresh.search.collect = self.real_collect
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, misses=None):
        path = os.path.join(self.dir, "ytch_x.json")
        station = {"network_name": "x", "channel_number": 1, "search_query": "x",
                   "streams": clips(5), "last_refreshed": 1234}
        if misses is not None:
            station["refresh_misses"] = misses
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": station}, handle)
        return path

    def night(self, path):
        with contextlib.redirect_stdout(io.StringIO()):
            return refresh.refresh(path, 10)

    def test_misses_are_counted_without_moving_the_cursor(self):
        path = self.write()
        self.night(path)
        station = confs.load(path)["station_conf"]
        self.assertEqual(1, station["refresh_misses"])
        self.assertEqual(1234, station["last_refreshed"])

    def test_the_last_allowed_miss_advances_the_cursor_and_resets_the_count(self):
        path = self.write()
        for _ in range(refresh.MAX_MISSES):
            _, _, kept = self.night(path)
            self.assertTrue(kept)
        station = confs.load(path)["station_conf"]
        self.assertGreater(station["last_refreshed"], 1234)
        self.assertNotIn("refresh_misses", station)
        self.assertEqual(clips(5), station["streams"])

    def test_a_successful_refresh_clears_the_count(self):
        path = self.write(misses=2)

        def fake_collect(target, lo, hi, seen, keys, out, want, exclude=()):
            if not out:
                out.extend(clips(3, prefix="B"))
            return 3
        refresh.search.collect = fake_collect
        self.night(path)
        self.assertNotIn("refresh_misses", confs.load(path)["station_conf"])


class TestTimeOfDayParts(unittest.TestCase):
    """A channel with `part_queries` searches once more per part and tags what each finds.

    The untagged clips from the channel's own query are the all-day pool; each part's clips carry
    `parts: [part]`. The app draws a half-hour slot from the clips tagged for the part of the day
    it falls in, so a part is effectively a channel of its own - and is guarded like one.
    """

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_collect = refresh.search.collect
        self.wants = {}

    def tearDown(self):
        refresh.search.collect = self.real_collect
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, streams=(), parts=None, stamp=1234):
        path = os.path.join(self.dir, "ytch_x.json")
        station = {"network_name": "x", "channel_number": 1, "search_query": "x",
                   "streams": list(streams), "last_refreshed": stamp}
        if parts:
            station["part_queries"] = parts
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": station}, handle)
        return path

    def searching(self, results):
        """Stand in for yt-dlp: `results` is {query: [clips]}, deduped through `seen` as the
        real collect() does, so a clip two queries both find lands once."""
        def fake_collect(target, lo, hi, seen, keys, out, want, exclude=()):
            query = target.split(":", 1)[1]
            self.wants.setdefault(query, want)
            added = 0
            for clip in results.get(query, []):
                if len(out) >= want:
                    break
                if clip["url"] in seen:
                    continue
                seen.add(clip["url"])
                out.append(dict(clip))
                added += 1
            return added
        refresh.search.collect = fake_collect

    def refresh_path(self, path, target=100):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            result = refresh.refresh(path, target)
        return result, out.getvalue()

    def test_each_part_is_searched_and_tagged_after_the_all_day_pool(self):
        path = self.write(parts={"breakfast": "morning x", "late": "night x"})
        self.searching({"x": clips(30), "morning x": clips(25, "M"), "night x": clips(20, "N")})
        (_, count, kept), _ = self.refresh_path(path)
        self.assertFalse(kept)
        self.assertEqual(75, count)
        streams = confs.load(path)["station_conf"]["streams"]
        self.assertEqual(clips(30), streams[:30])
        self.assertEqual([["breakfast"]] * 25, [s["parts"] for s in streams[30:55]])
        self.assertEqual([["late"]] * 20, [s["parts"] for s in streams[55:]])

    def test_a_part_fills_to_half_the_channels_target(self):
        # Half, so a mixed channel of four parts is at most three times the size of one without:
        # enough for a part's hours not to repeat for days, without every mix tripling a file
        # two televisions fetch over mobile data.
        path = self.write(parts={"prime": "evening x"})
        self.searching({"x": clips(100), "evening x": clips(80, "E")})
        (_, count, _), _ = self.refresh_path(path)
        self.assertEqual(150, count)
        self.assertEqual(50, self.wants["evening x"])

    def test_a_clip_is_on_the_channel_once_and_the_all_day_pool_keeps_it(self):
        # Whichever query found it first owns it. The channel's own query runs first, so a clip
        # it already has stays in the all-day pool rather than being narrowed to one part.
        shared = clips(5, "S")
        path = self.write(parts={"late": "night x"})
        self.searching({"x": shared + clips(20), "night x": shared + clips(10, "N")})
        self.refresh_path(path)
        streams = confs.load(path)["station_conf"]["streams"]
        urls = [s["url"] for s in streams]
        self.assertEqual(len(urls), len(set(urls)))
        self.assertEqual(shared, streams[:5])
        self.assertEqual(10, sum(1 for s in streams if s.get("parts") == ["late"]))

    def test_a_channel_without_parts_is_searched_exactly_as_before(self):
        path = self.write()
        self.searching({"x": clips(30)})
        self.refresh_path(path)
        self.assertEqual(clips(30), confs.load(path)["station_conf"]["streams"])
        self.assertTrue(set(self.wants) <= {"x 2026", "x", "x full"}, self.wants)

    def test_a_collapsed_part_keeps_yesterdays_whole_list(self):
        # One pool falling below half refuses the lot, like a channel. Keeping yesterday's list
        # whole - rather than today's list with yesterday's part spliced in - is what guarantees
        # the result passes the gate: the two lists were deduped against different searches.
        yesterday = clips(30) + [dict(c, parts=["prime"]) for c in clips(40, "P")]
        path = self.write(yesterday, parts={"prime": "evening x"})
        self.searching({"x": clips(30, "B"), "evening x": clips(3, "E")})
        (_, count, kept), out = self.refresh_path(path)
        self.assertTrue(kept)
        self.assertEqual(70, count)
        self.assertIn("prime fell from 40 to 3", out)
        station = confs.load(path)["station_conf"]
        self.assertEqual(yesterday, station["streams"])
        self.assertEqual(1234, station["last_refreshed"])
        self.assertEqual(1, station["refresh_misses"])

    def test_a_part_that_found_nothing_is_a_collapse_not_a_retirement(self):
        yesterday = clips(30) + [dict(c, parts=["prime"]) for c in clips(40, "P")]
        path = self.write(yesterday, parts={"prime": "evening x"})
        self.searching({"x": clips(30, "B")})
        (_, _, kept), out = self.refresh_path(path)
        self.assertTrue(kept)
        self.assertIn("prime fell from 40 to 0", out)

    def test_a_collapsed_all_day_pool_keeps_yesterday_too(self):
        yesterday = clips(40) + [dict(c, parts=["prime"]) for c in clips(40, "P")]
        path = self.write(yesterday, parts={"prime": "evening x"})
        self.searching({"x": clips(10, "B"), "evening x": clips(50, "E")})
        (_, _, kept), out = self.refresh_path(path)
        self.assertTrue(kept)
        self.assertIn("all-day fell from 40 to 10", out)

    def test_a_part_retired_from_the_dial_is_not_held_against_the_refresh(self):
        # dial.PARTS dropped "late"; apply_dial took it out of part_queries; yesterday's late
        # clips are stale tags that this refresh is meant to let go of.
        yesterday = clips(30) + [dict(c, parts=["late"]) for c in clips(40, "L")]
        path = self.write(yesterday)
        self.searching({"x": clips(30, "B")})
        (_, count, kept), _ = self.refresh_path(path)
        self.assertFalse(kept)
        self.assertEqual(30, count)

    def test_the_channels_own_query_finding_nothing_keeps_yesterday_whatever_the_parts_found(self):
        # The parts alone are not a channel: every part without a query of its own plays the
        # all-day pool, and a channel whose main search broke should wait for tomorrow.
        path = self.write(clips(5), parts={"prime": "evening x"})
        self.searching({"evening x": clips(30, "E")})
        (_, count, kept), out = self.refresh_path(path)
        self.assertTrue(kept)
        self.assertEqual(5, count)
        self.assertIn("search returned nothing", out)

    def test_a_part_clip_found_again_keeps_its_sponsor_lookup(self):
        again = dict(clips(1, "E")[0], parts=["prime"], skip=[[1.0, 9.0]], skip_checked=77)
        path = self.write(clips(10) + [again], parts={"prime": "evening x"})
        self.searching({"x": clips(10), "evening x": clips(1, "E")})
        self.refresh_path(path)
        found = confs.load(path)["station_conf"]["streams"][-1]
        self.assertEqual(["prime"], found["parts"])
        self.assertEqual([[1.0, 9.0]], found["skip"])
        self.assertEqual(77, found["skip_checked"])


class TestExcludeReachesTheSearch(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_collect = refresh.search.collect

    def tearDown(self):
        refresh.search.collect = self.real_collect
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_the_confs_exclude_list_is_handed_to_every_collect(self):
        path = os.path.join(self.dir, "ytch_x.json")
        with io.open(path, "w", encoding="utf-8") as handle:
            json.dump({"station_conf": {"network_name": "x", "channel_number": 1,
                                        "search_query": "x", "streams": [],
                                        "exclude": ["nrl"]}}, handle)
        handed = []

        def fake_collect(target, lo, hi, seen, keys, out, want, exclude=()):
            handed.append(exclude)
            return 0
        refresh.search.collect = fake_collect
        with contextlib.redirect_stdout(io.StringIO()):
            refresh.refresh(path, 10)
        self.assertTrue(handed)
        self.assertTrue(all(e == ["nrl"] for e in handed))


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
