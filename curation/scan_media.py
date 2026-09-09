#!/usr/bin/env python3
"""Rebuild each file_ channel's stream list from the media folders on the homelab server.

Run it ON the server, where the files are:

    ssh hermanb@100.74.3.68
    sudo -u http python3 ~/ytv/scan_media.py            # after an rsync of curation/
    sudo -u http python3 ~/ytv/scan_media.py --dry      # report, change nothing

then commit the changed confs and rebuild channels.json (build_lineup.py - the nightly
workflow's own step).

Every stream becomes a plain http url on the media server (tools/media-server/, port 4244),
so the app never resolves these: the url is handed straight to the player. Durations come
from ffprobe - the wall-clock rotation in the app joins a film partway through by arithmetic
on exactly these numbers, so a duration that is wrong by half makes every programme after it
in the cycle land wrong too. A file ffprobe cannot read is skipped, loudly, because shipping
it with a guessed duration is the phantom-clip failure build_lineup.py drops on sight.

Not refresh_channels.py: there is nothing to search. The dial only changes when the files do,
and the operator knows when that is - they put them there.
"""
import argparse
import os
import re
import subprocess
import sys
from urllib.parse import quote, unquote, urlparse

import confs

# Defaults for the homelab box; both overridable for another host or a mounted copy.
MEDIA_ROOT = "/mnt/hdd/nextcloud-data/data/hermanb/files"
BASE_URL = "http://192.168.4.58:4244"

VIDEO_EXT = {".mp4", ".mkv", ".m4v", ".mov", ".avi", ".webm", ".ts"}

# Directories that are never content: partial downloads and platform litter.
SKIP_DIRS = {"download-tmp", ".recycle", "@eadir"}
# "Sample" clips ride along in scene releases and are not the film. Word-boundary, so
# "The Sample Case" survives; "Movie.Sample.2024" does not.
SAMPLE = re.compile(r"\bsample\b", re.I)

# "Pioneer.One.S01E02.720p.x264-VODO" - the only episode numbering worth trusting in a
# filename. A bare number is a year or a resolution as often as an episode.
SEASON_EPISODE = re.compile(r"\bS(\d{1,2})\s?[Ee](\d{1,3})\b", re.I)

# Release-tag litter between the title and the extension: "2160p", "HDR10+", "x264",
# "WEB-DL", "PROPER", group names. Cosmetic only - the title never goes back to a path.
# The \b sits before the optional '+' so "HDR10+" is eaten whole (a \b after '+' never
# matches at end of name, and the regex would fall back to leaving the '+' behind), and
# the trailing \b keeps "webdlp" from being read as WEB-DL + "p". The cost: a real title
# word that is also a tag ("Proper Villains", "Extended") is eaten - cosmetic, accepted.
RELEASE_TAGS = re.compile(
    r"\b(480p|576p|720p|1080p|2160p|4k|8k|hdr10|hdr|sdr|dovi|dv|x264|x265|h264|h265|"
    r"hevc|av1|aac|dts|atmos|web[- ]?dl|webrip|bluray|bdrip|brrip|hdtv|dvdrip|extended|"
    r"proper|repack|internal|remux)\b\+?", re.I)


def humanise(stem):
    """A title a banner can show, from a filename that was never meant to be one.

    Returns "" when nothing but tags and separators survived - callers pick the fallback,
    because the right one differs: a film falls back to its whole name, an episode to no
    detail at all (otherwise the show name lands in the title twice)."""
    if " " not in stem:
        # Dot-style release name. The group suffix ("-VODO", "-RARBG") only exists in this
        # style, so it is stripped here and never from a space-separated name, where the
        # same shape is a real word (" - Earthfall Pilot" is a title, not a group).
        stem = re.sub(r"-[A-Za-z0-9]{2,12}$", "", stem)
        stem = re.sub(r"[._]+", " ", stem)
    stem = RELEASE_TAGS.sub("", stem)
    return re.sub(r"\s{2,}", " ", stem).strip(" -.")


def parse_episode(path, media_root):
    """(show, season, episode, title) for a series file, or None when it is not one.

    The show is the directory directly under Series/, because two shows' S01E01s must sort
    into two runs, not one. The title keeps whatever the uploader wrote after the SxxEyy tag,
    which is usually the episode name.
    """
    rel = os.path.relpath(path, media_root)
    parts = rel.split(os.sep)
    if len(parts) < 3:
        return None
    stem = os.path.splitext(parts[-1])[0]
    match = SEASON_EPISODE.search(stem)
    if not match:
        return None
    detail = humanise(stem[match.end():])
    return (humanise(parts[1]) or parts[1], int(match.group(1)), int(match.group(2)), detail)


def probe_duration(path):
    """Seconds from ffprobe, or None when the file is not a video ffprobe can open."""
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", path],
            capture_output=True, text=True, timeout=60)
        return int(round(float(out.stdout.strip())))
    except (ValueError, OSError, subprocess.TimeoutExpired):
        return None


def walk_videos(media_dir):
    """Video files under media_dir, skipping non-content directories, in stable order."""
    found = []
    for dirpath, dirnames, filenames in os.walk(media_dir):
        dirnames[:] = sorted(d for d in dirnames
                             if d.lower() not in SKIP_DIRS and not d.startswith("."))
        for name in sorted(filenames):
            if name.startswith(".") or SAMPLE.search(name):
                continue
            if os.path.splitext(name)[1].lower() in VIDEO_EXT:
                found.append(os.path.join(dirpath, name))
    return found


def collect(root, media_dir, base):
    """(stream, sort_key) pairs for one channel's folder.

    Series sort in show -> season -> episode runs; anything else (a film, a one-off) sorts
    alphabetically after the runs, by path rather than title so "A Film" and "a Film"
    cannot swap places between scans. The rotation walks this list in order, so the order
    here IS the broadcast order.
    """
    entries = []
    for path in walk_videos(media_dir):
        duration = probe_duration(path)
        if not duration or duration <= 0:
            print("warning: %s has no readable duration - left off the dial" % path,
                  file=sys.stderr)
            continue
        rel = os.path.relpath(path, root)
        url = "%s/%s" % (base.rstrip("/"), quote(rel.replace(os.sep, "/")))
        episode = parse_episode(path, os.path.dirname(media_dir))
        if episode:
            show, season, number, detail = episode
            title = "%s S%02dE%02d%s" % (show, season, number,
                                         " - " + detail if detail else "")
            key = (0, show.lower(), season, number, rel.lower())
        else:
            title = humanise(os.path.splitext(os.path.basename(path))[0]) \
                or os.path.splitext(os.path.basename(path))[0]
            key = (1, rel.lower(), 0, 0, rel.lower())
        entries.append(({"url": url, "duration": duration, "title": title}, key))
    entries.sort(key=lambda pair: pair[1])
    return [stream for stream, _ in entries]


def collect_remote(urls):
    """Streams for a conf's remote_urls: plain http(s) video files hosted elsewhere.

    Streams, not downloads: the dial needs only what ffprobe reads over the wire (the
    duration, via range requests), and the players fetch the same urls themselves at play
    time. Nothing of the file ever lives locally - which is exactly why these seeds must be
    dependable hosts, because the host's uptime IS the channel's uptime.

    Entries are "url" or "url|Title". Authored order IS broadcast order - there is no
    filesystem to inherit one from. Seeds must be https (or a host the app already trusts):
    the app's network config permits cleartext to exactly the two server IPs, so a plain http
    seed anywhere else is refused before a packet leaves the device.
    """
    streams = []
    for entry in urls:
        url, _, override = entry.partition("|")
        url, override = url.strip(), override.strip()
        if not url:
            continue
        duration = probe_duration(url)
        if not duration or duration <= 0:
            print("warning: %s has no readable duration - left off the dial" % url,
                  file=sys.stderr)
            continue
        stem = unquote(os.path.splitext(os.path.basename(urlparse(url).path))[0])
        streams.append({"url": url, "duration": duration,
                        "title": override or humanise(stem) or stem})
    return streams


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=MEDIA_ROOT,
                        help="directory the media dirs live under (default: %(default)s)")
    parser.add_argument("--base", default=BASE_URL,
                        help="url the media server publishes them at (default: %(default)s)")
    parser.add_argument("--confs", default=confs.default_dir())
    parser.add_argument("--only", metavar="SLUG", help="one channel, e.g. movies")
    parser.add_argument("--dry", action="store_true")
    args = parser.parse_args()

    rc = subprocess.run(["ffprobe", "-version"], capture_output=True)
    if rc.returncode != 0:
        # Fail before a walk's worth of files all report "no duration" and empty the dial.
        print("ffprobe is not runnable here; durations are the whole point", file=sys.stderr)
        return 1

    for path in sorted(confs.file_paths(args.confs)):
        slug = os.path.basename(path)[5:-5]
        if args.only and slug != args.only:
            continue
        conf = confs.load(path)
        station = conf.get("station_conf", {})
        declared_dir = station.get("media_dir", "")
        media_dir = os.path.join(args.root, declared_dir) if declared_dir else None
        streams = []
        if media_dir:
            if os.path.isdir(media_dir):
                streams = collect(args.root, media_dir, args.base)
            else:
                # A declared folder that is gone reads as an error, because the silent
                # version publishes an empty channel - the HDD stayed unmounted once before.
                print("%s: %s is not a directory - nothing scanned" % (slug, media_dir),
                      file=sys.stderr)
                continue
        streams += collect_remote(station.get("remote_urls", []))
        if not media_dir and not station.get("remote_urls"):
            print("%s: declares neither a media dir nor remote urls - nothing to scan"
                  % slug, file=sys.stderr)
            continue
        old = station.get("streams", [])
        print("%s: %d stream%s (%s)" % (slug, len(streams), "" if len(streams) == 1 else "s",
                                        "was %d" % len(old)))
        if not args.dry and streams != old:
            station["streams"] = streams
            confs.save(path, conf)
    return 0


if __name__ == "__main__":
    sys.exit(main())
