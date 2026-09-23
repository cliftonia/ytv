#!/usr/bin/env python3
"""The publish gate: refuse a channels.json that is worse than the one it replaces.

  python3 check_lineup.py [--lineup ../channels.json] [--against HEAD]

The televisions have no way to reject a bad lineup - they cache whatever parses - so this is the
only gate. It compares against the COMMITTED file rather than testing absolute floors, because the
failure that actually happens is a slice of the dial collapsing while the totals stay healthy:
eight channels dropping to one clip each still leaves 8231 clips across 108 channels, which sailed
through the old absolute guards. A channel reduced to one clip loops one programme forever.

Lived inline in .github/workflows/lineup.yml as a heredoc until ingest.py needed the same gate
before its own push - two copies of a gate drift, and the one that drifts is the one that lets a
bad dial through. Exit 1 with the reasons on stdout when refusing, 0 otherwise.

Warnings are printed too, and never change the exit code: they are things a curator should look
at (a channel short of short clips, a part of the day with only a handful of clips) that are not
a worse dial than the one on the televisions now.
"""
import argparse
import json
import os
import subprocess
import sys

import confs

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

# Below this many clips a channel is not compared against its previous size.
COLLAPSE_FLOOR = 20


def collapsed(before, after):
    """True when a channel shrank from `before` clips to `after` the way this gate refuses.

    Half is the threshold because a refresh legitimately replaces a channel's whole list; what it
    must never do is come back with a fraction of one. refresh_channels calls this too, before a
    conf is written, so a refresh never produces what the gate would then refuse.
    """
    return before >= COLLAPSE_FLOOR and after < before // 2


# The pool of untagged clips on a channel that has time-of-day mixes: what every part without a
# query of its own plays. Named in refusals, so it reads as a part would.
ALL_DAY = "all-day"


def pool_sizes(streams):
    """{pool: clip count} for a channel's time-of-day mixes, or {} for a channel without any.

    A pool is each part tagged on at least one stream, plus ALL_DAY for the untagged ones. A
    channel with no tags at all has one pool, the channel itself, which problems() already
    compares - so it has no pools here, and a dial without mixes is judged exactly as before.
    """
    sizes = {}
    untagged = 0
    for stream in streams:
        tags = stream.get("parts") or ()
        if not tags:
            untagged += 1
        for part in tags:
            sizes[part] = sizes.get(part, 0) + 1
    if sizes:
        sizes[ALL_DAY] = untagged
    return sizes


def _pool_order(pool):
    """The day's order, then the all-day pool, then anything unknown by name - stable output."""
    if pool in confs.DAY_PARTS:
        return (0, confs.DAY_PARTS.index(pool), pool)
    return (1 if pool == ALL_DAY else 2, 0, pool)


def retired_parts(before, after, parts=None):
    """The parts tagged in `before` that `after` let go of on purpose.

    `parts` is the parts the channel still declares, when the caller knows them - refresh_channels
    does, from the conf, and a declared part that comes back with nothing is a collapse to zero,
    never a retirement. The gate does not know them, so for the gate a part missing from `after`
    altogether was taken out of dial.PARTS. That is safe only because refresh refuses the empty
    case itself: zero in a lineup cannot be a search that failed for a part still asked for.
    """
    was, now = pool_sizes(before), pool_sizes(after)
    return {part for part in was
            if part != ALL_DAY and part not in now and (parts is None or part not in parts)}


def comparable_size(before, after, parts=None):
    """How many of `before`'s clips a channel's size today should be measured against.

    All of them, less the clips that were only in retired parts: a channel of 100 all-day clips
    and 150 part clips whose mix is taken out of the dial goes back to 100, and that is the
    curator's decision rather than a collapse.
    """
    gone = retired_parts(before, after, parts)
    return sum(1 for s in before if not (s.get("parts") and set(s["parts"]) <= gone))


def shrunk_pools(before, after, parts=None):
    """[(pool, was, now)] for each time-of-day pool that collapsed between two stream lists.

    Each part is a channel for this purpose: prime is what the viewer gets for five hours of an
    evening, and prime falling from forty clips to three passes every whole-channel count. Retired
    parts (see retired_parts) are not compared.
    """
    was, now = pool_sizes(before), pool_sizes(after)
    gone = retired_parts(before, after, parts)
    found = []
    for pool in sorted(was, key=_pool_order):
        if pool in gone:
            continue
        # Every part retired at once leaves no tags, and then every clip is all-day.
        current = now.get(pool, len(after) if pool == ALL_DAY else 0)
        if collapsed(was[pool], current):
            found.append((pool, was[pool], current))
    return found


def problems(dial, previous):
    """Why `dial` must not replace `previous` (both parsed channels.json), as a list of reasons.

    `previous` may be empty - a first publish, or no committed file - in which case only the
    absolute checks apply.
    """
    channels = dial["channels"]
    clips = sum(len(c["streams"]) for c in channels)

    found = []
    if len(channels) < 50:
        found.append("only %d channels - a collapsed dial" % len(channels))
    if clips < 500:
        found.append("only %d clips - an empty dial" % clips)

    numbers = [c["number"] for c in channels]
    if len(numbers) != len(set(numbers)):
        found.append("duplicate channel numbers")

    # A youtube channel this thin repeats itself constantly; below 10 it is one programme
    # on a loop, which is worse than the channel being absent.
    for c in channels:
        if c["kind"] == "youtube" and len(c["streams"]) < 10:
            found.append("%s (ch %d) has only %d clips"
                         % (c["name"], c["number"], len(c["streams"])))
        if c["kind"] == "youtube" and c.get("rotation") != "clock":
            found.append("%s (ch %d) has rotation %r, so it would replay clip 0 forever"
                         % (c["name"], c["number"], c.get("rotation")))

    # The youtube block must stay contiguous. A channel that empties is dropped silently by
    # build_lineup, and the viewer just finds a number missing.
    yt = sorted(c["number"] for c in channels if c["kind"] == "youtube")
    if yt and yt != list(range(1, len(yt) + 1)):
        missing = [n for n in range(1, max(yt) + 1) if n not in yt]
        found.append("gaps in the youtube block: %s" % missing)

    before_channels = previous.get("channels", [])
    if before_channels:
        streams_now = {c["number"]: c["streams"] for c in channels}
        # Measured against what the channel still carries: clips only in a retired time-of-day
        # part (comparable_size) are not a loss.
        was = {c["number"]: comparable_size(c["streams"], streams_now.get(c["number"], []))
               for c in before_channels}
        now = {c["number"]: len(c["streams"]) for c in channels}
        for number, before in was.items():
            after = now.get(number, 0)
            if collapsed(before, after):
                found.append("ch %d fell from %d clips to %d" % (number, before, after))
        for number in was:
            if number not in now:
                found.append("ch %d disappeared" % number)
        # The same rule again inside each channel, one time-of-day pool at a time.
        for c in before_channels:
            if c["number"] not in streams_now:
                continue
            for pool, before, after in shrunk_pools(c["streams"], streams_now[c["number"]]):
                found.append("ch %d %s fell from %d clips to %d"
                             % (c["number"], pool, before, after))
    return found


# A clip under this much watch time is a "short clip" to the app's half-hour schedule: what it
# tops up the end of a slot with after a programme. Watch time, so sponsor skips count.
SHORT_SECONDS = 300

# Below this share of short clips, a channel is reported as short of top-ups. A proxy, and
# deliberately so: the real measure is the "Up next" card time the app's packer leaves in a day,
# and porting the packer here would give the gate a second copy of the app's scheduling to drift
# from it. What the packer can do is bounded by what it is given, though - a channel with no
# short clip at all ends every half-hour slot on a card for whatever the programme left over.
SHORT_SHARE_WARN = 0.10

# A part of the day with fewer clips than this is reported, on the same reasoning as the thin
# channel refusal above - but only reported, as a part can be new and still filling.
THIN_PART = 10


def watch_seconds(stream):
    """What the app will actually play of a stream: its length less its sponsor skips."""
    return stream["duration"] - sum(end - start for start, end in stream.get("skip") or ())


def warnings(dial):
    """Things worth a curator's eye in `dial` that are no reason to refuse it."""
    found = []
    for c in dial["channels"]:
        if c.get("rotation") != "clock" or not c["streams"]:
            continue
        label = "%s (ch %d)" % (c["name"], c["number"])
        short = sum(1 for s in c["streams"] if watch_seconds(s) < SHORT_SECONDS)
        share = float(short) / len(c["streams"])
        if share < SHORT_SHARE_WARN:
            found.append("%s: %d of %d clips under 5 min (%d%%) - long Up next cards"
                         % (label, short, len(c["streams"]), round(share * 100)))
        for pool, size in sorted(pool_sizes(c["streams"]).items(),
                                 key=lambda item: _pool_order(item[0])):
            if pool != ALL_DAY and size < THIN_PART:
                found.append("%s: %s has only %d clips" % (label, pool, size))
    return found


def committed(rev, relpath="channels.json"):
    """The lineup as committed at `rev`, or {} when there is none (a first publish, or run
    outside a checkout). Same fallback as the workflow's old `|| echo '{}'`."""
    try:
        text = subprocess.run(["git", "show", "%s:%s" % (rev, relpath)], cwd=REPO,
                              capture_output=True, text=True, check=True).stdout
        return json.loads(text)
    except (subprocess.CalledProcessError, OSError, ValueError):
        return {}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--lineup", default=os.path.join(REPO, "channels.json"))
    parser.add_argument("--against", default="HEAD",
                        help="git revision whose channels.json this must not be worse than")
    args = parser.parse_args(argv)

    with open(args.lineup) as handle:
        dial = json.load(handle)
    clips = sum(len(c["streams"]) for c in dial["channels"])
    print("%d channels, %d clips" % (len(dial["channels"]), clips))

    advice = warnings(dial)
    if advice:
        print("\nWARNINGS (publishing anyway):")
        for line in advice:
            print("  " + line)

    found = problems(dial, committed(args.against))
    if found:
        print("\nREFUSING TO PUBLISH:")
        for p in found:
            print("  " + p)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
