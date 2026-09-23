#!/usr/bin/env python3
"""Turn the channel definitions in confs/ into the single channels.json the televisions read.

This replaced a `publish.py` that ran on a mini-pc at home and served the result over HTTP. The
output is byte-for-byte the same contract - the app was not changed to suit this - because the
lineup format is the one thing two televisions and a nightly workflow all have to agree on, and
changing it would mean every installed app stops working until someone sideloads a new one.

  python3 build_lineup.py [--out ../channels.json]

Deliberately does no network access. Fetching content is refresh_channels.py's job; this only
gathers what the confs already say. Keeping them apart means a broken YouTube cannot produce a
broken lineup - the worst it can do is produce a stale one.
"""
import argparse
import glob
import io
import json
import os
import re
import sys
import time

import confs

# `https://www.youtube.com/watch?v=dQw4w9WgXcQ` and nothing else. The app splits live channels
# from clips by whether a stream carries an id, so a url this fails to read would be played as
# though it were an HLS feed - silently, and wrongly.
VIDEO_ID = re.compile(r"[?&]v=([A-Za-z0-9_-]{11})")


def video_id(url):
    match = VIDEO_ID.search(url or "")
    return match.group(1) if match else None


# The least watch time a clip may be left with once its sponsor skips are taken out. Below this
# the skips are not published and the clip plays in full.
MIN_WATCH_SECONDS = 30


def published_skip(skip, duration):
    """The `skip` ranges to publish for a YouTube stream, or None to publish none.

    sponsor.py writes them into the confs already merged, clamped and sorted, so this only
    decides whether they go out. An empty list is a cached "SponsorBlock had nothing" - conf
    bookkeeping that would add nine thousand `[]`s to a file two televisions fetch over mobile
    data - and the app treats a missing field as no skips anyway (the contract tolerates unknown
    and absent fields). `duration` is left as the raw length; the app derives the watch time.

    A clip skipped down to almost nothing is a near-phantom for the same reason a zero-duration
    clip is (the rotation walks watch time), so its skips are withheld rather than the clip
    dropped: it plays in full, exactly as if SponsorBlock had no information, and the gate's clip
    counts do not move.
    """
    if not skip:
        return None
    if duration - sum(end - start for start, end in skip) < MIN_WATCH_SECONDS:
        return None
    return skip


def published_parts(parts):
    """The `parts` to publish for a YouTube stream, or None for the all-day pool.

    Untagged is how the contract says "all day", so an empty list is left off exactly as an empty
    `skip` is. Filtered to the parts the app knows and put in the day's order (confs.DAY_PARTS):
    a part name nothing draws from would be a clip that is never on, and the order of a list a
    conf happened to hold must not be able to churn a file that is only rewritten when it changes.
    """
    ordered = [part for part in confs.DAY_PARTS if part in (parts or ())]
    return ordered or None


def channel_from(path):
    """One channel in the app's contract, or None if this conf cannot become one."""
    conf = confs.load(path)
    station = conf.get("station_conf")
    if not station:
        return None

    name = station.get("network_name")
    number = station.get("channel_number")
    if not name or number is None:
        return None

    # WeatherStar and anything else pointing at a local web page. These were rendered by a browser
    # on the box that served them; with the box gone there is nothing behind the url, and shipping
    # a channel that can only ever show a connection error is worse than not shipping it.
    if station.get("network_type") == "web":
        return None

    is_youtube = os.path.basename(path).startswith("ytch_")
    # File channels (file_*.json) carry urls served by the homelab media server: nothing to
    # extract an id from and nothing to resolve - the url IS the playable.
    is_file = os.path.basename(path).startswith("file_")
    streams = []
    for stream in station.get("streams", []):
        url = stream.get("url")
        if not url:
            continue
        duration = int(stream.get("duration") or 0)
        if duration <= 0:
            # The rotation skips these, so they occupy a slot in the list and can never be on
            # air - a phantom that makes the channel's cycle shorter than it looks and can never
            # be diagnosed from the screen.
            continue
        entry = {"url": url, "duration": duration, "title": stream.get("title") or ""}
        if is_youtube:
            identifier = video_id(url)
            if not identifier:
                # A youtube channel entry whose url is not a watch url cannot be resolved, and
                # would be handed to the player as a live stream. Drop it rather than ship it.
                continue
            entry["id"] = identifier
            skip = published_skip(stream.get("skip"), duration)
            if skip:
                entry["skip"] = skip
            # Time-of-day mixes (dial.PARTS): which parts of the day refresh_channels found the
            # clip for. The app draws each half-hour slot from the clips tagged for its part.
            parts = published_parts(stream.get("parts"))
            if parts:
                entry["parts"] = parts
        streams.append(entry)

    if not streams:
        # A channel with nothing on it is a dead number on the dial: it tunes to black and the
        # viewer has to press twice to get past it.
        return None

    return {
        "number": int(number),
        "name": name,
        "kind": "youtube" if is_youtube else ("file" if is_file else "live"),
        # Clock rotation is what makes the dial feel like television: the clip is joined partway
        # through, at the offset the wall clock implies. File channels rotate the same way - the
        # film is always "on" somewhere in its runtime. Live feeds have no rotation - they are
        # already whatever they are at this moment.
        "rotation": (station.get("stream_rotation") if is_youtube
                     else "clock" if is_file else None),
        "streams": streams,
    }


def generated_for(channels, previous, now):
    """The `generated` stamp: the previous one when the channels are unchanged, else `now`.

    A fresh stamp on every build meant channels.json differed every night whether or not a single
    clip had moved, so the workflow's "no changes" branch was unreachable and every run
    committed. Keeping the stamp while the content is identical makes the file byte-for-byte the
    same, and the stamp still means what it says: when this lineup last changed. The app parses
    the field (DialContract.Dial, defaulting to 0) and reads it nowhere, so a stable value costs
    nothing on the televisions.
    """
    if previous and previous.get("channels") == channels and previous.get("generated"):
        return previous["generated"]
    return now


def read_previous(path):
    """The lineup already at `path`, or None when there is none or it does not parse."""
    try:
        with io.open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confs", default=confs.default_dir())
    parser.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..",
                                                     "channels.json"))
    args = parser.parse_args()

    channels = []
    skipped = []
    for path in sorted(glob.glob(os.path.join(args.confs, "*.json"))):
        channel = channel_from(path)
        if channel:
            channels.append(channel)
        else:
            skipped.append(os.path.basename(path))

    channels.sort(key=lambda c: c["number"])

    numbers = [c["number"] for c in channels]
    duplicates = sorted({n for n in numbers if numbers.count(n) > 1})
    if duplicates:
        # Two channels on one number means one of them is unreachable from the remote, and which
        # one wins depends on sort order - exactly the kind of fault that looks like "that channel
        # just vanished" months later. Refuse rather than publish it.
        print("error: duplicate channel numbers: %s" % duplicates, file=sys.stderr)
        return 1

    out = os.path.abspath(args.out)
    dial = {"generated": generated_for(channels, read_previous(out), int(time.time())),
            "channels": channels}
    with io.open(out, "w", encoding="utf-8") as handle:
        json.dump(dial, handle, ensure_ascii=False, separators=(",", ":"))

    clips = sum(len(c["streams"]) for c in channels)
    youtube = [c for c in channels if c["kind"] == "youtube"]
    live = [c for c in channels if c["kind"] == "live"]
    print("%s: %d channels (%d youtube, %d live), %d clips, %.1f MB"
          % (out, len(channels), len(youtube), len(live), clips,
             os.path.getsize(out) / 1024.0 / 1024.0))
    if skipped:
        # Named rather than counted: a conf silently dropping out of the dial is the failure this
        # whole script is most likely to cause, and a name is what makes it noticeable.
        print("skipped %d: %s" % (len(skipped), ", ".join(skipped)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
