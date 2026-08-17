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

Rotation is by conf modification time: refreshing rewrites the conf, so taking the N
least-recently-written channels advances the rotation by itself with nothing to keep track of.
At 8 a night the whole dial turns over in a fortnight, which is slow enough that a channel does
not feel like it resets and fast enough that nothing on it is ever months old.
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
    """Loose identity for a clip, so the same song from two uploaders lands once."""
    low = re.sub(r"[^a-z0-9 ]", " ", (title or "").lower())
    return " ".join(w for w in low.split() if not w.isdigit())[:40]


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
    elif args.rotate:
        paths.sort(key=os.path.getmtime)
        paths = paths[:args.rotate]
        print("rotating: %d least-recently-refreshed channels" % len(paths), flush=True)

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
    return 0


if __name__ == "__main__":
    sys.exit(main())
