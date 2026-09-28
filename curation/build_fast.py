#!/usr/bin/env python3
"""Build fast_candidates.json, the pool a third dial (FAST TV) will be picked from.

  python3 build_fast.py [--host hermanb@100.74.3.68]         fetch, filter, probe on the host, write
  python3 build_fast.py --direct d.json --us u.json          the same from saved probe results
  python3 build_fast.py probe urls.json                      the probe itself (what runs on the host)

FAST is free ad-supported streaming: Samsung TV Plus, Tubi, Xumo, Roku, Plex and the rest. iptv-org
publishes their channels as playlists, and its API says which language and category each one is.
This is a CANDIDATE list, not a lineup - the owner picks from it by category, the way he picked
pluto_lineup.json, and a picked channel reaches the televisions only through a lineup file that
does not exist yet. So nothing here writes anything the app reads.

The steps, in order:
  1. every channel in SOURCES, with its iptv-org metadata (by tvg-id; by exact name if it has none)
  2. drop what is not English, and news, business, weather, parliament, religion and shopping
  3. drop what the Pluto dial or the YouTube dial already has
  4. probe every remaining stream from Australia and through the home server's US tunnel
  5. collapse duplicates across sources, keeping the copy that plays from Australia
  6. write fast_candidates.json and fast_categories.md

Both probes run on the home server, so they see the same network the televisions do: (a) straight
out of its Australian residential line, (b) inside the `pluto-us` namespace, whose only way out is
a Mullvad US exit. The probe is this same file, copied to a temp dir there, run, and removed; it
changes nothing else on the server. Fetches are raw.githubusercontent.com and github.io only -
never api.github.com, whose 60/h limit the home IP shares with the televisions' update check.
"""
import argparse
import base64
import concurrent.futures
import json
import os
import re
import subprocess
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PLUTO_ALLOWLIST = os.path.join(HERE, "pluto_lineup.json")
YOUTUBE_DIAL = os.path.join(HERE, "..", "channels.json")
OUT_JSON = os.path.join(HERE, "fast_candidates.json")
OUT_SUMMARY = os.path.join(HERE, "fast_categories.md")

RAW = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/%s.m3u"
API = "https://iptv-org.github.io/api/%s.json"

# Order is the tie-break between two copies of one channel that probe the same: the Australian
# list first (its copy is the one most likely made for this country), then the UK, then the US.
SOURCES = [
    "au_samsung", "uk_samsung", "uk_rakuten", "us_samsung", "us_tubi", "us_xumo", "us_roku",
    "us_plex", "us_stirr", "us_sofast", "us_firetv", "ca_stingray",
    "us_distro", "us_vizio", "us_tcl", "us_amagi",
]
PLUTO_SOURCES = ["uk_pluto", "us_pluto"]

# iptv-org category ids that are dropped, with the reason the summary gives.
DROP_CATEGORIES = {
    "news": "news",
    "business": "business news",
    "weather": "news (weather)",
    "legislative": "news (parliament)",
    "religious": "religious",
    "shop": "shopping",
    "xxx": "adult",
}
# The same, for channels iptv-org knows nothing about: whole words in the name.
DROP_WORDS = [
    (r"news|newsmax|cnbc|bloomberg|cbsn|headlines|weather|weathernation|accuweather|the hill|"
      r"real america'?s voice", "news"),
    (r"church|gospel|christian|faith|bible|catholic|jesus|worship|islam|quran|torah", "religious"),
    (r"shop|shopping|qvc|hsn|jewelry|shophq", "shopping"),
]
# Local US broadcast stations (Tubi carries dozens): "ABC 2 Portland OR", "FOX 11 Green Bay WI".
# What they carry between network shows is local news, so they go with the news. A bare call
# sign and city ("WSOC Charlotte") too - but not WBTV, which is also Warner Bros' FAST brand.
LOCAL_STATION = re.compile(r"^((ABC|CBS|NBC|FOX|CW|PBS|MyNetwork|Telemundo|Univision) \d+ .+ [A-Z]{2}"
                           r"|(?!WBTV )[KW][A-Z]{3} [A-Z][a-z]+)$")
# Words in a name that mean the channel is not in English, for channels with no language data.
FOREIGN_WORDS = (r"espa[nñ]ol|latino|latina|telemundo|univision|estrella|estelar|canela|"
                 r"canal|novelas?|mundo|cine|animales|explora|motores|naturaleza|salvaje|coreano|"
                 r"mexican[oa]s?|regional|reggaeton|fado|mpb|luz|amor|culpa|turcas|todo|trufa|"
                 r"competencias|epoca|exitos|wapa|bar[cç]a|dfb|tadka|thai|latin|romantico|franco|nostalgie|"
                 r"souvenirs|palmares|zee|"
                 r"hindi|tamil|telugu|punjabi|bollywood|arabic|fran[cç]ais|deutsch|italiano|"
                 r"portugu[eê]s|brasil|tagalog|filipino|pinoy|vietnamese|chinese|mandarin")

# Categories are the Pluto dial's genres, so the two pools read alike, plus the three a FAST pool
# needs that Pluto's picks did not: Game Shows, Relax and Travel. A name that says what the
# channel is decides first - iptv-org files most single-show channels under "series" or
# "entertainment", which says nothing - then iptv-org's own category, then Other.
GENRES = [
    "Movies", "Drama & Series", "Classic TV", "Comedy", "Crime", "Reality", "Game Shows",
    "Documentary", "Kids", "Anime", "Music", "Relax", "Sports", "Motoring", "Food & Home",
    "Outdoors", "Travel", "Other",
]
OTHER = "Other"
# iptv-org categories specific enough to trust over a name.
FIRM_CATEGORIES = {
    "movies": "Movies", "kids": "Kids", "animation": "Kids", "music": "Music", "sports": "Sports",
    "auto": "Motoring", "cooking": "Food & Home", "outdoor": "Outdoors", "relax": "Relax",
    "travel": "Travel", "comedy": "Comedy", "classic": "Classic TV",
}
# iptv-org categories used only when the name says nothing.
LOOSE_CATEGORIES = {
    "series": "Drama & Series", "documentary": "Documentary", "science": "Documentary",
    "education": "Documentary", "culture": "Documentary", "lifestyle": "Food & Home",
}
# (genre, whole words in the name), first match wins - so the order settles every overlap:
# "Cartoon Classics" is Kids, "Drive In Movie Channel" Movies, "Murder She Wrote" Classic TV.
NAME_GENRES = [
    ("Anime", r"anime|hunter x hunter|jojo'?s?|crunchyroll|hidive"),
    ("Kids", r"kids?|kiddo|baby|babies|toons?|cartoons?|blippi|caillou|barney|barbie|pocoyo|masha|"
             r"power rangers|sonic|strawberry shortcake|rainbow ruby|slugterra|rabbids|mattel|"
             r"hot wheels|looloo|sensical|gnasher|fgteev|jr|junior|batterypop|pbs kids|teletubbies|"
             r"lego|pokemon|transformers|ryan"),
    ("Relax", r"vibes|fireplace|noise|peaceful|focus|zen|wellbeing|ambient|aquarium|cityscapes|"
              r"naturescape|slow"),
    ("Music", r"vevo|xite|stingray|billboard|music|concerts?|vinyl|feva|playing for change|k pop|"
              r"kpop|mtv|jams|hits|disco|anthems"),
    ("Game Shows", r"family feud|pointless|tipping point|let's make a deal|supermarket sweep|"
                   r"game shows?|buzzr|price is right|deal or no deal|who wants to be a millionaire"),
    ("Movies", r"movies?|cinema|cine|films?|flix|flick|cinevault|miramax|tribeca|westerns?|cowboy|"
               r"drive in|screen|horror|kung fu|hi yah|action|pulp"),
    ("Classic TV", r"classic|lassie|leave it to beaver|little house on the prairie|murder she wrote|"
                   r"lone ranger|death valley days|alfred hitchcock|saved by the bell|cw gold|"
                   r"cw forever|retro|throwbacks?|rewind"),
    ("Comedy", r"comedy|laughs?|lol|funny|sitcoms?|conan|smosh|lampoon|try guys|portlandia|afv|"
               r"stand up|jokes|graham norton|10 cats"),
    ("Documentary", r"history|historical|ancient|aliens?|marvels|biography|docu|cosmic|"
                    r"space|science|genealogy|oak island|unxplained|vice|ted|curiosity|"
                    r"paranormal|ghosts?|mayday|disasters?|xplored|unexplained|smithsonian|universe mysteries|"
                    r"warfare|military|explorers"),
    ("Crime", r"crimes?|criminals|murder|killers?|evil|justice|court|cops|pd|detectives?|fbi|"
              r"first 48|forensic|medical examiner|born to kill|mystery|mysteries|homicide|jail|"
              r"lockup|witness|cold case|thrillers?|bounty hunter"),
    ("Reality", r"reality|real housewives|got talent|idol|ninja warrior|masked singer|biggest loser|"
                r"hoarders|intervention|pawn|storage wars|dance moms|bad girls|cheaters|"
                r"little women|project runway|top chef|osbournes|ax men|ice road|pickers|deal|"
                r"caught on tape|chaos on cam|survived|untold stories|emergency|bring it|"
                r"challenge|repo|married|dating|cupid|dr g|rescue|vet|inspector|wipeout|"
                r"undercover boss|keeping up|forged in fire|true lives"),
    ("Food & Home", r"food|cook'?s?|cooking|kitchen|chefs?|tasty|cuisine|masterchef|menu|emeril|"
                    r"rachael ray|hot ones|wine|home|homes|house|property|garden|gardening|reno|"
                    r"renovation|flipping|listing|dream home|crafts?|craftsy|diy|makers|location|"
                    r"escape to the country|new life in the sun|attic|antiques?|design|dine|bed"),
    ("Outdoors", r"hunt|hunting|fish|fishing|outdoors?|boating|meateater|wilderness|camping|wild|"
                 r"wildlife|nature|earth|animals?|pets?|dogs?|dogtv|monkey|jack hanna|waterbear|"
                 r"tuna|river monsters|swamp|mountain men|equus"),
    ("Sports", r"sports?|tennis|nba|nfl|mlb|nhl|fight|ufc|pfl|wwe|boxing|goal|football|soccer|"
               r"golf|billiards|pickleball|surf|surfing|surfer|motogp|racing|rally|grappling|"
               r"team usa|hoop|homerun|big 12|nesn|msg|fanduel|chukker|pll|itsf|jim rome|xtrem|"
               r"xtreme|fifa|wrestling|poker|darts|snooker|rugby|cricket|bellator|dazn|victory"),
    ("Motoring", r"motors?|motoring|motorsports?|drive|speed|torque|wheels|garage|ride|rig|trucks?|"
                 r"truckers|rev|automotion|hagerty|top gear|rvtv|cars?|auto|powernation|motortrend|"
                 r"road|highway"),
    ("Travel", r"travel|escapes?|voyages|tourism|heritage|luxury|downunder|trips?|island"),
    ("Drama & Series", r"drama|series|thriller|romance|love|stories|rookie blue|van helsing|"
                       r"nikita|silent witness|sci fi|heartfelt|k drama|walking dead|heartland"),
]

BROWSER_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/128.0.0.0 Safari/537.36")
PROBE_TIMEOUT = 12
PROBE_WORKERS = 12
DEFAULT_HOST = "hermanb@100.74.3.68"
US_NAMESPACE = "pluto-us"

EXTINF = re.compile(r'#EXTINF:[^,]*?tvg-id="([^"]*)"[^,]*,(.*)$')
TAG = re.compile(r"\s*(\([^)]*\)|\[[^\]]*\])")
NOISE_WORDS = {"pluto", "tv", "the", "channel", "by", "network"}


# ---- names and ids ------------------------------------------------------------------------------

def clean_name(raw):
    """A playlist name without its (1080p) and [Geo-blocked] tags."""
    return TAG.sub("", raw).strip()


def norm_name(name):
    """The comparable core of a channel name: `BUZZR`, `Buzzr TV (1080p)` and `The Buzzr Channel`
    all come out `buzzr`. Only ever used to spot the same channel twice, never shown."""
    name = clean_name(name).lower().replace("&", " and ").replace("+", " plus ")
    words = re.findall(r"[a-z0-9]+", name)
    core = [w for w in words if w not in NOISE_WORDS]
    return "".join(core or words)


def channel_id(tvg_id):
    """`Buzzr.us@SD` -> `Buzzr.us`: two feeds of one channel are one channel here."""
    return (tvg_id or "").split("@")[0]


def has_word(pattern, text):
    """True when [pattern] matches whole words of [text], case and combining accents aside."""
    text = unicodedata.normalize("NFC", text.lower())
    return re.search(r"\b(?:%s)\b" % pattern, text) is not None


# ---- playlists ----------------------------------------------------------------------------------

def parse_m3u(text, source):
    """Every entry of an iptv-org playlist, in order: name, tvg-id, url, and any headers it needs.
    Header options (#EXTVLCOPT) belong to the entry they sit between the #EXTINF and the url of."""
    entries, pending, opts = [], None, {}
    for line in text.splitlines():
        line = line.strip()
        match = EXTINF.match(line)
        if match:
            pending, opts = {"tvg_id": match.group(1), "raw_name": match.group(2)}, {}
        elif line.startswith("#EXTVLCOPT:") and pending is not None:
            key, _, value = line[len("#EXTVLCOPT:"):].partition("=")
            opts[key.strip()] = value.strip()
        elif line and not line.startswith("#") and pending is not None:
            entry = dict(pending, url=line, source=source, name=clean_name(pending["raw_name"]))
            headers = {}
            if "http-user-agent" in opts:
                headers["User-Agent"] = opts["http-user-agent"]
            if "http-referrer" in opts:
                headers["Referer"] = opts["http-referrer"]
            if headers:
                entry["headers"] = headers
            entries.append(entry)
            pending = None
    return entries


# ---- iptv-org metadata --------------------------------------------------------------------------

class Metadata:
    """iptv-org's channels, feeds and logos, indexed the ways a playlist entry can be looked up."""

    def __init__(self, channels, feeds, logos):
        self.channels = {c["id"]: c for c in channels}
        self.feeds = {}
        for feed in feeds:
            self.feeds.setdefault(feed["channel"], []).append(feed)
        self.logos = {}
        for logo in logos:
            self.logos.setdefault(logo["channel"], []).append(logo)
        # Exact normalised names, for entries with no tvg-id - only English-speaking countries,
        # and only names no two channels share, so a guess is never a coin toss.
        by_name = {}
        for c in channels:
            if c.get("country") not in ("US", "UK", "GB", "AU", "CA", "NZ", "IE"):
                continue
            for name in [c["name"]] + list(c.get("alt_names") or []):
                by_name.setdefault(norm_name(name), set()).add(c["id"])
        self.by_name = {k: next(iter(v)) for k, v in by_name.items() if len(v) == 1 and k}

    def resolve(self, entry):
        """The iptv-org channel id for an entry, and how it was found ('id', 'name' or None)."""
        cid = channel_id(entry["tvg_id"])
        if cid in self.channels:
            return cid, "id"
        cid = self.by_name.get(norm_name(entry["name"]))
        return (cid, "name") if cid else (None, None)

    def languages(self, cid, tvg_id):
        """The feed's languages when the tvg-id names a feed, else the main feed's, else []."""
        feeds = self.feeds.get(cid, [])
        want = tvg_id.split("@")[1] if "@" in (tvg_id or "") else None
        for feed in feeds:
            if want and feed["id"] == want:
                return feed.get("languages") or []
        for feed in feeds:
            if feed.get("is_main"):
                return feed.get("languages") or []
        return feeds[0].get("languages") or [] if feeds else []

    def logo(self, cid, tvg_id):
        """The best logo url: the feed's own if it has one, a logo in use, the biggest."""
        want = tvg_id.split("@")[1] if "@" in (tvg_id or "") else None
        logos = self.logos.get(cid, [])
        if not logos:
            return None

        def rank(logo):
            return (logo.get("feed") == want, logo.get("feed") is None, bool(logo.get("in_use")),
                    (logo.get("width") or 0) * (logo.get("height") or 0))
        return max(logos, key=rank)["url"]


def genre_of(name, categories):
    """The dial genre for a channel: Anime by name always (iptv-org calls it animation), then a
    firm iptv-org category, then the name, then a loose iptv-org category, then Other."""
    words = name.replace("-", " ").replace(".", "")
    if has_word(NAME_GENRES[0][1], words):
        return NAME_GENRES[0][0]
    for category in categories:
        if category in FIRM_CATEGORIES:
            return FIRM_CATEGORIES[category]
    for genre, pattern in NAME_GENRES:
        if has_word(pattern, words):
            return genre
    for category in categories:
        if category in LOOSE_CATEGORIES:
            return LOOSE_CATEGORIES[category]
    return OTHER


def enrich(entry, meta):
    """The entry with its iptv-org channel, categories, category, languages and logo filled in."""
    cid, how = meta.resolve(entry)
    entry = dict(entry, iptv_channel=cid, matched_by=how)
    if cid:
        channel = meta.channels[cid]
        entry["categories"] = list(channel.get("categories") or [])
        entry["languages"] = meta.languages(cid, entry["tvg_id"] if how == "id" else "")
        entry["nsfw"] = bool(channel.get("is_nsfw"))
        entry["closed"] = bool(channel.get("closed"))
        entry["logo"] = meta.logo(cid, entry["tvg_id"] if how == "id" else "")
    else:
        entry.update(categories=[], languages=[], nsfw=False, closed=False, logo=None)
    entry["category"] = genre_of(entry["name"], entry["categories"])
    return entry


# ---- filters ------------------------------------------------------------------------------------

def language_problem(entry):
    """Why an entry is not English, or None. No language data means English unless the name says
    otherwise - these are US, UK, Australian and Canadian services."""
    langs = entry.get("languages") or []
    if langs and "eng" not in langs:
        return "not English (%s)" % ",".join(langs)
    # The name wins over iptv-org: "Fox Sports en Espanol" is filed under Fox Sports's feeds.
    if has_word(FOREIGN_WORDS, entry["name"]):
        return "not English (by name)"
    return None


def category_problem(entry):
    """Why an entry's category rules it out, or None."""
    for category in entry.get("categories") or []:
        if category in DROP_CATEGORIES:
            return DROP_CATEGORIES[category]
    if entry.get("nsfw"):
        return "adult"
    if LOCAL_STATION.match(entry["name"]):
        return "news (local station)"
    for pattern, reason in DROP_WORDS:
        if has_word(pattern, entry["name"]):
            return reason + " (by name)"
    return None


class Existing:
    """What the two dials already carry, as normalised names, iptv-org channel ids and urls."""

    def __init__(self, pluto_names=(), pluto_ids=(), youtube_names=(), urls=()):
        self.pluto_names = {norm_name(n) for n in pluto_names}
        self.pluto_ids = {channel_id(i) for i in pluto_ids if i}
        self.youtube_names = {norm_name(n) for n in youtube_names}
        self.urls = set(urls)

    def problem(self, entry):
        if "jmp2.uk/plu-" in entry["url"] or ".pluto.tv/" in entry["url"]:
            return "Pluto stream"
        if entry["url"] in self.urls:
            return "already on a dial (same url)"
        if channel_id(entry["tvg_id"]) in self.pluto_ids or (
                entry.get("iptv_channel") and entry["iptv_channel"] in self.pluto_ids):
            return "on the Pluto dial (tvg-id)"
        key = norm_name(entry["name"])
        if key in self.pluto_names:
            return "on the Pluto dial (name)"
        if key in self.youtube_names:
            return "on the YouTube dial (name)"
        return None


def pluto_tvg_ids(allowlist, pluto_entries):
    """tvg-ids of the Pluto dial's channels, via the Pluto playlists that carry their ids."""
    wanted = set()
    for entry in allowlist:
        wanted.add(entry["id"])
        if entry.get("alt"):
            wanted.add(entry["alt"])
    ids = set()
    for entry in pluto_entries:
        match = re.search(r"plu-([0-9a-f]+)\.m3u8", entry["url"])
        if match and match.group(1) in wanted and entry["tvg_id"]:
            ids.add(entry["tvg_id"])
    return ids


def screen(entries, existing):
    """(kept, dropped): every entry that survives language, category and dial filters, and every
    one that did not, each dropped entry carrying its `reason`."""
    kept, dropped = [], []
    for entry in entries:
        reason = (language_problem(entry) or category_problem(entry)
                  or ("channel closed" if entry.get("closed") else None) or existing.problem(entry))
        if reason:
            dropped.append(dict(entry, reason=reason))
        else:
            kept.append(entry)
    return kept, dropped


# ---- dedupe -------------------------------------------------------------------------------------

def dedupe_key(entry):
    """One channel's identity across sources: its iptv-org channel when known, else its name."""
    cid = channel_id(entry["tvg_id"]) or entry.get("iptv_channel")
    return "id:" + cid if cid else "name:" + norm_name(entry["name"])


ROUTE_RANK = {"direct": 0, "us": 1, "dead": 2}


def route_of(url, direct, us):
    """direct / us / dead from the two probe result maps (url -> {"ok": bool, ...})."""
    if direct.get(url, {}).get("ok"):
        return "direct"
    if us.get(url, {}).get("ok"):
        return "us"
    return "dead"


def dedupe(entries, routes, source_order=SOURCES):
    """One entry per channel: the copy that plays from Australia, else via the US, else any; ties
    go to the earlier source, then the earlier entry. An entry that shares a name with a kept
    entry is the same channel even when only one of them has a tvg-id. Each kept entry lists the
    sources of the copies it beat as `also`."""
    order = {s: i for i, s in enumerate(source_order)}
    ranked = sorted(enumerate(entries), key=lambda ie: (
        ROUTE_RANK[routes.get(ie[1]["url"], "dead")], order.get(ie[1]["source"], len(order)), ie[0]))
    kept, by_key, by_name = [], {}, {}
    for _, entry in ranked:
        key, name = dedupe_key(entry), "name:" + norm_name(entry["name"])
        winner = by_key.get(key) or by_name.get(name)
        if winner is not None:
            if entry["source"] != winner["source"] and entry["source"] not in winner["also"]:
                winner["also"].append(entry["source"])
            continue
        winner = dict(entry, route=routes.get(entry["url"], "dead"), also=[])
        kept.append(winner)
        by_key[key] = winner
        by_name[name] = winner
    return kept


# ---- output -------------------------------------------------------------------------------------

def candidate_record(entry):
    record = {
        "name": entry["name"],
        "source": entry["source"],
        "tvg_id": entry["tvg_id"] or None,
        "url": entry["url"],
        "category": entry["category"],
        "iptv_categories": entry.get("categories") or [],
        "route": entry["route"],
    }
    if entry.get("logo"):
        record["logo"] = entry["logo"]
    if entry.get("headers"):
        record["headers"] = entry["headers"]
    if entry.get("also"):
        record["also"] = entry["also"]
    return record


def summary(candidates, dropped, dead, counts):
    """The markdown the owner picks from: categories, then what was dropped and why."""
    by_cat = {}
    for c in candidates:
        by_cat.setdefault(c["category"], []).append(c)
    lines = ["# FAST TV candidates", "",
             "Generated by `build_fast.py`. %d live channels: %d play directly from Australia, %d only "
             "through the US tunnel. %d more were dead both ways." % (
                 len(candidates), sum(c["route"] == "direct" for c in candidates),
                 sum(c["route"] == "us" for c in candidates), len(dead)),
             "", "Categories are the Pluto dial's genres plus Game Shows, Relax and Travel. A name that "
             "says what a channel is decides its genre, else iptv-org's category does; Other is "
             "neither.",
             "", "| Category | Total | Direct | US only |", "|---|---:|---:|---:|"]
    order = sorted(by_cat, key=lambda k: GENRES.index(k) if k in GENRES else len(GENRES))
    for cat in order:
        group = by_cat[cat]
        lines.append("| %s | %d | %d | %d |" % (cat, len(group), sum(c["route"] == "direct" for c in group),
                                              sum(c["route"] == "us" for c in group)))
    lines += ["", "## Channels by category", "", "`(us)` marks a channel that plays only through the US tunnel.", ""]
    for cat in order:
        names = sorted(by_cat[cat], key=lambda c: c["name"].lower())
        lines.append("**%s** (%d): %s" % (cat, len(names), ", ".join(
            c["name"] + (" (us)" if c["route"] == "us" else "") for c in names)))
        lines.append("")
    lines += ["## Per source", "", "| Source | Entries | Kept after filters | Live after dedupe |",
              "|---|---:|---:|---:|"]
    for source in SOURCES:
        entries, kept = counts.get(source, (0, 0))
        lines.append("| %s | %d | %d | %d |" % (source, entries, kept,
                                                 sum(c["source"] == source for c in candidates)))
    lines += ["", "## Dropped", ""]
    reasons = {}
    for d in dropped:
        reasons.setdefault(d["reason"], []).append(d)
    for reason in sorted(reasons, key=lambda r: (-len(reasons[r]), r)):
        names = sorted({d["name"] for d in reasons[reason]}, key=str.lower)
        lines.append("**%s** (%d entries): %s" % (reason, len(reasons[reason]), ", ".join(names)))
        lines.append("")
    lines += ["**dead both ways** (%d): %s" % (len(dead), ", ".join(
        sorted({d["name"] for d in dead}, key=str.lower))), ""]
    return "\n".join(lines)


# ---- probe --------------------------------------------------------------------------------------

def _get(url, headers, limit=None):
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=PROBE_TIMEOUT) as response:
        body = response.read(limit) if limit else response.read()
        return response.status, response.geturl(), body


def _uris(text, base):
    return [urllib.parse.urljoin(base, line.strip()) for line in text.splitlines()
            if line.strip() and not line.startswith("#")]


def probe_one(url, headers=None):
    """{"ok": bool, "why": str}: the playlist, its first variant (or itself if a media playlist),
    and one media segment must all answer 200."""
    headers = dict({"User-Agent": BROWSER_UA}, **(headers or {}))
    try:
        status, final, body = _get(url, headers)
        text = body.decode("utf-8", "replace")
        if status != 200 or "#EXTM3U" not in text:
            return {"ok": False, "why": "playlist %d, not m3u" % status}
        for _ in range(3):  # master -> variant, allowing one nested master
            if "#EXT-X-STREAM-INF" not in text:
                break
            uris = _uris(text, final)
            if not uris:
                return {"ok": False, "why": "master with no variants"}
            status, final, body = _get(uris[0], headers)
            text = body.decode("utf-8", "replace")
            if status != 200:
                return {"ok": False, "why": "variant %d" % status}
        segments = _uris(text, final)
        if "#EXTINF" not in text or not segments:
            return {"ok": False, "why": "no segments"}
        status, _, _ = _get(segments[0], headers, limit=65536)
        if status != 200:
            return {"ok": False, "why": "segment %d" % status}
        return {"ok": True, "why": "ok"}
    except Exception as e:  # any failure is a failed probe, and why
        return {"ok": False, "why": ("%s: %s" % (type(e).__name__, e))[:160]}


def probe_all(items):
    """url -> probe result, for [{"url", "headers"?}], at most PROBE_WORKERS at a time."""
    with concurrent.futures.ThreadPoolExecutor(PROBE_WORKERS) as pool:
        futures = {pool.submit(probe_one, it["url"], it.get("headers")): it["url"] for it in items}
        return {futures[f]: f.result() for f in concurrent.futures.as_completed(futures)}


def remote_script(items):
    """The bash the host runs: write this file and the url list to a temp dir, probe from
    Australia, probe from inside the US namespace, remove the temp dir. Nothing else changes."""
    with open(os.path.abspath(__file__), "rb") as f:
        code = base64.b64encode(f.read()).decode()
    urls = base64.b64encode(json.dumps(items).encode()).decode()
    return "\n".join([
        "set -e",
        'd=$(mktemp -d /tmp/fastprobe.XXXXXX)',
        "trap 'rm -rf \"$d\"' EXIT",
        'echo %s | base64 -d > "$d/probe.py"' % code,
        'echo %s | base64 -d > "$d/urls.json"' % urls,
        'echo "===DIRECT==="',
        'timeout 3600 python3 "$d/probe.py" probe "$d/urls.json"',
        'echo "===US==="',
        'timeout 3600 sudo ip netns exec %s sudo -u "$(id -un)" python3 "$d/probe.py" probe "$d/urls.json"'
        % US_NAMESPACE,
        "",
    ])


def probe_remote(host, items):
    """(direct, us) result maps from running both probes on [host]."""
    script = remote_script(items)
    out = subprocess.run(["ssh", "-o", "BatchMode=yes", host, "bash -s"], input=script,
                         capture_output=True, text=True, timeout=7500, check=True).stdout
    direct_part, _, us_part = out.partition("===US===")
    return json.loads(direct_part.split("===DIRECT===", 1)[1]), json.loads(us_part)


# ---- main ---------------------------------------------------------------------------------------

def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "ytv-lineup"})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read().decode("utf-8")


def gather():
    """(entries, pluto entries, metadata) from the network."""
    entries = []
    for source in SOURCES:
        entries += parse_m3u(fetch(RAW % source), source)
    pluto = []
    for source in PLUTO_SOURCES:
        pluto += parse_m3u(fetch(RAW % source), source)
    meta = Metadata(*(json.loads(fetch(API % name)) for name in ("channels", "feeds", "logos")))
    return entries, pluto, meta


def existing_dials(pluto_entries):
    with open(PLUTO_ALLOWLIST) as f:
        allow = json.load(f)
    with open(YOUTUBE_DIAL) as f:
        youtube = json.load(f)["channels"]
    urls = {s["url"] for c in youtube for s in c.get("streams", []) if c.get("kind") == "live"}
    return Existing(pluto_names=[e["name"] for e in allow],
                    pluto_ids=pluto_tvg_ids(allow, pluto_entries),
                    youtube_names=[c["name"] for c in youtube], urls=urls)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["probe"]:
        with open(argv[1]) as f:
            json.dump(probe_all(json.load(f)), sys.stdout, indent=0)
        return 0

    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", default=DEFAULT_HOST, help="where both probes run")
    parser.add_argument("--direct", help="saved Australian probe results, instead of probing")
    parser.add_argument("--us", help="saved US probe results, instead of probing")
    parser.add_argument("--save-probes", help="directory to keep the raw probe results in")
    args = parser.parse_args(argv)

    entries, pluto, meta = gather()
    entries = [enrich(e, meta) for e in entries]
    kept, dropped = screen(entries, existing_dials(pluto))
    counts = {}
    for e in entries:
        total, k = counts.get(e["source"], (0, 0))
        counts[e["source"]] = (total + 1, k)
    for e in kept:
        total, k = counts[e["source"]]
        counts[e["source"]] = (total, k + 1)

    if args.direct and args.us:
        with open(args.direct) as f:
            direct = json.load(f)
        with open(args.us) as f:
            us = json.load(f)
    else:
        items, seen = [], set()
        for e in kept:
            if e["url"] not in seen:
                seen.add(e["url"])
                items.append({"url": e["url"], **({"headers": e["headers"]} if e.get("headers") else {})})
        print("probing %d streams on %s ..." % (len(items), args.host), file=sys.stderr)
        direct, us = probe_remote(args.host, items)
    if args.save_probes:
        os.makedirs(args.save_probes, exist_ok=True)
        for name, data in (("direct", direct), ("us", us)):
            with open(os.path.join(args.save_probes, name + ".json"), "w") as f:
                json.dump(data, f, indent=0)

    routes = {e["url"]: route_of(e["url"], direct, us) for e in kept}
    unique = dedupe(kept, routes)
    candidates = [candidate_record(e) for e in unique if e["route"] != "dead"]
    candidates.sort(key=lambda c: (GENRES.index(c["category"]), c["name"].lower()))
    dead = [e for e in unique if e["route"] == "dead"]

    with open(OUT_JSON, "w") as f:
        json.dump({"generated": int(time.time()), "candidates": candidates}, f, indent=1, ensure_ascii=False)
        f.write("\n")
    with open(OUT_SUMMARY, "w") as f:
        f.write(summary(candidates, dropped, dead, counts))
    print("%d candidates (%d direct, %d us), %d dead, %d dropped by filters" % (
        len(candidates), sum(c["route"] == "direct" for c in candidates),
        sum(c["route"] == "us" for c in candidates), len(dead), len(dropped)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
