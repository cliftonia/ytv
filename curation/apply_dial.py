#!/usr/bin/env python3
"""Reconcile the confs on disk to the dial declared in dial.py.

Renames, merges, creates, deletes and renumbers, then reports what it did. Idempotent: running it
twice changes nothing the second time, which is what makes it safe to run after editing dial.py
rather than something to be careful with.

  python3 apply_dial.py --dry     say what would change
  python3 apply_dial.py           do it

Content is never invented here. A newly created channel gets an empty stream list and is filled by
refresh_channels.py; a merged channel keeps the survivor's clips and lets the next refresh widen
them to the new query. Keeping the two apart means this can be re-run at any time without spending
an hour of searching.
"""
import argparse
import glob
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dial as DIAL

CONFS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "confs")


def path_for(slug, live=False):
    return os.path.join(CONFS, "%s.json" % (slug if live else "ytch_%s" % slug))


def load(path):
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def save(path, conf):
    with io.open(path, "w", encoding="utf-8") as handle:
        json.dump(conf, handle, indent=4, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry", action="store_true")
    args = parser.parse_args()
    actions = []

    def do(description, fn):
        actions.append(description)
        if not args.dry:
            fn()

    # 1. Renames first, so later steps find the slug they expect.
    for old, new in DIAL.RENAMED_SLUGS.items():
        src, dst = path_for(old), path_for(new)
        if os.path.exists(src) and not os.path.exists(dst):
            do("rename  ytch_%s -> ytch_%s" % (old, new),
               lambda s=src, d=dst: os.rename(s, d))

    # 2. Absorbed channels. The survivor's query in dial.py already covers their ground, so the
    #    conf is simply removed - its clips would be replaced by the next refresh regardless.
    for slug, into in sorted(DIAL.ABSORBED.items()):
        p = path_for(slug)
        if os.path.exists(p):
            do("merge   %-18s -> %s" % (slug, into), lambda p=p: os.remove(p))

    # 3. Dropped channels.
    for slug, why in sorted(DIAL.DROPPED.items()):
        p = path_for(slug)
        if os.path.exists(p):
            do("drop    %-18s (%s)" % (slug, why), lambda p=p: os.remove(p))

    # 4. YouTube channels: create what is missing, and set number, name and query on all of them.
    wanted_files = set()
    for number, slug, name, query in DIAL.YOUTUBE:
        p = path_for(slug)
        wanted_files.add(os.path.basename(p))
        if os.path.exists(p):
            conf = load(p)
            station = conf.setdefault("station_conf", {})
            changes = []
            if station.get("channel_number") != number:
                changes.append("ch %s->%s" % (station.get("channel_number"), number))
            if station.get("network_name") != name:
                changes.append("name %r->%r" % (station.get("network_name"), name))
            if station.get("search_query") != query:
                changes.append("query")
            playlists = DIAL.PLAYLISTS.get(slug)
            extra = DIAL.EXTRA_QUERIES.get(slug)
            if station.get("playlists") != playlists:
                changes.append("playlists")
            if station.get("extra_queries") != extra:
                changes.append("extra queries")
            if not changes:
                continue
            station["channel_number"] = number
            station["network_name"] = name
            station["network_long_name"] = name
            station["search_query"] = query
            if playlists:
                station["playlists"] = playlists
            else:
                station.pop("playlists", None)
            if extra:
                station["extra_queries"] = extra
            else:
                station.pop("extra_queries", None)
            station.setdefault("network_type", "streaming")
            station.setdefault("stream_rotation", "clock")
            station.setdefault("streams", [])
            do("update  %-18s %s" % (slug, ", ".join(changes)), lambda p=p, c=conf: save(p, c))
        else:
            station = {
                "network_name": name, "network_long_name": name, "network_type": "streaming",
                "channel_number": number, "stream_rotation": "clock",
                "search_query": query, "streams": [],
            }
            if DIAL.PLAYLISTS.get(slug):
                station["playlists"] = DIAL.PLAYLISTS[slug]
            if DIAL.EXTRA_QUERIES.get(slug):
                station["extra_queries"] = DIAL.EXTRA_QUERIES[slug]
            conf = {"station_conf": station}
            do("create  %-18s ch %d (empty until the next refresh)" % (slug, number),
               lambda p=p, c=conf: save(p, c))

    # 5. Live channels.
    for number, slug, name, streams in DIAL.LIVE:
        p = path_for(slug, live=True)
        wanted_files.add(os.path.basename(p))
        if os.path.exists(p):
            conf = load(p)
            station = conf.setdefault("station_conf", {})
            changed = station.get("channel_number") != number or station.get("network_name") != name
            station["channel_number"] = number
            station["network_name"] = name
            station["network_long_name"] = name
            station.setdefault("network_type", "streaming")
            if streams is not None:
                new = [{"url": u, "duration": 600, "title": t} for t, u in streams]
                if station.get("streams") != new:
                    station["streams"] = new
                    changed = True
            if changed:
                do("live    %-18s ch %d" % (slug, number), lambda p=p, c=conf: save(p, c))
        elif streams is not None:
            conf = {"station_conf": {
                "network_name": name, "network_long_name": name, "network_type": "streaming",
                "channel_number": number,
                "streams": [{"url": u, "duration": 600, "title": t} for t, u in streams],
            }}
            do("create  %-18s ch %d (live)" % (slug, number), lambda p=p, c=conf: save(p, c))
        else:
            actions.append("MISSING %-18s ch %d has no conf and no urls declared" % (slug, number))

    # 6. Anything left over. Named rather than deleted: a conf this does not know about is either
    #    something to add to dial.py or something to remove from it, and guessing which would
    #    eventually throw away a channel somebody wanted.
    for p in sorted(glob.glob(os.path.join(CONFS, "*.json"))):
        base = os.path.basename(p)
        if base not in wanted_files:
            actions.append("ORPHAN  %s is on disk but not in dial.py" % base)

    for line in actions:
        print("  " + line)
    print("\n%d action%s%s" % (len(actions), "" if len(actions) == 1 else "s",
                               " (dry run, nothing written)" if args.dry else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
