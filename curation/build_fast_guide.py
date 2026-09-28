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
  - only start times and titles

Where each service's guide comes from - every one public, with no login:

  samsung, plex, roku   i.mjh.nz's whole-service XMLTV (15-43 MB each uncompressed)
  xumo                  Xumo's own web guide api: six-hour pages of 50 channels
  tubi                  Tubi's own web guide api, asked for the dial's channels by id. It answers
                        only inside the US: from anywhere else every channel comes back empty
  rakuten               Rakuten TV's own live-channel api for the UK, with each channel's programmes
  stirr                 Stirr's own web guide api: every channel at once

The format, compact because every television fetches it several times a day:

  {"generated": epoch seconds, "base": epoch seconds,
   "titles": ["", "Title", ...],
   "channels": {"samsung:US1800015K5": [start, title, start, title, ...], ...}}

Each channel is a flat list of (start, title index) pairs, start in whole minutes after `base`
(negative for a programme already on air at build time). A programme runs until the next pair's
start. Title 0 is the empty title: "nothing listed", used for a gap and to close the last
programme - so the list always ends on a 0, and a time after it has no answer rather than a
stale one. Titles are shared across channels, since the same show airs on many.

Never fetches api.github.com, whose 60/h limit the home IP shares with the televisions' update
check.
"""
import argparse
import concurrent.futures
import datetime
import gzip
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
LINEUP = os.path.join(HERE, "..", "live.json")
OUT = os.path.join(HERE, "..", "fast_guide.json")

MJH = "https://i.mjh.nz/%s/all.xml.gz"
MJH_SERVICES = {"samsung": "SamsungTVPlus", "plex": "Plex", "roku": "Roku"}
SERVICES = ("samsung", "plex", "roku", "xumo", "tubi", "rakuten", "stirr")
WINDOW_HOURS = 30

# Xumo's web guide: one page per 50 channels per six-hour segment (0-3) of a UTC day.
XUMO_EPG = "https://valencia-app-mds.xumo.com/v2/epg/10006/%s/%d.json?f=asset.title&limit=50&offset=%d"
XUMO_PAGE = 50
TUBI_EPG = "https://tubitv.com/oz/epg/programming?content_id=%s"
TUBI_BATCH = 150
RAKUTEN_EPG = ("https://gizmo.rakuten.tv/v3/live_channels?classification_id=18&device_identifier=web"
               "&locale=en&market_code=uk&per_page=50&page=%d&epg_starts_at=%s&epg_ends_at=%s")
STIRR_EPG = "https://stirr.com/api/epg?tz=UTC"

# The web guides answer a browser; Tubi's answers nothing without an English-speaking one.
BROWSER = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like "
                         "Gecko) Chrome/124.0.0.0 Safari/537.36",
           "Accept-Language": "en-US,en;q=0.9", "Accept": "application/json"}

# Below this many channels with a programme, the guides changed shape rather than the dial: keep
# the committed file, whose titles are at worst a few hours stale, rather than publish nothing.
MIN_SHARE = 0.25


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


# ---- each service's guide, as guide id -> [(start, stop, title)] ---------------------------------

def windowed(items, now, window_hours=WINDOW_HOURS):
    """[(start, stop, title)] still on air at [now] or starting before [now] + [window_hours],
    in start order, each once: the pages a guide comes in may overlap."""
    until = now + window_hours * 3600
    out = set()
    for start, stop, title in items:
        if start is None or stop is None or not stop > start or stop <= now or start >= until:
            continue
        out.add((start, stop, " ".join((title or "").split())))
    return sorted(out)


def collect(pairs, ids, now):
    """guide id -> windowed programmes, for (id, (start, stop, title)) pairs of [ids] only."""
    raw = {}
    for gid, item in pairs:
        if gid in ids:
            raw.setdefault(gid, []).append(item)
    out = {gid: windowed(items, now) for gid, items in raw.items()}
    return {gid: items for gid, items in out.items() if items}


def programmes(data, ids, now, window_hours=WINDOW_HOURS):
    """guide id -> [(start, stop, title)] for [ids] only, from an XMLTV guide's bytes: the
    programmes still on air at [now] or starting before [now] + [window_hours], in start order.

    Streamed, and every element cleared once read: the biggest guide is over 40 MB of XML."""
    raw = {}
    for _, element in ET.iterparse(io.BytesIO(data), events=("end",)):
        if element.tag != "programme":
            if element.tag == "channel":
                element.clear()
            continue
        cid = element.get("channel")
        if cid in ids:
            raw.setdefault(cid, []).append((xmltv_seconds(element.get("start")),
                                            xmltv_seconds(element.get("stop")),
                                            element.findtext("title") or ""))
        element.clear()
    out = {cid: windowed(items, now, window_hours) for cid, items in raw.items()}
    return {cid: items for cid, items in out.items() if items}


def xumo_programmes(pages, ids, now):
    """From Xumo guide pages: {"channels": [{"channelId", "schedule": [{"assetId", "start",
    "end"}]}], "assets": {assetId: {"title"}}}. An asset's title is on the page that airs it."""
    pairs = []
    for page in pages:
        assets = page.get("assets") or {}
        for channel in page.get("channels") or []:
            cid = str(channel.get("channelId"))
            for slot in channel.get("schedule") or []:
                title = (assets.get(slot.get("assetId")) or {}).get("title")
                pairs.append((cid, (iso_seconds(slot.get("start")), iso_seconds(slot.get("end")), title)))
    return collect(pairs, ids, now)


def tubi_programmes(rows, ids, now):
    """From Tubi guide rows: [{"content_id", "programs": [{"title", "start_time", "end_time"}]}]."""
    return collect(((str(row.get("content_id")), (iso_seconds(p.get("start_time")),
                                                 iso_seconds(p.get("end_time")), p.get("title")))
                    for row in rows for p in row.get("programs") or []), ids, now)


def rakuten_programmes(pages, ids, now):
    """From Rakuten live-channel pages: {"data": [{"id", "live_programs": [{"title", "starts_at",
    "ends_at"}]}]}. The id is Rakuten's slug for the channel ("sci-fi-rakuten-tv")."""
    return collect(((channel.get("id"), (iso_seconds(p.get("starts_at")), iso_seconds(p.get("ends_at")),
                                         p.get("title")))
                    for page in pages for channel in page.get("data") or []
                    for p in channel.get("live_programs") or []), ids, now)


def stirr_programmes(data, ids, now):
    """From Stirr's guide: {"data": {"channels": [{"channel_id", "programs": [{"title", "start",
    "end", "start_time", "end_time"}]}]}} - see [stirr_seconds] for its times."""
    channels = (data.get("data") or {}).get("channels") or []
    return collect(((str(channel.get("channel_id")),
                     (stirr_seconds(p.get("start_time"), p.get("start"), now),
                      stirr_seconds(p.get("end_time"), p.get("end"), now), p.get("title")))
                    for channel in channels for p in channel.get("programs") or []), ids, now)


# ---- the file -----------------------------------------------------------------------------------

def encode(schedules, now):
    """The compact form of {key: [(start, stop, title)]} - see the module docstring."""
    base = now - now % 60
    titles, index = [""], {"": 0}
    channels = {}

    def minute(t):
        return (t - base) // 60

    for name in sorted(schedules):
        flat, end = [], None
        for start, stop, title in schedules[name]:
            s, e = minute(start), minute(stop)
            if end is not None and s < end:
                s = end  # overlapping listings: the later one waits for the earlier to end
            if e <= s:
                continue
            if end is not None and s > end:
                flat += [end, 0]  # a hole in the listings says nothing, never the last title
            if title not in index:
                index[title] = len(titles)
                titles.append(title)
            flat += [s, index[title]]
            end = e
        if flat:
            channels[name] = flat + [end, 0]
    return {"generated": now, "base": base, "titles": titles, "channels": channels}


def build(lineup, guides, now):
    """fast_guide.json's contents. [guides] is service -> XMLTV bytes, or -> its guide already as
    {guide id: [(start, stop, title)]}; any service may be missing."""
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


# ---- fetching -----------------------------------------------------------------------------------

def fetch(url, timeout=300, headers=None):
    request = urllib.request.Request(url, headers=headers or {"User-Agent": "ytv-lineup"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        data = response.read()
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def fetch_json(url, timeout=60):
    return json.loads(fetch(url, timeout, dict(BROWSER, **{"Accept-Encoding": "gzip"})))


def cached(cache, name, load):
    """[load]() - or, when [cache] holds [name], what an earlier run's [load]() returned."""
    path = os.path.join(cache, name) if cache else None
    if path and os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    value = load()
    if path:
        os.makedirs(cache, exist_ok=True)
        with open(path, "w") as f:
            json.dump(value, f)
    return value


def load_mjh(service, cache):
    """A service's XMLTV bytes, from [cache] when it holds a copy."""
    path = os.path.join(cache, service + ".xml.gz") if cache else None
    if path and os.path.exists(path):
        with open(path, "rb") as f:
            return gzip.decompress(f.read())
    request = urllib.request.Request(MJH % MJH_SERVICES[service], headers={"User-Agent": "ytv-lineup"})
    with urllib.request.urlopen(request, timeout=300) as response:
        raw = response.read()
    if path:
        os.makedirs(cache, exist_ok=True)
        with open(path, "wb") as f:
            f.write(raw)
    return gzip.decompress(raw)


def utc(t):
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc)


def xumo_segments(now, window_hours=WINDOW_HOURS):
    """(YYYYMMDD, segment) for every six-hour segment of Xumo's guide the window touches, from the
    one on air now."""
    out = []
    t = now - now % (6 * 3600)
    while t < now + window_hours * 3600:
        day = utc(t)
        out.append((day.strftime("%Y%m%d"), day.hour // 6))
        t += 6 * 3600
    return out


def fetch_xumo(now):
    """Every page of Xumo's guide for the window: every channel, since a channel's page is where
    Xumo's list order puts it, which moves whenever a channel is added."""
    def segment(day_part):
        day, part = day_part
        pages, offset, total = [], 0, None
        while total is None or offset < total:
            page = fetch_json(XUMO_EPG % (day, part, offset))
            total = page.get("totalChannels") or 0
            if not page.get("channels"):
                break
            pages.append({"channels": page["channels"], "assets": page.get("assets") or {}})
            offset += XUMO_PAGE
        return pages

    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        return [page for pages in pool.map(segment, xumo_segments(now)) for page in pages]


def fetch_tubi(ids):
    rows = []
    ids = sorted(ids)
    for i in range(0, len(ids), TUBI_BATCH):
        batch = ",".join(ids[i:i + TUBI_BATCH])
        rows += fetch_json(TUBI_EPG % urllib.parse.quote(batch, safe=",")).get("rows") or []
    return rows


def fetch_rakuten(now):
    def stamp(t):
        return urllib.parse.quote(utc(t).strftime("%Y-%m-%dT%H:%M:%S.000Z"))

    # Rakuten takes only whole hours (a 400 for anything else).
    hour = now - now % 3600
    pages, page, total = [], 1, 1
    while page <= total:
        reply = fetch_json(RAKUTEN_EPG % (page, stamp(hour - 6 * 3600), stamp(hour + (WINDOW_HOURS + 1) * 3600)))
        total = ((reply.get("meta") or {}).get("pagination") or {}).get("total_pages") or 0
        pages.append({"data": [{"id": c.get("id"), "live_programs": [
            {k: p.get(k) for k in ("title", "starts_at", "ends_at")} for p in c.get("live_programs") or []]}
            for c in reply.get("data") or []]})
        page += 1
    return pages


def fetch_stirr():
    return fetch_json(STIRR_EPG, timeout=120)


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
