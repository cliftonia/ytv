#!/usr/bin/env python3
"""Fetching for build_fast_guide.py: each service's whole guide, as its own public api gives it.

Every function here returns the service's reply (or the part of it the builder reads) unparsed;
build_fast_guide.py turns them into programmes. [cached] keeps a run's replies in a directory so
a rebuild can reuse them without asking again.

Never fetches api.github.com, whose 60/h limit the home IP shares with the televisions' update
check.
"""
import concurrent.futures
import datetime
import gzip
import json
import os
import urllib.parse
import urllib.request

# How far ahead every guide is asked for, and cut to: on air now, or starting within this.
WINDOW_HOURS = 30

MJH = "https://i.mjh.nz/%s/all.xml.gz"
MJH_SERVICES = {"samsung": "SamsungTVPlus", "plex": "Plex", "roku": "Roku"}

# Xumo's web guide: one page per 50 channels per six-hour segment (0-3) of a UTC day. Its assets
# carry only the fields asked for with f=.
XUMO_EPG = ("https://valencia-app-mds.xumo.com/v2/epg/10006/%s/%d.json?f=asset.title&f=asset.descriptions"
            "&limit=50&offset=%d")
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
    """A service's XMLTV bytes from i.mjh.nz, from [cache] when it holds a copy."""
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
    """Tubi's guide rows for [ids], whole: each programme with its description and images."""
    rows = []
    ids = sorted(ids)
    for i in range(0, len(ids), TUBI_BATCH):
        batch = ",".join(ids[i:i + TUBI_BATCH])
        rows += fetch_json(TUBI_EPG % urllib.parse.quote(batch, safe=",")).get("rows") or []
    return rows


# What the builder reads of a Rakuten programme; the rest (ratings, labels) is dropped.
RAKUTEN_FIELDS = ("title", "starts_at", "ends_at", "description")


def fetch_rakuten(now):
    def stamp(t):
        return urllib.parse.quote(utc(t).strftime("%Y-%m-%dT%H:%M:%S.000Z"))

    def programme(p):
        out = {k: p.get(k) for k in RAKUTEN_FIELDS}
        out["images"] = {"snapshot": (p.get("images") or {}).get("snapshot")}
        return out

    # Rakuten takes only whole hours (a 400 for anything else).
    hour = now - now % 3600
    pages, page, total = [], 1, 1
    while page <= total:
        reply = fetch_json(RAKUTEN_EPG % (page, stamp(hour - 6 * 3600), stamp(hour + (WINDOW_HOURS + 1) * 3600)))
        total = ((reply.get("meta") or {}).get("pagination") or {}).get("total_pages") or 0
        pages.append({"data": [{"id": c.get("id"), "live_programs": [programme(p) for p in c.get("live_programs") or []]}
                               for c in reply.get("data") or []]})
        page += 1
    return pages


def fetch_stirr():
    return fetch_json(STIRR_EPG, timeout=120)
