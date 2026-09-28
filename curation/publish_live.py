#!/usr/bin/env python3
"""Publish live.json, the LIVE TV dial, from the reviewed draft and the owner's ticks.

  python3 publish_live.py              pick, number, drop dead links, write ../live.json
  python3 publish_live.py --offline    the same without the dead-link pass (no network)

build_live.py drafts the dial (live_draft.json: every channel with its block, sub-block, play data
and guide) and never publishes; this does, nightly, from two committed files:

  live_draft.json   the channels, in block order, and which are on by default
  live_picks.json   the owner's ticks by channel name: {"off": [names], "on": [names]}. `off`
                    are default-on channels he unticked, `on` default-off ones he ticked. Names,
                    not numbers, because the draft renumbers whenever a channel is reclassified -
                    so every name in the draft must be unique, and is checked.
                    Absent means no changes: every default-on channel, nothing else.

The steps, in order:
  1. keep the picked channels
  2. leave out every non-Pluto channel with no guide: the owner's rule is that LIVE TV carries
     only channels that can show what is playing. A Pluto channel always can (the app asks
     Pluto); a FAST channel can only through fast_guide.json, so needs a `guide` it reads. Each
     one left out is logged by name. Stingray's music channels are no exception either way: kept
     when they have a guide, left out when they do not
  3. number them afresh with no gaps: each block on a round hundred, each sub-block on a round ten,
     so a channel left out simply closes up its sub-block, and a sub-block or block left empty
     takes no numbers at all
  4. drop dead links, the way build_pluto.py does: a Pluto channel whose id no Pluto playlist
     carries any more, and a FAST channel whose url its iptv-org playlist no longer lists
  5. write live.json in the app's Channel schema, plus the optional fields the LIVE TV dial adds

Numbers come from step 3 and step 4 never moves them: the app remembers the viewer's channel by
number, so a channel retired overnight leaves a gap rather than shifting everything after it. The
numbers change only when the draft or the ticks do - which is an edit, reviewed like any other.

Like build_pluto.py, any fetch failure exits non-zero WITHOUT writing, so the committed live.json
stays as it was: a stale dial beats an empty one. Fetches raw.githubusercontent.com only - never
api.github.com, whose 60/h limit the home IP shares with the televisions' update check.
"""
import argparse
import json
import os
import sys
import urllib.parse

import build_fast
import build_pluto

HERE = os.path.dirname(os.path.abspath(__file__))
DRAFT = os.path.join(HERE, "live_draft.json")
PICKS = os.path.join(HERE, "live_picks.json")
OUT = os.path.join(HERE, "..", "live.json")

# Live feeds have no real duration; this is the value every live channel already carries.
LIVE_DURATION = 600

# Guides the nightly fast_guide.json is built from. Pluto has its own live path in the app, and
# `none` has nothing to read.
FAST_GUIDES = ("samsung", "plex", "roku", "xumo", "tubi", "rakuten", "stirr")

# Music never cuts to a break of its own, so a cue there would only ever be a false positive.
NO_BREAK_BLOCKS = ("Music",)
NO_BREAK_SOURCES = ("ca_stingray",)


# ---- picking and numbering ----------------------------------------------------------------------

def load_picks(path):
    """(off, on) as sets of channel names; both empty when there is no picks file yet."""
    if not os.path.exists(path):
        return set(), set()
    with open(path) as f:
        picks = json.load(f)
    return set(picks.get("off") or []), set(picks.get("on") or [])


def picks_problem(channels, off, on):
    """Why the ticks cannot be applied to this draft, or None. A name on two channels would tick
    both, and a tick naming no channel is a pick silently lost - a renamed channel, a typo - so
    both stop the build rather than publish a dial the owner did not choose."""
    seen = set()
    for channel in channels:
        if channel["name"] in seen:
            return "two draft channels are called %r" % channel["name"]
        seen.add(channel["name"])
    unknown = sorted((off | on) - seen)
    if unknown:
        return "picks name channels the draft does not have: %s" % ", ".join(unknown)
    return None


def picked(channels, off, on):
    """The draft channels on the dial: default-on unless unticked, default-off only if ticked."""
    return [c for c in channels
            if (c.get("default", True) and c["name"] not in off)
            or (not c.get("default", True) and c["name"] in on)]


def has_guide(channel):
    """Whether the dial can show what [channel] is playing: Pluto's own guide, or a guide
    fast_guide.json is built from with the service's id for it."""
    if channel["source"] == "pluto":
        return True
    return channel.get("guide") in FAST_GUIDES and bool(channel.get("guide_id"))


def with_guides(channels):
    """(kept, names left out): [channels] without the ones that cannot show what is playing -
    see [has_guide]. Order is kept, so numbering what is left closes the gaps."""
    kept = [c for c in channels if has_guide(c)]
    return kept, [c["name"] for c in channels if not has_guide(c)]


# Sub-blocks the owner wants at the END of their block, in this order: the darker genres last, so
# surfing up from a block's start meets comedy and action before horror.
LAST_SUBS = {"Movies": ["Horror", "Thriller"], "Series": ["Crime"]}


def owner_order(blocks):
    """[blocks] with each block's LAST_SUBS moved to its end, everything else in draft order."""
    out = []
    for block in blocks:
        last = LAST_SUBS.get(block["name"], [])
        subs = [s for s in block["sub"] if s["name"] not in last]
        subs += [s for name in last for s in block["sub"] if s["name"] == name]
        out.append(dict(block, sub=subs))
    return out


def round_up(n, step):
    return -(-n // step) * step


def renumber(blocks, channels):
    """[(new number, draft channel)] in dial order.

    Blocks and their sub-blocks keep the draft's order, and channels within a sub-block keep their
    draft order. Each block starts on the next round hundred after the last, each sub-block on the
    next round ten, and an empty sub-block or block takes no numbers at all. A block with more than
    a hundred channels runs on into the next hundreds, and every later block moves up."""
    by_sub = {}
    for channel in sorted(channels, key=lambda c: c["number"]):
        by_sub.setdefault((channel["block"], channel["sub"]), []).append(channel)
    out, next_hundred = [], 100
    for block in blocks:
        position, last = next_hundred, None
        for sub in block["sub"]:
            group = by_sub.pop((block["name"], sub["name"]), [])
            if not group:
                continue
            first = round_up(position, 10)
            out += [(first + i, c) for i, c in enumerate(group)]
            last = first + len(group) - 1
            position = last + 1
        if last is not None:
            next_hundred = round_up(last + 1, 100)
    if by_sub:
        # A channel whose block or sub-block the draft does not list would silently vanish.
        raise ValueError("channels outside the draft's blocks: %s" % sorted(by_sub))
    return out


# ---- the published record -----------------------------------------------------------------------

def breaks_for(channel):
    """"cue" for a FAST channel - its ad breaks are marked in the stream - and None for Pluto,
    whose breaks the app already finds its own way, and for music."""
    if channel["source"] == "pluto":
        return None
    if channel["block"] in NO_BREAK_BLOCKS or channel["source"] in NO_BREAK_SOURCES:
        return None
    return "cue"


def lineup_record(number, channel):
    """One channel as live.json publishes it: the app's Channel schema - `kind: "live"`, no
    rotation, one stream with no `id` - plus the optional fields only this dial has."""
    out = {"number": number, "name": channel["name"], "kind": "live"}
    if channel["source"] == "pluto":
        out["streams"] = [dict(s, duration=s.get("duration", LIVE_DURATION)) for s in channel["streams"]]
        out["pluto"] = channel["pluto"]
    else:
        stream = {"url": channel["url"], "duration": LIVE_DURATION, "title": channel["name"]}
        if channel.get("route") == "us":
            stream["route"] = "us"
        out["streams"] = [stream]
    out["block"] = channel["block"]
    out["sub"] = channel["sub"]
    breaks = breaks_for(channel)
    if breaks:
        out["breaks"] = breaks
    if channel.get("guide") in FAST_GUIDES and channel.get("guide_id"):
        out["guide"] = channel["guide"]
        out["guide_id"] = channel["guide_id"]
    return out


def build(draft, off, on):
    """(live.json's channels, names left out for having no guide) from the draft and the ticks,
    before the dead-link pass. Raises ValueError when the ticks do not fit the draft - see
    [picks_problem]."""
    problem = picks_problem(draft["channels"], off, on)
    if problem:
        raise ValueError(problem)
    chosen, unguided = with_guides(picked(draft["channels"], off, on))
    return [dict(lineup_record(number, c), _source=c["source"], _tvg=c.get("tvg_id"),
                 _also=c.get("also") or [])
            for number, c in renumber(owner_order(draft["blocks"]), chosen)], unguided


def public(channel):
    """The record without the dead-link pass's working fields."""
    return {k: v for k, v in channel.items() if not k.startswith("_")}


# ---- dead links ---------------------------------------------------------------------------------

def pluto_alternatives(allowlist):
    """Pluto id -> every id its allowlist entry may play as, UK first then the US `alt`."""
    out = {}
    for entry in allowlist:
        ids = [i for i in (entry.get("id"), entry.get("alt")) if i]
        for pid in ids:
            out[pid] = ids
    return out


def host(url):
    return (urllib.parse.urlsplit(url).hostname or "").lower()


def alive(channels, pluto_streams, pluto_regions, fast_entries, allowlist):
    """(kept, dropped names): each channel checked against tonight's playlists.

    A Pluto channel keeps the first of its allowlist ids a Pluto playlist still carries, with that
    playlist's url and region - build_pluto's rule, so both dials agree. A FAST channel stays while
    its url is still listed; when its own playlist lists the same tvg-id at a new url on the SAME
    host it moves there, since a third-party list may repoint a channel but must never be able to
    send it to a host nobody reviewed. Anything else is dead and dropped."""
    alternatives = pluto_alternatives(allowlist)
    urls = {e["url"] for e in fast_entries}
    by_tvg = {}
    for e in fast_entries:
        if e.get("tvg_id"):
            by_tvg.setdefault((e["source"], e["tvg_id"]), []).append(e["url"])
    kept, dropped = [], []
    for channel in channels:
        stream = channel["streams"][0]
        if "pluto" in channel:
            pid = next((i for i in alternatives.get(channel["pluto"]["id"], [channel["pluto"]["id"]])
                        if i in pluto_streams), None)
            if pid is None:
                dropped.append(channel["name"])
                continue
            channel["pluto"] = {"id": pid, "region": pluto_regions[pid]}
            channel["streams"] = [dict(stream, url=pluto_streams[pid])]
            kept.append(channel)
            continue
        if stream["url"] not in urls:
            moved = [u for u in by_tvg.get((channel["_source"], channel["_tvg"]), [])
                     if host(u) == host(stream["url"])]
            if not moved:
                dropped.append(channel["name"])
                continue
            stream["url"] = moved[0]
        kept.append(channel)
    return kept, dropped


def refusal(kept, wanted):
    """Why this result must not be published, or None: build_pluto's line, half. A service drops
    the odd channel; losing most at once means a playlist changed shape, not the lineup."""
    if kept == 0:
        return "no channels survived"
    if kept * 2 < wanted:
        return "only %d of %d channels survived" % (kept, wanted)
    return None


def fetch_playlists(sources):
    """(pluto streams, pluto regions, FAST entries) from iptv-org's playlists."""
    pluto = [(region, build_pluto.fetch(url)) for region, url in build_pluto.PLAYLISTS]
    streams, regions = build_pluto.gather(pluto)
    entries = []
    for source in sorted(sources):
        entries += build_fast.parse_m3u(build_fast.fetch(build_fast.RAW % source), source)
    return streams, regions, entries


def write(path, channels):
    with open(path, "w") as f:
        json.dump({"channels": [public(c) for c in channels]}, f, indent=1, ensure_ascii=False)
        f.write("\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", default=OUT)
    parser.add_argument("--picks", default=PICKS)
    parser.add_argument("--offline", action="store_true", help="skip the dead-link pass")
    args = parser.parse_args(argv)

    with open(DRAFT) as f:
        draft = json.load(f)
    off, on = load_picks(args.picks)
    try:
        channels, unguided = build(draft, off, on)
    except ValueError as e:
        print("REFUSING TO PUBLISH: %s" % e, file=sys.stderr)
        return 1
    for name in unguided:
        print("no guide: %s" % name)
    wanted = len(channels)
    if not args.offline:
        try:
            playlists = fetch_playlists({c["_source"] for c in channels if c["_source"] != "pluto"})
        except Exception as e:
            print("could not fetch the playlists: %s - leaving live.json as it is" % e, file=sys.stderr)
            return 1
        channels, dropped = alive(channels, *playlists, build_pluto.load_allowlist())
        for name in dropped:
            print("dead: %s" % name)
        problem = refusal(len(channels), wanted)
        if problem:
            print("REFUSING TO PUBLISH: %s" % problem, file=sys.stderr)
            return 1
    write(args.out, channels)
    print("%s: %d channels (%d dead, %d with no guide left out)" % (
        os.path.normpath(args.out), len(channels), wanted - len(channels), len(unguided)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
