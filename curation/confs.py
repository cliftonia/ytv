#!/usr/bin/env python3
"""Reading and writing the channel confs in confs/.

Every script in this directory reads the same files, and until this module existed each of them
carried its own copy of the load, the save, the `ytch_` glob and the slug slice. Four copies of a
one-line json read is not a problem in itself; the problem is that changing anything about the
conf format means finding all four, and the one that gets missed is the one nobody notices until
a nightly run writes a file the next script cannot read.

Not a package - the scripts here are flat siblings run by name, so `sys.path[0]` is this
directory and a plain `import confs` finds it both from a script and from `unittest discover`.
"""
import glob
import io
import json
import os

CONFS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "confs")


def default_dir():
    """The confs directory, as every script's `--confs` default."""
    return CONFS


def path_for(slug, live=False):
    return os.path.join(CONFS, "%s.json" % (slug if live else "ytch_%s" % slug))


def youtube_paths(confs_dir):
    """Every YouTube channel conf, in a stable order.

    Live channels are excluded by the `ytch_` prefix, which is also how build_lineup decides
    whether a stream needs a video id.
    """
    return sorted(glob.glob(os.path.join(confs_dir, "ytch_*.json")))


def slug_for(path):
    """The channel slug behind a conf path: confs/ytch_blues.json -> blues."""
    return os.path.basename(path)[5:-5]


def load(path):
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def save(path, conf):
    """Write a conf back.

    Indented and with the original characters intact, because confs are committed and a human
    reads the diff: a refresh that replaced a channel's clips should show as a hundred changed
    lines, not as one unreadable line. channels.json is the opposite case and is written minified
    by build_lineup - two televisions fetch it over mobile data every time they start.
    """
    with io.open(path, "w", encoding="utf-8") as handle:
        json.dump(conf, handle, indent=4, ensure_ascii=False)


def last_refreshed(path):
    """When this channel was last re-searched, as an epoch. Never refreshed sorts first."""
    try:
        return load(path)["station_conf"].get("last_refreshed", 0)
    except Exception:
        return 0
