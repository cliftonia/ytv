#!/usr/bin/env python3
"""Build details.json: poster, overview, ratings and credits for what airs on the LIVE TV dial.

  TMDB_API_KEY=... OMDB_API_KEY=... python3 build_details.py [--repo DIR]

Run on the home server by publish_details.sh (which commits and pushes the result); stdlib only.
The LIVE TV picker shows, over its channel list, the programme on the highlighted channel: a
picture on the right, and on the left its title, description, IMDb and Rotten Tomatoes ratings,
cast and director, year, runtime and genre. The guides have titles, and some descriptions and
stills; the rest comes from TMDB and OMDb, asked here - never from the television, and never with
a key in the app or the repository (the server keeps them in ~/.config/ytv/details.env).

  1. Sightings: every programme in the next WINDOW_HOURS on the dial's FAST channels
     (the published fast_guide.json, in the clone publish_details.sh has just pulled) and Pluto
     channels (Pluto's per-channel guide, asked directly) - see details_sources.py.
  2. Queries: each distinct title parsed into something to search for - see titles.py. Titles
     that cannot be pinned down ("S2 E3 Apocalyptic Visions", live sport) are not searched.
  3. Lookups, cached for good in tools/details/details_cache.json, keyed by kind, title and year, so a
     title is looked up once. A miss is cached too, and asked again after MISS_DAYS. At most
     TMDB_PER_RUN new queries a run, soonest-airing first; the rest wait for the next run.
  4. Ratings from OMDb, whose free tier is 1,000 requests a day: at most OMDB_PER_DAY a UTC day
     (counted in the cache), never-rated titles first, then ratings older than RATINGS_DAYS.
  5. details.json: only titles airing in the window, keyed as the app keys them (titles.key of
     the raw guide title), images as paths under TMDB's image CDN.

Without TMDB_API_KEY nothing new is looked up and details.json is written from the cache alone;
without OMDB_API_KEY there are no new ratings. Without either and with nothing cached, nothing is
written, and the app shows what the guides have - as it does for any title missing here.

The format, compact because every television fetches it several times a day:

  {"generated": epoch seconds, "img": "https://image.tmdb.org/t/p/",
   "titles": {"<key of a raw guide title>": item index, ...},
   "items": [{"k": "m" | "t", "t": title, "y": year, "p": "w500/<poster>.jpg",
              "b": "w780/<backdrop>.jpg", "o": overview, "g": [genres], "r": runtime minutes,
              "c": [cast], "d": [director or creators], "i": "7.4", "rt": "88%"}, ...]}

Every item field but "t" may be missing. Fetches api.themoviedb.org, www.omdbapi.com and
api.pluto.tv only - never api.github.com.
"""
import argparse
import datetime
import json
import os
import sys
import time
import urllib.error
import urllib.request

import details_lookup as dl
import details_sources as ds
from titles import MOVIE, TV, key, parse, prefix, recurring_prefixes

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_NAME = os.path.join("tools", "details", "details_cache.json")

WINDOW_HOURS = 30
IMG = "https://image.tmdb.org/t/p/"
POSTER, BACKDROP = "w500", "w780"
TMDB_PER_RUN = 2500
# Lookups in flight at once: the first run asks about ~2,300 titles, one at a time ~70 minutes.
LOOKUP_THREADS = 6
# The cache is written after every this many new lookups, so a killed run keeps what it found.
CHECKPOINT_EVERY = 100
OMDB_PER_RUN = 900
OMDB_PER_DAY = 900
MISS_DAYS = 30
RATINGS_DAYS = 180
DAY = 86400
# Below this share of the last file's titles, the sources failed rather than the dial changing:
# the published file, at worst hours stale, stands.
MIN_SHARE = 0.25


class Query:
    """One thing to look up: a cleaned title, a kind if known, a year if known."""

    def __init__(self, title, kind=None, year=None):
        self.title, self.kind, self.year = title, kind, year

    @property
    def cache_key(self):
        return "%s|%s|%s" % (self.kind or "any", key(self.title), self.year or "")

    def __repr__(self):
        return "Query(%r, %r, %r)" % (self.title, self.kind, self.year)

    def __eq__(self, other):
        return isinstance(other, Query) and self.cache_key == other.cache_key


def _one(values):
    values = {v for v in values if v}
    return values.pop() if len(values) == 1 else None


def plan(raw, sightings, prefixes):
    """The queries for one raw title, most specific first; [] when it cannot be pinned down.

    The guide's own hints - Pluto's series name and type, the slot's length - are used only when
    every sighting of the title agrees on them."""
    parsed = parse(raw)
    if parsed is None:
        return []
    kind = parsed.kind or _one(s.kind for s in sightings)
    year = parsed.year or (_one(s.year for s in sightings) if kind == MOVIE else None)
    series = _one(s.series for s in sightings) if kind != MOVIE else None
    queries = [Query(series, TV) if series else Query(parsed.title, kind, year)]
    head = prefix(raw)
    if kind != MOVIE and head and key(head) in prefixes:
        fallback = parse(head)
        if fallback and key(fallback.title) != key(queries[0].title):
            queries.append(Query(fallback.title, TV))
    return queries


def fresh(entry, now):
    """A cached answer still worth using: any find, or a miss younger than MISS_DAYS."""
    return entry is not None and ("miss" not in entry or now - entry.get("at", 0) < MISS_DAYS * DAY)


def resolve(queries, cache, tmdb, now, budget, log=print):
    """The found entry for the first query that finds one, looking up what the cache lacks while
    [budget] (a one-item list, decremented) lasts. None when all miss, or when one is unanswered
    and the rest are misses - it is asked again next run."""
    for query in queries:
        entry = cache.get(query.cache_key)
        if not fresh(entry, now):
            if tmdb is None or budget[0] <= 0:
                return None
            budget[0] -= 1
            try:
                entry = dl.lookup(tmdb, query.title, query.kind, query.year, now)
            except LookupError as e:
                log("tmdb: %s" % e)
                return None
            cache[query.cache_key] = entry
        if "miss" not in entry:
            return entry
    return None


def rate(found, cache_meta, omdb, now, log=print):
    """Ratings for the found entries that need them, within the OMDb budget. [found] is soonest
    airing first; never-rated entries go before stale ones."""
    today = datetime.datetime.fromtimestamp(now, datetime.timezone.utc).strftime("%Y-%m-%d")
    if cache_meta.get("day") != today or "used" not in cache_meta:
        cache_meta.update(day=today, used=0)
    allowed = min(OMDB_PER_RUN, OMDB_PER_DAY - cache_meta["used"])
    unrated = [e for e in found if e.get("imdb") and "rated" not in e]
    stale = [e for e in found if e.get("imdb") and now - e.get("rated", now) > RATINGS_DAYS * DAY]
    done = 0
    for entry in unrated + stale:
        if omdb is None or done >= allowed:
            break
        try:
            ratings = omdb.ratings(entry["imdb"])
        except dl.Unavailable as e:
            log("omdb stopped: %s" % e)
            break
        except LookupError as e:
            log("omdb: %s" % e)
            continue
        finally:
            done += 1
            cache_meta["used"] += 1
        entry.pop("i", None)
        entry.pop("rt", None)
        entry.update(ratings, rated=now)
    return done


def item(entry):
    """The published form of a found cache entry."""
    out = {"k": "m" if entry.get("k") == MOVIE else "t", "t": entry.get("t")}
    for field in ("y", "o", "g", "r", "c", "d", "i", "rt"):
        if entry.get(field):
            out[field] = entry[field]
    if entry.get("p"):
        out["p"] = POSTER + entry["p"]
    if entry.get("b"):
        out["b"] = BACKDROP + entry["b"]
    return out


def prewarm(plans, entries, tmdb, now, budget, checkpoint=None, log=print):
    """Look up every plan's FIRST query the cache lacks, LOOKUP_THREADS at a time, within
    [budget]; [resolve] then finds them cached. Later queries of a plan (a looser form of the
    same title) are left to [resolve], one at a time, as before."""
    import concurrent.futures
    import threading
    wanted, seen = [], set()
    for queries in plans:
        if queries and queries[0].cache_key not in seen and not fresh(entries.get(queries[0].cache_key), now):
            seen.add(queries[0].cache_key)
            wanted.append(queries[0])
    wanted = wanted[:max(0, budget[0])]
    budget[0] -= len(wanted)
    lock, done = threading.Lock(), [0]

    def one(query):
        try:
            entry = dl.lookup(tmdb, query.title, query.kind, query.year, now)
        except dl.Unavailable:
            raise
        except LookupError as e:
            log("tmdb: %s" % e)
            return
        with lock:
            entries[query.cache_key] = entry
            done[0] += 1
            if checkpoint and done[0] % CHECKPOINT_EVERY == 0:
                checkpoint()
                log("details: %d of %d looked up" % (done[0], len(wanted)))

    with concurrent.futures.ThreadPoolExecutor(LOOKUP_THREADS) as pool:
        for future in [pool.submit(one, q) for q in wanted]:
            future.result()


def run(sightings, cache, now, tmdb=None, omdb=None, log=print, checkpoint=None):
    """details.json's contents, updating [cache] ({"omdb": {...}, "entries": {...}}) on the way."""
    by_key, soonest = {}, {}
    for s in sightings:
        k = key(s.raw)
        if k:
            by_key.setdefault(k, []).append(s)
            soonest[k] = min(soonest.get(k, s.start), s.start)
    prefixes = recurring_prefixes(s.raw for s in sightings)
    entries = cache.setdefault("entries", {})
    budget = [TMDB_PER_RUN]
    if tmdb is not None:
        try:
            prewarm([plan(by_key[k][0].raw, by_key[k], prefixes) for k in sorted(by_key, key=lambda k: (soonest[k], k))],
                    entries, tmdb, now, budget, checkpoint, log)
        except dl.Unavailable as e:
            log("tmdb stopped: %s" % e)
            tmdb = None
    titles, index, found = {}, {}, []
    for k in sorted(by_key, key=lambda k: (soonest[k], k)):
        group = by_key[k]
        try:
            entry = resolve(plan(group[0].raw, group, prefixes), entries, tmdb, now, budget, log)
        except dl.Unavailable as e:
            log("tmdb stopped: %s" % e)
            tmdb = None
            entry = None
        if entry is None or not entry.get("t"):
            continue
        ident = id(entry)
        if ident not in index:
            index[ident] = len(found)
            found.append(entry)
        titles[k] = index[ident]
    rated = rate(found, cache.setdefault("omdb", {}), omdb, now, log)
    items = [item(e) for e in found]
    log("details: %d titles airing, %d matched to %d items, %d tmdb calls, %d omdb calls"
        % (len(by_key), len(titles), len(items), tmdb.calls if tmdb else 0, rated))
    return {"generated": now, "img": IMG, "titles": titles, "items": items}


def fetch_json(url, headers, timeout=20):
    """(status, parsed JSON or None). Never raises for HTTP or network failure."""
    request = urllib.request.Request(url, headers=dict(headers, **{"User-Agent": "ytv-lineup"}))
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except (ValueError, OSError):
            return e.code, None
    except (urllib.error.URLError, OSError, ValueError):
        return 0, None


def load_cache(path):
    if not os.path.exists(path):
        return {"omdb": {}, "entries": {}}
    with open(path) as f:
        return json.load(f)


def save_cache(cache, path):
    """One entry per line, keys sorted, so a run's commit diff is the titles it looked up. Written
    whole then renamed, so a run killed mid-write never leaves half a cache."""
    lines = ['{"omdb": %s,' % json.dumps(cache.get("omdb", {}), sort_keys=True), '"entries": {']
    entries = cache.get("entries", {})
    body = ["%s: %s" % (json.dumps(k, ensure_ascii=False), json.dumps(entries[k], ensure_ascii=False,
                                                                      sort_keys=True))
            for k in sorted(entries)]
    lines.append(",\n".join(body))
    lines.append("}}")
    with open(path + ".tmp", "w") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(path + ".tmp", path)


def _previous(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--repo", default=os.path.join(HERE, "..", ".."),
                        help="the repository: live.json and fast_guide.json are read, details.json "
                             "and the cache written")
    args = parser.parse_args(argv)
    lineup_path, guide_path = os.path.join(args.repo, "live.json"), os.path.join(args.repo, "fast_guide.json")
    out_path, cache_path = os.path.join(args.repo, "details.json"), os.path.join(args.repo, CACHE_NAME)
    now = int(time.time())
    tmdb_key = os.environ.get("TMDB_API_KEY", "").strip()
    omdb_key = os.environ.get("OMDB_API_KEY", "").strip()
    cache = load_cache(cache_path)
    if not tmdb_key:
        print("TMDB_API_KEY is not set: nothing new is looked up", file=sys.stderr)
        if not cache.get("entries"):
            print("and nothing is cached - details.json left as it is", file=sys.stderr)
            return 0
    with open(lineup_path) as f:
        lineup = json.load(f)["channels"]
    sightings = []
    if os.path.exists(guide_path):
        with open(guide_path) as f:
            sightings += ds.fast_sightings(json.load(f), now, WINDOW_HOURS, ds.guide_blocks(lineup))
    sightings += ds.pluto_sightings(lineup, now, WINDOW_HOURS, fetch_json)
    tmdb = dl.Tmdb(tmdb_key, fetch_json) if tmdb_key else None
    omdb = dl.Omdb(omdb_key, fetch_json) if omdb_key else None
    details = run(sightings, cache, now, tmdb, omdb, checkpoint=lambda: save_cache(cache, cache_path))
    save_cache(cache, cache_path)
    previous = _previous(out_path)
    if previous and len(details["titles"]) < len(previous.get("titles") or {}) * MIN_SHARE:
        # Pluto unreachable, the guide missing: the dial did not change, the sources did.
        print("REFUSING TO PUBLISH: %d titles against %d last time"
              % (len(details["titles"]), len(previous["titles"])), file=sys.stderr)
        return 1
    if previous and all(previous.get(k) == details[k] for k in ("titles", "items")):
        print("details unchanged: %d titles" % len(details["titles"]))
        return 0
    with open(out_path + ".tmp", "w") as f:
        json.dump(details, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    os.replace(out_path + ".tmp", out_path)
    print("%s: %d titles, %d items" % (os.path.normpath(out_path), len(details["titles"]), len(details["items"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
