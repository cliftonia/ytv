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
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ALLOWLIST = os.path.join(HERE, "pluto_lineup.json")

# UK first: a channel in both lists is keyed by its UK id, with the US one as `alt`. The region
# travels with each id into pluto.json because the app asks for a Pluto session FROM that country:
# some channels show only Pluto's logo bumper to a session from anywhere else.
PLAYLISTS = [
    ("uk", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/uk_pluto.m3u"),
    ("us", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/us_pluto.m3u"),
]

# The order blocks of channels appear in on the dial, the way a cable lineup groups them. The
# numbers in the allowlist were assigned from this order once; a new channel should take a free
# number near its genre's block, but nothing here re-sorts anything.
GENRE_ORDER = [
    "Movies", "Drama & Series", "Classic TV", "Comedy", "Crime", "Reality", "Documentary",
    "Kids", "Anime", "Music", "Sports", "Motoring", "Food & Home", "Outdoors",
]

# Live feeds have no real duration; this is the value every live channel already carries.
LIVE_DURATION = 600

PLUTO_ID = re.compile(r"plu-([0-9a-f]+)\.m3u8")


# The only hosts a Pluto stream url may point at. iptv-org is a third-party, community-edited
# playlist, and whatever url it names goes straight onto every television's dial - so matching
# the `plu-<id>.m3u8` shape is not enough: that path on any other host would be published as-is.
# jmp2.uk is the redirector iptv-org uses for every Pluto entry today; pluto.tv (and its
# subdomains) is Pluto itself, should the list ever point there directly.
TRUSTED_HOSTS = ("jmp2.uk",)
TRUSTED_DOMAINS = ("pluto.tv",)


def trusted_host(url):
    """True when the url's host is one we will hand to a television. Exact host or a real
    subdomain only: `fakepluto.tv` and `jmp2.uk.evil.com` both end in the right letters."""
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    if host in TRUSTED_HOSTS:
        return True
    return any(host == d or host.endswith("." + d) for d in TRUSTED_DOMAINS)


def parse_m3u(text):
    """Pluto id -> stream url, for every Pluto stream on a trusted host in an m3u playlist."""
    streams = {}
    for line in text.splitlines():
        line = line.strip()
        match = PLUTO_ID.search(line)
        if match and not line.startswith("#") and trusted_host(line):
            streams[match.group(1)] = line
    return streams


def gather(playlists):
    """Pluto id -> url and Pluto id -> region, from (region, m3u text) pairs in PLAYLISTS order.
    An id in more than one playlist belongs to the first, exactly as its url always has."""
    streams, regions = {}, {}
    for region, text in playlists:
        for pid, url in parse_m3u(text).items():
            if pid not in streams:
                streams[pid] = url
                regions[pid] = region
    return streams, regions


def build(allow, streams, regions=None):
    """The dial's channels, and the names of allowlisted channels no playlist carries any more.

    With [regions], each channel also names the Pluto id it uses and the playlist that carried it,
    as `"pluto": {"id", "region"}` - the app's key to Pluto's own stream route. The jmp2 url stays
    in `streams`: it is what the app plays when it cannot get a session, and what an app that
    predates the field plays always."""
    found, missing = [], []
    for entry in allow:
        # The same choice as ever - the UK id when a playlist carries it, else the US `alt` - made
        # explicit, because the id chosen is now published as well as its url.
        pid = entry["id"] if entry["id"] in streams else entry.get("alt", "")
        url = streams.get(pid)
        if url:
            found.append((entry, url, pid))
        else:
            missing.append(entry["name"])

    # Each channel's number is the allowlist's, never its position. The app remembers the
    # viewer's channel by number, so numbering by sorted position meant one retired channel
    # quietly renumbered everything after it. Now a retirement leaves a gap on the dial instead.
    # The numbers were assigned once (Sep 2026) from the old genre-then-name order, so nobody's
    # remembered channel moved; a newly allowlisted channel takes any free number.
    channels = []
    for entry, url, pid in sorted(found, key=lambda item: item[0]["number"]):
        channel = {
            "number": entry["number"],
            "name": entry["name"],
            "kind": "live",
            "streams": [{"url": url, "duration": LIVE_DURATION, "title": entry["name"]}],
        }
        # Last, so every line above it in pluto.json is byte-for-byte what it was before.
        if regions and pid in regions:
            channel["pluto"] = {"id": pid, "region": regions[pid]}
        channels.append(channel)
    return channels, missing


def numbering_problem(allow):
    """Why the allowlist's numbers cannot be published, or None. Two channels on one number
    would leave one unreachable (which one wins being an accident of ordering), and a channel
    without one has no place on the dial - both are editing mistakes, caught here rather than
    shipped to the televisions."""
    seen = {}
    for entry in allow:
        number = entry.get("number")
        # bool is an int in Python; `"number": true` is a typo, not channel 1.
        if not isinstance(number, int) or isinstance(number, bool) or number < 1:
            return ('%s has no channel number - give it a free positive "number" in '
                    "pluto_lineup.json" % entry.get("name", entry.get("id")))
        if number in seen:
            return "channel number %d is used by both %s and %s" % (number, seen[number], entry["name"])
        seen[number] = entry["name"]
    return None


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
    problem = numbering_problem(allow)
    if problem:
        print("REFUSING TO PUBLISH: %s" % problem, file=sys.stderr)
        return 1

    fetched = []
    for region, url in PLAYLISTS:
        try:
            fetched.append((region, fetch(url)))
        except Exception as e:
            print("could not fetch %s: %s - leaving pluto.json as it is" % (url, e), file=sys.stderr)
            return 1

    streams, regions = gather(fetched)
    channels, missing = build(allow, streams, regions)
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
