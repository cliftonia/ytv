#!/usr/bin/env python3
"""Find the ad boundaries in one archive.org reel and print its pool record as JSON.

    cut_reel.py <identifier> [--title T] [--era E]

Reads https://archive.org/metadata/<id> for the h.264 .mp4 and its length, then streams that
mp4 through ffmpeg ONCE - nothing is saved - with three detectors on a 160x120 copy of the
picture and a 16 kHz copy of the sound: blackdetect (black runs), a scene-change score, and
the audio RMS level every 40 ms. adcuts.py turns those into cuts (see its doc for why black
alone is not enough). Measured on the home server: an 18.5-minute reel takes ~12-15 s, bound
by the download (~115 MB), not the decoding.

The record always prints (exit 0) so the caller can remember a reel it should not retry:

    {"id", "title", "era", "url", "duration", "cuts", "status": "ok"|"rejected", "reason",
     "checked": <unix>, "seconds": <processing time>}

A network or ffmpeg failure exits 1 with nothing on stdout - try again another night.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adcuts  # noqa: E402
from find_reels import era_of  # noqa: E402

USER_AGENT = "ytv-ads-pool/1.0 (+https://github.com/cliftonia/ytv)"
MIN_DURATION = 180.0     # s: shorter items are single spots, not reels
MAX_DURATION = 4 * 3600.0
FFMPEG_TIMEOUT = 1800    # s

# Preferred video formats, best first. archive.org derives "h.264" (640x480 for these reels);
# some uploads carry their original mp4 as "MPEG4".
_FORMATS = ("h.264", "h.264 IA", "MPEG4", "h.264 HD")


def fetch_metadata(ident):
    request = urllib.request.Request("https://archive.org/metadata/" + urllib.parse.quote(ident),
                                     headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def parse_length(value):
    """archive.org `length`: seconds as a string, or sometimes MM:SS / HH:MM:SS."""
    if value is None:
        return None
    try:
        parts = [float(p) for p in str(value).split(":")]
    except ValueError:
        return None
    seconds = 0.0
    for part in parts:
        seconds = seconds * 60 + part
    return seconds


def pick_mp4(files):
    """(name, length seconds) of the best .mp4 in an item's file list, or None."""
    mp4s = [f for f in files if str(f.get("name", "")).lower().endswith(".mp4")]
    if not mp4s:
        return None

    def rank(f):
        fmt = f.get("format", "")
        return (_FORMATS.index(fmt) if fmt in _FORMATS else len(_FORMATS), f.get("name"))

    best = min(mp4s, key=rank)
    return best["name"], parse_length(best.get("length"))


def download_url(ident, name):
    return "https://archive.org/download/%s/%s" % (ident, urllib.parse.quote(name))


class NoStream(Exception):
    """The file lacks a picture or a sound track: a permanent verdict, not a retry."""


def detect(url, workdir):
    """Run the one ffmpeg pass; returns (scenes, rms, blacks, stderr)."""
    scene_file = os.path.join(workdir, "scene.txt")
    rms_file = os.path.join(workdir, "rms.txt")
    graph = ("[0:v]scale=160:120,blackdetect=d=0.03:pix_th=0.15:pic_th=0.90,"
             "select='gt(scene,0.25)',metadata=print:key=lavfi.scene_score:file=%s[v];"
             "[0:a]asplit=2[as][al];[al]ebur128=framelog=quiet,anullsink;"
             "[as]aresample=16000,asetnsamples=640,astats=metadata=1:reset=1,"
             "ametadata=print:key=lavfi.astats.Overall.RMS_level:file=%s[a]") % (scene_file, rms_file)
    command = ["ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-loglevel", "info",
               "-user_agent", USER_AGENT, "-reconnect", "1", "-reconnect_streamed", "1",
               "-reconnect_delay_max", "30", "-rw_timeout", "60000000",
               "-i", url, "-filter_complex", graph, "-map", "[v]", "-map", "[a]", "-f", "null", "-"]
    done = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                          stderr=subprocess.PIPE, text=True, errors="replace", timeout=FFMPEG_TIMEOUT)
    if done.returncode != 0:
        if "matches no streams" in done.stderr:
            raise NoStream("no audio or no video stream")
        raise RuntimeError("ffmpeg exit %d: %s" % (done.returncode, done.stderr.strip()[-400:]))
    with open(scene_file) as handle:
        scenes = adcuts.parse_metadata_print(handle.read(), "lavfi.scene_score")
    with open(rms_file) as handle:
        rms = adcuts.parse_metadata_print(handle.read(), "lavfi.astats.Overall.RMS_level")
    return scenes, rms, adcuts.parse_blackdetect(done.stderr), done.stderr


def measure_loudness(url):
    """The whole file's integrated loudness (LUFS), audio only - for a reel cut before it was kept."""
    command = ["ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-loglevel", "info",
               "-user_agent", USER_AGENT, "-reconnect", "1", "-reconnect_streamed", "1",
               "-reconnect_delay_max", "30", "-rw_timeout", "60000000",
               "-i", url, "-vn", "-map", "0:a:0", "-af", "ebur128=framelog=quiet", "-f", "null", "-"]
    done = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                          stderr=subprocess.PIPE, text=True, errors="replace", timeout=FFMPEG_TIMEOUT)
    if done.returncode != 0:
        raise RuntimeError("ffmpeg exit %d: %s" % (done.returncode, done.stderr.strip()[-400:]))
    return adcuts.parse_loudness(done.stderr)


def process(ident, title=None, era=None):
    started = time.time()
    record = {"id": ident, "title": title, "era": era, "url": None, "duration": None,
              "cuts": [], "status": "rejected", "reason": None}
    meta = fetch_metadata(ident)
    if not title:
        record["title"] = (meta.get("metadata") or {}).get("title") or ident
    if not era:
        record["era"] = era_of(record["title"], (meta.get("metadata") or {}).get("year"))
    chosen = pick_mp4(meta.get("files") or [])
    if chosen is None:
        record["reason"] = "no mp4"
    else:
        name, length = chosen
        record["url"] = download_url(ident, name)
        record["duration"] = round(length, 2) if length else None
        if length is not None and not MIN_DURATION <= length <= MAX_DURATION:
            record["reason"] = "duration %.0fs" % length
        else:
            try:
                with tempfile.TemporaryDirectory(prefix="ytv-ads-") as workdir:
                    scenes, rms, blacks, stderr = detect(record["url"], workdir)
                record["loudness"] = adcuts.parse_loudness(stderr)
            except NoStream:
                scenes, rms, blacks = [], [], []
            if not rms:
                record["reason"] = "no audio"
            else:
                if record["duration"] is None:
                    record["duration"] = round(rms[-1][0], 2)
                cuts = adcuts.choose_cuts(adcuts.candidates(scenes, rms, blacks), record["duration"])
                record["cuts"] = cuts
                record["reason"] = adcuts.verdict(cuts, record["duration"])
                if record["reason"] is None:
                    record["status"] = "ok"
    record["checked"] = int(time.time())
    record["seconds"] = round(time.time() - started, 1)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("identifier", help="archive.org item, or with --loudness the reel's url")
    parser.add_argument("--loudness", action="store_true",
                        help="only measure the reel's integrated loudness and print it (LUFS, or null)")
    parser.add_argument("--title")
    parser.add_argument("--era", choices=("70s", "80s", "90s"))
    args = parser.parse_args()
    if args.loudness:
        try:
            print(json.dumps(measure_loudness(args.identifier)))
        except Exception as error:
            print("cut_reel --loudness: %s" % error, file=sys.stderr)
            return 1
        return 0
    try:
        record = process(args.identifier, args.title, args.era)
    except Exception as error:  # network, ffmpeg, bad json: nothing learned, retry later
        print("cut_reel %s: %s" % (args.identifier, error), file=sys.stderr)
        return 1
    json.dump(record, sys.stdout)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
