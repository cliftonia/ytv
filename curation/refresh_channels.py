#!/usr/bin/env python3
"""Re-search each channel for fresh clips and write them back into its conf.

Runs nightly in CI. Preserves every channel's `channel_number`, so the dial ordering the viewer
has learned is never disturbed by a content refresh - only what is ON each channel changes.

Each conf carries its own `search_query`. That used to live in seven separate builder scripts
that this imported, which meant losing one of them lost the definition of twenty channels; the
query now travels with the channel it describes.

  python3 refresh_channels.py --rotate 8          the nightly conveyor
  python3 refresh_channels.py --only blues        one channel, for checking a query
  python3 refresh_channels.py --target 100        how many clips a channel should end up with

Rotation is by a `last_refreshed` stamp written into each conf. It used to be by file
modification time, which worked on a long-lived machine and was silently a no-op in CI: the job
starts with `actions/checkout`, which writes every file at clone time, so all 90 confs carried
the same instant and the "least recently refreshed" eight were simply the first eight
alphabetically. The same eight channels refreshed every night for weeks and the other 82 never
did - and the run went green each time, because nothing checks WHICH channels moved.

The cursor has to survive cloning, so it lives in the committed json (`last_refreshed`, plus a
`refresh_misses` count for channels whose search keeps failing - keep() documents both). At 8 a
night the whole dial turns over in a fortnight, which is slow enough that a channel does not feel
like it resets and fast enough that nothing on it is ever months old.
"""
import argparse
import datetime
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import check_lineup
import confs
import filters
import search

# How many clips a channel should end up with. The CLI default, not a property of searching, so
# it stays here with the argument that overrides it.
TARGET = 100


def refresh(path, target):
    """Refill one channel. Returns (name, clip count, kept).

    `kept` is True when yesterday's clips were left in place - the search came back empty, or
    with so few that the publish gate would refuse it (see keep()). One kept channel is a query
    having a bad night; most of a slice kept is yt-dlp having one, and main() is the only place
    that can see the difference.
    """
    conf = confs.load(path)
    station = conf["station_conf"]
    slug = confs.slug_for(path)
    name = station.get("network_name") or slug
    query = station.get("search_query")
    if not query:
        print("  [skip] %s has no search_query" % name, flush=True)
        return name, len(station.get("streams", [])), False

    lo, hi = filters.window(slug)
    streams, seen, keys = [], set(), set()
    # Title terms that never belong on this channel (dial.EXCLUDE, copied into the conf by
    # apply_dial). Applied to playlists as well as searches: a curated list can drift too.
    exclude = station.get("exclude") or ()

    # Curated playlists pinned to this channel are the best material it has; they go in first and
    # search only fills whatever is left over. Several are allowed because some subjects have no
    # single deep list - the 1930s needs three to make a hundred.
    for playlist in station.get("playlists") or []:
        if len(streams) >= target:
            break
        search.collect("https://www.youtube.com/playlist?list=" + playlist, lo, hi, seen, keys,
                       streams, target, exclude=exclude)

    attempts = search.queries_for(query, slug, station.get("extra_queries"),
                                  datetime.date.today().year)

    for attempt in attempts:
        if len(streams) >= target:
            break
        # Ask for several times the target. The duration window and the commentary filter both
        # reject as they go, so searching exactly `target` results can only ever come back short -
        # which is how a music channel once ended up with seventeen clips on it.
        search.collect("ytsearch%d:%s" % (target * search.SEARCH_DEPTH, attempt), lo, hi,
                       seen, keys, streams, target, exclude=exclude)

    before = len(station.get("streams", []))
    if not streams:
        # Writing an empty list would take the channel off the dial entirely. Leaving yesterday's
        # content is strictly better than that, and the next rotation will try again.
        return keep(path, conf, name, "search returned nothing")
    if check_lineup.collapsed(before, len(streams)):
        # The publish gate's own rule (check_lineup.py, run by lineup.yml), applied here first.
        # Without it one query having a bad night wrote a conf the gate then refused - and since
        # the gate refuses the WHOLE dial, every nightly after it failed on that one channel until
        # someone intervened. Yesterday's list passes the gate by definition.
        return keep(path, conf, name, "fell from %d to %d" % (before, len(streams)))

    station["streams"] = streams
    station["last_refreshed"] = int(time.time())
    station.pop("refresh_misses", None)
    confs.save(path, conf)
    print("  %-26s %3d clips" % (name, len(streams)), flush=True)
    return name, len(streams), False


# How many consecutive nights a channel may keep yesterday's clips before its cursor advances
# anyway. See keep().
MAX_MISSES = 3


def keep(path, conf, name, why):
    """Leave yesterday's clips in place and report the channel as kept.

    The rotation cursor is two fields in the conf:

      last_refreshed  epoch of the last time this channel's turn was USED UP. The nightly slice is
                      the N smallest values, so a channel whose stamp does not move is first in
                      line again tomorrow.
      refresh_misses  consecutive nights this channel's turn ended in keep(). Absent means zero;
                      a real refresh removes it.

    A kept channel normally does NOT advance `last_refreshed`: it has not been refreshed, and
    stamping it would send it to the back of the queue for a fortnight on the strength of one
    failed search. But a query that is broken for good would then take a slot every night forever
    and starve the rest of the dial. So misses are counted, and the MAX_MISSES-th in a row stamps
    the cursor anyway and resets the count - the channel keeps its old clips and waits its turn
    like everyone else. Either way the conf is saved, because the count has to survive the CI
    checkout just as the stamp does.
    """
    station = conf["station_conf"]
    misses = station.get("refresh_misses", 0) + 1
    if misses >= MAX_MISSES:
        station["last_refreshed"] = int(time.time())
        station.pop("refresh_misses", None)
        why += "; %d nights running, moving it to the back of the rotation" % misses
    else:
        station["refresh_misses"] = misses
    confs.save(path, conf)
    print("  %-26s %s, keeping what was there" % (name, why), flush=True)
    return name, len(station.get("streams", [])), True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=int, default=TARGET)
    parser.add_argument("--only")
    parser.add_argument("--rotate", type=int, default=None,
                        help="refresh only the N least-recently-refreshed channels")
    parser.add_argument("--confs", default=confs.default_dir())
    args = parser.parse_args()

    paths = confs.youtube_paths(args.confs)
    if args.only:
        paths = [p for p in paths if confs.slug_for(p) == args.only]
        if not paths:
            print("no channel called %s" % args.only, file=sys.stderr)
            return 2
    elif args.rotate is not None:
        # `is not None`, so --rotate 0 means "none" rather than falling through to all 90 and
        # spending an hour getting the runner's egress address throttled.
        if args.rotate <= 0:
            print("--rotate 0: nothing to do", flush=True)
            return 0
        paths.sort(key=confs.last_refreshed)
        paths = paths[:args.rotate]
        print("rotating %d channels: %s" % (
            len(paths), ", ".join(confs.slug_for(p) for p in paths)), flush=True)

    results = []
    # Four at a time. yt-dlp is network-bound so this is worth doing, but more than a handful of
    # concurrent searches is what gets an IP throttled, and a throttled run refreshes nothing.
    with ThreadPoolExecutor(max_workers=4) as pool:
        for result in pool.map(lambda p: refresh(p, args.target), paths):
            results.append(result)

    thin = ["%s(%d)" % (n, c) for n, c, _ in results if c < args.target // 2]
    kept = sum(1 for _, _, k in results if k)
    print("\nrefreshed %d channels" % len(results), flush=True)
    if thin:
        print("thin: %s" % ", ".join(thin), flush=True)

    # A night where every request was refused looked exactly like a night where nothing had
    # changed: all confs untouched, no diff, "no changes", green tick. The dial could stop
    # updating for weeks with no signal anywhere. Half the slice coming back thin is not a
    # content problem, it is yt-dlp being throttled, and it should be visible.
    if results and len(thin) > len(results) // 2:
        print("\nFAILED: %d of %d channels came back thin - almost certainly throttled"
              % (len(thin), len(results)), file=sys.stderr)
        return 1

    # The thin guard above cannot see the other failure shape. A channel whose search came back
    # EMPTY keeps yesterday's clips, which is right for one channel and camouflage for a whole
    # run: a night of total yt-dlp breakage returns every channel at yesterday's healthy count,
    # no conf changes, no diff, green tick - while the dial quietly stops updating. Kept channels
    # are the signal thinness cannot carry, and the threshold is the same: half the slice having
    # a bad night is not a coincidence.
    if results and kept > len(results) // 2:
        print("\nFAILED: %d of %d channels found nothing and kept yesterday's clips - almost"
              " certainly yt-dlp breakage" % (kept, len(results)), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
