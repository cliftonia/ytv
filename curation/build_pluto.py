#!/usr/bin/env python3
"""Build pluto.json, the second dial, from a hand-picked allowlist and iptv-org's playlists.

  python3 build_pluto.py [--out ../pluto.json]

The allowlist (pluto_lineup.json) is the lineup; iptv-org only supplies each channel's current
stream url. That way round on purpose: most of what Pluto carries is one old show on a loop, so a
channel Pluto adds is far more likely to be another of those than something worth a slot. New
channels reach the dial when somebody adds them to the allowlist, not on their own.

Every channel comes out exactly like the live news channels on the YouTube dial - `kind: "live"`,
no rotation, one stream with no id - so the app plays it through the path that already plays
Euronews and CBS News, which are themselves Pluto streams from the same iptv-org list.

Fetches the playlists and nothing else. Any fetch failure exits non-zero WITHOUT writing, so the
committed pluto.json stays as it was: a stale dial beats an empty one.
"""
import argparse
import json
import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ALLOWLIST = os.path.join(HERE, "pluto_lineup.json")

# UK first: a channel in both lists is keyed by its UK id, with the US one as `alt`.
PLAYLISTS = [
    "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/uk_pluto.m3u",
    "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/us_pluto.m3u",
]

# The order blocks of channels appear in on the dial, the way a cable lineup groups them.
GENRE_ORDER = [
    "Movies", "Drama & Series", "Classic TV", "Comedy", "Crime", "Reality", "Documentary",
    "Kids", "Anime", "Music", "Sports", "Motoring", "Food & Home", "Outdoors",
]

# Live feeds have no real duration; this is the value every live channel already carries.
LIVE_DURATION = 600

PLUTO_ID = re.compile(r"plu-([0-9a-f]+)\.m3u8")


def parse_m3u(text):
    """Pluto id -> stream url, for every Pluto stream in an m3u playlist."""
    streams = {}
    for line in text.splitlines():
        line = line.strip()
        match = PLUTO_ID.search(line)
        if match and not line.startswith("#"):
            streams[match.group(1)] = line
    return streams


def build(allow, streams):
    """The dial's channels, and the names of allowlisted channels no playlist carries any more."""
    found, missing = [], []
    for entry in allow:
        url = streams.get(entry["id"]) or streams.get(entry.get("alt", ""))
        if url:
            found.append((entry, url))
        else:
            missing.append(entry["name"])

    def order(item):
        genre = item[0]["genre"]
        rank = GENRE_ORDER.index(genre) if genre in GENRE_ORDER else len(GENRE_ORDER)
        return rank, item[0]["name"].lower()

    channels = []
    for number, (entry, url) in enumerate(sorted(found, key=order), start=1):
        channels.append({
            "number": number,
            "name": entry["name"],
            "kind": "live",
            "streams": [{"url": url, "duration": LIVE_DURATION, "title": entry["name"]}],
        })
    return channels, missing


def refusal(resolved, wanted):
    """Why this result must not be published, or None. Half is the line: Pluto retires the odd
    channel, but losing most of them at once means the playlists changed shape, not the lineup."""
    if resolved == 0:
        return "no channels resolved"
    if resolved * 2 < wanted:
        return "only %d of %d channels resolved" % (resolved, wanted)
    return None


def load_allowlist():
    with open(ALLOWLIST) as f:
        return json.load(f)


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "ytv-lineup"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", default=os.path.join(HERE, "..", "pluto.json"))
    args = parser.parse_args()

    allow = load_allowlist()
    streams = {}
    for url in PLAYLISTS:
        try:
            streams.update({k: v for k, v in parse_m3u(fetch(url)).items() if k not in streams})
        except Exception as e:
            print("could not fetch %s: %s - leaving pluto.json as it is" % (url, e), file=sys.stderr)
            return 1

    channels, missing = build(allow, streams)
    for name in missing:
        print("retired: %s" % name)
    problem = refusal(len(channels), len(allow))
    if problem:
        print("REFUSING TO PUBLISH: %s" % problem, file=sys.stderr)
        return 1

    with open(args.out, "w") as f:
        json.dump({"channels": channels}, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("%s: %d channels (%d retired)" % (os.path.normpath(args.out), len(channels), len(missing)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
