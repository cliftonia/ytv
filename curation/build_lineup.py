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
        streams.append(entry)

    if not streams:
        # A channel with nothing on it is a dead number on the dial: it tunes to black and the
        # viewer has to press twice to get past it.
        return None

    return {
        "number": int(number),
        "name": name,
        "kind": "youtube" if is_youtube else "live",
        # Clock rotation is what makes the dial feel like television: the clip is joined partway
        # through, at the offset the wall clock implies. Live feeds have no rotation - they are
        # already whatever they are at this moment.
        "rotation": station.get("stream_rotation") if is_youtube else None,
        "streams": streams,
    }


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

    dial = {"generated": int(time.time()), "channels": channels}
    out = os.path.abspath(args.out)
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
