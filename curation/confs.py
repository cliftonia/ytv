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
import sys

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

    Written to a sibling temporary file first and moved into place, because a run can be killed
    mid-write - a CI timeout, a full disk, a ctrl-C - and an in-place open(path, "w") truncates
    the conf before the first byte of the new dump has arrived. A truncated conf then fails to
    parse, sorts to the front of every rotation as "never refreshed", and breaks every nightly
    from then on. os.replace is atomic on the same filesystem, so what is on disk is always
    either the old conf or the new one, never a torn half of each.
    """
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8") as handle:
        json.dump(conf, handle, indent=4, ensure_ascii=False)
    os.replace(tmp, path)


def last_refreshed(path):
    """When this channel was last re-searched, as an epoch. Never refreshed sorts first.

    A conf that cannot be parsed also sorts first, but it says so: silently returning 0 made a
    corrupted file look like a fresh channel patiently waiting its turn, when what it actually
    needs is a human. It will then fail loudly when refresh() tries to load it, rather than
    skewing the rotation with nobody the wiser. The warning goes to stderr so a green run's
    stdout stays a clean report.
    """
    try:
        return load(path)["station_conf"].get("last_refreshed", 0)
    except Exception as exc:
        print("warning: %s cannot be parsed (%s); treating it as never refreshed"
              % (path, exc), file=sys.stderr)
        return 0
