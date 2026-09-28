#!/usr/bin/env python3
"""Build fast_guide.json: what is on the LIVE TV dial's FAST channels for the next day and a bit.

  python3 build_fast_guide.py [--xumo xumo.xml] [--cache DIR]   fetch the guides, write ../fast_guide.json
  python3 build_fast_guide.py xumo-channels OUT.xml             the Xumo grabber's channel list

Pluto channels have their own live path in the app (one request per channel actually looked at),
but a FAST service has no per-channel guide a television could ask. What they do have is a whole
guide per service, which i.mjh.nz republishes as XMLTV every few hours: 15-43 MB each, far too
much for a 2.3 GB television to download and parse. So the nightly job does it instead, and
publishes only what the dial can use:

  - only channels in live.json whose `guide` is samsung, plex, roku or xumo, matched on the
    `guide_id` the draft recorded - the service's own id, so no name matching happens here
  - only programmes on air now or starting in the next WINDOW_HOURS
  - only start times and titles, and each programme's description and picture where the guide
    has them (for the LIVE TV picker's details pane; trimmed, and shared through tables)

Xumo publishes no XMLTV of its own. The workflow runs iptv-org's epg grabber for xumo.tv against
the channel list `xumo-channels` writes, and hands its output in as --xumo; without it the Xumo
channels are simply absent, which the app shows as no title, exactly as before this existed.

The format, compact because every television fetches it several times a day:

  {"generated": epoch seconds, "base": epoch seconds,
   "titles": ["", "Title", ...],
   "channels": {"samsung:US1800015K5": [start, title, start, title, ...], ...}}

Each channel is a flat list of (start, title index) pairs, start in whole minutes after `base`
(negative for a programme already on air at build time). A programme runs until the next pair's
start. Title 0 is the empty title: "nothing listed", used for a gap and to close the last
programme - so the list always ends on a 0, and a time after it has no answer rather than a
stale one. Titles are shared across channels, since the same show airs on many.

Optional, and only when some programme has one (older apps ignore them):

   "descs": ["", "A description", ...], "icons": ["", "https://...", ...],
   "info": {"samsung:US1800015K5": [desc, icon, desc, icon, ...], ...}

`info` runs alongside `channels`: one (desc index, icon index) pair per (start, title) pair, 0 for
none. Descriptions are cut to DESC_CHARS on a word; the picker shows three lines of them.

Fetches i.mjh.nz only - never api.github.com, whose 60/h limit the home IP shares with the
televisions' update check.
"""
import argparse
import datetime
import gzip
import io
import json
import os
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, quoteattr

HERE = os.path.dirname(os.path.abspath(__file__))
LINEUP = os.path.join(HERE, "..", "live.json")
OUT = os.path.join(HERE, "..", "fast_guide.json")

MJH = "https://i.mjh.nz/%s/all.xml.gz"
MJH_SERVICES = {"samsung": "SamsungTVPlus", "plex": "Plex", "roku": "Roku"}
SERVICES = ("samsung", "plex", "roku", "xumo")
WINDOW_HOURS = 30

# Below this many channels with a programme, the guides changed shape rather than the dial: keep
# the committed file, whose titles are at worst a few hours stale, rather than publish nothing.
MIN_SHARE = 0.25

# The picker's details pane shows three lines of description; more is bytes nobody reads.
DESC_CHARS = 240


def key(service, guide_id):
    """How a channel is named in fast_guide.json, and how the app looks it up."""
    return "%s:%s" % (service, guide_id)


def wanted(lineup):
    """service -> {guide id: key} for every live.json channel with a guide this file carries."""
    out = {}
    for channel in lineup:
        service, gid = channel.get("guide"), channel.get("guide_id")
        if service in SERVICES and gid:
            out.setdefault(service, {})[gid] = key(service, gid)
    return out


def xmltv_seconds(text):
    """Epoch seconds for an XMLTV time (`20260928010918 +0000`), or None when it is not one."""
    try:
        return int(datetime.datetime.strptime(text, "%Y%m%d%H%M%S %z").timestamp())
    except (TypeError, ValueError):
        return None


def trimmed(text, limit=DESC_CHARS):
    """[text] on one line, cut to [limit] characters at a word with an ellipsis."""
    text = " ".join((text or "").split())
    if len(text) <= limit:
        return text
    cut = text[:limit - 1].rsplit(" ", 1)[0].rstrip(" ,;:-")
    return cut + "\u2026"


def programmes(data, ids, now, window_hours=WINDOW_HOURS):
    """guide id -> [(start, stop, title, desc, icon)] for [ids] only, from an XMLTV guide's bytes:
    the programmes still on air at [now] or starting before [now] + [window_hours], in start order.
    desc and icon are "" where the guide has none.

    Streamed, and every element cleared once read: the biggest guide is over 40 MB of XML."""
    until = now + window_hours * 3600
    out = {}
    for _, element in ET.iterparse(io.BytesIO(data), events=("end",)):
        if element.tag != "programme":
            if element.tag == "channel":
                element.clear()
            continue
        cid = element.get("channel")
        if cid in ids:
            start, stop = xmltv_seconds(element.get("start")), xmltv_seconds(element.get("stop"))
            title = " ".join((element.findtext("title") or "").split())
            if start is not None and stop is not None and stop > now and start < until and stop > start:
                icon = element.find("icon")
                icon = (icon.get("src") or "") if icon is not None else ""
                desc = trimmed(element.findtext("desc"))
                out.setdefault(cid, []).append((start, stop, title, desc,
                                                icon if icon.startswith("https://") else ""))
        element.clear()
    for items in out.values():
        items.sort()
    return out


def encode(schedules, now):
    """The compact form of {key: [(start, stop, title[, desc, icon])]} - see the module docstring."""
    base = now - now % 60
    titles, index = [""], {"": 0}
    tables = {"descs": ([""], {"": 0}), "icons": ([""], {"": 0})}
    channels, info = {}, {}

    def shared(table, text):
        values, at = tables[table]
        if text not in at:
            at[text] = len(values)
            values.append(text)
        return at[text]

    def minute(t):
        return (t - base) // 60

    for name in sorted(schedules):
        flat, extra, end = [], [], None
        for item in schedules[name]:
            start, stop, title = item[:3]
            desc, icon = (tuple(item[3:5]) + ("", ""))[:2]
            s, e = minute(start), minute(stop)
            if end is not None and s < end:
                s = end  # overlapping listings: the later one waits for the earlier to end
            if e <= s:
                continue
            if end is not None and s > end:
                flat += [end, 0]  # a hole in the listings says nothing, never the last title
                extra += [0, 0]
            if title not in index:
                index[title] = len(titles)
                titles.append(title)
            flat += [s, index[title]]
            extra += [shared("descs", desc or ""), shared("icons", icon or "")]
            end = e
        if flat:
            channels[name] = flat + [end, 0]
            if any(extra):
                info[name] = extra + [0, 0]
    out = {"generated": now, "base": base, "titles": titles, "channels": channels}
    if info:
        out.update(descs=tables["descs"][0], icons=tables["icons"][0], info=info)
    return out


def build(lineup, guides, now):
    """fast_guide.json's contents. [guides] is service -> XMLTV bytes, any service missing."""
    schedules = {}
    for service, ids in wanted(lineup).items():
        data = guides.get(service)
        if data is None:
            continue
        for gid, items in programmes(data, ids, now).items():
            schedules[ids[gid]] = items
    return encode(schedules, now)


def refusal(guide, lineup):
    """Why this result must not be published, or None."""
    want = sum(len(ids) for ids in wanted(lineup).values())
    have = len(guide["channels"])
    if want and have < want * MIN_SHARE:
        return "only %d of %d guide channels have programmes" % (have, want)
    return None


def xumo_channels(lineup):
    """The grabber's channels.xml for every Xumo channel on the dial. xmltv_id stays empty so the
    grabber names each channel by its site_id, which is the guide_id live.json carries."""
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', "<channels>"]
    for channel in lineup:
        if channel.get("guide") == "xumo" and channel.get("guide_id"):
            lines.append('  <channel site="xumo.tv" site_id=%s lang="en" xmltv_id="">%s</channel>'
                         % (quoteattr(channel["guide_id"]), escape(channel["name"])))
    lines.append("</channels>")
    return "\n".join(lines) + "\n"


def fetch(url, timeout=300):
    request = urllib.request.Request(url, headers={"User-Agent": "ytv-lineup"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def load(service, cache):
    """A service's XMLTV bytes, from [cache] when it holds a copy."""
    path = os.path.join(cache, service + ".xml.gz") if cache else None
    if path and os.path.exists(path):
        with open(path, "rb") as f:
            raw = f.read()
    else:
        raw = fetch(MJH % MJH_SERVICES[service])
        if path:
            os.makedirs(cache, exist_ok=True)
            with open(path, "wb") as f:
                f.write(raw)
    return gzip.decompress(raw)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    with open(LINEUP) as f:
        lineup = json.load(f)["channels"]
    if argv[:1] == ["xumo-channels"]:
        with open(argv[1], "w") as f:
            f.write(xumo_channels(lineup))
        return 0

    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--xumo", help="the xumo.tv grabber's XMLTV output, if it ran")
    parser.add_argument("--cache", help="keep the downloaded guides here, and reuse them")
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args(argv)

    guides = {}
    for service in MJH_SERVICES:
        try:
            guides[service] = load(service, args.cache)
        except Exception as e:  # one service down costs its channels, not the whole file
            print("could not load the %s guide: %s" % (service, e), file=sys.stderr)
    if args.xumo and os.path.exists(args.xumo):
        with open(args.xumo, "rb") as f:
            guides["xumo"] = f.read()
    elif args.xumo:
        print("no xumo guide at %s - Xumo channels left out" % args.xumo, file=sys.stderr)

    guide = build(lineup, guides, int(time.time()))
    problem = refusal(guide, lineup)
    if problem:
        print("REFUSING TO PUBLISH: %s" % problem, file=sys.stderr)
        return 1
    with open(args.out, "w") as f:
        json.dump(guide, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    print("%s: %d channels, %d titles" % (os.path.normpath(args.out), len(guide["channels"]),
                                          len(guide["titles"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
