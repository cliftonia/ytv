#!/usr/bin/env python3
"""Order a channel's clips so a series plays from episode one, in order.

The app needs no changes for this. `ClockRotation.playPointFor` already walks the clip list in
order and cycles, so a channel plays clip 0, then 1, then 2 - the only reason it feels shuffled
is that the list arrives in YouTube search-result order. Sorting the list IS the feature.

What this cannot do is invent sequences that were never sourced - but note that the measurement
once quoted here blamed the wrong thing. "Only 10 of 100 anime clips in a run" was read as
uploaders posting nothing but episode one; the real cause was our own deduplication, which
stripped standalone digits from titles and so collapsed "Episode 1" and "Episode 2" into a single
key, deleting whole series before search had even finished. That is fixed in refresh_channels.py,
and the runs should now reflect what YouTube actually offers rather than what we threw away.

So this pays off in proportion to how much of a channel comes from SEASON PLAYLISTS rather than
search - a season playlist arrives already complete and already in order. See PLAYLISTS in
dial.py. Run this after refresh_channels.py, on channels marked `sequence` in dial.py.

  python3 sequence.py                 order every channel that asks for it
  python3 sequence.py --only anime    one channel
  python3 sequence.py --report        say what would happen, change nothing
"""
import argparse
import collections
import os
import re
import sys

import confs

# Ordered most-specific first: "S2E13" must win before the bare "Episode 13" pattern sees it.
SEASON_EPISODE = re.compile(
    r"\bS(?:eason)?\s*(\d+)\s*[-, ]?\s*E(?:p|pisode)?\.?\s*(\d+)\b", re.I)
# "I Married Joan S1-15" - season and episode joined by a hyphen with no E at all. Common on the
# 1950s uploads, and missed entirely by the pattern above because it requires the E.
SEASON_DASH_EPISODE = re.compile(r"\bS(\d+)\s*-\s*(\d+)\b", re.I)
# QI numbers its series with LETTERS - "Series D, Episode 3" - so the season group is not \d+.
SERIES_THEN_EP = re.compile(r"\bSeries\s+([A-Z0-9]+).{0,30}?\bEp(?:isode)?\.?\s*(\d+)\b", re.I)
EP_THEN_SERIES = re.compile(r"\bEp(?:isode)?\.?\s*(\d+).{0,30}?\bSeries\s+([A-Z0-9]+)\b", re.I)
# "Episode 5 D" - the series letter with the word "Series" left off entirely, which QI uploaders
# do about a third of the time. Without this those parsed as season 1, and since numeric seasons
# sort ahead of lettered ones they were all hoisted to the front of the biggest run on the dial.
EP_THEN_BARE_LETTER = re.compile(r"\bEp(?:isode)?\.?\s*(\d+)[\s,]+([A-Z])\b")
BARE_EPISODE = re.compile(r"\bEp(?:isode)?\.?\s*(\d+)\b", re.I)

# An episode title in quotes, of any of the shapes uploaders actually use.
QUOTED = re.compile(r"[\'\"\u2018\u2019\u201c\u201d][^\'\"\u2018\u2019\u201c\u201d]{1,60}"
                    r"[\'\"\u2018\u2019\u201c\u201d]")

# Words that are in every uploader's title and say nothing about which show it is. Without these
# "QI Full Episode" and "QI XL Full Episode" become two different shows and neither gets ordered.
NOISE = re.compile(
    r"\b(full|complete|official|new|hd|4k|1080p|720p|english|eng|dub|dubbed|sub|subbed|"
    r"subtitles|multi|episode|episodes|ep|series|season|part|the|a|an|watch|free|online|xl)\b")


def parse(title):
    """(show, season, episode) from a title, or None if it names no episode.

    `season` is returned as a string because QI counts its series in letters, and comparing "D" to
    2 would raise. Sorting is done on a key that copes with both.
    """
    for pattern, order in ((SEASON_EPISODE, "se"), (SEASON_DASH_EPISODE, "se"),
                           (SERIES_THEN_EP, "se"), (EP_THEN_SERIES, "es"),
                           (EP_THEN_BARE_LETTER, "es")):
        match = pattern.search(title)
        if match:
            first, second = match.group(1), match.group(2)
            season, episode = (first, second) if order == "se" else (second, first)
            return show_name(title[:match.start()]), str(season).upper(), int(episode)
    match = BARE_EPISODE.search(title)
    if match:
        # No season stated. "1" rather than None so everything sorts against the same shape; a
        # show that never states a season has only one as far as this is concerned.
        return show_name(title[:match.start()]), "1", int(match.group(1))
    return None


def show_name(text):
    """The show, normalised enough that two uploaders' titles land in the same bucket.

    Quoted text is dropped first. Uploaders put the EPISODE title in quotes before the number -
    "QI FULL EPISODE! \'Dogs\' Episode 3" - and keeping it made every QI episode its own show, so
    the biggest genuine run on the dial never sorted at all.
    """
    without_episode_title = QUOTED.sub(" ", text)
    lowered = re.sub(r"[^a-z0-9 ]", " ", without_episode_title.lower())
    return " ".join(NOISE.sub(" ", lowered).split())[:32]


def sort_key(season, episode):
    """Season then episode, coping with QI's lettered series.

    Numeric seasons sort before lettered ones and both sort stably, so a channel carrying
    "Season 2" and "Series D" produces a defined order rather than a TypeError.
    """
    return (0, int(season), episode) if season.isdigit() else (1, season, episode)


def ordered(streams, min_run=2):
    """Streams reordered so each show's episodes run in sequence.

    Shows with fewer than [min_run] episodes are left in the tail, untouched and in their original
    order: a single episode is not a sequence, and hoisting it into a block would only shuffle a
    channel that had nothing to gain.

    Show blocks come first, longest first, so the runs that are actually worth watching in order
    are the ones a viewer lands in. Within a block, episode order. Everything else follows.
    """
    shows = collections.OrderedDict()
    loose = []
    for stream in streams:
        parsed = parse(stream.get("title", ""))
        if not parsed or not parsed[0]:
            loose.append(stream)
            continue
        show, season, episode = parsed
        shows.setdefault(show, []).append((sort_key(season, episode), stream))

    blocks, singles = [], []
    for show, entries in shows.items():
        if len(entries) >= min_run:
            entries.sort(key=lambda pair: pair[0])
            blocks.append([stream for _, stream in entries])
        else:
            singles.extend(stream for _, stream in entries)

    blocks.sort(key=len, reverse=True)
    out = [stream for block in blocks for stream in block]
    out.extend(singles)
    out.extend(loose)
    return out


def runs(streams, min_run=2):
    """(show, count) for each real sequence, longest first. Used by --report and the tests."""
    shows = collections.Counter()
    for stream in streams:
        parsed = parse(stream.get("title", ""))
        if parsed and parsed[0]:
            shows[parsed[0]] += 1
    return sorted(((s, n) for s, n in shows.items() if n >= min_run),
                  key=lambda pair: -pair[1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only")
    parser.add_argument("--report", action="store_true")
    parser.add_argument("--confs", default=confs.default_dir())
    args = parser.parse_args()

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import dial as DIAL

    wanted = {args.only} if args.only else set(DIAL.SEQUENCED)
    touched = 0
    for path in confs.youtube_paths(args.confs):
        slug = confs.slug_for(path)
        if slug not in wanted:
            continue
        conf = confs.load(path)
        station = conf["station_conf"]
        streams = station.get("streams", [])
        found = runs(streams)
        in_runs = sum(n for _, n in found)
        print("  %-24s %3d of %3d clips in %d series" % (
            station.get("network_name"), in_runs, len(streams), len(found)))
        for show, count in found[:5]:
            print("       %-34s %d episodes" % (show[:34], count))
        if not args.report:
            station["streams"] = ordered(streams)
            confs.save(path, conf)
            touched += 1

    print("\n%d channel%s %s" % (touched, "" if touched == 1 else "s",
                                 "reported" if args.report else "reordered"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
