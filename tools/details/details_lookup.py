#!/usr/bin/env python3
"""TMDB and OMDb, asked about one title at a time, for build_details.py.

TMDB finds the film or series and its poster, overview, genres, runtime, cast, director or
creators and IMDb id; OMDb, by that IMDb id, the IMDb and Rotten Tomatoes ratings. Every call
goes through one injected `get(url, headers) -> (status, json or None)`, so the tests run with no
network and no keys.

Matching is strict, because a wrong poster is worse than none: a candidate's title (or original
title) must equal the guide's once both are keyed, a leading "the" aside; with a year, the
candidate's must be within one of it; and when several candidates remain, the most-voted must
out-vote the next by DOMINANCE (CROSS_KIND_DOMINANCE between a film and a series), or nothing is
chosen. Without a year a candidate also needs
MIN_VOTES, so an obscure namesake never stands in for a title TMDB does not have.
"""
import time
import urllib.parse

from titles import MOVIE, TV, key, same, trimmed, year_of

TMDB = "https://api.themoviedb.org/3"
OMDB = "https://www.omdbapi.com/"
MIN_VOTES = 10
# Several films of one exact name and no year from the guide: the top one needs this many times
# the next one's votes.
NO_YEAR_DOMINANCE = 10
DOMINANCE = 3
CROSS_KIND_DOMINANCE = 10
TMDB_GAP_SECONDS = 0.03


class Unavailable(Exception):
    """The service cannot be used this run: a bad key, the daily limit, or it is down."""


class Tmdb:

    def __init__(self, api_key, get, sleep=time.sleep):
        self.api_key, self.get, self.sleep = api_key, get, sleep
        self.calls = 0
        self._failures = 0

    def _call(self, path, params):
        params = dict(params)
        headers = {"Accept": "application/json"}
        # A v4 read-access token is a JWT; a v3 key is 32 hex characters. Both are accepted.
        if self.api_key.startswith("eyJ"):
            headers["Authorization"] = "Bearer " + self.api_key
        else:
            params["api_key"] = self.api_key
        url = "%s%s?%s" % (TMDB, path, urllib.parse.urlencode(params))
        for attempt in range(3):
            if self.calls:
                self.sleep(TMDB_GAP_SECONDS)
            self.calls += 1
            status, data = self.get(url, headers)
            if status == 429:
                self.sleep(2 * (attempt + 1))
                continue
            if status in (401, 403):
                raise Unavailable("tmdb refused the key (%s)" % status)
            if status == 404:
                return None
            if status != 200 or not isinstance(data, dict):
                self._failures += 1
                if self._failures >= 5:
                    raise Unavailable("tmdb keeps failing (%s)" % status)
                raise LookupError("tmdb %s on %s" % (status, path))
            self._failures = 0
            return data
        raise LookupError("tmdb rate limited on %s" % path)

    def search(self, kind, title):
        data = self._call("/search/%s" % kind, {"query": title, "include_adult": "false",
                                               "language": "en-US", "page": 1})
        return (data or {}).get("results") or []

    def details(self, kind, tmdb_id):
        return self._call("/%s/%d" % (kind, tmdb_id), {"append_to_response": "credits,external_ids",
                                                      "language": "en-US"})


def choose(results_by_kind, title, year):
    """(kind, result) for the one candidate [title] certainly means, or (None, why not)."""
    want = key(title)
    candidates = []
    for kind, results in results_by_kind:
        for result in results:
            names = (result.get("title") or result.get("name"),
                     result.get("original_title") or result.get("original_name"))
            if not any(n and same(key(n), want) for n in names):
                continue
            released = year_of(result.get("release_date") or result.get("first_air_date"))
            candidates.append((result.get("vote_count") or 0, kind, result, released))
    if year:
        candidates = [c for c in candidates if c[3] and abs(c[3] - year) <= 1]
    if not candidates:
        return None, "no exact title"
    candidates.sort(key=lambda c: -c[0])
    best = candidates[0]
    if len(candidates) > 1:
        # A film and a series of one name, with nothing saying which: only a landslide decides.
        factor = DOMINANCE if candidates[1][1] == best[1] else CROSS_KIND_DOMINANCE
        if not year:
            # Nothing but the name: "Deep in the Heart" is six films, and 93 votes to 18 made a
            # Chinese thriller the answer for a Pluto channel's American drama. Only a rout decides.
            factor = max(factor, NO_YEAR_DOMINANCE)
        if best[0] < factor * max(1, candidates[1][0]):
            return None, "ambiguous"
    if not year and best[0] < MIN_VOTES:
        return None, "too few votes to be sure"
    return (best[1], best[2]), None


def entry_from(kind, data, now):
    """A cache entry from TMDB's details for a film or series, empty fields left out."""
    credits = data.get("credits") or {}
    cast = [c.get("name") for c in (credits.get("cast") or [])[:4] if c.get("name")]
    if kind == MOVIE:
        makers = [c.get("name") for c in credits.get("crew") or [] if c.get("job") == "Director"]
        title, released, runtime = data.get("title"), data.get("release_date"), data.get("runtime")
    else:
        makers = [c.get("name") for c in data.get("created_by") or []]
        title, released = data.get("name"), data.get("first_air_date")
        runtime = (data.get("episode_run_time") or [None])[0]
    entry = {
        "at": now, "tmdb": data.get("id"), "k": kind, "t": title, "y": year_of(released),
        "p": data.get("poster_path"), "b": data.get("backdrop_path"),
        "o": trimmed(data.get("overview")), "g": [g.get("name") for g in data.get("genres") or []][:3],
        "r": runtime or None, "c": cast, "d": [m for m in makers if m][:2],
        "imdb": (data.get("external_ids") or {}).get("imdb_id") or data.get("imdb_id"),
    }
    return {k: v for k, v in entry.items() if v not in (None, "", [])}


def lookup(tmdb, title, kind, year, now):
    """A cache entry for one query: found, or {"miss": why}. Raises LookupError when TMDB could not
    answer (nothing is cached then, so it is asked again next run) and Unavailable to stop."""
    kinds = [kind] if kind else [MOVIE, TV]
    results = [(k, tmdb.search(k, title)) for k in kinds]
    chosen, why = choose(results, title, year)
    if chosen is None:
        return {"at": now, "miss": why}
    found_kind, result = chosen
    data = tmdb.details(found_kind, result["id"])
    if not data:
        return {"at": now, "miss": "no details"}
    return entry_from(found_kind, data, now)


class Omdb:

    def __init__(self, api_key, get):
        self.api_key, self.get = api_key, get
        self.calls = 0

    def ratings(self, imdb_id):
        """{"i": "7.4", "rt": "88%"}, either or both missing when OMDb has none."""
        self.calls += 1
        url = "%s?%s" % (OMDB, urllib.parse.urlencode({"i": imdb_id, "apikey": self.api_key}))
        status, data = self.get(url, {})
        error = (data or {}).get("Error", "") if isinstance(data, dict) else ""
        if status == 401 or "limit" in error.lower() or "invalid api key" in error.lower():
            raise Unavailable("omdb: %s" % (error or status))
        if status != 200 or not isinstance(data, dict):
            raise LookupError("omdb %s" % status)
        if data.get("Response") != "True":
            return {}
        out = {}
        rating = data.get("imdbRating")
        if rating and rating != "N/A":
            out["i"] = rating
        for source in data.get("Ratings") or []:
            if source.get("Source") == "Rotten Tomatoes" and source.get("Value"):
                out["rt"] = source["Value"]
        return out
