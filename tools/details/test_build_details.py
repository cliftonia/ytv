#!/usr/bin/env python3
"""details.json: TMDB and OMDb answers for what airs on the LIVE TV dial, with every HTTP call
mocked - no network, no keys. The guide shapes are cut from real replies (Pluto's v2 channel
guide and fast_guide.json, 28 Sep 2026).

Run: python3 -m unittest discover -s tools/details"""
import json
import os
import sys
import unittest
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_details as bd  # noqa: E402
import details_lookup as dl  # noqa: E402
import details_sources as ds  # noqa: E402
from titles import MOVIE, TV  # noqa: E402

T0 = 1790553600  # 2026-09-28 00:00:00 UTC


class FakeHttp:
    """Answers by path: TMDB search by query, TMDB details by id, OMDb by IMDb id."""

    def __init__(self, searches=None, details=None, omdb=None, status=None, pluto=None):
        self.searches, self.details, self.omdb = searches or {}, details or {}, omdb or {}
        self.status, self.pluto = status, pluto
        self.urls = []

    def __call__(self, url, headers):
        self.urls.append((url, headers))
        if "api.pluto.tv" in url:
            return (200, self.pluto) if self.pluto else (503, None)
        if self.status:
            return self.status, {"Error": "Request limit reached!"}
        parts = urllib.parse.urlparse(url)
        query = dict(urllib.parse.parse_qsl(parts.query))
        if "omdbapi" in url:
            return 200, self.omdb.get(query["i"], {"Response": "False", "Error": "not found"})
        if parts.path.startswith("/3/search/"):
            kind = parts.path.rsplit("/", 1)[1]
            return 200, {"results": self.searches.get((kind, query["query"]), [])}
        kind, ident = parts.path.split("/")[2:4]
        found = self.details.get((kind, int(ident)))
        return (200, found) if found else (404, None)


def movie(ident, title, date, votes):
    return {"id": ident, "title": title, "original_title": title, "release_date": date, "vote_count": votes}


def show(ident, name, date, votes):
    return {"id": ident, "name": name, "original_name": name, "first_air_date": date, "vote_count": votes}


KARATE = {"id": 1885, "title": "The Karate Kid", "release_date": "1984-06-22", "runtime": 126,
          "overview": "Hounded by bullies, Daniel finds a mentor.", "poster_path": "/kk.jpg",
          "backdrop_path": "/kkb.jpg", "genres": [{"name": "Action"}, {"name": "Drama"}],
          "credits": {"cast": [{"name": n} for n in ("Ralph Macchio", "Pat Morita", "Elisabeth Shue",
                                                     "William Zabka", "Martin Kove")],
                      "crew": [{"job": "Director", "name": "John G. Avildsen"}, {"job": "Writer", "name": "X"}]},
          "external_ids": {"imdb_id": "tt0087538"}}
DARIA = {"id": 2004, "name": "Daria", "first_air_date": "1997-03-03", "episode_run_time": [22],
         "overview": "A smart, sardonic teen.", "poster_path": "/daria.jpg", "genres": [{"name": "Animation"}],
         "created_by": [{"name": "Glenn Eichler"}, {"name": "Susie Lewis"}],
         "credits": {"cast": [{"name": "Tracy Grandstaff"}]}, "external_ids": {"imdb_id": "tt0118298"}}


def http_for_karate_and_daria(**kw):
    return FakeHttp(
        searches={("movie", "The Karate Kid"): [movie(1885, "The Karate Kid", "1984-06-22", 5000),
                                                movie(38575, "The Karate Kid", "2010-06-10", 7000)],
                  ("tv", "Daria"): [show(2004, "Daria", "1997-03-03", 400), show(9, "Daria Morgendorffer", "", 1)]},
        details={("movie", 1885): KARATE, ("tv", 2004): DARIA}, **kw)


def sightings():
    return [ds.Sighting("Movie: The Karate Kid (1984)", T0 + 600, 126, MOVIE),
            ds.Sighting("Daria", T0, 30, TV),
            ds.Sighting("Daria", T0 + 1800, 30, TV),
            ds.Sighting("S2 E3 Apocalyptic Visions", T0, 45, TV)]


class TestSources(unittest.TestCase):

    def test_fast_programmes_in_the_window_with_their_slot_lengths(self):
        guide = {"base": T0, "titles": ["", "Terry and June", "Hard Knocks"],
                 "channels": {"samsung:A": [-10, 1, 20, 0, 30, 2, 90, 0, 40 * 60, 1, 40 * 60 + 30, 0]}}
        got = ds.fast_sightings(guide, T0, 30)
        self.assertEqual([("Terry and June", 30, TV), ("Hard Knocks", 60, None)],
                         [(s.raw, s.minutes, s.kind) for s in got])

    def test_a_pluto_film(self):
        s = ds.pluto_sighting({"title": "Righteous Kill", "start": "2026-09-28T04:37:11.000Z",
                               "stop": "2026-09-28T06:40:01.000Z",
                               "episode": {"name": "Righteous Kill", "duration": 6420000,
                                           "clip": {"originalReleaseDate": "1970-01-01T00:00:00.000Z"},
                                           "series": {"name": "Righteous Kill", "type": "live"}}})
        self.assertEqual((MOVIE, None, None), (s.kind, s.year, s.series), "1970 is Pluto's unknown")

    def test_a_pluto_release_year_counts_only_before_the_year_it_airs(self):
        def film(released):
            return ds.pluto_sighting({"title": "Zoolander", "start": "2026-09-28T04:00:00Z",
                                      "stop": "2026-09-28T05:40:00Z",
                                      "episode": {"name": "Zoolander", "clip": {"originalReleaseDate": released},
                                                  "series": {"name": "Zoolander", "type": "film"}}})
        self.assertEqual(2001, film("2001-09-28T00:00:00.000Z").year)
        self.assertIsNone(film("2026-03-01T00:00:00.000Z").year, "the date it joined Pluto")

    def test_a_pluto_series_episode_names_its_series(self):
        s = ds.pluto_sighting({"title": "Cosmic Vistas: Night Fire", "start": "2026-09-28T04:00:00Z",
                               "stop": "2026-09-28T04:30:00Z",
                               "episode": {"name": "Night Fire", "duration": 1620000,
                                           "series": {"name": "Cosmic Vistas", "type": "tv"}}})
        self.assertEqual((TV, "Cosmic Vistas"), (s.kind, s.series))

    def test_the_raw_title_falls_back_to_the_episode_name_like_the_app(self):
        s = ds.pluto_sighting({"title": " ", "start": "2026-09-28T04:00:00Z", "stop": "2026-09-28T05:00:00Z",
                               "episode": {"name": "Raw Deal", "series": {"name": "Raw Deal"}}})
        self.assertEqual("Raw Deal", s.raw)

    def test_pluto_channels_are_asked_once_each_and_failures_cost_only_themselves(self):
        lineup = [{"pluto": {"id": "a"}}, {"pluto": {"id": "a"}}, {"pluto": {"id": "b"}}, {"name": "x"}]
        replies = {"a": (200, {"timelines": [{"title": "Daria", "start": "2026-09-28T01:00:00Z",
                                              "stop": "2026-09-28T01:30:00Z"}]}), "b": (500, None)}
        asked = []

        def get(url, headers):
            ident = url.split("/channels/")[1].split("?")[0]
            asked.append(ident)
            return replies[ident]
        got = ds.pluto_sightings(lineup, T0, 30, get, sleep=lambda s: None, log=lambda m: None)
        self.assertEqual(["a", "b"], asked)
        self.assertEqual(["Daria"], [s.raw for s in got])


class TestPlan(unittest.TestCase):

    def test_a_series_name_from_pluto_is_searched_as_a_series(self):
        group = [ds.Sighting("Cosmic Vistas: Night Fire", T0, 27, TV, series="Cosmic Vistas")]
        self.assertEqual([bd.Query("Cosmic Vistas", TV)], bd.plan(group[0].raw, group, set()))

    def test_a_recurring_prefix_is_the_fallback(self):
        group = [ds.Sighting("The Super Simple Show - Animals", T0, 20, TV)]
        self.assertEqual([bd.Query("The Super Simple Show - Animals", TV), bd.Query("The Super Simple Show", TV)],
                         bd.plan(group[0].raw, group, {"the super simple show"}))

    def test_disagreeing_hints_are_dropped(self):
        group = [ds.Sighting("Line of Duty", T0, 120, MOVIE), ds.Sighting("Line of Duty", T0, 60, TV)]
        self.assertEqual([bd.Query("Line of Duty")], bd.plan("Line of Duty", group, set()))

    def test_a_movie_prefix_is_never_split(self):
        group = [ds.Sighting("Mission: Impossible", T0, 110, MOVIE)]
        self.assertEqual([bd.Query("Mission: Impossible", MOVIE)], bd.plan(group[0].raw, group, {"mission"}))


class TestChoose(unittest.TestCase):

    def test_the_year_picks_between_remakes(self):
        results = [(MOVIE, [movie(38575, "The Karate Kid", "2010-06-10", 7000),
                            movie(1885, "The Karate Kid", "1984-06-22", 5000)])]
        (kind, got), _ = dl.choose(results, "The Karate Kid", 1984)
        self.assertEqual((MOVIE, 1885), (kind, got["id"]))

    def test_remakes_without_a_year_are_ambiguous(self):
        results = [(MOVIE, [movie(38575, "The Karate Kid", "2010-06-10", 7000),
                            movie(1885, "The Karate Kid", "1984-06-22", 5000)])]
        self.assertEqual((None, "ambiguous"), dl.choose(results, "Karate Kid", None))

    def test_a_year_nobody_matches_is_a_miss_not_a_guess(self):
        results = [(MOVIE, [movie(1885, "The Karate Kid", "1984-06-22", 5000)])]
        self.assertEqual((None, "no exact title"), dl.choose(results, "The Karate Kid", 1999))

    def test_a_near_title_is_not_the_title(self):
        results = [(MOVIE, [movie(8, "The Karate Kid Part II", "1986-06-20", 3000)])]
        self.assertEqual((None, "no exact title"), dl.choose(results, "The Karate Kid", None))

    def test_the_film_and_the_series_of_one_name_need_the_kind(self):
        results = [(MOVIE, [movie(5, "Line of Duty", "2019-11-15", 300)]), (TV, [show(6, "Line of Duty", "2012-06-26", 1500)])]
        self.assertEqual((None, "ambiguous"), dl.choose(results, "Line of Duty", None))
        (kind, got), _ = dl.choose(results[:1], "Line of Duty", None)
        self.assertEqual((MOVIE, 5), (kind, got["id"]))

    def test_an_obscure_namesake_without_a_year_is_refused(self):
        results = [(TV, [show(7, "Homeful", "2024-01-01", 2)])]
        self.assertEqual((None, "too few votes to be sure"), dl.choose(results, "Homeful", None))


class TestRun(unittest.TestCase):

    def run_once(self, cache, http, now=T0, omdb=True):
        tmdb = dl.Tmdb("k" * 32, http, sleep=lambda s: None)
        rater = dl.Omdb("o", http) if omdb else None
        return bd.run(sightings(), cache, now, tmdb, rater, log=lambda m: None)

    def test_the_published_file(self):
        http = http_for_karate_and_daria(omdb={"tt0087538": {"Response": "True", "imdbRating": "7.3",
                                                             "Ratings": [{"Source": "Rotten Tomatoes", "Value": "90%"}]}})
        out = self.run_once({}, http)
        self.assertEqual("https://image.tmdb.org/t/p/", out["img"])
        karate = out["items"][out["titles"]["movie the karate kid 1984"]]
        self.assertEqual({"k": "m", "t": "The Karate Kid", "y": 1984, "p": "w500/kk.jpg", "b": "w780/kkb.jpg",
                          "o": "Hounded by bullies, Daniel finds a mentor.", "g": ["Action", "Drama"], "r": 126,
                          "c": ["Ralph Macchio", "Pat Morita", "Elisabeth Shue", "William Zabka"],
                          "d": ["John G. Avildsen"], "i": "7.3", "rt": "90%"}, karate)
        daria = out["items"][out["titles"]["daria"]]
        self.assertEqual(("t", ["Glenn Eichler", "Susie Lewis"], 22), (daria["k"], daria["d"], daria["r"]))
        self.assertNotIn("i", daria, "OMDb had nothing for it")
        self.assertEqual(2, len(out["titles"]), "the episode-only title is not published")

    def test_a_second_run_asks_tmdb_nothing(self):
        cache = {}
        self.run_once(cache, http_for_karate_and_daria())
        again = http_for_karate_and_daria()
        out = self.run_once(cache, again)
        self.assertEqual([], [u for u, _ in again.urls if "themoviedb" in u])
        self.assertEqual(2, len(out["items"]))

    def test_a_miss_is_remembered_and_asked_again_after_a_month(self):
        cache = {}
        http = FakeHttp()
        self.run_once(cache, http)
        self.assertTrue(all("miss" in e for e in cache["entries"].values()))
        quiet = FakeHttp()
        self.run_once(cache, quiet, now=T0 + 86400)
        self.assertEqual([], quiet.urls)
        later = FakeHttp()
        self.run_once(cache, later, now=T0 + (bd.MISS_DAYS + 1) * 86400)
        self.assertTrue(later.urls)

    def test_without_keys_the_cache_alone_is_published(self):
        cache = {}
        self.run_once(cache, http_for_karate_and_daria())
        out = bd.run(sightings(), cache, T0, None, None, log=lambda m: None)
        self.assertEqual(2, len(out["items"]))
        self.assertEqual({"generated": T0, "img": bd.IMG, "titles": {}, "items": []},
                         bd.run(sightings(), {}, T0, None, None, log=lambda m: None))

    def test_the_tmdb_budget_leaves_the_rest_for_the_next_run(self):
        cache, saved = {}, bd.TMDB_PER_RUN
        bd.TMDB_PER_RUN = 1
        try:
            out = self.run_once(cache, http_for_karate_and_daria())
        finally:
            bd.TMDB_PER_RUN = saved
        self.assertEqual(["daria"], list(out["titles"]), "the soonest airing goes first")
        self.assertEqual(1, len(cache["entries"]), "the other is not cached as a miss")

    def test_a_refused_tmdb_key_stops_lookups_without_failing_the_run(self):
        out = self.run_once({}, FakeHttp(status=401), omdb=False)
        self.assertEqual([], out["items"])


class TestRatings(unittest.TestCase):

    def found(self, n):
        return [{"t": "T%d" % i, "imdb": "tt%d" % i} for i in range(n)]

    def test_the_daily_budget_is_shared_between_runs(self):
        meta, saved = {}, bd.OMDB_PER_DAY
        bd.OMDB_PER_DAY = 3
        try:
            http = FakeHttp()
            self.assertEqual(2, bd.rate(self.found(2), meta, dl.Omdb("o", http), T0))
            self.assertEqual(1, bd.rate(self.found(5), meta, dl.Omdb("o", http), T0 + 60))
            self.assertEqual(3, bd.rate(self.found(5), meta, dl.Omdb("o", http), T0 + 86400), "a new UTC day")
        finally:
            bd.OMDB_PER_DAY = saved

    def test_the_limit_reply_stops_the_run(self):
        found = self.found(3)
        self.assertEqual(1, bd.rate(found, {}, dl.Omdb("o", FakeHttp(status=401)), T0, log=lambda m: None))
        self.assertTrue(all("rated" not in e for e in found), "asked again tomorrow")

    def test_rated_entries_are_not_asked_again_until_stale(self):
        found = [{"t": "A", "imdb": "tt1", "rated": T0}]
        http = FakeHttp()
        self.assertEqual(0, bd.rate(found, {}, dl.Omdb("o", http), T0 + 86400))
        self.assertEqual(1, bd.rate(found, {}, dl.Omdb("o", http), T0 + (bd.RATINGS_DAYS + 1) * 86400))

    def test_na_ratings_are_left_out(self):
        http = FakeHttp(omdb={"tt1": {"Response": "True", "imdbRating": "N/A", "Ratings": []}})
        self.assertEqual({}, dl.Omdb("o", http).ratings("tt1"))


class TestTmdbAuth(unittest.TestCase):

    def test_a_v3_key_is_a_parameter_and_a_v4_token_a_header(self):
        http = FakeHttp()
        dl.Tmdb("abc", http).search(MOVIE, "X")
        dl.Tmdb("eyJhbGciOi", http, sleep=lambda s: None).search(MOVIE, "X")
        (first, h1), (second, h2) = http.urls
        self.assertIn("api_key=abc", first)
        self.assertNotIn("Authorization", h1)
        self.assertNotIn("api_key", second)
        self.assertEqual("Bearer eyJhbGciOi", h2["Authorization"])


class TestMain(unittest.TestCase):
    """The whole script against a scratch repository, every HTTP call mocked."""

    def setUp(self):
        import tempfile
        self.dir = tempfile.TemporaryDirectory()
        self.repo = self.dir.name
        os.makedirs(os.path.join(self.repo, "tools", "details"))
        with open(os.path.join(self.repo, "live.json"), "w") as f:
            json.dump({"channels": [{"name": "P", "pluto": {"id": "p1"}}]}, f)
        now = int(__import__("time").time())
        with open(os.path.join(self.repo, "fast_guide.json"), "w") as f:
            json.dump({"base": now - now % 60, "titles": ["", "Daria"], "channels": {"samsung:A": [0, 1, 30, 0]}}, f)
        self.saved = bd.fetch_json, os.environ.get("TMDB_API_KEY"), os.environ.get("OMDB_API_KEY")
        os.environ["TMDB_API_KEY"], os.environ["OMDB_API_KEY"] = "k" * 32, "o"

    def tearDown(self):
        bd.fetch_json = self.saved[0]
        for name, value in zip(("TMDB_API_KEY", "OMDB_API_KEY"), self.saved[1:]):
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
        self.dir.cleanup()

    def read(self, name):
        with open(os.path.join(self.repo, name)) as f:
            return json.load(f)

    def test_writes_the_file_and_the_cache_and_skips_an_unchanged_rewrite(self):
        bd.fetch_json = http_for_karate_and_daria()
        self.assertEqual(0, bd.main(["--repo", self.repo]))
        self.assertEqual(["daria"], list(self.read("details.json")["titles"]))
        self.assertIn("tv|daria|", self.read(bd.CACHE_NAME)["entries"])
        stamp = os.path.getmtime(os.path.join(self.repo, "details.json"))
        self.assertEqual(0, bd.main(["--repo", self.repo]))
        self.assertEqual(stamp, os.path.getmtime(os.path.join(self.repo, "details.json")))

    def test_refuses_to_publish_when_the_sources_came_back_empty(self):
        with open(os.path.join(self.repo, "details.json"), "w") as f:
            json.dump({"generated": 1, "titles": {"a": 0, "b": 0, "c": 0, "d": 0, "e": 0}, "items": [{"t": "A"}]}, f)
        os.remove(os.path.join(self.repo, "fast_guide.json"))
        bd.fetch_json = FakeHttp()
        self.assertEqual(1, bd.main(["--repo", self.repo]))
        self.assertEqual(5, len(self.read("details.json")["titles"]))


class TestCacheFile(unittest.TestCase):

    def test_round_trip(self):
        import tempfile
        cache = {"omdb": {"day": "2026-09-28", "used": 3},
                 "entries": {"movie|x|": {"at": 1, "miss": "ambiguous"}, "tv|daria|": {"t": "Daria"}}}
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "c.json")
            bd.save_cache(cache, path)
            self.assertEqual(cache, bd.load_cache(path))
            bd.save_cache({"omdb": {}, "entries": {}}, path)
            self.assertEqual({"omdb": {}, "entries": {}}, bd.load_cache(path))


if __name__ == "__main__":
    unittest.main()
