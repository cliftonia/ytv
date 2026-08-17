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

The cursor has to survive cloning, so it lives in the committed json. At 8 a night the whole dial
turns over in a fortnight, which is slow enough that a channel does not feel like it resets and
fast enough that nothing on it is ever months old.
"""
import argparse
import datetime
import glob
import io
import json
import os
import re
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

TARGET = 100

# How many search results to sift per target clip. Three is enough for a broad query and not
# so many that a single channel's refresh takes minutes.
SEARCH_DEPTH = 3

# Channels whose whole point is that the content is old. Asking these for this year's uploads
# returns reaction videos and retrospectives instead of the thing itself.
PERIOD = ("music_19", "music_20", "yacht", "westerns", "sitcoms", "anime_classic", "cartoons",
          "public_domain", "vintage", "retro", "classic", "silent", "noir")

# A song is three minutes; a documentary is fifty. One duration window cannot serve both, and
# the wrong one is what once left a music channel with seventeen clips on it.
SONGS = ("music_", "yacht", "rock", "jazz", "blues", "country", "reggae", "hiphop", "hip_hop",
         "soul", "motown", "electronic", "classical", "bossa", "christian_music", "opera")
LONG = ("documentaries", "podcasts", "concerts", "public_domain", "westerns", "samurai",
        "docos", "history", "philosophy", "lecture")

# Somebody talking about the thing, rather than the thing. Every one of these was found on a
# channel it had no business being on.
COMMENTARY = ("top ", "best ", "ranked", "tier list", "greatest", "need to watch", "must watch",
              "recommendation", "in a nutshell", "of all time", "i watched", "i binged",
              "reaction", "explained", "review", "ranking", "compilation of", "tiktok",
              "shorts compilation")


def is_period(slug):
    return any(token in slug for token in PERIOD)


def window(slug):
    """The duration band a clip must fall inside to belong on this channel."""
    if any(token in slug for token in SONGS):
        return 60, 420
    if any(token in slug for token in LONG):
        return 1200, 9000
    return 240, 5400


def usable(title):
    low = (title or "").lower()
    if not low:
        return False
    return not any(word in low for word in COMMENTARY)


def ytdlp(target, timeout=300):
    """Flat-list a search or playlist. Returns (id, duration, title) rows."""
    try:
        out = subprocess.run(
            ["yt-dlp", target, "--flat-playlist", "--no-warnings",
             "--print", "%(id)s\t%(duration)s\t%(title).90s"],
            capture_output=True, text=True, timeout=timeout)
    except Exception as exc:
        print("    [warn] %s: %s" % (target[:60], exc), flush=True)
        return []
    if out.returncode != 0 and not out.stdout.strip():
        # Distinguishes "this search legitimately found nothing" from "yt-dlp could not run".
        print("    [warn] yt-dlp exited %d: %s"
              % (out.returncode, (out.stderr or "").strip()[:120]), flush=True)
    rows = []
    for line in out.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        try:
            rows.append((parts[0], int(float(parts[1])), parts[2]))
        except ValueError:
            continue
    return rows


def title_key(title):
    """Loose identity for a clip, so the same song from two uploaders lands once.

    Digits are KEPT. Dropping standalone numbers was meant to stop "Live 2019" and "Live 2024"
    counting as two, and instead it deleted every episode of every series: "Episode 1" and
    "Episode 2" collapsed to the same key, so a season playlist was reduced to one episode before
    search even began. That is why the episodic channels never had a run longer than two - not
    because uploaders only post pilots, which is what the code used to say.

    The duplicate-year case it was protecting against is rare and harmless; losing whole series
    was neither.
    """
    low = re.sub(r"[^a-z0-9 ]", " ", (title or "").lower())
    return " ".join(low.split())[:60]


def collect(target, lo, hi, seen, keys, out, want):
    added = 0
    for vid, seconds, title in ytdlp(target):
        if len(out) >= want:
            break
        if not (lo <= seconds <= hi) or not usable(title):
            continue
        url = "https://www.youtube.com/watch?v=%s" % vid
        key = title_key(title)
        if url in seen or key in keys:
            continue
        seen.add(url)
        keys.add(key)
        out.append({"url": url, "duration": seconds, "title": title.strip()})
        added += 1
    return added


def last_refreshed(path):
    """When this channel was last re-searched, as an epoch. Never refreshed sorts first."""
    try:
        with io.open(path, encoding="utf-8") as handle:
            return json.load(handle)["station_conf"].get("last_refreshed", 0)
    except Exception:
        return 0


def refresh(path, target):
    """Refill one channel. Returns (name, clip count)."""
    with io.open(path, encoding="utf-8") as handle:
        conf = json.load(handle)
    station = conf["station_conf"]
    slug = os.path.basename(path)[5:-5]
    name = station.get("network_name") or slug
    query = station.get("search_query")
    if not query:
        print("  [skip] %s has no search_query" % name, flush=True)
        return name, len(station.get("streams", []))

    lo, hi = window(slug)
    streams, seen, keys = [], set(), set()

    # Curated playlists pinned to this channel are the best material it has; they go in first and
    # search only fills whatever is left over. Several are allowed because some subjects have no
    # single deep list - the 1930s needs three to make a hundred.
    for playlist in station.get("playlists") or []:
        if len(streams) >= target:
            break
        collect("https://www.youtube.com/playlist?list=" + playlist, lo, hi, seen, keys,
                streams, target)

    year = datetime.date.today().year
    attempts = [query]
    if not is_period(slug):
        # This year first, then unqualified. A bias, not a filter: a quiet channel that has
        # nothing from this year still fills rather than emptying itself.
        attempts.insert(0, "%s %d" % (query, year))
    attempts.append("%s full" % query)
    # Named programmes and named people, which beat a genre word every time - see EXTRA_QUERIES
    # in dial.py. They come after the general query so the channel still leads with the broad
    # sweep, and they are what actually carries a thin channel to a hundred.
    attempts.extend(station.get("extra_queries") or [])

    for attempt in attempts:
        if len(streams) >= target:
            break
        # Ask for several times the target. The duration window and the commentary filter both
        # reject as they go, so searching exactly `target` results can only ever come back short -
        # which is how a music channel once ended up with seventeen clips on it.
        collect("ytsearch%d:%s" % (target * SEARCH_DEPTH, attempt), lo, hi, seen, keys,
                streams, target)

    if not streams:
        # Writing an empty list would take the channel off the dial entirely. Leaving yesterday's
        # content is strictly better than that, and the next rotation will try again.
        print("  %-26s search returned nothing, keeping what was there" % name, flush=True)
        return name, len(station.get("streams", []))

    station["streams"] = streams
    station["last_refreshed"] = int(time.time())
    with io.open(path, "w", encoding="utf-8") as handle:
        json.dump(conf, handle, indent=4, ensure_ascii=False)
    print("  %-26s %3d clips" % (name, len(streams)), flush=True)
    return name, len(streams)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=int, default=TARGET)
    parser.add_argument("--only")
    parser.add_argument("--rotate", type=int, default=None,
                        help="refresh only the N least-recently-refreshed channels")
    parser.add_argument("--confs", default=os.path.join(os.path.dirname(__file__), "confs"))
    args = parser.parse_args()

    paths = sorted(glob.glob(os.path.join(args.confs, "ytch_*.json")))
    if args.only:
        paths = [p for p in paths if os.path.basename(p) == "ytch_%s.json" % args.only]
        if not paths:
            print("no channel called %s" % args.only, file=sys.stderr)
            return 2
    elif args.rotate is not None:
        # `is not None`, so --rotate 0 means "none" rather than falling through to all 90 and
        # spending an hour getting the runner's egress address throttled.
        if args.rotate <= 0:
            print("--rotate 0: nothing to do", flush=True)
            return 0
        paths.sort(key=last_refreshed)
        paths = paths[:args.rotate]
        print("rotating %d channels: %s" % (
            len(paths), ", ".join(os.path.basename(p)[5:-5] for p in paths)), flush=True)

    results = []
    # Four at a time. yt-dlp is network-bound so this is worth doing, but more than a handful of
    # concurrent searches is what gets an IP throttled, and a throttled run refreshes nothing.
    with ThreadPoolExecutor(max_workers=4) as pool:
        for result in pool.map(lambda p: refresh(p, args.target), paths):
            results.append(result)

    thin = ["%s(%d)" % (n, c) for n, c in results if c < args.target // 2]
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
    return 0


if __name__ == "__main__":
    sys.exit(main())
