#!/usr/bin/env python3
"""Draft the LIVE TV dial: the Pluto dial and the live FAST candidates as one lineup, by genre.

  python3 build_live.py [--cache DIR]      fetch the guides (or reuse DIR's copies), write the draft

A DRAFT for the owner to read, not a dial: it writes live_draft.json and live_draft.md here and
nothing the app reads. pluto.json, channels.json and fast_candidates.json are inputs only.

The steps, in order:
  1. every Pluto dial channel (pluto.json, with its allowlist genre) and every live FAST candidate
  2. each channel's programme guide, when its OWN service publishes one: Pluto's per-channel
     guide, and i.mjh.nz's Samsung TV Plus, Plex and Roku guides for channels from those services
  3. merge near-duplicates the FAST pass missed ("Pointless" and "Pointless UK"), keeping Pluto,
     then a channel with a guide, then one that plays direct
  4. a block and sub-block for every channel, with a one-line `why`: a known show or a word in
     the name first, then what the guide airs, then the Pluto genre or iptv-org category; then a
     finer sub-block where a big one splits (REFINE), and off-format channels flagged off
  5. numbers by block (each on a round hundred) and sub-block (each on a round ten), with gaps,
     alphabetical within a sub-block

Guides are matched only within one service: a Samsung channel gets Samsung's guide or none, never
Plex's channel of the same name, whose schedule is not the same. What a guide airs is a hint about
genre, though, so a channel without its own guide may still be CLASSIFIED by what another
service's same-named channel airs; its `why` says so, and its `guide` stays `none`.

Fetches i.mjh.nz, api.pluto.tv and iptv-org.github.io - never api.github.com, whose 60/h limit
the home IP shares with the televisions' update check.
"""
import argparse
import collections
import concurrent.futures
import datetime
import gzip
import io
import json
import os
import re
import sys
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET

import build_fast
from build_fast import has_word, norm_name

HERE = os.path.dirname(os.path.abspath(__file__))
PLUTO_DIAL = os.path.join(HERE, "..", "pluto.json")
PLUTO_ALLOWLIST = os.path.join(HERE, "pluto_lineup.json")
FAST_CANDIDATES = os.path.join(HERE, "fast_candidates.json")
OUT_JSON = os.path.join(HERE, "live_draft.json")
OUT_SUMMARY = os.path.join(HERE, "live_draft.md")
GUIDE_IDS = os.path.join(HERE, "guide_ids.json")

MJH = "https://i.mjh.nz/%s/%s"
# service -> i.mjh.nz folder. all.xml.gz is the guide; .channels.json is the same service's own
# channel list, which adds what the XML lacks: each channel's genre group and its description.
MJH_SERVICES = {"samsung": "SamsungTVPlus", "plex": "Plex", "roku": "Roku"}
IPTV_GUIDES = "https://iptv-org.github.io/api/guides.json"
PLUTO_GUIDE = "https://api.pluto.tv/v2/channels/%s?start=%s&stop=%s"
PLUTO_GUIDE_HOURS = 12

# FAST source -> (guide service, the region its channels are listed under there). Samsung's ids
# begin with their country; Samsung publishes no Australian lineup, so au_samsung has no guide.
# Tubi, Rakuten and Stirr publish guides (build_fast_guide.py reads them) but no channel list this
# draft could name-match against from here - Tubi's answers only in the US - so their ids come
# from guide_ids.json alone.
SOURCE_GUIDES = {
    "us_samsung": ("samsung", "us"), "uk_samsung": ("samsung", "gb"),
    "us_plex": ("plex", "us"), "us_roku": ("roku", None), "us_xumo": ("xumo", None),
    "us_tubi": ("tubi", None), "uk_rakuten": ("rakuten", None), "us_stirr": ("stirr", None),
}

# Guides a channel's own service publishes, and fast_guide.json carries.
FAST_GUIDES = ("samsung", "plex", "roku", "xumo", "tubi", "rakuten", "stirr")


# ---- guides -------------------------------------------------------------------------------------

# A programme this long with no episode number is a film. The XMLTV guides carry no genres, so
# this and the titles are all they say about what a channel is.
FILM_MINUTES = 75


def parse_xmltv(data):
    """channel id -> {"name", "titles", "kinds"} for an XMLTV guide's bytes: minutes on air by
    kind - `film` or `tv` - and, for series, by title."""
    channels = {}

    def entry_for(cid):
        return channels.setdefault(cid, {"name": "", "titles": {}, "kinds": {}})

    for _, element in ET.iterparse(io.BytesIO(data), events=("end",)):
        if element.tag == "channel":
            entry_for(element.get("id"))["name"] = (element.findtext("display-name") or "").strip()
            element.clear()
        elif element.tag == "programme":
            entry = entry_for(element.get("channel"))
            title = (element.findtext("title") or "").strip()
            if title:
                minutes = xmltv_minutes(element.get("start"), element.get("stop"))
                episodic = element.find("episode-num") is not None or element.find("sub-title") is not None
                kind = "film" if minutes >= FILM_MINUTES and not episodic else "tv"
                # A film's title names no show: "Transformers: Dark of the Moon" is not the cartoon.
                if kind == "tv":
                    entry["titles"][title] = entry["titles"].get(title, 0) + minutes
                entry["kinds"][kind] = entry["kinds"].get(kind, 0) + minutes
            element.clear()
    return channels


def xmltv_minutes(start, stop):
    """Whole minutes between two XMLTV times (`20260928010918 +0000`); 0 when either is odd."""
    try:
        fmt = "%Y%m%d%H%M%S %z"
        delta = datetime.datetime.strptime(stop, fmt) - datetime.datetime.strptime(start, fmt)
        return max(0, int(delta.total_seconds() // 60))
    except (TypeError, ValueError):
        return 0


def service_channels(service, listing, xml_channels):
    """One service's guide channels: id -> {"name", "region", "groups", "description", "titles"}.
    [listing] is its .channels.json, which says what the XML does not - region, genre group and
    description - and [xml_channels] is parse_xmltv's result."""
    out = {}

    def add(cid, info, region, groups):
        xml = xml_channels.get(cid, {})
        out[cid] = {"name": info.get("name") or xml.get("name", ""), "region": region,
                    "groups": [g for g in groups if g], "description": info.get("description") or "",
                    "titles": xml.get("titles", {}), "kinds": xml.get("kinds", {})}

    if service == "samsung":
        for region, block in (listing.get("regions") or {}).items():
            for cid, info in (block.get("channels") or {}).items():
                add(cid, info, region, [info.get("group")])
    elif service == "plex":
        for cid, info in (listing.get("channels") or {}).items():
            regions = info.get("regions") or []
            add(cid, info, "us" if "us" in regions else (regions[0] if regions else None), [])
    else:
        for cid, info in (listing.get("channels") or {}).items():
            add(cid, info, None, info.get("groups") or [])
    return out


def iso_time(text):
    return datetime.datetime.strptime(text[:19], "%Y-%m-%dT%H:%M:%S")


def pluto_guide(reply):
    """What a Pluto channel airs, from api.pluto.tv's reply: {"category", "titles", "genres",
    "kinds"} - `film`/`tv`, `genre/subGenre` pairs and series titles, each weighted by minutes."""
    titles, genres, kinds = {}, {}, {}
    for entry in reply.get("timelines") or []:
        episode = entry.get("episode") or {}
        try:
            minutes = int((iso_time(entry["stop"]) - iso_time(entry["start"])).total_seconds() // 60)
        except (KeyError, TypeError, ValueError):
            minutes = int((episode.get("duration") or 0) / 60000)
        # Pluto says `film` or `tv`, or `live` for a stitched feed. Then a long programme that is
        # episode 1 of season 1 is a film; a 90-minute Columbo is episode 5 of season 3.
        kind = (episode.get("series") or {}).get("type")
        if kind not in ("film", "tv"):
            first = (episode.get("number") or 1) <= 1 and (episode.get("season") or 1) <= 1
            kind = "film" if minutes >= FILM_MINUTES and first else "tv"
        kinds[kind] = kinds.get(kind, 0) + minutes
        series = (episode.get("series") or {}).get("name") or entry.get("title") or ""
        if series and kind == "tv":
            titles[series] = titles.get(series, 0) + minutes
        genre = "%s/%s" % (episode.get("genre") or "", episode.get("subGenre") or "")
        if genre != "/":
            genres[genre] = genres.get(genre, 0) + minutes
    return {"category": reply.get("category") or "", "titles": titles, "genres": genres, "kinds": kinds}


# ---- matching a channel to its own service's guide ----------------------------------------------

def xumo_id(site_id):
    """Xumo's channel id from iptv-org's `<offset>#<id>` site id. The offset is the grabber's page
    in Xumo's channel list, which moves whenever Xumo adds a channel; the id does not."""
    return site_id.rsplit("#", 1)[-1] if site_id else site_id


def load_guide_ids(path=GUIDE_IDS):
    """guide_ids.json without its doc string: source -> name -> {id, how, guide?}."""
    with open(path) as f:
        return {k: v for k, v in json.load(f).items() if not k.startswith("_")}


def listed_guide(table, source, name):
    """(guide, guide_id, how) for a channel guide_ids.json names, else None."""
    entry = (table or {}).get(source, {}).get(name)
    if not entry:
        return None
    service = entry.get("guide") or SOURCE_GUIDES.get(source, (None, None))[0]
    if service not in FAST_GUIDES:
        raise ValueError("guide_ids.json: %s %r has no guide service" % (source, name))
    return service, entry["id"], entry["how"]


def same_channel_id(service, cid):
    """The part of a guide id that names the channel: Plex's ids are "<region>-<channel>"."""
    return cid.rsplit("-", 1)[-1] if service == "plex" else cid


class Guides:
    """Every guide this draft uses, and the lookups that tie a channel to one.

    [services] is service -> service_channels(...); [pluto] is Pluto id -> pluto_guide(...);
    [iptv] is iptv-org's guides.json, which knows many iptv-org channels' ids on Plex and Xumo;
    [table] is guide_ids.json, the ids checked by hand, which win over any match here."""

    def __init__(self, services=None, pluto=None, iptv=(), table=None):
        self.services = services or {}
        self.pluto = pluto or {}
        self.table = table or {}
        self.site_ids = {}
        for row in iptv:
            if row.get("channel") and row.get("site") in ("plex.tv", "xumo.tv"):
                self.site_ids.setdefault((row["site"], row["channel"]), row["site_id"])
        # (service, region, name) and (service, name) -> id; a name two channels share names
        # neither, so a match is never a coin toss. Plex lists one channel once per region, as
        # "<region>-<channel>": those copies are one channel, not a tie.
        self.by_name, self.any_region = {}, {}
        for service, channels in self.services.items():
            for cid, info in channels.items():
                for index, key in ((self.by_name, (service, info["region"], norm_name(info["name"]))),
                                   (self.any_region, (service, norm_name(info["name"])))):
                    if key not in index:
                        index[key] = cid
                    elif index[key] is not None and same_channel_id(service, index[key]) != same_channel_id(service, cid):
                        index[key] = None

    def match(self, fast):
        """(guide, guide_id, how) for a FAST candidate: its own service's guide channel, or
        ("none", None, None). guide_ids.json first. Xumo's guide is fetched later, by
        build_fast_guide.py, so a Xumo channel is `xumo`, with iptv-org's Xumo id for it when
        there is one."""
        listed = listed_guide(self.table, fast["source"], fast["name"])
        if listed:
            return listed
        service, region = SOURCE_GUIDES.get(fast["source"], (None, None))
        if service is None or service not in self.services and service != "xumo":
            return "none", None, None
        iptv_id = build_fast.channel_id(fast.get("tvg_id"))
        if service == "xumo":
            site_id = xumo_id(self.site_ids.get(("xumo.tv", iptv_id))) if iptv_id else None
            return "xumo", site_id, "tvg-id" if site_id else "source"
        channels = self.services.get(service, {})
        site_id = self.site_ids.get(("plex.tv", iptv_id)) if service == "plex" and iptv_id else None
        if site_id:
            for cid in channels:
                if cid == site_id or cid.endswith("-" + site_id):
                    return "plex", cid, "tvg-id"
        cid = self.by_name.get((service, region, norm_name(fast["name"])))
        if cid:
            return service, cid, "name"
        return "none", None, None

    def evidence(self, channel):
        """What a channel airs, for classifying it: {"titles", "genres", "groups", "via"}, or None.
        Its own guide when it has one; else a same-named channel on Samsung, Plex or Roku - good
        enough to say what KIND of thing is on, though never what is on now."""
        guide, gid = channel["guide"], channel.get("guide_id")
        if guide == "pluto":
            info = self.pluto.get(gid)
            if not info:
                return None
            return {"titles": info["titles"], "genres": info["genres"], "kinds": info.get("kinds", {}),
                    "groups": [info["category"]] if info["category"] else [], "via": None}
        if guide in self.services and gid in self.services[guide]:
            info = self.services[guide][gid]
            return {"titles": info["titles"], "genres": {}, "kinds": info["kinds"],
                    "groups": info["groups"], "via": None}
        if channel["source"] == "pluto":
            return None
        for service in ("samsung", "plex", "roku"):
            cid = self.any_region.get((service, norm_name(channel["name"])))
            info = self.services[service].get(cid) if cid else None
            if info and any(info[k] for k in ("titles", "groups", "kinds")):
                label = "Samsung" if service == "samsung" else service.title()
                return {"titles": info["titles"], "genres": {}, "kinds": info["kinds"],
                        "groups": info["groups"], "via": "%s's %s" % (label, info["name"])}
        return None


# ---- fetching -----------------------------------------------------------------------------------

def fetch_bytes(url, timeout=120):
    request = urllib.request.Request(url, headers={"User-Agent": "ytv-lineup"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def cached(cache, name, url):
    """The bytes at [url], kept as [cache]/[name] when there is a cache, so a rerun reuses them."""
    path = os.path.join(cache, name) if cache else None
    if path and os.path.exists(path):
        with open(path, "rb") as f:
            return f.read()
    data = fetch_bytes(url)
    if path:
        with open(path, "wb") as f:
            f.write(data)
    return data


def fetch_pluto_guides(ids, cache):
    """Pluto id -> pluto_guide(...) for the next PLUTO_GUIDE_HOURS, six channels at a time. A
    channel whose guide will not load simply has none: the draft goes on without it."""
    path = os.path.join(cache, "pluto_guides.json") if cache else None
    if path and os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0, tzinfo=None)
    start = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    stop = (now + datetime.timedelta(hours=PLUTO_GUIDE_HOURS)).strftime("%Y-%m-%dT%H:%M:%SZ")

    def one(pid):
        try:
            return pid, pluto_guide(json.loads(fetch_bytes(PLUTO_GUIDE % (pid, start, stop), 30)))
        except Exception as e:  # a guide that will not load is a channel without one
            print("no Pluto guide for %s: %s" % (pid, e), file=sys.stderr)
            return pid, None

    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        guides = {pid: g for pid, g in pool.map(one, ids) if g}
    if path and guides:  # never cache a failed run as "no guides"
        with open(path, "w") as f:
            json.dump(guides, f)
    return guides


def load_guides(pluto_ids, cache):
    services = {}
    for service, folder in MJH_SERVICES.items():
        xml = gzip.decompress(cached(cache, service + ".xml.gz", MJH % (folder, "all.xml.gz")))
        listing = json.loads(cached(cache, service + ".channels.json", MJH % (folder, ".channels.json")))
        services[service] = service_channels(service, listing, parse_xmltv(xml))
    iptv = json.loads(cached(cache, "iptv_guides.json", IPTV_GUIDES))
    return Guides(services, fetch_pluto_guides(pluto_ids, cache), iptv, load_guide_ids())



# ---- the pool -----------------------------------------------------------------------------------

def pluto_channels(dial, allowlist):
    """The Pluto dial's channels as draft entries: name, the play data pluto.json already carries
    (`pluto` and `streams`), the allowlist genre as a hint, and Pluto's own guide."""
    genres = {e["number"]: e.get("genre", "") for e in allowlist}
    out = []
    for channel in dial:
        pid = (channel.get("pluto") or {}).get("id") or re.search(
            r"plu-([0-9a-f]+)", channel["streams"][0]["url"]).group(1)
        out.append({"name": channel["name"], "source": "pluto", "hint": genres.get(channel["number"], ""),
                    "iptv_categories": [], "pluto": channel.get("pluto"), "streams": channel["streams"],
                    "route": "pluto", "guide": "pluto", "guide_id": pid, "guide_how": "pluto id",
                    "old_number": channel["number"]})
    return out


def fast_channels(candidates, guides):
    """The live FAST candidates as draft entries, each matched to its own service's guide."""
    out = []
    for c in candidates:
        guide, gid, how = guides.match(c)
        entry = {"name": c["name"], "source": c["source"], "hint": c.get("category", ""),
                 "iptv_categories": c.get("iptv_categories") or [], "tvg_id": c.get("tvg_id"),
                 "url": c["url"], "route": c["route"], "guide": guide, "guide_id": gid,
                 "guide_how": how}
        for key in ("headers", "logo", "also"):
            if c.get(key):
                entry[key] = c[key]
        out.append(entry)
    return out


# ---- near-duplicates ----------------------------------------------------------------------------

# Words that dress a name without changing the channel: "Pointless UK", "NBA FAST Channel". Not
# "pluto": "Pluto TV Horror" is not "Horror by ALTER". And "+" is a word: "Tennis+" is not
# "Tennis Channel".
DRESSING = {"uk", "us", "usa", "au", "fast", "24", "7", "hd", "the", "tv", "channel", "network"}
# Tails that credit a presenter or an owner: "AFV with Alfonso Ribeiro", "Hoarders by A&E".
CREDIT = re.compile(r"\s+(with|presented by|by)\s+.+$", re.I)
# Pairs no rule can see are one channel, each spelling -> the other's key. Checked by hand.
SAME_CHANNEL = {
    "TennisChannel 2": "Tennis Channel 2", "PFL MMA": "PFL", "BBC Top Gear": "Top Gear",
    "CraftsyTV": "Craftsy", "Stingray TikTok Radio": "TikTok Radio", "60 Days in Jail": "60 Days In",
    "Rally.TV FAST+": "Rally TV",
    # Tennis Channel 2 again, under Fire TV's name; and Little Dot's Real Crime on Rakuten's feed.
    "T2 Tennis Channel": "Tennis Channel 2", "Real Crime Beta": "Real Crime",
    # The same feed under two names, by its stream or its schedule:
    # - Xumo's "BBC Impossible" and Roku's "Impossible Quiz Show" are one Wurl channel,
    #   bbc-impossible-1-us (bbc-impossible-1-us.xumo.wurl.tv and .roku.wurl.tv);
    # - Samsung UK's "Born to Kill" and Rakuten's "True Lives" are one Amagi channel, amg00654c7,
    #   playing the playlist amg00654-itvstudiosfast-truelives on both;
    # - Samsung's "WWE Superstar Central" airs Pluto's "Wrestling Legends TV" schedule: the same
    #   titles starting at the same minutes (02:17, 04:00, 04:50, 05:41, 06:33, 08:17 UTC).
    "BBC Impossible": "Impossible Quiz Show", "Born to Kill": "True Lives",
    "WWE Superstar Central": "Wrestling Legends TV",
}


def merge_key(name):
    """The name a near-duplicate shares: norm_name without credits, regions or FAST dressing."""
    name = SAME_CHANNEL.get(name, name)
    bare = CREDIT.sub("", build_fast.clean_name(name)) or name
    words = [w for w in re.findall(r"[a-z0-9]+", bare.lower().replace("&", " and ").replace("+", " plus "))
             if w not in DRESSING]
    return "".join(words) or norm_name(name)


def keep_rank(channel):
    """Which copy of a channel stays: Pluto, then a FAST channel with its own guide, then one that
    plays direct from Australia, then the rest."""
    if channel["source"] == "pluto":
        return 0
    if channel["guide"] in FAST_GUIDES and channel["guide"] != "xumo":
        return 1
    return 2 if channel["route"] == "direct" else 3


def merge_duplicates(channels):
    """(kept, merges): one channel per merge_key, the best by keep_rank (ties to the earlier), and
    [(kept name, [dropped names])]. Two Pluto channels are never merged: both are on the Pluto dial
    because the owner picked both, and "Tough Jobs" and "Pluto TV Tough Jobs" air different shows."""
    groups = collections.OrderedDict()
    for index, channel in enumerate(channels):
        key = merge_key(channel["name"])
        if channel["source"] == "pluto" and any(m[2]["source"] == "pluto" for m in groups.get(key, [])):
            key = ("pluto", channel["name"])
        groups.setdefault(key, []).append((keep_rank(channel), index, channel))
    kept, merges = [], []
    for members in groups.values():
        members.sort(key=lambda m: (m[0], m[1]))
        winner = dict(members[0][2])
        losers = [m[2] for m in members[1:]]
        if losers:
            winner["merged"] = [{"name": c["name"], "source": c["source"]} for c in losers]
            merges.append((winner["name"], [c["name"] for c in losers]))
        kept.append(winner)
    return kept, merges


# ---- the dial's blocks --------------------------------------------------------------------------

MIXED_MOVIES = "Movies – Mixed"
FLAGGED = "Flagged"
# (block, its sub-blocks) in dial order, as the owner set it. Sports is the last block that is on
# by default; Relax, Unsorted and Flagged follow it, listed but off. Sub-blocks past the owner's
# own (Westerns and Classic Drama in Series, say) split a big sub-block into ones you can surf;
# see REFINE.
BLOCKS = [
    ("Movies", ["Action", "Comedy", "Romance", "Horror", "Thriller", "Sci-Fi", "Westerns", "Family",
                "Classic", "Drama", MIXED_MOVIES]),
    ("Series", ["Comedy", "Action", "Westerns", "Sci-Fi & Fantasy", "Drama", "Classic Drama",
                "Family & Teen Drama", "K-Drama", "Crime", "Reality", "Competition Reality",
                "Dating Reality", "Pawn & Deals", "Docu-Reality", "Judge & Talk"]),
    ("Sitcoms (USA)", ["Sitcoms"]),
    ("Game Shows", ["Game Shows"]),
    ("Cartoons & Kids", ["Cartoons", "Kids", "Preschool"]),
    ("Anime", ["Anime"]),
    ("Food, Home & Travel", ["Food", "Food Reality", "Home", "Antiques", "Crafts & Garden", "Travel"]),
    ("Music", ["60s & 70s", "80s", "90s & 2000s", "Rock", "Country", "Hip-Hop/R&B", "Pop",
               "Classical/Jazz", "Concerts & Live", "Other"]),
    ("Documentaries", ["General", "Nature", "Pets & Vets", "History", "Science & Space", "Paranormal",
                       "True Crime", "Forensics & Cold Cases", "Cops & Courts", "Motoring", "Outdoors"]),
    ("Sports", ["General", "Combat", "Soccer", "US Leagues", "Motorsport", "Tennis & Golf",
                "Cue, Darts & Poker", "Action & Extreme", "Other Sports"]),
    ("Relax", ["Relax"]),
    ("Unsorted", ["Unsorted"]),
    (FLAGGED, [FLAGGED]),
]
# Movie sub-genres beyond the owner's ten. Each gets its own sub-block, after Drama, only when
# MIN_EXTRA or more channels share it; fewer fold into Movies – Mixed. The finer sub-blocks REFINE
# makes need MIN_EXTRA too, or fold back into the sub-block they came from.
EXTRA_MOVIE_SUBS = ["Decades", "Martial Arts", "Black Cinema", "Cult & B-Movies", "Indie & World"]
MIN_EXTRA = 3
OFF_BY_DEFAULT = {"Relax", "Unsorted", FLAGGED}

# Channels that are not what a retro cable dial carries, though they slipped past the FAST pass's
# filters: news, shopping, religion, adult, betting or not in English. Listed, never deleted, and
# off: norm_name -> why. Checked by hand, each with its evidence.
OFF_FORMAT = {
    "sportsgrid": "sports betting: Samsung's listing calls it \"the first 24-hour sports betting "
                  "channel\" (odds and wagering news)",
    "draftkings": "sports betting: the sportsbook's own network, \"built for today's passionate "
                  "fans and bettors\" (Samsung's listing)",
}

# A movie sub-genre, for a channel of series rather than films.
SERIES_FOR = {
    "Action": "Action", "Comedy": "Comedy", "Romance": "Drama", "Horror": "Sci-Fi & Fantasy",
    "Thriller": "Crime", "Sci-Fi": "Sci-Fi & Fantasy", "Westerns": "Westerns", "Family": "Drama",
    "Classic": "Drama", "Drama": "Drama", "Decades": "Drama", "Martial Arts": "Action",
    "Black Cinema": "Drama", "Cult & B-Movies": "Action", "Indie & World": "Drama",
    MIXED_MOVIES: "Drama",
}


# ---- what a name says ---------------------------------------------------------------------------

def words(name):
    """A name as has_word reads it: `Classic TV: Families` -> `Classic TV Families`."""
    name = name.replace("’", "'").replace(".", "")
    return re.sub(r"\s+", " ", re.sub(r"[-:!,/+|]", " ", name)).strip()


# Names whose words mislead, checked by hand: norm_name -> (block, sub, why).
EXACT = {
    "pop": ("Cartoons & Kids", "Cartoons", "Pop is a kids' cartoon channel (Garfield, Total Drama)"),
    "mtv": ("Series", "Reality", "MTV's reality shows (Punk'd, Cribs), not music videos"),
    "tracesportstars": ("Sports", "General", "Trace's sports channel, not its music ones"),
    "mrbeananimated": ("Cartoons & Kids", "Cartoons", "the Mr. Bean cartoon"),
    "surfcinema": ("Sports", "Action & Extreme", "surf films"),
    "inter247": ("Sports", "Soccer", "Inter Milan's club channel"),
    "hardknocks": ("Sports", "Combat", "Hard Knocks Fighting Championship, Canadian MMA"),
    "wild": ("Documentaries", "Outdoors", "Wild TV is a hunting and fishing channel"),
    "wildwest": ("Series", "Westerns", "western series (Death Valley Days and the like)"),
    "wbtvfbi": ("Series", "Crime", "The F.B.I., the 1965 police drama"),
    "videogameheroes": ("Cartoons & Kids", "Cartoons", "video-game cartoons (Sonic X, Donkey Kong Country)"),
    "peopleareawesome": ("Sports", "Action & Extreme", "stunt and extreme-sport clips"),
    "cirquedusoleil": ("Music", "Concerts & Live", "Cirque du Soleil shows"),
    "kidsmovieclub": ("Cartoons & Kids", "Kids", "kids' films"),
    "bravovault": ("Series", "Reality", "Bravo's reality shows"),
    "revry": ("Unsorted", "Unsorted", "LGBTQ+ films and series, no single genre"),
    "stingraymoviemusic": ("Music", "Other", "film soundtracks, a music channel"),
    "crimeflix": ("Movies", "Thriller", "crime films"),
    "revandroll": ("Cartoons & Kids", "Cartoons", "Rev & Roll, a cartoon (Samsung files it under Kids)"),
    # Cartoons for grown-ups, not kids: Samsung files both under Comedy or Anime & Gaming.
    "animationplus": ("Series", "Comedy", "adult cartoons (The Cyanide & Happiness Show, Skits From "
                                          "My Cell); Samsung files it under Comedy"),
    "bizaartv": ("Series", "Comedy", "cult and adult cartoons (The Goode Family, Speed Racer); "
                                     "Samsung: \"mind-bending animation\""),
    "dungeonsanddragonsadventures": ("Cartoons & Kids", "Cartoons",
                                     "eOne's Dungeons & Dragons channel (the 1983 cartoon), a "
                                     "sibling of its Transformers and Power Rangers feeds"),
    "comedydynamics": ("Series", "Comedy", "stand-up specials: an hour or more each, so its guide "
                                           "reads as films"),
    "evolutionearth": ("Documentaries", "History", "ancient-history and prophecy documentaries "
                                                   "(Roman Engineering, Omens of Doom), not nature"),
    "feva": ("Unsorted", "Unsorted", "Nigerian general entertainment (films, documentaries, "
                                     "gospel mixes), not a music channel"),
    "foxsoul": ("Unsorted", "Unsorted", "Black culture talk and lifestyle (Samsung's listing), "
                                        "with Sunday worship: no one genre"),
}

# Known shows, by the block they air in: (block, sub, what it is, shows). A show in a channel's
# name places the channel ("Baywatch" -> Series, Action); so does a guide that is mostly shows of
# one kind. TITLE_ONLY shows count in guides only: as words in a channel name they mislead
# ("Hunter" is in "Hunter x Hunter").
SHOWS = [
    ("Series", "Action", "action series", [
        "baywatch", "nikita", "the a team", "knight rider",
        "bionic woman", "walker texas ranger", "sea patrol", "leverage", "macgyver", "airwolf",
        "relic hunter"]),
    ("Series", "Sci-Fi & Fantasy", "sci-fi or fantasy series", [
        "doctor who", "star trek", "stargate", "snowpiercer", "the librarians", "land of the lost",
        "z nation", "starhunter", "outer limits", "twilight zone", "new twilight zone", "xena",
        "hercules", "highlander", "the incredible hulk", "battlestar galactica", "the lost world",
        "van helsing", "ghost whisperer", "charmed", "smallville", "beauty and the beast",
        "good witch", "walking dead", "the walking dead"]),
    ("Series", "Westerns", "western series", [
        "rawhide", "wagon train", "tales of wells fargo", "laramie", "death valley days",
        "wanted dead or alive", "the lone ranger", "lone ranger", "have gun will travel",
        "tombstone territory", "bonanza", "gunsmoke", "the rifleman", "the big valley", "the virginian",
        "daniel boone", "annie oakley"]),
    ("Series", "Crime", "crime series", [
        "21 jump street", "midsomer murders", "murder she wrote", "columbo", "ncis", "rookie blue",
        "csi", "csi crime scene investigation", "major crimes", "diagnosis murder",
        "in the heat of the night", "the streets of san francisco", "mannix", "barnaby jones",
        "foyle's war", "murder in", "the coroner", "the inspector lynley mysteries", "father brown",
        "numb3rs", "criminal minds", "silent witness", "new tricks", "nash bridges", "hawaii five 0",
        "the listener", "law and order", "breaking bad", "the adventures of sherlock holmes",
        "sherlock holmes returns", "alfred hitchcock presents", "alfred hitchcock hour", "dragnet",
        "perry mason", "matlock", "magnum pi", "miami vice", "kojak", "the rockford files", "ironside",
        "murdoch mysteries", "shetland", "inspector morse", "poirot", "agatha christie's poirot",
        "miss marple", "a touch of frost", "colonel march of scotland yard", "black snow"]),
    ("Series", "Drama", "drama series", [
        "heartland", "degrassi", "designated survivor", "little house on the prairie", "lassie",
        "7th heaven", "touched by an angel", "dr quinn medicine woman",
        "everwood", "hart of dixie", "edgemont", "mcleod's daughters", "saving hope",
        "nurse jackie", "mad men", "zatima", "tyler perry's the oval",
        "sheriff country", "twin peaks", "chicago fire", "a house divided", "the waltons", "dallas",
        "melrose place", "one tree hill", "gilmore girls", "heartbeat",
        "when calls the heart", "highway to heaven", "lucky romance", "goddess of fire"]),
    ("Sitcoms (USA)", "Sitcoms", "American sitcom", [
        "the conners", "anger management", "are we there yet", "saved by the bell",
        "leave it to beaver", "the drew carey show", "all in the family", "maude", "the odd couple",
        "married with children", "my three sons", "dennis the menace", "father knows best",
        "hogan's heroes", "the jeffersons", "webster", "sister sister", "moesha",
        "hangin' with mr cooper", "my wife and kids", "half and half", "girlfriends",
        "tyler perry's house of payne", "house of payne", "assisted living",
        "tyler perry's assisted living", "tyler perry's love thy neighbor", "love thy neighbor",
        "the neighborhood", "the king of queens", "rules of engagement", "man with a plan", "becker",
        "sabrina the teenage witch", "the steve harvey show", "hot in cleveland", "mork and mindy",
        "the exes", "cheers", "frasier", "three's company", "happy days", "laverne and shirley",
        "that girl", "i love lucy", "the andy griffith show", "the beverly hillbillies", "bewitched",
        "i dream of jeannie", "gilligan's island", "the brady bunch", "family ties", "growing pains",
        "full house", "sanford and son", "good times", "diff'rent strokes", "the facts of life",
        "the dick van dyke show", "the munsters", "the addams family", "green acres",
        "petticoat junction", "get smart", "mchale's navy", "welcome back kotter", "mama's family",
        "the nanny", "home improvement", "roseanne", "everybody loves raymond", "the bernie mac show",
        "everybody hates chris", "the parkers", "living single", "the wayans bros", "family matters",
        "the fresh prince of bel air", "two and a half men", "last man standing", "reba",
        "malcolm in the middle", "3rd rock from the sun", "newsradio", "night court",
        "who's the boss", "perfect strangers", "step by step", "boy meets world", "the hughleys",
        "one on one", "meet the browns"]),
    ("Series", "Comedy", "comedy", [
        "coupling", "mr bean", "mr bean live action", "terry and june", "portlandia", "afv",
        "america's funniest home videos", "just for laughs", "just for laughs gags", "conan",
        "conan o'brien", "graham norton", "the graham norton show", "8 out of 10 cats", "smosh",
        "the try guys", "mythical", "workaholics", "drunk history", "south park", "daria",
        "crank yankers", "the kids in the hall", "the whitest kids u' know", "rifftrax",
        "mystery science theater 3000", "the carol burnett show", "the best of the carol burnett show",
        "johnny carson", "the johnny carson show", "failfactory", "world's most expensive fails",
        "world's funniest videos", "the goode family", "key and peele", "chappelle's show",
        "whose line is it anyway", "the kumars at no 42", "fawlty towers", "only fools and horses",
        "blackadder", "red dwarf", "keeping up appearances", "are you being served", "dad's army",
        "absolutely fabulous", "the vicar of dibley", "the young ones", "the cyanide and happiness show"]),
    ("Series", "Reality", "reality show", [
        "survivor", "america's got talent", "americas got talent", "american idol",
        "american ninja warrior", "ninja warrior", "bad girls club", "bondi rescue", "bondi vet",
        "bring it", "cheaters", "dance moms", "pawn stars", "hardcore pawn", "storage wars",
        "forged in fire", "hoarders", "intervention", "little women la", "married at first sight",
        "love after lockup", "operation repo", "project runway", "project runway all stars",
        "real housewives", "the real housewives of potomac", "keeping up with the kardashians",
        "revenge body with khloe kardashian", "the masked singer", "the biggest loser", "the osbournes",
        "undercover boss", "wipeout", "total wipeout", "total wipeout uk", "fear factor",
        "don't tell the bride", "duck dynasty", "vanderpump rules", "rupaul's drag race", "punk'd",
        "mtv cribs", "siesta key", "laguna beach", "tattoo fixers", "flavor of love",
        "double shot at love with dj pauly d and vinny", "judge mathis", "people's court",
        "the people's court", "judge judy", "divorce court", "cutlers court",
        "couples court with the cutlers", "the steve wilkos show", "the jerry springer show", "maury",
        "robot wars", "ex isle", "marriage boot camp reality stars", "neighborhood wars",
        "shipping wars", "auction hunters", "beverly hills pawn", "extreme salvage squad",
        "salvage kings", "flipping bangers", "american pickers", "alone", "relative race",
        "wahlburgers", "escaping polygamy", "bridezillas", "basketball wives", "basketball wives la",
        "skin wars fresh paint", "studs", "steve austin's broken skull challenge", "counting cars",
        "parking wars", "90 day fiance", "love island", "big brother", "jersey shore", "teen mom",
        "catfish", "the bachelor", "honesty box", "snack wars", "untold stories of the er",
        "mr beast", "mrbeast", "back to school with mrbeast"]),
    ("Game Shows", "Game Shows", "game show", [
        "family feud", "pointless", "tipping point", "let's make a deal", "the price is right",
        "deal or no deal", "supermarket sweep", "celebrity name game", "concentration",
        "password plus", "body language", "idiotest", "minute to win it", "pictionary",
        "are you smarter than a fifth grader", "25 words or less", "match game", "the joker's wild",
        "card sharks", "press your luck", "jeopardy", "wheel of fortune",
        "who wants to be a millionaire", "the chase", "eggheads", "blockbusters", "catchphrase",
        "the weakest link", "1 vs 100", "lingo", "chain reaction", "to tell the truth",
        "what's my line", "name that tune", "the newlywed game", "the dating game",
        "sale of the century", "tic tac dough", "impossible quiz", "mastermind",
        "university challenge", "the crystal maze", "golden balls", "the cube"]),
    ("Documentaries", "True Crime", "true-crime documentary", [
        "forensic files", "cold case files", "the first 48", "dateline", "dateline nbc", "20 20",
        "48 hours", "unsolved mysteries", "dr g medical examiner", "medical detectives",
        "evidence of evil", "world's most evil killers", "deadly women", "born to kill",
        "bloodline detectives", "60 days in", "jail", "cops", "cops reloaded", "live pd",
        "live pd police patrol", "on patrol live", "court cam", "american greed", "snapped",
        "snapped killer couples", "alaska state troopers", "campus pd", "takedown with chris hansen",
        "buzzfeed unsolved", "dog the bounty hunter", "the fbi files", "american justice",
        "24 hours in police custody", "killers caught on camera", "72 hours",
        "stalked someone's watching", "crime expose with nancy o'dell", "happily never after",
        "great crimes and trials", "crime scene solvers", "conmen case files", "killer mysteries",
        "murder by the sea", "storm of suspicion", "forensic factor",
        "robbie coltrane's critical evidence", "crimes that shook britain",
        "i met my murderer online", "dark waters of crime", "interview with a killer",
        "atl homicide", "court tv live", "bodycam by law and crime", "most outrageous crimes",
        "law and crime investigates", "psychic investigators", "lady killers with martina cole",
        "the boneyard", "999 killer on the line", "sheriffs el dorado county", "women behind bars",
        "i lived with a killer", "rachel shannon true crime", "matthew cox inside true crime",
        "ancient murders unearthed"]),
    ("Documentaries", "Paranormal", "paranormal documentary", [
        "ancient aliens", "the unxplained", "ghost hunters", "most haunted", "paranormal 911",
        "scariest night of my life", "celebrity ghost stories", "psychic witness", "ufo hunters",
        "alien files unsealed", "the conspiracy show with richard syrett", "the curse of oak island",
        "the haunting of", "mysteries unearthed with danny trejo", "myth hunters",
        "top 10 secrets and mysteries", "the curse of civil war gold", "alien agenda",
        "alien from area 51", "ufo conspiracies"]),
    ("Documentaries", "History", "history documentary", [
        "modern marvels", "museum secrets", "the dark ages", "wwii in color", "world war ii in hd color",
        "battlefields", "hitler and the nazis", "great tank battles of world war ii",
        "the world on the brink", "how the victorians built britain", "megapolis",
        "britain's most historic towns", "abandoned engineering", "history's greatest mysteries",
        "lost cities of the ancients", "ice age giants", "tutankhamun", "treasures of ancient egypt",
        "could you survive", "ten days to victory", "wwii the call of duty", "field of valor",
        "the finest hours of wwii"]),
    ("Documentaries", "Science & Space", "science documentary", [
        "mythbusters", "mark rober tv", "cosmic vistas", "the universe",
        "the universe ancient mysteries solved", "sciencenow", "space time", "eons", "infinite series",
        "how it's made", "nova", "through the wormhole", "earthrise the first lunar voyage"]),
    ("Documentaries", "General", "documentary", [
        "mayday air disaster", "air disasters", "the accident files", "i survived",
        "i shouldn't be alive", "inside the ambulance", "999 rescue squad",
        "stanley tucci searching for italy", "jungletown", "abandoned", "tech toys",
        "aerial america", "america's secret space heroes", "wrestleher"]),
    ("Documentaries", "Nature", "nature documentary", [
        "wild africa", "wild caribbean", "natural world", "seasonal wonderlands", "cub camp",
        "deep dive north america", "the reptiles", "dog whisperer", "dog whisperer with cesar millan",
        "lucky dog", "mega zoo", "the yorkshire vet", "the yorkshire vet casebook", "er vets",
        "pet vet dream team", "vets saving pets", "river monsters", "jack hanna", "safarilive",
        "wild france", "aquarium an aquatic life", "icons of the wildlife", "world of the wild",
        "wild galapagos", "big cats of the serengeti", "countdown the funniest pets",
        "animals unscripted", "wildest latin america", "australia's wild places", "wildest places"]),
    ("Documentaries", "Motoring", "motoring show", [
        "top gear", "fifth gear", "full custom garage", "texas metal", "texas metal's loud and lifted",
        "the auto firm with alex vega", "roadkill's junkyard gold", "truck tech", "music city trucks",
        "detroit muscle", "bangers and cash", "barrett jackson revved up", "stacey david's gearz",
        "gearz", "the driver's seat with henry catchpole", "barn find hunter", "will it run",
        "jay leno's garage", "highway thru hell", "heavy rescue 401", "find me a classic",
        "wheeler dealers", "overhaulin'", "chasing classic cars", "car sos", "road wars"]),
    ("Documentaries", "Outdoors", "outdoors show", [
        "ice road truckers", "ax men", "swamp people", "mountain men", "wicked tuna", "timber kings",
        "jade fever", "ice pilots", "fishing impossible", "meateater", "fishing expedition amazonia",
        "wardens", "dangerous waters", "deadliest catch", "in fisherman tv", "guns and ammo tv",
        "north american whitetail", "whitetail frenzy", "modern hunter", "huntstand origins",
        "work on the wild side"]),
    ("Food, Home & Travel", "Food", "cooking show", [
        "buddy vs duff", "unwrapped", "valerie's home cooking", "anthony bourdain parts unknown",
        "ainsley's caribbean kitchen", "fire masters", "hardcore carnivore", "jamie's 15 minute meals",
        "martha cooks", "martha bakes", "america's test kitchen", "cook's country",
        "great british menu", "hell's kitchen", "kitchen nightmares", "ramsay's kitchen nightmares",
        "come dine with me", "couples come dine with me", "masterchef", "emeril live",
        "rachael ray's meals in minutes", "no passport required", "exploring europe's great food markets",
        "hot ones", "top chef", "chopped", "gordon ramsay", "jamie oliver cooks the mediterranean",
        "pati's mexican table"]),
    ("Food, Home & Travel", "Home", "home show", [
        "50 50 flip", "zombie house flipping", "holmes makes it right", "holmes on homes",
        "antiques roadshow", "cash in the attic", "a new life in the sun", "escape to the country",
        "tiny house nation", "million dollar dream home", "million dollar listing",
        "million dollar listing los angeles", "this old house", "ask this old house",
        "homes under the hammer", "the joy of painting with bob ross", "bob ross",
        "alan cumming's paradise homes", "backyard builds", "monster house", "four in a bed",
        "the hotel inspector", "building alaska", "this old house makers"]),
    ("Cartoons & Kids", "Cartoons", "cartoon", [
        "garfield and friends", "the garfield show", "danger mouse", "count duckula", "the smurfs",
        "sonic the hedgehog", "dennis the menance and gnasher", "dennis and gnasher",
        "he man and the masters of the universe", "pac man and the ghostly adventures", "angry birds",
        "totally spies", "rabbids invasion", "slugterra", "strawberry shortcake", "rainbow ruby",
        "transformers", "transformers armada", "camp lakebottom", "hey arnold", "aaahh real monsters",
        "the wild thornberrys", "the angry beavers", "all grown up", "the new pink panther show",
        "spongebob squarepants", "the loud house", "looney tunes", "tom and jerry", "scooby doo",
        "the flintstones", "the jetsons", "popeye", "inspector gadget", "teenage mutant ninja turtles",
        "thundercats", "super mario brothers super show", "bill and ted's excellent adventures",
        "total drama action", "kung fu dino posse", "magi nation", "shasha and milo"]),
    ("Cartoons & Kids", "Kids", "kids' show", [
        "henry danger", "big time rush", "kenan and kel", "clarissa explains it all",
        "h2o just add water", "ryan's world", "fgteev", "ninja kidz", "the fun squad", "lankybox",
        "power rangers", "monster high", "chicken girls", "rebecca zamolo"]),
    ("Cartoons & Kids", "Preschool", "preschool show", [
        "barney and friends", "the wubbulous world of dr seuss", "the cat in the hat knows a lot about",
        "maggie and the ferocious beast", "bubble guppies", "paw patrol", "blaze and the monster machines",
        "blippi", "caillou", "pocoyo", "teletubbies", "baby einstein", "baby shark", "bebefinn",
        "pinkfong songs for children", "super simple songs", "little baby bum", "t rex ranch",
        "thomas and friends", "kipper", "dinocity", "true and the rainbow kingdom", "dino ranch",
        "sid the science kid", "slick slime sam", "cocomelon", "peppa pig", "bluey"]),
    ("Anime", "Anime", "anime", [
        "speed racer", "jojo's bizarre adventure", "hunter x hunter", "hunterxhunter", "naruto",
        "yu gi oh", "pokemon", "robotech", "food wars", "amagi brilliant park", "maid sama",
        "miss kobayashi's dragon maid", "yuyu hakusho", "rwby", "my hero academia", "dorohedoro",
        "digimon", "space adventure cobra", "future boy conan", "dragon ball", "sailor moon",
        "one piece", "cowboy bebop", "gundam"]),
]
TITLE_ONLY = {"hunter", "power", "vera", "ransom", "arrow", "emergency", "the border", "taxi",
              "martin", "the game", "divided", "baggage", "the 100", "the case", "payback",
              "nature", "password", "alice", "mom", "wings"}
TITLE_SHOWS = [
    ("Series", "Crime", "crime series", ["hunter", "vera", "ransom", "the border"]),
    ("Series", "Action", "action series", ["emergency"]),
    ("Series", "Sci-Fi & Fantasy", "sci-fi or fantasy series", ["arrow", "the 100"]),
    ("Series", "Drama", "drama series", ["power"]),
    ("Sitcoms (USA)", "Sitcoms", "American sitcom", ["taxi", "martin", "the game", "alice", "mom", "wings"]),
    ("Game Shows", "Game Shows", "game show", ["divided", "baggage", "password"]),
    ("Documentaries", "Nature", "nature documentary", ["nature"]),
]


def show_key(text):
    """A show title as SHOWS spells it: lower case, `&` as `and`, no punctuation but apostrophes."""
    text = words(text).lower().replace("&", " and ")
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    text = re.sub(r"[^a-z0-9' ]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def _show_index():
    by_title, name_patterns = {}, []
    for block, sub, kind, shows in SHOWS + TITLE_SHOWS:
        for show in shows:
            by_title[show_key(show)] = (block, sub, kind, show)
            if show not in TITLE_ONLY:
                name_patterns.append((show_key(show), (block, sub, kind, show)))
    # Longest first, so "the carol burnett show" wins over a shorter show inside it.
    name_patterns.sort(key=lambda item: -len(item[0]))
    return by_title, name_patterns


SHOW_TITLES, SHOW_NAMES = _show_index()
# A guide title "starts with" a show when the show is followed by the end or a separator:
# "Timber Kings: Out on a Limb", "MythBusters Season 04", "Pointless UK Season 05".
TITLE_TAIL = re.compile(r"^(new |the best of )?(.+?)( (season|series|s\d|uk|us|classics?)\b.*)?$")


def title_keys(title):
    """The show keys a guide title may be: itself, the part before a colon ("Timber Kings: Out on
    a Limb" is an episode of Timber Kings), and that without a season or region tail."""
    whole, head = show_key(title), show_key(title.split(":")[0])
    match = TITLE_TAIL.match(head)
    return [whole, head] + ([match.group(2)] if match else [])


def show_of_title(title):
    """(block, sub, kind, show) for a guide title that is a known show, else None."""
    for key in title_keys(title):
        if key in SHOW_TITLES:
            return SHOW_TITLES[key]
    return None


def show_in_name(name):
    """(block, sub, kind, show) for a known show named in a channel name, else None."""
    key = show_key(name)
    for pattern, show in SHOW_NAMES:
        if re.search(r"(^| )%s( |$)" % re.escape(pattern), key):
            return show
    return None


# Name words that decide a block outright, in order: (block, sub or None, words). A sub of None is
# settled by the block's own sub rules below.
STRONG_WORDS = [
    ("Relax", "Relax", r"(?<!reggae )vibes|fireplace|aquarium|cityscapes|holidayscapes|the spa|"
                       r"green noise|music for focus|peaceful piano|zen|zenlife|myzen|wellbeing|"
                       r"naturescape|dronetv|space live|slow tv|omstars|yoga"),
    ("Anime", "Anime", r"anime|hidive|crunchyroll|retrocrush|naruto|yu gi oh|pok[eé]mon|"
                       r"hunter x hunter|jojo'?s"),
    ("Cartoons & Kids", "Preschool", r"babies|baby|blippi|caillou|pocoyo|teletubbies|super simple|"
                                     r"nick jr|little stars|looloo|moonbug|ducktv|toon goggles junior|"
                                     r"sensical jr|kiddo|zoo moo|tiny pop|mattel jr|happykids"),
    ("Cartoons & Kids", "Cartoons", r"toons?|cartoons?|kartoon|toongoggles|animation|animated|sonic|"
                                    r"smurfs?|dennis and gnasher|rabbids|slugterra|strawberry shortcake|"
                                    r"rainbow ruby|transformers"),
    ("Cartoons & Kids", "Kids", r"kids?|nickelodeon|brat|ryan and friends|fgteev|ninja kidz|"
                                r"power rangers|barbie|hot wheels|lego|pocket ?watch|sensical|batterypop"),
]
MOVIE_WORDS = (r"movies?|cinema|cine|films?|flix|flicks|reels|cinevault|screen|drive in|miramax|"
               r"moviesphere|midnight pulp|best (action|drama|thriller) tv|rakuten tv (sci fi|thrillers)")
MORE_STRONG_WORDS = [
    ("Game Shows", "Game Shows", r"game shows?|buzzr|quiz"),
    ("Sitcoms (USA)", "Sitcoms", r"sitcoms?|sitcom legends|classic tv comedy|classic tv families"),
    ("Music", None, r"vevo|xite|stingray|mtv|now (70|80|90|rock)\S*|trace|billboard|music|concerts?|"
                    r"qello|djazz|jazz|vinyl|feva|playing for change|k ?pop|karaoke|tiktok radio|cmt|"
                    r"hits|disco|oldies|jukebox|anthems"),
    ("Documentaries", "Outdoors", r"hunt|hunting|fish|fishing|boating|outdoors?|meateater|"
                                  r"backcountry|camping|game and fish|game & fish|pursuit|waypoint|"
                                  r"shooting|xtreme outdoor|tough jobs|tuna|swamp|mountain men|outside tv"),
    ("Sports", None, r"sports?|sportszone|tennis|tennischannel|nba|nfl|mlb|nhl|ufc|pfl|wwe|tna|"
                     r"wrestling|boxing|mma|bellator|fight|fite|grappling|combat|golf|golfpass|pga|billiards?|snooker|darts|"
                     r"poker|pokergo|rally|rallytv|motogp|nascar|nhra|racing|racer|mtrspt 1|speed sport|"
                     r"monster jam|pbr|surf|surfing|surfer|red bull|xtrem|fuel|nautical|goal|golazo|fifa|"
                     r"uefa|football|soccer|gfn|rugby|cricket|willow|pickleball|pickleballtv|strongman|ocho|"
                     r"big 12|accdn|homerun|hoop|flohockey|floracing|itsf|kozoom|chukker|overtime|stadium|"
                     r"sportsgrid|draftkings|jim rome|team usa|victory|bein|awsn|dominance fc|top rank|dazn|"
                     r"equus|horse|rightnow|slvr|ace"),
    ("Documentaries", "Motoring", r"motors?|motoring|motortrend|top gear|cars|auto|automotion|garage|"
                                  r"torque|hagerty|drive|road renegades|wheels|trucks?|"
                                  r"truckers|rig|choppertown|powernation|petrolheads|turbo|transport|"
                                  r"test my ride|introuble|motorvision"),
    ("Food, Home & Travel", "Food", r"food|cook'?s?|cooking|kitchen|chefs?|tasty|cuisine|masterchef|menu|"
                                    r"emeril|rachael ray|hot ones|bon app[eé]tit|gordon ramsay|come dine|"
                                    r"hungry|gusto|gustotv|jamie oliver|martha stewart|smokehouse|"
                                    r"delicious|wine|whiskey|tastemade(?! home| travel)"),
    ("Food, Home & Travel", "Home", r"home|homes|homeful|house|property|garden|gardening|reno|renovation|"
                                    r"flipping|listing|crafts?|craftsy|diy|makers|design|attic|"
                                    r"antiques?|handyman|hammer|four in a bed|hotel inspector|"
                                    r"rustic retreats|thesorrygirls|bob ross|escape to the country|"
                                    r"new life in the sun|made nation|how to|inside outside"),
    ("Food, Home & Travel", "Travel", r"travel|escapes?|voyages|tourism|heritage|luxury|downunder|"
                                      r"trips?|journy|intravel|yachting"),
    ("Documentaries", "True Crime", r"true crime|forensic|cold case|first 48|dateline|killers?|evil|"
                                    r"medical examiner|dr g|detectives|jail|cops|live pd|court tv|"
                                    r"bounty hunter|investigation|criminals|crime and justice|"
                                    r"crime & justice|"
                                    r"crimes cults|crime beat|real crime|total crime|crime 24 7|"
                                    r"law & crime|law and crime|trublu|reelz|american crimes"),
    ("Documentaries", "Paranormal", r"paranormal|ghosts?|haunt|haunted|unxplained|unexplained|aliens?|"
                                    r"ufo|conspiracy|xplored|unidentified|mysterious worlds"),
    ("Documentaries", "Science & Space", r"science|space|cosmic|universe|curiosity|mythbusters|"
                                         r"digital studios|ted|magellantv|explorers|inwonder|wonder|xplore"),
    ("Documentaries", "History", r"history|historical|military|war|warfare|ancient|dinos|biography|"
                                 r"genealogy|smithsonian|the past|discovering china|autentic"),
    ("Documentaries", "Nature", r"nature|naturetime|wild|wildlife|wildearth|earth|animals?|pets?|dogs?|"
                                r"dog whisperer|dogtv|vet|zoo|planet|waterbear|terra mater|inwild|"
                                r"paws|clarity|brave wilderness"),
    ("Documentaries", "General", r"documentary|documentaries|docu|vice|get factual|cnn originals|"
                                 r"disaster|mayday|danger|dangertv|real heroes|backstage"),
    ("Series", "Reality", r"reality|real housewives|we tv|keeping up|got talent|idol|ninja warrior|"
                          r"masked singer|biggest loser|pawn|storage wars|bad girls|cheaters|"
                          r"caught on tape|chaos on cam|challenge|repo|married|marriage|dating|weddings?|bride|"
                          r"judge|nosey|queens of reality|logo|perform|true lives|competition|deal masters|"
                          r"deal zone|lives|unscripted|4uv"),
]
# Genre words that do not say films from series: the channel's guide, or else its Pluto genre or
# iptv-org category, settles which. (movie sub-genre, words) in order, first match wins. Each is a
# genre on its own: "love", "heart", "alter" and "fury" were not ("Love Pets", "Series K Heart").
GENRE_WORDS = [
    ("Martial Arts", r"kung fu|hi yah|martial arts"),
    ("Action", r"action"),
    ("Comedy", r"comedy|comedies|laughs?|lol|funny|jokes|gags|lampoon|stand up"),
    ("Romance", r"romance|romantic|love (&|and) drama|hallmark|sparkle"),
    ("Horror", r"horror|terror|scares|fright|monsters|shudder|outersphere"),
    ("Thriller", r"thrillers?|thrillher|suspense|mystery|mysteries|crime|crimes|detectives?|sherlock|"
                 r"murder|mayhem"),
    ("Sci-Fi", r"sci fi|scifi|fantastic|fantasy|supernatural|dark matter"),
    ("Westerns", r"westerns?|cowboy|western bound|grjngo|wild west"),
    ("Black Cinema", r"bet cinema|black cinema|ebony|bounce|shades of black|nolly|naija|urban"),
    ("Cult & B-Movies", r"cult|pulp|asylum|grindhouse|drive in|el rey"),
    ("Indie & World", r"curzon|tribeca|gravitas|indie|independent|icon film"),
    ("Family", r"family|dove"),
    ("Drama", r"drama|dramas|stories|series|heartfelt|lifetime|k content"),
]
# Words that say when, or say little, not what: a guide of one kind of show, or Pluto's genres,
# beat them ("BET Classics" airs The Jeffersons and Webster; "Pluto TV Adventure" airs Snowpiercer
# and The 100). Only a name with no other genre word falls back on one.
WEAK_GENRE_WORDS = [
    ("Decades", r"\d0s|\d0's|throwback|rewind|replay"),
    ("Classic", r"classics?|retro|vault|gold|forever"),
    ("Action", r"adventure"),
]

# Sub-block words inside a block the name or guide has already settled. First match wins.
# One decade alone is too few channels to surf (the 60s had one, the 2000s two), so the 60s share
# with the 70s and the 2000s with the 90s.
MUSIC_SUBS = [
    ("60s & 70s", r"60s|60's|sixties|oldies|jukebox|70s|70's|seventies"),
    ("80s", r"80s|80's|eighties"), ("90s & 2000s", r"90s|90's|90s00s|nineties|2000s|00s|2k|y2k"),
    ("Concerts & Live", r"concerts?|qello|live music|playing for change|vinyl"),
    ("Other", r"karaoke|movie music|holiday|christmas|gospel|reggae|"
              r"cozy cafe|easy listening|feva"),
    ("Rock", r"rock|metal|alternative"), ("Country", r"country|cmt"),
    ("Hip-Hop/R&B", r"hip hop|r&b|rnb|rap|soul|urban|trace|jams|yo"),
    ("Classical/Jazz", r"classica|classical|jazz|djazz|piano"),
    ("Pop", r"pop|hits?|hit list|tiktok|billboard|spankin|euro|disco|celebrates|k ?pop|kpop"),
]
SPORTS_SUBS = [
    ("Combat", r"ufc|pfl|wwe|tna|wrestling|boxing|mma|bellator|fight|fite|grappling|combat|"
               r"dominance fc|top rank|dazn|bkfc"),
    ("Soccer", r"soccer|goal|golazo|fifa|uefa|gfn|football|bein"),
    ("US Leagues", r"nba|nfl|mlb|nhl|hockey|flohockey|accdn|big 12|homerun|hoop"),
    ("Motorsport", r"rally|rallytv|motogp|nascar|nhra|racing|racer|mtrspt 1|speed sport|monster jam|"
                   r"floracing|motorsports?"),
    ("Tennis & Golf", r"tennis|tennischannel|golf|golfpass|pga|t2|pickleball|pickleballtv"),
    ("Cue, Darts & Poker", r"billiards?|snooker|darts|poker|pokergo|kozoom|itsf"),
    ("Action & Extreme", r"surf|surfing|surfer|red bull|xtrem|fuel|nautical|ocho|strongman|pbr"),
    ("Other Sports", r"rugby|cricket|willow|chukker|horse|equus|shooting"),
]
# Finer sub-blocks for sub-blocks too big to surf: (block, sub) -> [(finer sub, name words, shows,
# channel groups or FAST categories)]. A channel moves to the first finer sub whose words are in its
# name; else the one whose show IS its name; else the one REFINE_SHARE of its guide's airtime
# belongs to; else the one its group or category names. Otherwise it stays where it was.
REFINE_SHARE = 0.5
# A guide settles a finer sub-block, or overrides a weak name word, only with this many different
# known shows: twelve hours of one show's marathon is a sample of the rotation, not the channel.
MIN_SHOWS = 2
REFINE = {
    ("Series", "Drama"): [
        ("K-Drama", r"k drama|k content|k stories|series k|korean", [], ()),
        ("Family & Teen Drama", r"family|feel good|hometown|heartfelt|generation|teen|byutv|dhar mann", [
            "heartland", "when calls the heart", "touched by an angel", "dr quinn medicine woman",
            "7th heaven", "everwood", "hart of dixie", "degrassi", "edgemont", "one tree hill",
            "gilmore girls", "mcleod's daughters", "highway to heaven", "ruby and the well",
            "holly hobbie"], ()),
        ("Classic Drama", r"classic|forever|tv land|retro", [
            "lassie", "little house on the prairie", "the waltons", "dallas", "melrose place",
            "twin peaks", "heartbeat", "in the heat of the night"],
         ("Classic TV", "Western & Classic TV")),
    ],
    ("Series", "Reality"): [
        ("Judge & Talk", r"judge|nosey|court", [
            "judge mathis", "people's court", "the people's court", "judge judy", "divorce court",
            "cutlers court", "couples court with the cutlers", "the steve wilkos show",
            "the jerry springer show", "maury"], ("Talk", "Talk Show")),
        ("Dating Reality", r"dating|married|marriage|weddings?|bride|cheaters", [
            "flavor of love", "double shot at love with dj pauly d and vinny", "married at first sight",
            "90 day fiance", "love island", "the bachelor", "catfish", "cheaters", "ex isle",
            "marriage boot camp reality stars", "don't tell the bride", "bridezillas",
            "love after lockup"], ()),
        ("Competition Reality", r"competition|got talent|idol|ninja warrior|masked singer|"
                                r"biggest loser|challenge|wipeout|fear factor|robot wars|mr ?beast", [
            "survivor", "america's got talent", "americas got talent", "american idol",
            "american ninja warrior", "ninja warrior", "the masked singer", "the biggest loser",
            "wipeout", "total wipeout", "total wipeout uk", "fear factor", "robot wars",
            "forged in fire", "project runway", "project runway all stars", "skin wars fresh paint",
            "steve austin's broken skull challenge", "rupaul's drag race", "bring it", "big brother",
            "mr beast", "mrbeast", "back to school with mrbeast", "alone", "hell's kitchen"],
         ("Reality Competition", "Competition Reality")),
        ("Pawn & Deals", r"pawn|deals?|storage|repo|pickers|salvage", [
            "pawn stars", "hardcore pawn", "beverly hills pawn", "storage wars", "auction hunters",
            "shipping wars", "extreme salvage squad", "salvage kings", "flipping bangers",
            "american pickers", "operation repo", "container wars"], ()),
        ("Docu-Reality", r"lives|rescue|hoarders|intervention|undercover boss|vet", [
            "hoarders", "intervention", "untold stories of the er", "bondi rescue", "bondi vet",
            "undercover boss", "duck dynasty", "escaping polygamy", "kitchen nightmares",
            "ramsay's kitchen nightmares"], ()),
    ],
    ("Food, Home & Travel", "Food"): [
        ("Food Reality", r"chefs?|masterchef|come dine|kitchen nightmares|hell's kitchen|"
                         r"gordon ramsay|top chef|great british menu", [
            "buddy vs duff", "great british menu", "hell's kitchen", "kitchen nightmares",
            "ramsay's kitchen nightmares", "come dine with me", "couples come dine with me",
            "masterchef", "top chef", "chopped", "gordon ramsay", "fire masters"], ()),
    ],
    ("Food, Home & Travel", "Home"): [
        ("Antiques", r"antiques?|roadshow|attic", ["antiques roadshow", "cash in the attic"], ()),
        ("Crafts & Garden", r"crafts?|craftsy|diy|makers|how to|gardening|bob ross|thesorrygirls|"
                            r"handyman", [
            "the joy of painting with bob ross", "bob ross", "this old house makers"], ()),
    ],
    ("Documentaries", "Nature"): [
        ("Pets & Vets", r"pets?|dogs?|dogtv|vets?|paws|cats?", [
            "dog whisperer", "dog whisperer with cesar millan", "lucky dog", "the yorkshire vet",
            "the yorkshire vet casebook", "er vets", "pet vet dream team", "vets saving pets",
            "countdown the funniest pets", "animals unscripted", "bondi vet"], ()),
    ],
    ("Documentaries", "True Crime"): [
        ("Cops & Courts", r"cops|jail|court|patrol|live pd|law (&|and) crime|bounty hunter|"
                          r"criminals|police|troopers", [
            "cops", "cops reloaded", "live pd", "live pd police patrol", "on patrol live",
            "court cam", "court tv live", "bodycam by law and crime", "jail", "60 days in",
            "alaska state troopers", "campus pd", "dog the bounty hunter", "takedown with chris hansen",
            "sheriffs el dorado county", "women behind bars", "24 hours in police custody",
            "interrogations by law and crime", "the interrogators", "the interrogators no shelter"], ()),
        ("Forensics & Cold Cases", r"forensics?|cold case|medical examiner|dr g|detectives|unsolved|"
                                   r"first 48|crime scenes?|investigation", [
            "forensic files", "cold case files", "the first 48", "medical detectives",
            "dr g medical examiner", "bloodline detectives", "unsolved mysteries", "buzzfeed unsolved",
            "crime scene solvers", "forensic factor", "robbie coltrane's critical evidence",
            "the fbi files", "conmen case files"], ()),
    ],
}
# Each finer sub-block and the sub-block it folds back into when too small. Series – Westerns is
# made by SHOWS and GENRE_WORDS, not REFINE, but folds the same way.
FINER_PARENT = {(block, rule[0]): (block, sub) for (block, sub), rules in REFINE.items() for rule in rules}
FINER_PARENT[("Series", "Westerns")] = ("Series", "Action")
REFINE_SHOWS = {(block, sub, rule[0]): {show_key(show) for show in rule[2]}
                for (block, sub), rules in REFINE.items() for rule in rules}

# Pluto's programme genres (the part before the slash) -> a movie sub-genre.
PLUTO_MOVIE_GENRES = {
    "Action & Adventure": "Action", "Comedy": "Comedy", "Romance": "Romance", "Horror": "Horror",
    "Thriller": "Thriller", "Crime": "Thriller", "Sci-Fi & Fantasy": "Sci-Fi", "Western": "Westerns",
    "Children & Family": "Family", "Drama": "Drama", "Musical": "Classic", "War": "Action",
}
# Pluto's programme genres -> a block outright, for genres that are not film genres.
PLUTO_BLOCK_GENRES = {
    "Documentary": ("Documentaries", "General"), "Paranormal": ("Documentaries", "Paranormal"),
    "Reality": ("Series", "Reality"), "Game Show": ("Game Shows", "Game Shows"),
    "Talk Show": ("Series", "Reality"), "Anime": ("Anime", "Anime"), "Music": ("Music", None),
    "Sports": ("Sports", None), "Food & Cooking": ("Food, Home & Travel", "Food"),
    "Home & Lifestyle": ("Food, Home & Travel", "Home"), "Crime": ("Documentaries", "True Crime"),
    "Instructional & Educational": ("Documentaries", "General"),
}
PLUTO_MUSIC_GENRES = {"Pop": "Pop", "Rock": "Rock", "Country": "Country", "R&B": "Hip-Hop/R&B",
                      "Hip-Hop": "Hip-Hop/R&B", "Jazz": "Classical/Jazz", "Classical": "Classical/Jazz"}
# A service's own channel group -> a block (and sub, or a movie sub-genre marked "film:").
GROUPS = {
    "Movies": ("Movies", MIXED_MOVIES), "Crime": ("Documentaries", "True Crime"),
    "True Crime": ("Documentaries", "True Crime"), "Reality TV": ("Series", "Reality"),
    "Reality": ("Series", "Reality"), "Reality Competition": ("Series", "Reality"),
    "Competition Reality": ("Series", "Reality"), "Real Life Adventure": ("Series", "Reality"),
    "Comedy": ("film", "Comedy"), "Kids": ("Cartoons & Kids", "Kids"), "Anime": ("Anime", "Anime"),
    "Music": ("Music", None), "Music Videos": ("Music", None),
    "Music & Ambient": ("Music", None), "Ambiance": ("Relax", "Relax"), "Sports": ("Sports", None),
    "Sports & Outdoors": ("Sports", None), "Sport": ("Sports", None),
    "Motor Sports": ("Sports", "Motorsport"),
    "Home & Food": ("Food, Home & Travel", "Food"), "Home + Food": ("Food, Home & Travel", "Food"),
    "Food & Travel": ("Food, Home & Travel", "Food"), "Living": ("Food, Home & Travel", "Home"),
    "Nature, History & Science": ("Documentaries", "General"), "Documentaries": ("Documentaries", "General"),
    "Nature": ("Documentaries", "Nature"), "Animals + Nature": ("Documentaries", "Nature"),
    "History + Science": ("Documentaries", "History"), "Paranormal": ("Documentaries", "Paranormal"),
    "Action & Drama": ("film", "Drama"), "TV Series": ("Series", "Drama"), "Drama": ("film", "Drama"),
    "Bingeable Drama": ("Series", "Drama"), "Crime Drama": ("Series", "Crime"),
    "Classic TV": ("Series", "Drama"), "Sci-Fi & Horror": ("film", "Horror"), "Sci-Fi": ("film", "Sci-Fi"),
    "Sci-Fi & Fantasy": ("film", "Sci-Fi"), "Western & Classic TV": ("film", "Westerns"),
    "Western": ("film", "Westerns"), "Westerns": ("film", "Westerns"), "Romance": ("film", "Romance"),
    "Action": ("film", "Action"), "Game Shows": ("Game Shows", "Game Shows"),
    "Game Show": ("Game Shows", "Game Shows"),
}
# A "film:" group that is not films: Samsung's classic TV is mostly drama, not westerns.
GROUP_SERIES = {"Western & Classic TV": "Drama"}
# The Pluto dial's genre or the FAST pass's category -> a block: the last word before Unsorted.
HINTS = {
    "Movies": ("Movies", MIXED_MOVIES), "Drama & Series": ("Series", "Drama"),
    "Classic TV": ("Series", "Drama"), "Comedy": ("Series", "Comedy"),
    "Crime": ("Documentaries", "True Crime"), "Reality": ("Series", "Reality"),
    "Game Shows": ("Game Shows", "Game Shows"), "Documentary": ("Documentaries", "General"),
    "Kids": ("Cartoons & Kids", "Kids"), "Anime": ("Anime", "Anime"), "Music": ("Music", None),
    "Relax": ("Relax", "Relax"), "Sports": ("Sports", None), "Motoring": ("Documentaries", "Motoring"),
    "Food & Home": ("Food, Home & Travel", "Food"), "Outdoors": ("Documentaries", "Outdoors"),
    "Travel": ("Food, Home & Travel", "Travel"),
}
# A guide this much of one kind of show, or of one genre, decides.
GUIDE_SHARE = 0.6
GENRE_SHARE = 0.5
MOVIES_HINT_FILM_SHARE = 0.1


def shares(counts):
    """{key: fraction of the total} for a {key: minutes} map; {} when there are no minutes."""
    total = sum(counts.values())
    return {k: v / total for k, v in counts.items()} if total else {}


def top_genre(genres):
    """(top-level genre, share, subGenre shares) of a Pluto channel's `genre/subGenre` minutes."""
    top = {}
    for key, minutes in genres.items():
        top[key.split("/")[0]] = top.get(key.split("/")[0], 0) + minutes
    if not top:
        return None, 0, {}
    name, share = max(shares(top).items(), key=lambda kv: kv[1])
    subs = shares({k.split("/", 1)[1]: v for k, v in genres.items() if k.split("/")[0] == name})
    return name, share, subs


def is_film(channel, ev, text):
    """(True if films, why) - by the name, else the guide, else the Pluto genre or FAST category."""
    if has_word(MOVIE_WORDS, text):
        return True, "films"
    kinds = shares((ev or {}).get("kinds") or {})
    if kinds.get("film", 0) >= 0.5:
        return True, "guide: films"
    # A 12-hour guide can catch a film channel in a series marathon: one the owner filed under
    # Movies stays films while any films are on.
    if channel["hint"] == "Movies" and kinds.get("film", 0) >= MOVIES_HINT_FILM_SHARE:
        return True, "Pluto dial genre Movies, some films on"
    if kinds.get("tv", 0) >= 0.5:
        return False, "guide: series"
    return channel["hint"] == "Movies", "films" if channel["hint"] == "Movies" else "series"


def as_form(channel, ev, text, movie_sub, why, series_sub=None):
    """A movie sub-genre as Movies/<sub> for a channel of films, Series/<equivalent> otherwise -
    except that crime which is not films, on a channel filed under Crime, is true crime."""
    film, form_why = is_film(channel, ev, text)
    if film:
        return "Movies", movie_sub, "%s (%s)" % (why, form_why)
    series_sub = series_sub or SERIES_FOR[movie_sub]
    if series_sub == "Crime" and channel["hint"] == "Crime":
        return "Documentaries", "True Crime", "%s (%s, filed under Crime)" % (why, form_why)
    return "Series", series_sub, "%s (%s)" % (why, form_why)


def via(ev):
    return " via %s" % ev["via"] if ev and ev.get("via") else ""


def guide_vote(ev, share=GUIDE_SHARE, min_shows=1):
    """(block, sub, why) when [share] or more of a guide's airtime is known shows of one kind -
    [min_shows] different ones or more, where one show's marathon is too thin a sample."""
    if not ev or not ev.get("titles"):
        return None
    votes, named, shows = {}, {}, {}
    total = sum((ev.get("kinds") or {}).values()) or sum(ev["titles"].values())
    for title, minutes in ev["titles"].items():
        show = show_of_title(title)
        if show:
            votes[show[:3]] = votes.get(show[:3], 0) + minutes
            named.setdefault(show[:3], []).append((minutes, title))
            shows.setdefault(show[:3], set()).add(show[3])
    if not votes or not total:
        return None
    (block, sub, kind), minutes = max(votes.items(), key=lambda kv: kv[1])
    if minutes / total < share or len(shows[(block, sub, kind)]) < min_shows:
        return None
    titles = [t for _, t in sorted(named[(block, sub, kind)], reverse=True)[:2]]
    return block, sub, "guide: mostly %s (%s)%s" % (", ".join(titles), kind, via(ev))


def agrees(vote, movie_sub):
    """True when a guide's (block, sub) fits a genre word from the name."""
    block, sub = vote[0], vote[1]
    return (block not in ("Movies", "Series", "Sitcoms (USA)")
            or (block == "Movies" and sub == movie_sub)
            or (block == "Series" and sub == SERIES_FOR[movie_sub])
            or (block == "Sitcoms (USA)" and movie_sub == "Comedy"))


def guide_genres(channel, ev, text):
    """(block, sub, why) from Pluto's programme genres, then the service's own channel group."""
    if not ev:
        return None
    genre, share, subs = top_genre(ev.get("genres") or {})
    if genre and share >= GENRE_SHARE:
        why = "guide genres: %s %d%%" % (genre, round(share * 100))
        if genre in PLUTO_MOVIE_GENRES:
            if genre == "Action & Adventure" and subs.get("Martial Arts", 0) >= GENRE_SHARE:
                return as_form(channel, ev, text, "Martial Arts", why + " (martial arts)")
            placed = as_form(channel, ev, text, PLUTO_MOVIE_GENRES[genre], why)
            # Pluto's own rotating picks ("Pluto TV Icons", "Staff Picks", "Trending Now") air any
            # genre; twelve hours of guide is a sample of the rotation, not what the channel is.
            if placed and placed[0] == "Movies" and text.lower().startswith("pluto tv "):
                return "Movies", MIXED_MOVIES, "Pluto's rotating movie picks (%s now)" % genre
            return placed
        if genre == "Children & Family":
            return "Cartoons & Kids", "Kids", why
        if genre in PLUTO_BLOCK_GENRES:
            block, sub = PLUTO_BLOCK_GENRES[genre]
            if genre == "Instructional & Educational":
                sub = "History" if subs.get("History & Social Studies", 0) >= 0.5 else (
                    "Science & Space" if subs.get("Science", 0) >= 0.5 else "General")
            return block, sub, why
    for group in ev.get("groups") or []:
        if group in GROUPS:
            block, sub = GROUPS[group]
            why = "%s group: %s%s" % ("Pluto" if channel["source"] == "pluto" else "service", group, via(ev))
            if block == "film":
                return as_form(channel, ev, text, sub, why, GROUP_SERIES.get(group))
            return block, sub, why
    return None


def classify(channel, ev):
    """(block, sub, why) for one channel: an exact name, a known show, strong name words, the
    guide's shows, genre words in the name, the guide's genres, then the Pluto genre or FAST
    category. A sub of None here is settled by settle_sub."""
    name = channel["name"]
    text = words(name)
    if norm_name(name) in OFF_FORMAT:
        return FLAGGED, FLAGGED, "flagged off: " + OFF_FORMAT[norm_name(name)]
    if norm_name(name) in EXACT:
        return EXACT[norm_name(name)]
    show = show_in_name(name)
    if show:
        return show[0], show[1], 'name is the show "%s" (%s)' % (show[3], show[2])
    found, word = first_match([((b, s), p) for b, s, p in STRONG_WORDS], text)
    if found:
        return found[0], found[1], 'name: "%s"' % word
    if has_word(MOVIE_WORDS, text):
        movie_sub, word = first_match(GENRE_WORDS + WEAK_GENRE_WORDS, text)
        if movie_sub:
            return "Movies", movie_sub, 'name: films, "%s"' % word
        return "Movies", None, "name: films"
    found, word = first_match([((b, s), p) for b, s, p in MORE_STRONG_WORDS], text)
    if found:
        return found[0], found[1], 'name: "%s"' % word
    # The guide's shows decide, unless the name says a genre they contradict: "Pluto TV Horror"
    # stays horror through a Colonel March marathon, "Classic TV Drama" drama through Diagnosis
    # Murder. A guide of documentaries is not contradicted by a topic word: "Pluto TV Crime".
    movie_sub, word = first_match(GENRE_WORDS, text)
    weak_sub, weak_word = first_match(WEAK_GENRE_WORDS, text)
    # A word that says only when ("80s", "Classics") yields to a guide of MIN_SHOWS known shows,
    # half of one kind, and otherwise stands: a decade's films rotate genres, so Pluto's genres
    # would only sample them.
    vote = (guide_vote(ev, GENRE_SHARE, MIN_SHOWS) if weak_sub and not movie_sub else guide_vote(ev))
    if vote and (movie_sub is None or agrees(vote, movie_sub)):
        return vote
    if movie_sub:
        return as_form(channel, ev, text, movie_sub, 'name: "%s"' % word)
    if weak_sub:
        return as_form(channel, ev, text, weak_sub, 'name: "%s"' % weak_word)
    from_guide = guide_genres(channel, ev, text)
    if from_guide:
        return from_guide
    if channel["hint"] in HINTS:
        block, sub = HINTS[channel["hint"]]
        return block, sub, "%s: %s" % ("Pluto dial genre" if channel["source"] == "pluto"
                                       else "FAST category", channel["hint"])
    groups = [g for g in (ev or {}).get("groups") or [] if g]
    # A guide that is mostly films, with no channel group to say what kind: a film channel.
    if not groups and shares((ev or {}).get("kinds") or {}).get("film", 0) >= GUIDE_SHARE:
        return "Movies", None, "guide: films%s" % via(ev)
    if groups:
        return "Unsorted", "Unsorted", "service group: %s%s - no block for it" % (", ".join(groups), via(ev))
    return "Unsorted", "Unsorted", "nothing in the name, guide or category says what it is"


def first_match(rules, text):
    """(result, the words that matched) for the first (result, words) rule matching [text]."""
    for result, pattern in rules:
        match = re.search(r"\b(?:%s)\b" % pattern, unicodedata.normalize("NFC", text.lower()))
        if match:
            return result, match.group(0)
    return None, None


def settle_sub(channel, ev, block, sub, why):
    """(sub, why) with a sub-block for a channel whose block is settled but sub is not yet."""
    text = words(channel["name"])
    if block == "Movies" and sub is None:
        genre, share, subs = top_genre((ev or {}).get("genres") or {})
        if genre in PLUTO_MOVIE_GENRES and share >= GENRE_SHARE:
            if genre == "Action & Adventure" and subs.get("Martial Arts", 0) >= GENRE_SHARE:
                return "Martial Arts", "%s; guide genres: martial arts" % why
            return PLUTO_MOVIE_GENRES[genre], "%s; guide genres: %s %d%%" % (why, genre, round(share * 100))
        for group in (ev or {}).get("groups") or []:
            if GROUPS.get(group, (None,))[0] == "film":
                return GROUPS[group][1], "%s; group: %s%s" % (why, group, via(ev))
        return MIXED_MOVIES, why
    if block == "Music" and sub is None:
        found, word = first_match(MUSIC_SUBS, text)
        if found:
            return found, why if word in why else '%s; "%s"' % (why, word)
        genre, share, subs = top_genre((ev or {}).get("genres") or {})
        if genre == "Music" and subs:
            style, part = max(subs.items(), key=lambda kv: kv[1])
            for key, value in PLUTO_MUSIC_GENRES.items():
                if style.startswith(key) and part >= GENRE_SHARE:
                    return value, "%s; guide genres: %s" % (why, style)
        return "Other", why
    if block == "Sports" and sub is None:
        found, word = first_match(SPORTS_SUBS, text)
        if found:
            return found, why if word in why else '%s; "%s"' % (why, word)
        return "General", why
    if sub is None:
        return dict(BLOCKS)[block][0], why
    return sub, why


def refine(channel, ev, block, sub, why):
    """(sub, why) with a finer sub-block from REFINE, or the sub as it was. In Cartoons & Kids a
    guide of one kind of show settles which of the three, over the word that chose the block."""
    if block == "Cartoons & Kids":
        vote = guide_vote(ev)
        if vote and vote[0] == block and vote[1] != sub:
            return vote[1], "%s; %s" % (why, vote[2])
        return sub, why
    rules = REFINE.get((block, sub))
    if not rules:
        return sub, why
    found, word = first_match([(rule[0], rule[1]) for rule in rules if rule[1]], words(channel["name"]))
    if found:
        return found, why if word in why else '%s; "%s"' % (why, word)
    name_key = show_key(channel["name"])
    for rule in rules:
        if name_key in REFINE_SHOWS[(block, sub, rule[0])]:
            return rule[0], why
    if ev and ev.get("titles"):
        total = sum((ev.get("kinds") or {}).values()) or sum(ev["titles"].values())
        votes, shows = collections.Counter(), collections.defaultdict(set)
        for title, minutes in ev["titles"].items():
            for rule in rules:
                keys = REFINE_SHOWS[(block, sub, rule[0])]
                matched = [k for k in title_keys(title) if k in keys]
                if matched:
                    votes[rule[0]] += minutes
                    shows[rule[0]].add(matched[0])
                    break
        if votes:
            finer, minutes = votes.most_common(1)[0]
            if minutes / total >= REFINE_SHARE and len(shows[finer]) >= MIN_SHOWS:
                return finer, "%s; guide: mostly %s shows" % (why, finer.lower())
    labels = [channel["hint"]] + list((ev or {}).get("groups") or [])
    for rule in rules:
        if any(label in rule[3] for label in labels):
            return rule[0], "%s; filed under %s" % (why, next(l for l in labels if l in rule[3]))
    return sub, why


def fold_finer_subs(channels):
    """A finer sub-block with fewer than MIN_EXTRA channels folds back into the one it came from."""
    counts = collections.Counter((c["block"], c["sub"]) for c in channels)
    for c in channels:
        parent = FINER_PARENT.get((c["block"], c["sub"]))
        if parent and counts[(c["block"], c["sub"])] < MIN_EXTRA:
            c["why"] += "; too few %s channels, so %s" % (c["sub"], parent[1])
            c["sub"] = parent[1]


def place(channel, ev):
    """(block, sub, why) for one channel: classify, then settle_sub, then refine."""
    block, sub, why = classify(channel, ev)
    sub, why = settle_sub(channel, ev, block, sub, why)
    sub, why = refine(channel, ev, block, sub, why)
    return block, sub, why


def fold_movie_subs(channels):
    """Movie sub-genres beyond the owner's ten stay only with MIN_EXTRA or more channels; fewer
    join Movies – Mixed. Returns the extra sub-blocks kept, in EXTRA_MOVIE_SUBS order."""
    counts = collections.Counter(c["sub"] for c in channels if c["block"] == "Movies")
    kept = [s for s in EXTRA_MOVIE_SUBS if counts.get(s, 0) >= MIN_EXTRA]
    for c in channels:
        if c["block"] == "Movies" and c["sub"] in EXTRA_MOVIE_SUBS and c["sub"] not in kept:
            c["why"] += "; too few %s channels, so Mixed" % c["sub"]
            c["sub"] = MIXED_MOVIES
    return kept


# ---- numbers ------------------------------------------------------------------------------------

def round_up(n, step):
    return -(-n // step) * step


def sub_order(extra_movie_subs=()):
    """[(block, [subs])] in dial order, with the kept extra movie subs before Movies – Mixed."""
    order = []
    for block, subs in BLOCKS:
        if block == "Movies":
            subs = subs[:-1] + list(extra_movie_subs) + subs[-1:]
        order.append((block, subs))
    return order


def surf_order(channel):
    """Where a channel sits in its sub-block: alphabetical, as the owner's picking list reads,
    ignoring case and a leading "The" - so "The Conners" sits with the Cs. The source is not the
    order: a Pluto channel and a Tubi one of the same kind are the same to a viewer surfing."""
    name = channel["name"].lower()
    return (re.sub(r"^the\s+", "", name), name)


def number(channels, extra_movie_subs=()):
    """Give every channel its number; return the blocks as published.

    Each block starts on a round hundred and each sub-block on a round ten within it, leaving at
    least one free number after every sub-block. A block that needs more than a hundred numbers
    keeps going into the next hundreds, and every later block moves up."""
    blocks, next_hundred = [], 100
    for block, subs in sub_order(extra_movie_subs):
        members = [c for c in channels if c["block"] == block]
        if not members:
            continue
        entry = {"name": block, "first_number": next_hundred, "default": block not in OFF_BY_DEFAULT,
                 "sub": []}
        position, last = next_hundred, next_hundred
        for sub in subs:
            group = sorted((c for c in members if c["sub"] == sub), key=surf_order)
            if not group:
                continue
            first = round_up(position, 10)
            for offset, channel in enumerate(group):
                channel["number"] = first + offset
            last = first + len(group) - 1
            entry["sub"].append({"name": sub, "first_number": first})
            position = round_up(last + 2, 10)
        blocks.append(entry)
        next_hundred = round_up(last + 1, 100)
    return blocks


# ---- output -------------------------------------------------------------------------------------

def record(channel):
    """One channel as live_draft.json publishes it."""
    out = {"number": channel["number"], "name": channel["name"], "block": channel["block"],
           "sub": channel["sub"], "source": channel["source"]}
    if channel["source"] == "pluto":
        out["pluto"] = channel["pluto"]
        out["streams"] = channel["streams"]
    else:
        out["url"] = channel["url"]
        out["route"] = channel["route"]
        if channel.get("headers"):
            out["headers"] = channel["headers"]
    out["guide"] = channel["guide"]
    out["guide_id"] = channel.get("guide_id")
    out["home_only"] = channel["route"] == "us"
    out["default"] = channel["block"] not in OFF_BY_DEFAULT
    out["why"] = channel["why"]
    for key in ("tvg_id", "logo", "also", "merged"):
        if channel.get(key):
            out[key] = channel[key]
    return out


def has_guide(channel):
    """True when the channel has a guide the dial can read: Pluto's, or an id in a FAST guide."""
    return channel["guide"] == "pluto" or (channel["guide"] in FAST_GUIDES and bool(channel.get("guide_id")))


def rematch(channels, table):
    """Apply guide_ids.json, and Xumo's offset-free ids, to an existing draft's channels in place,
    leaving blocks and numbers alone: a new guide id is not a reason to renumber the dial.
    Returns the names whose guide changed."""
    changed = []
    for channel in channels:
        if channel["source"] == "pluto":
            continue
        listed = listed_guide(table, channel["source"], channel["name"])
        if listed:
            guide, gid, _ = listed
        elif channel["guide"] == "xumo":
            guide, gid = "xumo", xumo_id(channel.get("guide_id"))
        else:
            continue
        if (guide, gid) != (channel["guide"], channel.get("guide_id")):
            channel["guide"], channel["guide_id"] = guide, gid
            changed.append(channel["name"])
    return changed


def summary(channels, blocks, merges):
    """The markdown the owner reads: counts per block and sub-block, then the merges."""
    guides = collections.Counter(c["guide"] if has_guide(c) else "none" for c in channels)
    lines = ["# LIVE TV dial - draft", "",
             "Generated by `build_live.py` from the Pluto dial and the live FAST candidates. "
             "%d channels: %d Pluto, %d FAST, %d merged away as duplicates. Guides: %s." % (
                 len(channels), sum(c["source"] == "pluto" for c in channels),
                 sum(c["source"] != "pluto" for c in channels), sum(len(m[1]) for m in merges),
                 ", ".join("%s %d" % (g, n) for g, n in guides.most_common())),
             "", "Guide = a guide id on the channel's own service: Pluto, Samsung TV Plus, Plex, Roku, "
             "Xumo, Tubi, Rakuten TV or Stirr (build_fast_guide.py reads all but Pluto, whose "
             "guide the app asks itself). Home-only = plays only "
             "through the home server's US tunnel. Relax, Unsorted and Flagged are off by default. "
             "Within a sub-block channels run alphabetically.", "",
             "Every channel's `why` in live_draft.json says what placed it. Only Pluto's guide "
             "carries programme genres; the Samsung, Plex and Roku guides give titles and lengths, "
             "so for those channels a guide counts through known show titles, films against "
             "series, and the service's own channel group. A channel with no guide of its own may "
             "be placed by what another service's same-named channel airs (`via`), but keeps "
             "`guide: none`.", "",
             "| Block | Sub-block | Numbers | Total | Pluto | FAST | Guide | No guide | Home-only |",
             "|---|---|---|---:|---:|---:|---:|---:|---:|"]
    for block in blocks:
        members = [c for c in channels if c["block"] == block["name"]]
        rows = [(s["name"], [c for c in members if c["sub"] == s["name"]]) for s in block["sub"]]
        rows.append(("**all**", members))
        for sub, group in rows:
            numbers = sorted(c["number"] for c in group)
            lines.append("| %s | %s | %d-%d | %d | %d | %d | %d | %d | %d |" % (
                block["name"] + ("" if block["default"] else " (off)"), sub, numbers[0], numbers[-1],
                len(group), sum(c["source"] == "pluto" for c in group),
                sum(c["source"] != "pluto" for c in group), sum(has_guide(c) for c in group),
                sum(not has_guide(c) for c in group), sum(c.get("route") == "us" for c in group)))
    lines += ["", "## Merged duplicates (%d)" % len(merges), "",
              "Kept first, then what it replaced. Pluto is kept over FAST, then a channel with its own "
              "guide, then one that plays direct.", ""]
    for kept, dropped in merges:
        lines.append("- %s <- %s" % (kept, ", ".join(dropped)))
    unsorted = sorted(c["name"] for c in channels if c["block"] == "Unsorted")
    lines += ["", "## Unsorted (%d)" % len(unsorted), "", ", ".join(unsorted), ""]
    flagged = [c for c in channels if c["block"] == FLAGGED]
    lines += ["## Flagged (%d)" % len(flagged), "",
              "Not what a retro cable dial carries (news, shopping, religion, adult, betting, not in "
              "English): listed, and off.", ""]
    lines += ["- %s: %s" % (c["name"], c["why"]) for c in flagged] + [""]
    return "\n".join(lines)


def build(dial, allowlist, candidates, guides):
    """(channels, blocks, merges): the whole draft from its inputs, nothing fetched here."""
    pool = pluto_channels(dial, allowlist) + fast_channels(candidates, guides)
    channels, merges = merge_duplicates(pool)
    for channel in channels:
        channel["block"], channel["sub"], channel["why"] = place(channel, guides.evidence(channel))
    fold_finer_subs(channels)
    extra = fold_movie_subs(channels)
    blocks = number(channels, extra)
    channels.sort(key=lambda c: c["number"])
    return channels, blocks, merges


def merges_from_summary(path):
    """The merges the last full build listed in its summary, as [(kept, [dropped])]: the draft's
    records keep only some of them, and never in the order they were made."""
    merges, inside = [], False
    with open(path) as f:
        for line in f:
            if line.startswith("## "):
                inside = line.startswith("## Merged duplicates")
            elif inside and line.startswith("- ") and " <- " in line:
                kept, dropped = line[2:].rstrip("\n").split(" <- ", 1)
                merges.append((kept, dropped.split(", ")))
    return merges


def rematch_draft():
    """--rematch: the committed draft with guide_ids.json applied, and its summary redone."""
    with open(OUT_JSON) as f:
        draft = json.load(f)
    changed = rematch(draft["channels"], load_guide_ids())
    merges = merges_from_summary(OUT_SUMMARY)
    with open(OUT_JSON, "w") as f:
        json.dump(draft, f, indent=1, ensure_ascii=False)
        f.write("\n")
    with open(OUT_SUMMARY, "w") as f:
        f.write(summary(draft["channels"], draft["blocks"], merges))
    print("%d channels' guides changed" % len(changed))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--cache", help="keep the fetched guides here, and reuse them on a rerun")
    parser.add_argument("--rematch", action="store_true",
                        help="only re-apply guide_ids.json to the committed draft: no fetching, no "
                             "reclassifying, no renumbering")
    args = parser.parse_args(argv)
    if args.rematch:
        return rematch_draft()
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)

    with open(PLUTO_DIAL) as f:
        dial = json.load(f)["channels"]
    with open(PLUTO_ALLOWLIST) as f:
        allowlist = json.load(f)
    with open(FAST_CANDIDATES) as f:
        candidates = json.load(f)["candidates"]
    try:
        guides = load_guides([(c.get("pluto") or {}).get("id") for c in dial if c.get("pluto")], args.cache)
    except Exception as e:  # without the mjh guides the draft would silently lose its guide ids
        print("could not load the guides: %s - nothing written" % e, file=sys.stderr)
        return 1

    channels, blocks, merges = build(dial, allowlist, candidates, guides)
    with open(OUT_JSON, "w") as f:
        json.dump({"blocks": blocks, "channels": [record(c) for c in channels]}, f, indent=1,
                  ensure_ascii=False)
        f.write("\n")
    with open(OUT_SUMMARY, "w") as f:
        f.write(summary(channels, blocks, merges))
    print("%d channels in %d blocks, %d merged away" % (
        len(channels), len(blocks), sum(len(m[1]) for m in merges)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
