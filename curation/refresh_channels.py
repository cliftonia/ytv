#!/usr/bin/env python3
"""Re-search each channel for fresh clips and write them back into its conf.

Runs nightly in CI. Preserves every channel's `channel_number`, so the dial ordering the viewer
has learned is never disturbed by a content refresh - only what is ON each channel changes.

Each conf carries its own `search_query`. That used to live in seven separate builder scripts
that this imported, which meant losing one of them lost the definition of twenty channels; the
query now travels with the channel it describes.

  python3 refresh_channels.py --rotate 8          the nightly conveyor
  python3 refresh_channels.py --only blues        one channel, for checking a query
  python3 refresh_channels.py --target 100        how many clips a channel should end up with

Rotation is by a `last_refreshed` stamp written into each conf. It used to be by file
modification time, which worked on a long-lived machine and was silently a no-op in CI: the job
starts with `actions/checkout`, which writes every file at clone time, so all 90 confs carried
the same instant and the "least recently refreshed" eight were simply the first eight
alphabetically. The same eight channels refreshed every night for weeks and the other 82 never
did - and the run went green each time, because nothing checks WHICH channels moved.

The cursor has to survive cloning, so it lives in the committed json. At 8 a night the whole dial
turns over in a fortnight, which is slow enough that a channel does not feel like it resets and
fast enough that nothing on it is ever months old.
"""
import argparse
import datetime
import glob
import io
import json
import os
import re
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

TARGET = 100

# How many search results to sift per target clip. Three is enough for a broad query and not
# so many that a single channel's refresh takes minutes.
SEARCH_DEPTH = 3

# Channels whose whole point is that the content is old. Asking these for this year's uploads
# returns reaction videos and retrospectives instead of the thing itself.
PERIOD = ("music_19", "music_20", "yacht", "westerns", "sitcoms", "anime_classic", "cartoons",
          "public_domain", "vintage", "retro", "classic", "silent", "noir")

# A song is three minutes; a documentary is fifty. One duration window cannot serve both, and
# the wrong one is what once left a music channel with seventeen clips on it.
SONGS = ("music_", "yacht", "rock", "jazz", "blues", "country", "reggae", "hiphop", "hip_hop",
         "soul", "motown", "electronic", "classical", "bossa", "christian_music", "opera")
LONG = ("documentaries", "podcasts", "concerts", "public_domain", "westerns", "samurai",
        "docos", "history", "philosophy", "lecture")

# Somebody talking about the thing, rather than the thing. Every one of these was found on a
# channel it had no business being on.
COMMENTARY = ("top ", "best ", "ranked", "tier list", "greatest", "need to watch", "must watch",
              "recommendation", "in a nutshell", "of all time", "i watched", "i binged",
              "reaction", "explained", "review", "ranking", "compilation of", "tiktok",
              "shorts compilation")


# Languages that turn up on this dial. Matched only where the word names the language OF the
# content, never where it merely describes the subject - see english_speech().
FOREIGN = (r"hindi|urdu|tamil|telugu|bangla|bengali|punjabi|marathi|malayalam|kannada|"
           r"indonesia|bahasa|espa[nñ]ol|latino|portugu[eê]s|brasil|fran[cç]ais|deutsch|"
           r"t[uü]rk[cç]e|russian|filipino|tagalog|vietnam|thai|arabic|farsi|persian|"
           r"mandarin|cantonese|korean|japanese|italiano|nederlands|polski")

# The language word standing next to something that says it IS the audio: a dub, a feed, a
# commentary. "Hindi Dubbed" and "National Geographic Hindi |" are the content's language;
# "Filipino street food" and "Japanese LoFi" are its subject, and both are perfectly watchable.
# Nouns that make the language word describe the AUDIO rather than the subject. "Tamil Christian
# Short Film" is a film in Tamil; "Filipino street food" is food from the Philippines, in English.
# `song` and `mix` are deliberately absent - a Japanese lofi mix has no speech to be in a language.
MEDIA_NOUN = (r"film|movie|serial|drama|episode|sermon|documentary|show|series|comedy|"
              r"dub|dubbed|sub|subs|subbed|subtitle[sd]?|audio|version|voice[- ]?over|"
              r"commentary|highlights|explained|summary|news|channel|tv")

CONTENT_LANGUAGE = re.compile(
    # The language, then a media noun within a few words: "Hindi Dubbed", "Tamil Christian Short
    # Film", "Telugu Full Episode".
    r"(?:\b(?:%s)\b[\w\s'&-]{0,24}?\b(?:%s)\b"
    # Or the other way round: "Dubbed in Hindi", "Audio: Telugu".
    r"|\b(?:%s)\b[\s|,:-]*(?:in\s+)?\b(?:%s)\b"
    # Or the language ENDING a pipe-delimited segment, which is how a feed names itself:
    # "| National Geographic Hindi |", "| ETV Telugu".
    r"|(?:%s)\s*(?:\||$))" % (FOREIGN, MEDIA_NOUN, MEDIA_NOUN, FOREIGN, FOREIGN), re.I)

# An explicit statement that this IS in English. Overrides everything below: a Japanese OVA
# labelled "[Eng Dub]" is exactly what the Classic Anime channel wants, original title and all.
ENGLISH = re.compile(r"\b(eng(?:lish)?[\s.-]*(?:dub|dubbed|sub|subbed|subtitle[sd]?|audio)"
                     r"|english|\[eng\]|\(eng\)|eng\s*dub|multi[- ]?sub)\b", re.I)

# How much of a title may be in another script before it stops being an English title. A few
# characters are an original title in brackets or a stylistic flourish; a third of the line is
# the actual name of a programme in another language.
FOREIGN_SCRIPT_SHARE = 0.20


def _ranges(*bounds):
    out = set()
    for low, high in bounds:
        out.update(range(low, high + 1))
    return frozenset(out)


# Scripts that are never decoration. Unlike Japanese or Korean - which appear in the original
# title of plenty of English-dubbed material - a single character from any of these means the
# title was written for someone who reads it.
CONTENT_SCRIPTS = _ranges(
    (0x0400, 0x04FF),   # Cyrillic
    (0x0590, 0x05FF),   # Hebrew
    (0x0600, 0x06FF),   # Arabic
    (0x0900, 0x097F),   # Devanagari
    (0x0980, 0x09FF),   # Bengali
    (0x0A00, 0x0A7F),   # Gurmukhi
    (0x0B80, 0x0BFF),   # Tamil
    (0x0C00, 0x0C7F),   # Telugu
    (0x0C80, 0x0CFF),   # Kannada
    (0x0D00, 0x0D7F),   # Malayalam
    (0x0E00, 0x0E7F),   # Thai
)


def english_speech(title):
    """Whether this looks like something an English speaker can follow.

    Three real titles off the dial set the shape of this, and a blunter filter gets all three
    wrong: "Angel Cop [Eng Dub] (OVA - 1989) エンゼル コップ" is an English dub whose original title
    is Japanese; "Philippines Street Food!! 14 Hour FILIPINO STREET FOOD Tour" is English-language
    vlogging where the language word describes the food; "Japanese LoFi HipHop Mix 時間の流れ" has no
    speech in it at all. Meanwhile "National Geographic Hindi" and "KBC के मंच पर पहुंची" genuinely
    are in another language.

    So: an explicit English marker wins outright, then a language named as the audio loses, then
    a title mostly written in another script loses.
    """
    text = title or ""
    if not text:
        return False
    if ENGLISH.search(text):
        return True
    if CONTENT_LANGUAGE.search(text):
        return False
    # Two classes of script, because they mean different things in a title.
    #
    # Japanese and Korean characters routinely appear in the ORIGINAL title of something that is
    # nonetheless in English - "Angel Cop [Eng Dub] (OVA - 1989) エンゼル コップ" - so a few of them
    # are allowed. Devanagari, Tamil, Telugu, Bengali, Arabic, Hebrew, Thai and Cyrillic do not
    # turn up that way: a title carrying any of them at all is a title written for someone who
    # reads them, and "KBC S18 | Ep. 1 | Full Episode | Sunny के Dhai Kilo" is a Hindi programme
    # however much of its title happens to be in English.
    if any(ord(c) in CONTENT_SCRIPTS for c in text):
        return False
    letters = [c for c in text if not c.isspace()]
    if not letters:
        return False
    foreign = sum(1 for c in letters if ord(c) > 0x0400)
    return foreign <= len(letters) * FOREIGN_SCRIPT_SHARE


def is_period(slug):
    return any(token in slug for token in PERIOD)


def window(slug):
    """The duration band a clip must fall inside to belong on this channel."""
    if any(token in slug for token in SONGS):
        return 60, 420
    if any(token in slug for token in LONG):
        return 1200, 9000
    return 240, 5400


def usable(title):
    low = (title or "").lower()
    if not low:
        return False
    if any(word in low for word in COMMENTARY):
        return False
    # A programme nobody in the room can follow is not content, whatever else is right about it.
    return english_speech(title)


def ytdlp(target, timeout=300):
    """Flat-list a search or playlist. Returns (id, duration, title) rows."""
    try:
        out = subprocess.run(
            ["yt-dlp", target, "--flat-playlist", "--no-warnings",
             "--print", "%(id)s\t%(duration)s\t%(title).90s"],
            capture_output=True, text=True, timeout=timeout)
    except Exception as exc:
        print("    [warn] %s: %s" % (target[:60], exc), flush=True)
        return []
    if out.returncode != 0 and not out.stdout.strip():
        # Distinguishes "this search legitimately found nothing" from "yt-dlp could not run".
        print("    [warn] yt-dlp exited %d: %s"
              % (out.returncode, (out.stderr or "").strip()[:120]), flush=True)
    rows = []
    for line in out.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        try:
            rows.append((parts[0], int(float(parts[1])), parts[2]))
        except ValueError:
            continue
    return rows


def title_key(title):
    """Loose identity for a clip, so the same song from two uploaders lands once.

    Digits are KEPT. Dropping standalone numbers was meant to stop "Live 2019" and "Live 2024"
    counting as two, and instead it deleted every episode of every series: "Episode 1" and
    "Episode 2" collapsed to the same key, so a season playlist was reduced to one episode before
    search even began. That is why the episodic channels never had a run longer than two - not
    because uploaders only post pilots, which is what the code used to say.

    The duplicate-year case it was protecting against is rare and harmless; losing whole series
    was neither.
    """
    low = re.sub(r"[^a-z0-9 ]", " ", (title or "").lower())
    return " ".join(low.split())[:60]


def collect(target, lo, hi, seen, keys, out, want):
    added = 0
    for vid, seconds, title in ytdlp(target):
        if len(out) >= want:
            break
        if not (lo <= seconds <= hi) or not usable(title):
            continue
        url = "https://www.youtube.com/watch?v=%s" % vid
        key = title_key(title)
        if url in seen or key in keys:
            continue
        seen.add(url)
        keys.add(key)
        out.append({"url": url, "duration": seconds, "title": title.strip()})
        added += 1
    return added


def last_refreshed(path):
    """When this channel was last re-searched, as an epoch. Never refreshed sorts first."""
    try:
        with io.open(path, encoding="utf-8") as handle:
            return json.load(handle)["station_conf"].get("last_refreshed", 0)
    except Exception:
        return 0


def refresh(path, target):
    """Refill one channel. Returns (name, clip count)."""
    with io.open(path, encoding="utf-8") as handle:
        conf = json.load(handle)
    station = conf["station_conf"]
    slug = os.path.basename(path)[5:-5]
    name = station.get("network_name") or slug
    query = station.get("search_query")
    if not query:
        print("  [skip] %s has no search_query" % name, flush=True)
        return name, len(station.get("streams", []))

    lo, hi = window(slug)
    streams, seen, keys = [], set(), set()

    # Curated playlists pinned to this channel are the best material it has; they go in first and
    # search only fills whatever is left over. Several are allowed because some subjects have no
    # single deep list - the 1930s needs three to make a hundred.
    for playlist in station.get("playlists") or []:
        if len(streams) >= target:
            break
        collect("https://www.youtube.com/playlist?list=" + playlist, lo, hi, seen, keys,
                streams, target)

    year = datetime.date.today().year
    attempts = [query]
    if not is_period(slug):
        # This year first, then unqualified. A bias, not a filter: a quiet channel that has
        # nothing from this year still fills rather than emptying itself.
        attempts.insert(0, "%s %d" % (query, year))
    attempts.append("%s full" % query)
    # Named programmes and named people, which beat a genre word every time - see EXTRA_QUERIES
    # in dial.py. They come after the general query so the channel still leads with the broad
    # sweep, and they are what actually carries a thin channel to a hundred.
    attempts.extend(station.get("extra_queries") or [])

    for attempt in attempts:
        if len(streams) >= target:
            break
        # Ask for several times the target. The duration window and the commentary filter both
        # reject as they go, so searching exactly `target` results can only ever come back short -
        # which is how a music channel once ended up with seventeen clips on it.
        collect("ytsearch%d:%s" % (target * SEARCH_DEPTH, attempt), lo, hi, seen, keys,
                streams, target)

    if not streams:
        # Writing an empty list would take the channel off the dial entirely. Leaving yesterday's
        # content is strictly better than that, and the next rotation will try again.
        print("  %-26s search returned nothing, keeping what was there" % name, flush=True)
        return name, len(station.get("streams", []))

    station["streams"] = streams
    station["last_refreshed"] = int(time.time())
    with io.open(path, "w", encoding="utf-8") as handle:
        json.dump(conf, handle, indent=4, ensure_ascii=False)
    print("  %-26s %3d clips" % (name, len(streams)), flush=True)
    return name, len(streams)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=int, default=TARGET)
    parser.add_argument("--only")
    parser.add_argument("--rotate", type=int, default=None,
                        help="refresh only the N least-recently-refreshed channels")
    parser.add_argument("--confs", default=os.path.join(os.path.dirname(__file__), "confs"))
    args = parser.parse_args()

    paths = sorted(glob.glob(os.path.join(args.confs, "ytch_*.json")))
    if args.only:
        paths = [p for p in paths if os.path.basename(p) == "ytch_%s.json" % args.only]
        if not paths:
            print("no channel called %s" % args.only, file=sys.stderr)
            return 2
    elif args.rotate is not None:
        # `is not None`, so --rotate 0 means "none" rather than falling through to all 90 and
        # spending an hour getting the runner's egress address throttled.
        if args.rotate <= 0:
            print("--rotate 0: nothing to do", flush=True)
            return 0
        paths.sort(key=last_refreshed)
        paths = paths[:args.rotate]
        print("rotating %d channels: %s" % (
            len(paths), ", ".join(os.path.basename(p)[5:-5] for p in paths)), flush=True)

    results = []
    # Four at a time. yt-dlp is network-bound so this is worth doing, but more than a handful of
    # concurrent searches is what gets an IP throttled, and a throttled run refreshes nothing.
    with ThreadPoolExecutor(max_workers=4) as pool:
        for result in pool.map(lambda p: refresh(p, args.target), paths):
            results.append(result)

    thin = ["%s(%d)" % (n, c) for n, c in results if c < args.target // 2]
    print("\nrefreshed %d channels" % len(results), flush=True)
    if thin:
        print("thin: %s" % ", ".join(thin), flush=True)

    # A night where every request was refused looked exactly like a night where nothing had
    # changed: all confs untouched, no diff, "no changes", green tick. The dial could stop
    # updating for weeks with no signal anywhere. Half the slice coming back thin is not a
    # content problem, it is yt-dlp being throttled, and it should be visible.
    if results and len(thin) > len(results) // 2:
        print("\nFAILED: %d of %d channels came back thin - almost certainly throttled"
              % (len(thin), len(results)), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
