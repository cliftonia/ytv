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
"""
import argparse
import json
import os
import subprocess
import sys

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
        was = {c["number"]: len(c["streams"]) for c in before_channels}
        now = {c["number"]: len(c["streams"]) for c in channels}
        for number, before in was.items():
            after = now.get(number, 0)
            if collapsed(before, after):
                found.append("ch %d fell from %d clips to %d" % (number, before, after))
        for number in was:
            if number not in now:
                found.append("ch %d disappeared" % number)
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

    found = problems(dial, committed(args.against))
    if found:
        print("\nREFUSING TO PUBLISH:")
        for p in found:
            print("  " + p)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
