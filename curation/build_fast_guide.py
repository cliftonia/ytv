#!/usr/bin/env python3
"""Build fast_guide.json: what is on the LIVE TV dial's FAST channels for the next day and a bit.

  python3 build_fast_guide.py [--cache DIR]      fetch the guides, write ../fast_guide.json

Pluto channels have their own live path in the app (one request per channel actually looked at),
but a FAST service has no per-channel guide a television could ask. What they do have is a whole
guide per service - far too much for a 2.3 GB television to download and parse. So a job does it
instead, every six hours, and publishes only what the dial can use:

  - only channels in live.json whose `guide` is one of SERVICES, matched on the `guide_id` the
    draft recorded - the service's own id, so no name matching happens here
  - only programmes on air now or starting in the next WINDOW_HOURS
  - only start times and titles, and each programme's description and picture where the guide
    has them (for the LIVE TV picker's details pane; trimmed, and shared through tables)

Where each service's guide comes from - every one public, with no login (fast_guide_fetch.py):

  samsung, plex, roku   i.mjh.nz's whole-service XMLTV (15-43 MB each uncompressed); a
                        programme's <desc> and https <icon>
  xumo                  Xumo's own web guide api: six-hour pages of 50 channels; an asset's
                        descriptions (it names no picture)
  tubi                  Tubi's own web guide api, asked for the dial's channels by id; a programme's
                        description and images. It answers only inside the US: from anywhere else
                        every channel comes back empty
  rakuten               Rakuten TV's own live-channel api for the UK, with each channel's programmes;
                        their description and snapshot
  stirr                 Stirr's own web guide api: every channel at once; descriptions, no pictures

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
none, and a channel whose programmes have neither has no `info` list. Descriptions are cut to
DESC_CHARS on a word; the picker shows three lines of them. Pictures are https only.

Never fetches api.github.com, whose 60/h limit the home IP shares with the televisions' update
check.
"""
import argparse
import datetime
import io
import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET

from fast_guide_fetch import (MJH_SERVICES, WINDOW_HOURS, cached, fetch_rakuten, fetch_stirr, fetch_tubi,
                              fetch_xumo, load_mjh)

HERE = os.path.dirname(os.path.abspath(__file__))
LINEUP = os.path.join(HERE, "..", "live.json")
OUT = os.path.join(HERE, "..", "fast_guide.json")

SERVICES = ("samsung", "plex", "roku", "xumo", "tubi", "rakuten", "stirr")

# Below this many channels with a programme, the guides changed shape rather than the dial: keep
# the committed file, whose titles are at worst a few hours stale, rather than publish nothing.
MIN_SHARE = 0.25

# The picker's details pane shows three lines of description; more is bytes nobody reads.
DESC_CHARS = 240

# Which of a Tubi programme's images to show, best first: the pane's picture is wide.
TUBI_IMAGES = ("landscape", "thumbnail", "hero", "poster")

# Xumo's descriptions come in sizes; the largest that fits DESC_CHARS is shown whole.
XUMO_DESCS = ("medium", "small", "tiny")


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


# ---- times --------------------------------------------------------------------------------------

def xmltv_seconds(text):
    """Epoch seconds for an XMLTV time (`20260928010918 +0000`), or None when it is not one."""
    try:
        return int(datetime.datetime.strptime(text, "%Y%m%d%H%M%S %z").timestamp())
    except (TypeError, ValueError):
        return None


def iso_seconds(text):
    """Epoch seconds for an ISO 8601 time with its offset - `2026-09-28T01:00:00Z`,
    `...+0000`, `...T06:51:01.000+01:00` - or None. A time with no offset is not one: guessing
    its zone would put every title hours out."""
    if not isinstance(text, str):
        return None
    text = re.sub(r"Z$", "+00:00", text.strip())
    text = re.sub(r"([+-]\d\d)(\d\d)$", r"\1:\2", text)
    try:
        parsed = datetime.datetime.fromisoformat(text)
    except ValueError:
        return None
    return int(parsed.timestamp()) if parsed.tzinfo else None


def stirr_seconds(value, text, now):
    """Epoch seconds for one Stirr programme time, whose channels do not agree on a form:
      - epoch seconds, as most send
      - YYYYMMDDHHMM in UTC in the same field, with a nonsense date (year 8390) beside it
      - year 0000 ("0000-09-28 00:00:00", epoch -62143804800): the year of [now], or the one
        either side of it when that puts the time within half a year of [now]"""
    if isinstance(value, int) and 190001010000 <= value <= 299912312359:
        try:
            parsed = datetime.datetime.strptime(str(value), "%Y%m%d%H%M")
        except ValueError:
            return None
        return int(parsed.replace(tzinfo=datetime.timezone.utc).timestamp())
    if isinstance(value, int) and value > 0:
        return value
    if isinstance(text, str) and text.startswith("0000-"):
        year = datetime.datetime.fromtimestamp(now, datetime.timezone.utc).year
        best = None
        for y in (year - 1, year, year + 1):
            try:
                parsed = datetime.datetime.strptime("%04d%s" % (y, text[4:19]), "%Y-%m-%d %H:%M:%S")
            except ValueError:
                continue
            t = int(parsed.replace(tzinfo=datetime.timezone.utc).timestamp())
            if best is None or abs(t - now) < abs(best - now):
                best = t
        return best
    return None


# ---- a programme's description and picture ------------------------------------------------------

def trimmed(text, limit=DESC_CHARS):
    """[text] on one line, cut to [limit] characters at a word with an ellipsis."""
    text = " ".join((text if isinstance(text, str) else "").split())
    if len(text) <= limit:
        return text
    cut = text[:limit - 1].rsplit(" ", 1)[0].rstrip(" ,;:-")
    return cut + "…"


def picture(value, prefer=()):
    """The first https url in a guide's image field - a url, a list of them, or a dict of either
    ([prefer]'s keys first) - or "". A plain-http picture is none: the app will not load one."""
    if isinstance(value, str):
        return value if value.startswith("https://") else ""
    if isinstance(value, dict):
        keys = [k for k in prefer if k in value] + sorted(k for k in value if k not in prefer)
        value = [value[k] for k in keys]
    for item in value if isinstance(value, list) else ():
        found = picture(item)
        if found:
            return found
    return ""


def xumo_desc(descriptions):
    """The largest of a Xumo asset's descriptions ({"tiny", "small", "medium"}) that fits
    DESC_CHARS, else the smallest cut to it."""
    sizes = [trimmed(descriptions.get(k)) for k in XUMO_DESCS] if isinstance(descriptions, dict) else []
    whole = [d for d in sizes if d and not d.endswith("…")]
    return whole[0] if whole else next((d for d in reversed(sizes) if d), "")


# ---- each service's guide, as guide id -> [(start, stop, title, desc, icon)] --------------------

def windowed(items, now, window_hours=WINDOW_HOURS):
    """[(start, stop, title, desc, icon)] still on air at [now] or starting before [now] +
    [window_hours], in start order, each once: the pages a guide comes in may overlap. Items may
    be (start, stop, title) only; desc and icon are "" where the guide has none."""
    until = now + window_hours * 3600
    out = set()
    for item in items:
        start, stop, title = item[:3]
        desc, icon = (tuple(item[3:5]) + ("", ""))[:2]
        if start is None or stop is None or not stop > start or stop <= now or start >= until:
            continue
        out.add((start, stop, " ".join((title or "").split()), trimmed(desc), picture(icon)))
    return sorted(out)


def collect(pairs, ids, now):
    """guide id -> windowed programmes, for (id, (start, stop, title[, desc, icon])) pairs of
    [ids] only."""
    raw = {}
    for gid, item in pairs:
        if gid in ids:
            raw.setdefault(gid, []).append(item)
    out = {gid: windowed(items, now) for gid, items in raw.items()}
    return {gid: items for gid, items in out.items() if items}


def programmes(data, ids, now, window_hours=WINDOW_HOURS):
    """guide id -> [(start, stop, title, desc, icon)] for [ids] only, from an XMLTV guide's bytes:
    the programmes still on air at [now] or starting before [now] + [window_hours], in start order.

    Streamed, and every element cleared once read: the biggest guide is over 40 MB of XML."""
    raw = {}
    for _, element in ET.iterparse(io.BytesIO(data), events=("end",)):
        if element.tag != "programme":
            if element.tag == "channel":
                element.clear()
            continue
        cid = element.get("channel")
        if cid in ids:
            icon = element.find("icon")
            raw.setdefault(cid, []).append((xmltv_seconds(element.get("start")),
                                            xmltv_seconds(element.get("stop")),
                                            element.findtext("title") or "", element.findtext("desc"),
                                            icon.get("src") if icon is not None else None))
        element.clear()
    out = {cid: windowed(items, now, window_hours) for cid, items in raw.items()}
    return {cid: items for cid, items in out.items() if items}


def xumo_programmes(pages, ids, now):
    """From Xumo guide pages: {"channels": [{"channelId", "schedule": [{"assetId", "start",
    "end"}]}], "assets": {assetId: {"title", "descriptions"}}}. An asset is on the page that airs
    it. Xumo's guide names no picture."""
    pairs = []
    for page in pages:
        assets = page.get("assets") or {}
        for channel in page.get("channels") or []:
            cid = str(channel.get("channelId"))
            for slot in channel.get("schedule") or []:
                asset = assets.get(slot.get("assetId")) or {}
                pairs.append((cid, (iso_seconds(slot.get("start")), iso_seconds(slot.get("end")),
                                    asset.get("title"), xumo_desc(asset.get("descriptions")))))
    return collect(pairs, ids, now)


def tubi_programmes(rows, ids, now):
    """From Tubi guide rows: [{"content_id", "programs": [{"title", "start_time", "end_time",
    "description", "images": {"landscape": [url], ...}}]}]."""
    return collect(((str(row.get("content_id")), (iso_seconds(p.get("start_time")),
                                                 iso_seconds(p.get("end_time")), p.get("title"),
                                                 p.get("description"), picture(p.get("images"), TUBI_IMAGES)))
                    for row in rows for p in row.get("programs") or []), ids, now)


def rakuten_programmes(pages, ids, now):
    """From Rakuten live-channel pages: {"data": [{"id", "live_programs": [{"title", "starts_at",
    "ends_at", "description", "images": {"snapshot"}}]}]}. The id is Rakuten's slug for the
    channel ("sci-fi-rakuten-tv"); most programmes' snapshot is null."""
    return collect(((channel.get("id"), (iso_seconds(p.get("starts_at")), iso_seconds(p.get("ends_at")),
                                         p.get("title"), p.get("description"),
                                         (p.get("images") or {}).get("snapshot")))
                    for page in pages for channel in page.get("data") or []
                    for p in channel.get("live_programs") or []), ids, now)


def stirr_programmes(data, ids, now):
    """From Stirr's guide: {"data": {"channels": [{"channel_id", "programs": [{"title", "start",
    "end", "start_time", "end_time", "description"}]}]}} - see [stirr_seconds] for its times.
    Stirr's programmes carry no picture."""
    channels = (data.get("data") or {}).get("channels") or []
    return collect(((str(channel.get("channel_id")),
                     (stirr_seconds(p.get("start_time"), p.get("start"), now),
                      stirr_seconds(p.get("end_time"), p.get("end"), now), p.get("title"), p.get("description")))
                    for channel in channels for p in channel.get("programs") or []), ids, now)


# ---- the file -----------------------------------------------------------------------------------

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
    """fast_guide.json's contents. [guides] is service -> XMLTV bytes, or -> its guide already as
    {guide id: [(start, stop, title[, desc, icon])]}; any service may be missing."""
    schedules = {}
    for service, ids in wanted(lineup).items():
        data = guides.get(service)
        if data is None:
            continue
        got = programmes(data, ids, now) if isinstance(data, bytes) else collect(
            ((gid, item) for gid, items in data.items() for item in items), ids, now)
        for gid, items in got.items():
            schedules[ids[gid]] = items
    return encode(schedules, now)


def refusal(guide, lineup):
    """Why this result must not be published, or None."""
    want = sum(len(ids) for ids in wanted(lineup).values())
    have = len(guide["channels"])
    if want and have < want * MIN_SHARE:
        return "only %d of %d guide channels have programmes" % (have, want)
    return None


# ---- fetching (see fast_guide_fetch.py) ------------------------------------------------------

def load_all(lineup, cache, now):
    """service -> its guide, for every service that loaded; one down costs only its channels."""
    ids = wanted(lineup)
    loaders = {service: (lambda s=service: load_mjh(s, cache)) for service in MJH_SERVICES}
    loaders.update({
        "xumo": lambda: xumo_programmes(cached(cache, "xumo.json", lambda: fetch_xumo(now)),
                                        ids.get("xumo", {}), now),
        "tubi": lambda: tubi_programmes(cached(cache, "tubi.json", lambda: fetch_tubi(ids.get("tubi", {}))),
                                        ids.get("tubi", {}), now),
        "rakuten": lambda: rakuten_programmes(cached(cache, "rakuten.json", lambda: fetch_rakuten(now)),
                                              ids.get("rakuten", {}), now),
        "stirr": lambda: stirr_programmes(cached(cache, "stirr.json", fetch_stirr), ids.get("stirr", {}), now),
    })
    guides = {}
    for service, load in loaders.items():
        if service not in ids:
            continue
        try:
            guides[service] = load()
        except Exception as e:  # one service down costs its channels, not the whole file
            print("could not load the %s guide: %s" % (service, e), file=sys.stderr)
    return guides


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--cache", help="keep the downloaded guides here, and reuse them")
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)
    with open(LINEUP) as f:
        lineup = json.load(f)["channels"]

    now = int(time.time())
    guide = build(lineup, load_all(lineup, args.cache, now), now)
    problem = refusal(guide, lineup)
    if problem:
        print("REFUSING TO PUBLISH: %s" % problem, file=sys.stderr)
        return 1
    with open(args.out, "w") as f:
        json.dump(guide, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    have = {}
    for name in guide["channels"]:
        have[name.split(":", 1)[0]] = have.get(name.split(":", 1)[0], 0) + 1
    print("%s: %d channels (%s), %d titles" % (
        os.path.normpath(args.out), len(guide["channels"]),
        ", ".join("%s %d/%d" % (s, have.get(s, 0), len(i)) for s, i in sorted(wanted(lineup).items())),
        len(guide["titles"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
