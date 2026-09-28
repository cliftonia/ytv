#!/usr/bin/env python3
"""What airs on the LIVE TV dial in the next day and a bit, as "sightings" for build_details.py.

A sighting is one programme on one channel: the raw title exactly as the app will have it on
screen, plus whatever the guide says that helps pin it down - a series name, a kind, a year, the
slot's length, and when it starts (so the soonest titles are looked up first).

Two sources, the same two the app reads:

  - fast_guide.json, already committed by the FAST guide job (titles and slot lengths)
  - Pluto's public per-channel guide, `api.pluto.tv/v2/channels/<id>`, the endpoint the app asks
    for its NOW and NEXT. No session, no boot call: one plain GET per channel, spaced out. Its
    replies name the series, its type ("film", "tv", "live"), the episode and a release date.
"""
import datetime
import time

from titles import MOVIE, TV, key, kind_from_minutes

PLUTO = "https://api.pluto.tv/v2/channels/%s?start=%s&stop=%s"
PLUTO_GAP_SECONDS = 0.2


class Sighting:
    def __init__(self, raw, start, minutes=None, kind=None, year=None, series=None):
        self.raw, self.start, self.minutes = raw, start, minutes
        self.kind, self.year, self.series = kind, year, series

    def __repr__(self):
        return "Sighting(%r, kind=%r, year=%r, series=%r)" % (self.raw, self.kind, self.year, self.series)


def fast_sightings(guide, now, window_hours):
    """Every titled programme in a fast_guide.json that is on air at [now] or starts within the
    window. A programme runs until the next pair's start, as the app reads it."""
    until = now + window_hours * 3600
    base, titles = guide.get("base", 0), guide.get("titles", [])
    out = []
    for flat in guide.get("channels", {}).values():
        pairs = list(zip(flat[0::2], flat[1::2]))
        for (start, title), (stop, _) in zip(pairs, pairs[1:]):
            t0, t1 = base + start * 60, base + stop * 60
            raw = titles[title] if 0 < title < len(titles) else ""
            if raw and t1 > now and t0 < until:
                minutes = stop - start
                out.append(Sighting(raw, t0, minutes, kind_from_minutes(minutes)))
    return out


def _iso(seconds):
    return datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _seconds(text):
    try:
        return int(datetime.datetime.strptime(text[:19], "%Y-%m-%dT%H:%M:%S")
                   .replace(tzinfo=datetime.timezone.utc).timestamp())
    except (TypeError, ValueError):
        return None


def pluto_ids(lineup):
    """The Pluto ids on the dial, once each, in dial order."""
    seen = []
    for channel in lineup:
        pid = (channel.get("pluto") or {}).get("id")
        if pid and pid not in seen:
            seen.append(pid)
    return seen


def pluto_sighting(entry):
    """One Pluto timeline entry as a sighting, or None. The raw title is the app's: the entry's
    title, else the episode's name (PlutoApi.parse)."""
    episode = entry.get("episode") or {}
    series = episode.get("series") or {}
    raw = (entry.get("title") or "").strip() or (episode.get("name") or "").strip()
    start, stop = _seconds(entry.get("start")), _seconds(entry.get("stop"))
    if not raw or start is None or stop is None or stop <= start:
        return None
    minutes = (episode.get("duration") or (stop - start) * 1000) // 60000
    name, series_name = episode.get("name") or "", series.get("name") or ""
    kind = {"film": MOVIE, "movie": MOVIE, "tv": TV}.get((series.get("type") or "").lower())
    if kind is None and name and series_name and key(name) != key(series_name):
        kind = TV  # a named episode of a series
    if kind is None:
        kind = kind_from_minutes(minutes)
    year = None
    released = (episode.get("clip") or {}).get("originalReleaseDate") or ""
    if kind == MOVIE and released[:4].isdigit():
        # 1970 is Pluto's "unknown", and a date in the airing year is when it was added, not made.
        aired = datetime.datetime.fromtimestamp(start, datetime.timezone.utc).year
        year = int(released[:4]) if 1900 < int(released[:4]) < aired and released[:4] != "1970" else None
    series_out = series_name if kind != MOVIE and series_name and key(series_name) != key(raw) else None
    return Sighting(raw, start, minutes, kind, year, series_out)


def pluto_sightings(lineup, now, window_hours, get, sleep=time.sleep, log=print):
    """Every programme on the dial's Pluto channels in the window. A channel that fails costs its
    own programmes only."""
    out, failed = [], 0
    start, stop = _iso(now), _iso(now + window_hours * 3600)
    for i, pid in enumerate(pluto_ids(lineup)):
        if i:
            sleep(PLUTO_GAP_SECONDS)
        status, data = get(PLUTO % (pid, start, stop), {})
        if status != 200 or not isinstance(data, dict):
            failed += 1
            continue
        for entry in data.get("timelines") or []:
            sighting = pluto_sighting(entry)
            if sighting and sighting.start < now + window_hours * 3600:
                out.append(sighting)
    if failed:
        log("pluto guide: %d channels failed" % failed)
    return out
