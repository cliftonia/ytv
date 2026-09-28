#!/usr/bin/env python3
"""live_draft.json: the Pluto dial and the live FAST candidates as one LIVE TV lineup by genre.

Nothing here touches the network: guides are small XMLTV strings, Pluto replies are dicts shaped
like api.pluto.tv's, and the service listings are the same shape i.mjh.nz publishes.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import build_live

XMLTV = b"""<?xml version="1.0" encoding="UTF-8"?><tv>
  <channel id="USBC1"><display-name>Baywatch</display-name></channel>
  <programme channel="USBC1" start="20260928000000 +0000" stop="20260928010000 +0000">
    <title>Baywatch</title><sub-title>S01 E01</sub-title></programme>
  <programme channel="USBC1" start="20260928010000 +0000" stop="20260928030000 +0000">
    <title>Baywatch</title><sub-title>S01 E02</sub-title></programme>
  <programme channel="USMV1" start="20260928000000 +0000" stop="20260928020000 +0000">
    <title>Transformers: Dark of the Moon</title></programme>
</tv>"""


def pluto(name, pid="a" * 24, number=1, genre="Movies", region="us"):
    """A pluto.json channel and its allowlist entry."""
    channel = {"number": number, "name": name, "kind": "live",
               "streams": [{"url": "https://jmp2.uk/plu-%s.m3u8" % pid, "duration": 600, "title": name}],
               "pluto": {"id": pid, "region": region}}
    return channel, {"number": number, "id": pid, "name": name, "genre": genre}


def fast(name, source="us_samsung", route="direct", category="Other", tvg_id=None, **extra):
    c = {"name": name, "source": source, "tvg_id": tvg_id, "category": category,
         "url": "https://%s.example/%s.m3u8" % (source, name.replace(" ", "")), "route": route,
         "iptv_categories": []}
    c.update(extra)
    return c


def service_channel(name, region="us", titles=None, groups=(), kinds=None):
    return {"name": name, "region": region, "groups": list(groups), "description": "",
            "titles": titles or {}, "kinds": kinds or {}}


def guides(samsung=None, plex=None, roku=None, pluto_guides=None, iptv=()):
    return build_live.Guides({"samsung": samsung or {}, "plex": plex or {}, "roku": roku or {}},
                             pluto_guides or {}, iptv)


def draft_channel(name, source="us_samsung", hint="Other", guide="none", route="direct", **extra):
    c = {"name": name, "source": source, "hint": hint, "guide": guide, "guide_id": None,
         "route": route, "iptv_categories": []}
    c.update(extra)
    return c


class TestGuides(unittest.TestCase):

    def test_xmltv_counts_minutes_by_kind_and_titles_only_for_series(self):
        channels = build_live.parse_xmltv(XMLTV)
        self.assertEqual("Baywatch", channels["USBC1"]["name"])
        self.assertEqual({"Baywatch": 180}, channels["USBC1"]["titles"])
        self.assertEqual({"tv": 180}, channels["USBC1"]["kinds"])
        # A film's title names no show: the Transformers film is not the cartoon.
        self.assertEqual({}, channels["USMV1"]["titles"])
        self.assertEqual({"film": 120}, channels["USMV1"]["kinds"])

    def test_pluto_guide_reads_genres_kinds_and_series(self):
        reply = {"category": "Movies", "timelines": [
            {"title": "Columbo", "start": "2026-09-28T00:00:00.000Z", "stop": "2026-09-28T01:30:00.000Z",
             "episode": {"number": 5, "season": 3, "genre": "Crime", "subGenre": "Crime",
                         "series": {"name": "Columbo", "type": "live"}}},
            {"title": "Raw Deal", "start": "2026-09-28T01:30:00.000Z", "stop": "2026-09-28T03:30:00.000Z",
             "episode": {"number": 1, "season": 1, "genre": "Action & Adventure", "subGenre": "Action",
                         "series": {"name": "Raw Deal", "type": "live"}}},
        ]}
        guide = build_live.pluto_guide(reply)
        self.assertEqual({"tv": 90, "film": 120}, guide["kinds"])
        self.assertEqual({"Columbo": 90}, guide["titles"])
        self.assertEqual({"Crime/Crime": 90, "Action & Adventure/Action": 120}, guide["genres"])
        self.assertEqual("Movies", guide["category"])

    def test_service_listings_keep_region_and_group(self):
        listing = {"regions": {"us": {"channels": {"USBC1": {"name": "Baywatch", "group": "Action & Drama"}}},
                               "gb": {"channels": {"GBBC1": {"name": "Baywatch", "group": "Drama"}}}}}
        channels = build_live.service_channels("samsung", listing, build_live.parse_xmltv(XMLTV))
        self.assertEqual("us", channels["USBC1"]["region"])
        self.assertEqual(["Action & Drama"], channels["USBC1"]["groups"])
        self.assertEqual({"Baywatch": 180}, channels["USBC1"]["titles"])
        self.assertEqual("gb", channels["GBBC1"]["region"])


class TestGuideMatching(unittest.TestCase):

    def setUp(self):
        self.guides = guides(
            samsung={"USBC1": service_channel("Baywatch", "us"), "GBPT1": service_channel("Pointless", "gb")},
            plex={"6a16-60e737a4": service_channel("AfroLand", "us"),
                  "6a16-bbb": service_channel("Scares by Shudder", "us", titles={"Creepshow": 60})},
            roku={"r1": service_channel("This Old House", None)},
            iptv=[{"channel": "AfroLandTV.us", "site": "plex.tv", "site_id": "60e737a4"},
                  {"channel": "HiYAH.us", "site": "xumo.tv", "site_id": "0#9995106"}])

    def test_same_service_same_region_by_name(self):
        self.assertEqual(("samsung", "USBC1", "name"), self.guides.match(fast("Baywatch")))
        self.assertEqual(("roku", "r1", "name"), self.guides.match(fast("This Old House", "us_roku")))

    def test_never_another_services_or_regions_guide(self):
        # Tubi's Baywatch is not Samsung's: the schedules differ.
        self.assertEqual(("none", None, None), self.guides.match(fast("Baywatch", "us_tubi")))
        # A US Samsung channel does not take the UK lineup's guide.
        self.assertEqual(("none", None, None), self.guides.match(fast("Pointless", "us_samsung")))
        self.assertEqual(("samsung", "GBPT1", "name"), self.guides.match(fast("Pointless", "uk_samsung")))
        # Samsung publishes no Australian lineup.
        self.assertEqual(("none", None, None), self.guides.match(fast("Baywatch", "au_samsung")))

    def test_plex_by_tvg_id_and_xumo_by_its_iptv_org_id(self):
        self.assertEqual(("plex", "6a16-60e737a4", "tvg-id"),
                         self.guides.match(fast("AfroLandTV", "us_plex", tvg_id="AfroLandTV.us@SD")))
        self.assertEqual(("xumo", "0#9995106", "tvg-id"),
                         self.guides.match(fast("Hi-Yah!", "us_xumo", tvg_id="HiYAH.us@SD")))
        self.assertEqual(("xumo", None, "source"), self.guides.match(fast("Lassie", "us_xumo")))

    def test_plex_lists_one_channel_per_region_and_that_is_no_tie(self):
        g = guides(plex={"aaa-66bf": service_channel("RCM", "us", kinds={"film": 600}),
                         "bbb-66bf": service_channel("RCM", "ca", kinds={"film": 600})})
        evidence = g.evidence(draft_channel("RCM", "au_samsung"))
        self.assertEqual({"film": 600}, evidence["kinds"])
        self.assertEqual("Plex's RCM", evidence["via"])

    def test_a_shared_name_matches_nothing(self):
        g = guides(samsung={"US1": service_channel("Crime", "us"), "US2": service_channel("Crime", "us")})
        self.assertEqual(("none", None, None), g.match(fast("Crime")))

    def test_borrowed_evidence_is_labelled_and_never_becomes_the_guide(self):
        channel = draft_channel("Scares by Shudder", "us_xumo", guide="xumo")
        evidence = self.guides.evidence(channel)
        self.assertEqual({"Creepshow": 60}, evidence["titles"])
        self.assertEqual("Plex's Scares by Shudder", evidence["via"])
        self.assertEqual("xumo", channel["guide"])


class TestMerge(unittest.TestCase):

    def test_near_duplicates_share_a_key(self):
        key = build_live.merge_key
        self.assertEqual(key("Pointless"), key("Pointless UK"))
        self.assertEqual(key("AFV"), key("AFV with Alfonso Ribeiro"))
        self.assertEqual(key("Hoarders"), key("Hoarders by A&E"))
        self.assertEqual(key("Tennis Channel 2"), key("TennisChannel 2"))
        self.assertNotEqual(key("Pluto TV Horror"), key("Horror by ALTER"))
        self.assertNotEqual(key("Tennis Channel"), key("Tennis+"))
        self.assertNotEqual(key("Pluto TV Crime"), key("Crime 24/7"))

    def test_keeps_pluto_then_a_guide_then_direct(self):
        pool = [draft_channel("Pointless UK", route="us"),
                draft_channel("Pointless", "au_samsung", route="direct"),
                draft_channel("AFV", "us_plex", route="direct"),
                draft_channel("AFV with Alfonso Ribeiro", "us_samsung", route="us", guide="samsung"),
                draft_channel("Vevo Pop UK", "uk_samsung", guide="samsung"),
                draft_channel("Vevo Pop", "pluto", guide="pluto", route="pluto")]
        kept, merges = build_live.merge_duplicates(pool)
        self.assertEqual(["Pointless", "AFV with Alfonso Ribeiro", "Vevo Pop"], [c["name"] for c in kept])
        self.assertEqual([("Pointless", ["Pointless UK"]), ("AFV with Alfonso Ribeiro", ["AFV"]),
                          ("Vevo Pop", ["Vevo Pop UK"])], merges)
        self.assertEqual([{"name": "AFV", "source": "us_plex"}], kept[1]["merged"])

    def test_the_same_feed_under_another_name_merges(self):
        key = build_live.merge_key
        self.assertEqual(key("BBC Impossible"), key("Impossible Quiz Show"))
        self.assertEqual(key("Born to Kill"), key("True Lives"))
        self.assertEqual(key("WWE Superstar Central"), key("Wrestling Legends TV"))
        pool = [draft_channel("Wrestling Legends TV", "pluto", guide="pluto", route="pluto"),
                draft_channel("WWE Superstar Central", guide="samsung")]
        self.assertEqual([("Wrestling Legends TV", ["WWE Superstar Central"])],
                         build_live.merge_duplicates(pool)[1])

    def test_two_pluto_channels_are_never_merged(self):
        pool = [draft_channel("Tough Jobs", "pluto", guide="pluto", route="pluto"),
                draft_channel("Pluto TV Tough Jobs", "pluto", guide="pluto", route="pluto")]
        kept, merges = build_live.merge_duplicates(pool)
        self.assertEqual(2, len(kept))
        self.assertEqual([], merges)


class TestClassify(unittest.TestCase):

    def classify(self, channel, evidence=None):
        return build_live.place(channel, evidence)

    def test_single_show_channels_go_with_their_show(self):
        self.assertEqual(("Series", "Action"), self.classify(draft_channel("Baywatch"))[:2])
        self.assertEqual(("Game Shows", "Game Shows"), self.classify(draft_channel("Pointless"))[:2])
        self.assertEqual(("Documentaries", "Motoring"), self.classify(draft_channel("Top Gear"))[:2])
        self.assertEqual(("Sitcoms (USA)", "Sitcoms"), self.classify(draft_channel("The Conners"))[:2])
        self.assertIn('the show "baywatch"', self.classify(draft_channel("Baywatch"))[2])

    def test_a_show_inside_a_longer_name_does_not_mislead(self):
        self.assertEqual(("Anime", "Anime"), self.classify(draft_channel("Hunter x Hunter"))[:2])
        self.assertEqual(("Series", "Drama"), self.classify(draft_channel("Designated Survivor"))[:2])

    def test_genre_words_are_films_or_series_by_the_guide(self):
        films = {"titles": {}, "genres": {}, "groups": [], "kinds": {"film": 600}, "via": None}
        series = {"titles": {"Starhunter": 600}, "genres": {}, "groups": [], "kinds": {"tv": 600}, "via": None}
        self.assertEqual(("Movies", "Sci-Fi"), self.classify(draft_channel("Pluto TV Sci-Fi"), films)[:2])
        self.assertEqual(("Series", "Sci-Fi & Fantasy"), self.classify(draft_channel("Pluto TV Sci-fi Series"), series)[:2])
        self.assertEqual(("Movies", "Westerns"), self.classify(draft_channel("Cowboy Movie Channel"))[:2])

    def test_a_guide_of_one_kind_of_show_decides(self):
        columbo = {"titles": {"Columbo": 600}, "genres": {}, "groups": [], "kinds": {"tv": 600}, "via": None}
        block, sub, why = self.classify(draft_channel("Universal Crime", "pluto", hint="Movies"), columbo)
        self.assertEqual(("Series", "Crime"), (block, sub))
        self.assertIn("Columbo", why)

    def test_a_name_genre_beats_a_guide_that_contradicts_it(self):
        marathon = {"titles": {"Colonel March of Scotland Yard": 460}, "genres": {}, "groups": [],
                    "kinds": {"tv": 460, "film": 140}, "via": None}
        channel = draft_channel("Pluto TV Horror", "pluto", hint="Movies")
        self.assertEqual(("Movies", "Horror"), self.classify(channel, marathon)[:2])

    def test_pluto_genres_place_a_name_that_says_nothing(self):
        thrills = {"titles": {}, "genres": {"Thriller/Thriller": 500, "Drama/Crime Drama": 100}, "groups": [],
                   "kinds": {"film": 600}, "via": None}
        self.assertEqual(("Movies", "Thriller"), self.classify(draft_channel("Suspense Vault", "pluto"), thrills)[:2])

    def test_plutos_rotating_picks_are_mixed_whatever_the_guide_samples(self):
        staff = {"titles": {}, "genres": {"Thriller/Thriller": 500, "Drama/Crime Drama": 100}, "groups": [],
                 "kinds": {"film": 600}, "via": None}
        self.assertEqual(("Movies", "Movies – Mixed"),
                         self.classify(draft_channel("Pluto TV Staff Picks", "pluto"), staff)[:2])

    def test_sub_blocks_from_the_name(self):
        self.assertEqual(("Music", "80s"), self.classify(draft_channel("Stingray Remember the 80s"))[:2])
        self.assertEqual(("Music", "Country"), self.classify(draft_channel("XITE Country Today"))[:2])
        self.assertEqual(("Sports", "Combat"), self.classify(draft_channel("Bellator MMA"))[:2])
        self.assertEqual(("Relax", "Relax"), self.classify(draft_channel("Fireplace Vibes"))[:2])
        self.assertEqual(("Music", "Other"), self.classify(draft_channel("XITE Reggae Vibes"))[:2])

    def test_single_words_that_are_not_a_genre_place_nothing(self):
        # "love", "heart", "wedo", "alter" and "fury" once chose Romance, Horror or Martial Arts.
        self.assertEqual(("Movies", build_live.MIXED_MOVIES),
                         self.classify(draft_channel("wedo movies", hint="Movies"))[:2])
        self.assertEqual(("Series", "Drama"),
                         self.classify(draft_channel("Love 2 Hate TV", hint="Drama & Series"))[:2])
        self.assertNotEqual("Martial Arts", self.classify(draft_channel("Flicks of Fury", hint="Movies"))[1])
        # A phrase that is a genre still is.
        self.assertEqual(("Movies", "Romance"),
                         self.classify(draft_channel("Lifetime Movies Love & Drama", hint="Movies"))[:2])

    def test_pluto_genres_still_find_martial_arts_without_the_word(self):
        kicks = {"titles": {}, "genres": {"Action & Adventure/Martial Arts": 630,
                                          "Action & Adventure/Adventures": 150}, "groups": [],
                 "kinds": {"film": 780}, "via": None}
        self.assertEqual(("Movies", "Martial Arts"),
                         self.classify(draft_channel("Flicks of Fury", "pluto", hint="Movies"), kicks)[:2])

    def test_a_weak_word_yields_to_a_guide_of_several_shows(self):
        sitcoms = {"titles": {"The Jeffersons": 240, "Webster": 150, "Sister, Sister": 120, "Soul!": 390},
                   "genres": {}, "groups": [], "kinds": {"tv": 900}, "via": None}
        self.assertEqual(("Sitcoms (USA)", "Sitcoms"),
                         self.classify(draft_channel("BET Classics", "pluto", hint="Classic TV"), sitcoms)[:2])
        scifi = {"titles": {"Snowpiercer": 300, "The Librarians": 200, "The 100": 100}, "genres": {},
                 "groups": [], "kinds": {"tv": 600}, "via": None}
        self.assertEqual(("Series", "Sci-Fi & Fantasy"),
                         self.classify(draft_channel("Pluto TV Adventure", "pluto", hint="Movies"), scifi)[:2])

    def test_a_weak_word_stands_against_one_shows_marathon(self):
        marathon = {"titles": {"Beauty and the Beast": 500, "7th Heaven": 100}, "genres": {}, "groups": [],
                    "kinds": {"tv": 600}, "via": None}
        self.assertEqual(("Series", "Classic Drama"),
                         self.classify(draft_channel("CW FOREVER", hint="Classic TV"), marathon)[:2])
        # A decade's films stay a decade's, whatever genre twelve hours of them sample.
        comedies = {"titles": {}, "genres": {"Comedy/Comedy": 500, "Drama/Drama": 100}, "groups": [],
                    "kinds": {"film": 600}, "via": None}
        self.assertEqual(("Movies", "Decades"),
                         self.classify(draft_channel("80s Rewind", "pluto", hint="Classic TV"), comedies)[:2])

    def test_westerns_and_the_walking_dead_have_their_own_series_blocks(self):
        self.assertEqual(("Series", "Westerns"), self.classify(draft_channel("Death Valley Days"))[:2])
        self.assertEqual(("Series", "Westerns"), self.classify(draft_channel("Wild West TV"))[:2])
        self.assertEqual(("Series", "Sci-Fi & Fantasy"),
                         self.classify(draft_channel("The Walking Dead Universe"))[:2])

    def test_big_sub_blocks_split_by_name_show_and_guide(self):
        self.assertEqual(("Series", "Judge & Talk"), self.classify(draft_channel("Judge Nosey", hint="Reality"))[:2])
        self.assertEqual(("Series", "Dating Reality"),
                         self.classify(draft_channel("WBTV Love and Marriage", hint="Drama & Series"))[:2])
        self.assertEqual(("Series", "Competition Reality"), self.classify(draft_channel("Survivor"))[:2])
        self.assertEqual(("Series", "K-Drama"),
                         self.classify(draft_channel("K Stories by CJ ENM", hint="Drama & Series"))[:2])
        self.assertEqual(("Series", "Classic Drama"),
                         self.classify(draft_channel("Shout! TV"), {"titles": {}, "genres": {}, "kinds": {"tv": 60},
                                                                  "groups": ["Western & Classic TV"], "via": None})[:2])
        self.assertEqual(("Documentaries", "Cops & Courts"), self.classify(draft_channel("Live PD Presents"))[:2])
        self.assertEqual(("Documentaries", "Forensics & Cold Cases"),
                         self.classify(draft_channel("Forensic Files"))[:2])
        self.assertEqual(("Documentaries", "Pets & Vets"), self.classify(draft_channel("Rovr Pets"))[:2])
        self.assertEqual(("Food, Home & Travel", "Food Reality"), self.classify(draft_channel("Hell's Kitchen"))[:2])
        self.assertEqual(("Food, Home & Travel", "Antiques"), self.classify(draft_channel("Antiques Roadshow UK"))[:2])
        self.assertEqual(("Food, Home & Travel", "Crafts & Garden"),
                         self.classify(draft_channel("Epic Gardening TV"))[:2])
        pawn = {"titles": {"Auction Hunters": 300, "Shipping Wars": 200, "Beverly Hills Pawn": 100}, "genres": {},
                "groups": [], "kinds": {"tv": 600}, "via": None}
        block, sub, why = self.classify(draft_channel("Spike Pluto TV", "pluto", hint="Drama & Series"), pawn)
        self.assertEqual(("Series", "Pawn & Deals"), (block, sub))
        self.assertIn("pawn & deals", why)

    def test_one_shows_marathon_does_not_split_a_sub_block(self):
        teen = {"titles": {"Edgemont": 600}, "genres": {}, "groups": [], "kinds": {"tv": 600}, "via": None}
        self.assertEqual(("Series", "Drama"),
                         self.classify(draft_channel("Pluto TV Drama", "pluto", hint="Drama & Series"), teen)[:2])

    def test_kids_channels_go_where_their_guide_says(self):
        toons = {"titles": {"Hey Arnold!": 300, "The Angry Beavers": 300}, "genres": {}, "groups": [],
                 "kinds": {"tv": 600}, "via": None}
        self.assertEqual(("Cartoons & Kids", "Cartoons"),
                         self.classify(draft_channel("90's Kids", "pluto", hint="Kids"), toons)[:2])

    def test_music_decades_merge_and_concerts_split(self):
        self.assertEqual("60s & 70s", self.classify(draft_channel("Stingray Jukebox Oldies"))[1])
        self.assertEqual("90s & 2000s", self.classify(draft_channel("Stingray Y2K"))[1])
        self.assertEqual("Concerts & Live", self.classify(draft_channel("Qello Concerts"))[1])

    def test_off_format_channels_are_flagged_off(self):
        block, sub, why = self.classify(draft_channel("SportsGrid"))
        self.assertEqual((build_live.FLAGGED, build_live.FLAGGED), (block, sub))
        self.assertIn("betting", why)
        self.assertIn(build_live.FLAGGED, build_live.OFF_BY_DEFAULT)

    def test_a_guide_of_films_with_no_group_is_a_film_channel(self):
        films = {"titles": {}, "genres": {}, "groups": [], "kinds": {"film": 600}, "via": "Plex's RCM"}
        self.assertEqual(("Movies", build_live.MIXED_MOVIES), self.classify(draft_channel("RCM"), films)[:2])
        # A podcast of hour-long episodes has a group, and stays unsorted.
        talk = dict(films, groups=["Lifestyle & Pop Culture"], via=None)
        self.assertEqual("Unsorted", self.classify(draft_channel("The Diary Of A CEO"), talk)[0])

    def test_the_category_is_the_last_word_then_unsorted(self):
        self.assertEqual(("Documentaries", "Outdoors"),
                         self.classify(draft_channel("Equinox Nine", hint="Outdoors"))[:2])
        block, sub, why = self.classify(draft_channel("Jupiter TV"))
        self.assertEqual(("Unsorted", "Unsorted"), (block, sub))
        self.assertTrue(why)


class TestNumbers(unittest.TestCase):

    def channel(self, name, block, sub, source="us_samsung"):
        return draft_channel(name, source, block=block, sub=sub, why="")

    def test_blocks_on_hundreds_and_subs_on_tens_with_gaps(self):
        channels = ([self.channel("A%d" % i, "Movies", "Action") for i in range(10)]
                    + [self.channel("C1", "Movies", "Comedy"), self.channel("S1", "Sports", "General"),
                       self.channel("R1", "Relax", "Relax")])
        blocks = build_live.number(channels)
        by_name = {c["name"]: c["number"] for c in channels}
        self.assertEqual(100, by_name["A0"])
        self.assertEqual(109, by_name["A9"])
        self.assertEqual(120, by_name["C1"])  # 110-119 would leave no gap after Action
        self.assertEqual(200, by_name["S1"])
        self.assertEqual(300, by_name["R1"])
        self.assertEqual([("Movies", 100), ("Sports", 200), ("Relax", 300)],
                         [(b["name"], b["first_number"]) for b in blocks])
        self.assertEqual([{"name": "Action", "first_number": 100}, {"name": "Comedy", "first_number": 120}],
                         blocks[0]["sub"])
        self.assertEqual([True, True, False], [b["default"] for b in blocks])

    def test_a_block_over_a_hundred_takes_the_next_hundreds(self):
        channels = ([self.channel("A%03d" % i, "Movies", "Action") for i in range(95)]
                    + [self.channel("C%d" % i, "Movies", "Comedy") for i in range(8)]
                    + [self.channel("S1", "Series", "Comedy")])
        build_live.number(channels)
        by_name = {c["name"]: c["number"] for c in channels}
        self.assertEqual(200, by_name["C0"])
        self.assertEqual(300, by_name["S1"])

    def test_a_sub_block_runs_alphabetically_whatever_the_source(self):
        channels = [dict(self.channel("Zebra", "Movies", "Action", "pluto"), guide="pluto", route="pluto"),
                    self.channel("The Conners", "Movies", "Action"),
                    self.channel("aardvark", "Movies", "Action", "us_tubi")]
        build_live.number(channels)
        # "The" is ignored and case does not count: aardvark, (The) Conners, Zebra.
        self.assertEqual([102, 101, 100], [c["number"] for c in channels])

    def test_a_finer_sub_block_needs_three_channels_or_folds_back(self):
        channels = ([self.channel("J%d" % i, "Series", "Judge & Talk") for i in range(2)]
                    + [self.channel("P%d" % i, "Series", "Pawn & Deals") for i in range(3)]
                    + [self.channel("W1", "Series", "Westerns")])
        build_live.fold_finer_subs(channels)
        self.assertEqual(["Reality", "Reality", "Pawn & Deals", "Pawn & Deals", "Pawn & Deals", "Action"],
                         [c["sub"] for c in channels])
        self.assertIn("too few Judge & Talk channels, so Reality", channels[0]["why"])

    def test_every_finer_sub_block_is_on_the_dial(self):
        for (block, finer), (parent_block, parent) in build_live.FINER_PARENT.items():
            subs = dict(build_live.BLOCKS)[block]
            self.assertIn(finer, subs)
            self.assertIn(parent, subs)

    def test_extra_movie_subs_need_three_channels(self):
        channels = ([self.channel("K%d" % i, "Movies", "Martial Arts") for i in range(3)]
                    + [self.channel("D1", "Movies", "Decades")])
        kept = build_live.fold_movie_subs(channels)
        self.assertEqual(["Martial Arts"], kept)
        self.assertEqual(build_live.MIXED_MOVIES, channels[3]["sub"])
        self.assertIn("so Mixed", channels[3]["why"])


class TestRecord(unittest.TestCase):

    def test_pluto_keeps_its_play_data(self):
        dial, allow = pluto("Pluto TV Action", "b" * 24, genre="Movies", region="uk")
        channel = build_live.pluto_channels([dial], [allow])[0]
        channel.update(number=100, block="Movies", sub="Action", why="name")
        out = build_live.record(channel)
        self.assertEqual({"id": "b" * 24, "region": "uk"}, out["pluto"])
        self.assertEqual(dial["streams"], out["streams"])
        self.assertEqual(("pluto", "b" * 24), (out["guide"], out["guide_id"]))
        self.assertFalse(out["home_only"])
        self.assertTrue(out["default"])
        self.assertNotIn("url", out)

    def test_fast_keeps_url_route_and_headers(self):
        candidate = fast("Needy", "us_tubi", route="us", headers={"Referer": "https://needy.example/"})
        channel = build_live.fast_channels([candidate], guides())[0]
        channel.update(number=1800, block="Unsorted", sub="Unsorted", why="nothing")
        out = build_live.record(channel)
        self.assertEqual((candidate["url"], "us"), (out["url"], out["route"]))
        self.assertEqual({"Referer": "https://needy.example/"}, out["headers"])
        self.assertTrue(out["home_only"])
        self.assertFalse(out["default"])
        self.assertEqual(("none", None), (out["guide"], out["guide_id"]))


class TestBuild(unittest.TestCase):

    def test_the_whole_draft_from_its_inputs(self):
        dial, allow = [], []
        for number, name, genre in [(1, "Pluto TV Westerns", "Movies"), (2, "Pluto TV Kids", "Kids")]:
            d, a = pluto(name, "%024d" % number, number, genre)
            dial.append(d)
            allow.append(a)
        candidates = [fast("Pointless UK", "uk_samsung", route="us"), fast("Pointless", "au_samsung"),
                      fast("Stingray Classic Rock", "ca_stingray"), fast("Fireplace Vibes", "uk_samsung")]
        channels, blocks, merges = build_live.build(dial, allow, candidates, guides())
        self.assertEqual([("Pointless", ["Pointless UK"])], merges)
        placed = {c["name"]: (c["number"], c["block"], c["sub"]) for c in channels}
        self.assertEqual((100, "Movies", "Westerns"), placed["Pluto TV Westerns"])
        self.assertEqual((200, "Game Shows", "Game Shows"), placed["Pointless"])
        self.assertEqual("Cartoons & Kids", placed["Pluto TV Kids"][1])
        self.assertEqual(("Music", "Rock"), placed["Stingray Classic Rock"][1:])
        self.assertEqual("Relax", blocks[-1]["name"])
        self.assertEqual(sorted(c["number"] for c in channels), [c["number"] for c in channels])
        md = build_live.summary(channels, blocks, merges)
        self.assertIn("Pointless <- Pointless UK", md)
        self.assertIn("| Game Shows | Game Shows | 200-200 | 1 |", md)


if __name__ == "__main__":
    unittest.main()
