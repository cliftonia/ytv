#!/usr/bin/env python3
"""Keep a copy of every published ad reel on this machine, for the televisions on the LAN.

    mirror_ads.py <ads.json> <mirror dir> [--pause S] [--limit N]

WHY. A reel from archive.org takes 8.6-10 s to show its first frame on the TCL: from Brisbane the
download redirect costs ~0.9 s and every range request ~1.5 s to its first byte, and a join is
several of them - the ~570 KB moov at the front, then a seek to the cut mid-file. The same file
from this box on the LAN is a few milliseconds per request. serve_ads.py serves what this keeps.

WHAT. For each reel in the pool, downloads its url to <dir>/<id>.mp4 unless that file is already
there: the same bytes, so the pool's cut offsets hold. A download lands as <id>.mp4.part and is
renamed only when complete (and as long as the server said it would be), so the server never
hands out half a file. Reels no longer in the pool are deleted, and so are stale .part files -
but only when the pool read cleanly and has reels, so a bad night never empties the mirror.

Polite: one download at a time, a pause between them, a user agent naming the project. A reel
that fails is skipped and tried again on the next run; the exit status is 0 regardless, because
the mirror is an accelerator and never the reason the nightly publish fails.
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

USER_AGENT = "ytv-ads-pool/1.0 mirror (+https://github.com/cliftonia/ytv)"
# archive.org identifiers: letters, digits, dot, dash, underscore. Anything else - and anything
# starting with a dot, so never "." or ".." - is not a file name this will make or serve.
ID_PATTERN = re.compile(r"^[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}$")
SUFFIX = ".mp4"
PART = ".part"
CHUNK = 1 << 20
TIMEOUT = 60


def valid_id(ident):
    return isinstance(ident, str) and bool(ID_PATTERN.match(ident))


def path_for(directory, ident):
    """Where reel `ident` is kept; ValueError for an id that is not a plain file name."""
    if not valid_id(ident):
        raise ValueError("not a reel id: %r" % (ident,))
    return os.path.join(directory, ident + SUFFIX)


def load_pool(path):
    """{id: url} for every mirrorable reel in ads.json, or None when the file cannot be read."""
    try:
        with open(path, encoding="utf-8") as handle:
            reels = json.load(handle).get("reels")
    except (OSError, ValueError, AttributeError):
        return None
    if not isinstance(reels, list):
        return None
    pool = {}
    for reel in reels:
        if not isinstance(reel, dict):
            continue
        ident, url = reel.get("id"), reel.get("url")
        if valid_id(ident) and isinstance(url, str) and url.startswith("https://"):
            pool[ident] = url
    return pool


def present(directory):
    """Ids already mirrored in full (no .part), and the leftover .part names."""
    done, parts = set(), []
    for name in os.listdir(directory):
        if name.endswith(SUFFIX + PART):
            parts.append(name)
        elif name.endswith(SUFFIX) and valid_id(name[:-len(SUFFIX)]):
            done.add(name[:-len(SUFFIX)])
    return done, parts


def plan(pool, done, parts):
    """(ids to fetch in a stable order, file names to delete)."""
    fetch = sorted(i for i in pool if i not in done)
    stale = sorted(i + SUFFIX for i in done if i not in pool)
    # Every .part is from an interrupted run: this one restarts it from nothing.
    return fetch, stale + sorted(parts)


def download(url, dest, opener=urllib.request.urlopen):
    """Fetch url into dest via dest.part; returns bytes written. Raises on any shortfall."""
    partial = dest + PART
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    written = 0
    try:
        with opener(request, timeout=TIMEOUT) as response, open(partial, "wb") as out:
            expected = response.headers.get("Content-Length")
            while True:
                chunk = response.read(CHUNK)
                if not chunk:
                    break
                out.write(chunk)
                written += len(chunk)
        if expected is not None and int(expected) != written:
            raise IOError("short read: %d of %s bytes" % (written, expected))
        if written == 0:
            raise IOError("empty body")
        os.replace(partial, dest)
    except BaseException:
        try:
            os.remove(partial)
        except OSError:
            pass
        raise
    return written


def run(pool_path, directory, pause=5.0, limit=None, opener=urllib.request.urlopen,
        sleep=time.sleep, log=print):
    """One mirror pass. Returns (fetched, failed, pruned) counts."""
    os.makedirs(directory, exist_ok=True)
    pool = load_pool(pool_path)
    if not pool:
        log("mirror: no pool in %s; leaving the mirror as it is" % pool_path)
        return 0, 0, 0
    fetch, delete = plan(pool, *present(directory))
    for name in delete:
        try:
            os.remove(os.path.join(directory, name))
        except OSError as exc:
            log("mirror: could not delete %s: %s" % (name, exc))
    if limit is not None:
        fetch = fetch[:limit]
    log("mirror: %d reels in the pool, %d to fetch, %d deleted" % (len(pool), len(fetch), len(delete)))
    fetched = failed = 0
    for index, ident in enumerate(fetch):
        if index:
            sleep(pause)
        started = time.time()
        try:
            size = download(pool[ident], path_for(directory, ident), opener)
        except Exception as exc:  # noqa: BLE001 - any failure is retried on the next run
            failed += 1
            log("  %-60s failed: %s" % (ident[:60], exc))
            continue
        fetched += 1
        log("  %-60s %6.1f MB in %.0fs" % (ident[:60], size / 1e6, time.time() - started))
    return fetched, failed, len(delete)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("pool")
    parser.add_argument("directory")
    parser.add_argument("--pause", type=float, default=5.0, help="seconds between downloads")
    parser.add_argument("--limit", type=int, default=None, help="at most this many downloads")
    args = parser.parse_args(argv)
    fetched, failed, pruned = run(args.pool, args.directory, args.pause, args.limit)
    print("mirror: %d fetched, %d failed, %d deleted" % (fetched, failed, pruned))
    return 0


if __name__ == "__main__":
    sys.exit(main())
