#!/usr/bin/env python3
"""The publish gate, which is the only thing standing between a bad night and two televisions.

Every rule here was once the inline heredoc in lineup.yml; these tests pin its behaviour so the
extraction (and anything after it) cannot quietly loosen it.
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
import check_lineup


def channel(number, clips=20, kind="youtube", rotation="clock", name=None):
    c = {"number": number, "name": name or "ch%d" % number, "kind": kind,
         "streams": [{"url": "u%d" % i, "duration": 300, "title": "t"} for i in range(clips)]}
    if kind == "youtube":
        c["rotation"] = rotation
    return c


def healthy(count=60, clips=20):
    """A dial the gate passes: 60 contiguous youtube channels of 20 clips (1200 clips)."""
    return {"channels": [channel(n, clips) for n in range(1, count + 1)]}


class TestAbsolute(unittest.TestCase):

    def test_a_healthy_dial_passes(self):
        self.assertEqual([], check_lineup.problems(healthy(), {}))

    def test_too_few_channels(self):
        found = check_lineup.problems(healthy(count=49, clips=20), {})
        self.assertIn("only 49 channels - a collapsed dial", found)

    def test_too_few_clips(self):
        dial = {"channels": [channel(n, 10) for n in range(1, 50)]}
        self.assertIn("only 490 clips - an empty dial", check_lineup.problems(dial, {}))

    def test_duplicate_numbers(self):
        dial = healthy()
        dial["channels"].append(channel(3))
        self.assertIn("duplicate channel numbers", check_lineup.problems(dial, {}))

    def test_a_thin_youtube_channel(self):
        dial = healthy()
        dial["channels"][4] = channel(5, clips=9, name="Blues")
        self.assertIn("Blues (ch 5) has only 9 clips", check_lineup.problems(dial, {}))

    def test_a_thin_live_channel_is_fine(self):
        dial = healthy()
        dial["channels"].append(channel(95, clips=1, kind="live"))
        self.assertEqual([], check_lineup.problems(dial, {}))

    def test_a_youtube_channel_without_clock_rotation(self):
        dial = healthy()
        dial["channels"][0] = channel(1, rotation=None, name="Jazz")
        self.assertIn("Jazz (ch 1) has rotation None, so it would replay clip 0 forever",
                      check_lineup.problems(dial, {}))

    def test_gaps_in_the_youtube_block(self):
        dial = healthy()
        del dial["channels"][6]
        self.assertIn("gaps in the youtube block: [7]", check_lineup.problems(dial, {}))


class TestAgainstPrevious(unittest.TestCase):

    def test_a_collapsed_channel(self):
        previous = healthy(clips=40)
        dial = healthy(clips=40)
        dial["channels"][1] = channel(2, clips=19)
        self.assertEqual(["ch 2 fell from 40 clips to 19"], check_lineup.problems(dial, previous))

    def test_exactly_half_passes(self):
        previous = healthy(clips=40)
        dial = healthy(clips=40)
        dial["channels"][1] = channel(2, clips=20)
        self.assertEqual([], check_lineup.problems(dial, previous))

    def test_a_small_channel_is_not_compared(self):
        previous = healthy(clips=40)
        previous["channels"].append(channel(95, clips=19, kind="live"))
        dial = healthy(clips=40)
        dial["channels"].append(channel(95, clips=1, kind="live"))
        self.assertEqual([], check_lineup.problems(dial, previous))

    def test_a_disappeared_channel(self):
        previous = healthy()
        previous["channels"].append(channel(95, clips=1, kind="live"))
        found = check_lineup.problems(healthy(), previous)
        self.assertEqual(["ch 95 disappeared"], found)

    def test_sponsor_skips_arriving_do_not_trip_the_gate(self):
        # The first nights after sponsor.py lands add `skip` to thousands of streams at once. The
        # gate counts clips, never watch time, so that must read as an unchanged dial - were it
        # ever to count watch time, a channel of ad-heavy clips would look like a collapse.
        previous = healthy(clips=40)
        dial = healthy(clips=40)
        for c in dial["channels"]:
            for stream in c["streams"]:
                stream["skip"] = [[0.0, 30.0], [200.0, 270.0]]
        self.assertEqual([], check_lineup.problems(dial, previous))

    def test_collapse_rule(self):
        self.assertTrue(check_lineup.collapsed(40, 19))
        self.assertFalse(check_lineup.collapsed(40, 20))
        self.assertFalse(check_lineup.collapsed(19, 0))
        self.assertTrue(check_lineup.collapsed(20, 9))


def mixed(number, untagged=40, **parts):
    """A youtube channel with `untagged` all-day clips and `parts` = {part: count} tagged ones."""
    c = channel(number, untagged)
    for part, count in sorted(parts.items()):
        c["streams"].extend({"url": "%s%d" % (part, i), "duration": 300, "title": "t",
                             "parts": [part]} for i in range(count))
    return c


class TestParts(unittest.TestCase):
    """Each part of the day is held to the collapse rule as though it were a channel.

    A part is what the viewer gets for five hours of an evening. Prime falling from forty clips to
    three while the rest of the channel stays healthy passes every whole-channel count, and plays
    the same three clips every night.
    """

    def dial_with(self, c):
        dial = healthy(clips=40)
        dial["channels"][c["number"] - 1] = c
        return dial

    def test_a_channel_without_parts_has_no_pools(self):
        self.assertEqual({}, check_lineup.pool_sizes(channel(1, 40)["streams"]))

    def test_pools_count_each_part_and_the_all_day_clips(self):
        self.assertEqual({"all-day": 40, "prime": 30, "late": 25},
                         check_lineup.pool_sizes(mixed(1, prime=30, late=25)["streams"]))

    def test_a_collapsed_part_is_refused(self):
        previous = self.dial_with(mixed(2, prime=40))
        dial = self.dial_with(mixed(2, prime=19))
        self.assertEqual(["ch 2 prime fell from 40 clips to 19"],
                         check_lineup.problems(dial, previous))

    def test_exactly_half_a_part_passes(self):
        previous = self.dial_with(mixed(2, prime=40))
        self.assertEqual([], check_lineup.problems(self.dial_with(mixed(2, prime=20)), previous))

    def test_a_collapsed_all_day_pool_is_refused_even_when_the_channel_total_is_not(self):
        # 40 all-day + 60 prime -> 19 all-day + 60 prime is 100 -> 79 for the channel, which the
        # whole-channel rule passes; but the all-day pool is every part that has no mix.
        previous = self.dial_with(mixed(2, untagged=40, prime=60))
        dial = self.dial_with(mixed(2, untagged=19, prime=60))
        self.assertEqual(["ch 2 all-day fell from 40 clips to 19"],
                         check_lineup.problems(dial, previous))

    def test_a_part_arriving_is_not_a_collapse(self):
        self.assertEqual([], check_lineup.problems(self.dial_with(mixed(2, prime=30)),
                                                   healthy(clips=40)))

    def test_a_retired_part_is_not_a_collapse(self):
        # A part taken out of dial.PARTS leaves no tagged clip behind after the next refresh.
        # refresh_channels refuses a part the conf still declares coming back empty, so zero in
        # a lineup is a retirement, never a failed search.
        previous = self.dial_with(mixed(2, prime=30, late=30))
        self.assertEqual([], check_lineup.problems(self.dial_with(mixed(2, prime=30)), previous))

    def test_retiring_a_mix_is_not_a_collapse_of_the_channel(self):
        # 40 all-day + 60 prime back to 40 all-day is 100 -> 40, below half - but the 60 were
        # prime clips and prime is no longer asked for.
        previous = self.dial_with(mixed(2, untagged=40, prime=60))
        self.assertEqual([], check_lineup.problems(self.dial_with(mixed(2, untagged=40)),
                                                   previous))

    def test_a_declared_part_coming_back_empty_is_a_collapse(self):
        # What refresh_channels asks: it knows which parts the conf still declares.
        before = mixed(1, prime=30)["streams"]
        after = mixed(1)["streams"]
        self.assertEqual([], check_lineup.shrunk_pools(before, after))
        self.assertEqual([("prime", 30, 0)],
                         check_lineup.shrunk_pools(before, after, parts=("prime",)))


class TestWarnings(unittest.TestCase):
    """Reported, never refused: things worth a curator's eye that are not a broken dial."""

    def test_a_channel_of_nothing_but_long_programmes_is_short_of_top_ups(self):
        # Every half-hour slot ends with whatever the programme left over; with no clip under five
        # minutes to top it up, all of that is an Up next card.
        dial = {"channels": [channel(1, clips=20, name="Docs")]}
        dial["channels"][0]["streams"][0]["duration"] = 3000
        self.assertEqual(["Docs (ch 1): 0 of 20 clips under 5 min (0%) - long Up next cards"],
                         check_lineup.warnings(dial))

    def test_enough_short_clips_is_not_reported(self):
        c = channel(1, clips=20)
        for stream in c["streams"][:2]:
            stream["duration"] = 200
        self.assertEqual([], check_lineup.warnings({"channels": [c]}))

    def test_short_means_watch_time_after_the_sponsor_skips(self):
        c = channel(1, clips=20)
        for stream in c["streams"][:2]:
            stream["duration"] = 400
            stream["skip"] = [[0.0, 50.0], [300.0, 400.0]]
        self.assertEqual([], check_lineup.warnings({"channels": [c]}))

    def test_live_channels_have_no_schedule_to_report(self):
        self.assertEqual([], check_lineup.warnings({"channels": [channel(101, 1, kind="live")]}))

    def test_a_thin_part_is_reported(self):
        c = mixed(1, prime=4)
        for stream in c["streams"][:5]:
            stream["duration"] = 200
        self.assertEqual(["ch1 (ch 1): prime has only 4 clips"],
                         check_lineup.warnings({"channels": [c]}))


class TestMain(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.real_committed = check_lineup.committed

    def tearDown(self):
        check_lineup.committed = self.real_committed
        shutil.rmtree(self.dir, ignore_errors=True)

    def run_main(self, dial, previous):
        path = os.path.join(self.dir, "channels.json")
        with open(path, "w") as handle:
            json.dump(dial, handle)
        check_lineup.committed = lambda rev: previous
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = check_lineup.main(["--lineup", path])
        return status, out.getvalue()

    def test_passes_with_a_summary(self):
        status, out = self.run_main(healthy(), {})
        self.assertEqual(0, status)
        self.assertTrue(out.startswith("60 channels, 1200 clips\n"), out)

    def test_warnings_are_printed_and_do_not_refuse(self):
        # healthy() is sixty channels of five-minute clips: none of them short.
        status, out = self.run_main(healthy(), {})
        self.assertEqual(0, status)
        self.assertIn("WARNINGS (publishing anyway):\n  ch1 (ch 1): 0 of 20 clips under 5 min", out)
        self.assertNotIn("REFUSING", out)

    def test_refuses_with_every_reason(self):
        dial = healthy()
        dial["channels"][4] = channel(5, clips=9)
        status, out = self.run_main(dial, healthy(clips=20))
        self.assertEqual(1, status)
        self.assertIn("REFUSING TO PUBLISH:\n  ch5 (ch 5) has only 9 clips\n  ch 5 fell from 20 clips to 9",
                      out)

    def test_no_committed_lineup_means_no_comparison(self):
        # Outside a checkout, or on a first publish: the workflow used to fall back to '{}'.
        self.assertEqual({}, check_lineup.committed("no-such-revision-anywhere"))


if __name__ == "__main__":
    unittest.main()
