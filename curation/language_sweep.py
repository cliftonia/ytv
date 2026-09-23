#!/usr/bin/env python3
"""Drop clips that YouTube itself says are not in English.

The authoritative answer to a question four title-based filters could not settle. A title is a
name rather than a sentence: "Sarah McLachlan: Tiny Desk Concert" carries no English function
word and "Kung Fu Chaos" carries a Filipino one, so every heuristic built on titles either kept
Sinhala reggae or deleted the Martial Arts channel. YouTube publishes the language per video, and
that cannot be fooled by a loanword or a surname.

The declared language needs a FULL extraction, not the flat-playlist listing the nightly refresh
uses - about two seconds a clip, which across nine thousand clips is over an hour. So the work
happens on the accelerator, which does full extractions anyway and has the cores for it, and this
only reads the verdict.

Partial by nature. Measured over a real search, 8 clips in 14 declared a language and 6 declared
nothing, so this REPLACES nothing: it removes what it is sure about, and filters.english_speech
still handles everything that stays silent.

Where the verdict comes from. The nightly runs on a GitHub runner, and the accelerator is on the
tailnet - which the runner cannot reach, so every nightly sweep so far printed "could not reach"
and removed nothing. The verdict now travels the other way: tools/publish_foreign.sh runs on the
home server, asks the accelerator locally, and commits curation/foreign.json; this reads that
file. Asking the server directly is still possible from a machine on the tailnet, but only when
told to (--from-server), so a missing file is never quietly papered over by a network call.

  python3 language_sweep.py --dry                 say what would go
  python3 language_sweep.py                       remove it, going by foreign.json
  python3 language_sweep.py --from-server         ask the accelerator instead (tailnet only)
"""
import argparse
import json
import os
import sys
import urllib.request

import build_lineup
import confs

SERVER = "http://100.74.3.68:4243"

# {"generated": <unix>, "foreign": [video ids]}, written by tools/publish_foreign.sh.
VERDICTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "foreign.json")


def foreign_from_file(path):
    """Ids the committed verdict file lists as not English, as {id: ""}, or None when there is no
    usable file.

    Same contract as foreign_ids(): None is "no information" and the caller leaves the dial
    alone, while an empty list is a real answer. A file of the wrong shape is None and says so -
    guessing at a half-written file could remove clips on the strength of garbage.
    """
    if not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as handle:
            body = json.load(handle)
        ids = body["foreign"]
        if not isinstance(ids, list) or not all(isinstance(i, str) for i in ids):
            raise ValueError("'foreign' is not a list of video ids")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print("could not read %s: %s" % (path, exc), file=sys.stderr)
        return None
    # A map rather than a set, so the report below reads the same whichever source answered. The
    # file carries ids only - the language is on the server if anyone needs it.
    return {identifier: "" for identifier in ids}


def foreign_ids(server, timeout=30):
    """Ids the accelerator has seen declare a language that is not English, or None if it cannot
    be reached.

    None rather than an empty set on failure, and the caller stops: an unreachable server means
    "no information", and treating that as "nothing is foreign" would be indistinguishable from a
    successful sweep that found nothing. Silence and a clean bill of health must not look alike.
    """
    try:
        with urllib.request.urlopen("%s/languages" % server, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
    except Exception as exc:
        print("could not reach %s: %s" % (server, exc), file=sys.stderr)
        return None
    return body.get("foreign") or {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry", action="store_true")
    parser.add_argument("--verdicts", default=VERDICTS,
                        help="the committed verdict file (default: %(default)s)")
    parser.add_argument("--from-server", action="store_true",
                        help="ask the accelerator directly instead of reading --verdicts")
    parser.add_argument("--server", default=SERVER)
    parser.add_argument("--confs", default=confs.default_dir())
    args = parser.parse_args()

    if args.from_server:
        foreign, source = foreign_ids(args.server), "the accelerator"
    else:
        foreign, source = foreign_from_file(args.verdicts), os.path.basename(args.verdicts)
    if foreign is None:
        # Not a failure of the dial, so not a failure of the run: the nightly should carry on and
        # publish, with the title rules doing what they always did.
        print("no language data available; leaving the dial alone")
        return 0
    print("%s reports %d clips in another language" % (source, len(foreign)))

    removed = 0
    for path in confs.youtube_paths(args.confs):
        conf = confs.load(path)
        station = conf["station_conf"]
        streams = station.get("streams", [])
        # Conf streams carry only a url - the "id" field is minted later, by build_lineup, when
        # the lineup is published. Matching on s.get("id") here compared None against the foreign
        # map for every stream, so the sweep reported a healthy-looking run while never removing
        # anything. The id has to be derived from the url, with the same extraction the publisher
        # uses, or the sweep and the lineup disagree about which clip is which.
        keep = [s for s in streams
                if build_lineup.video_id(s.get("url")) not in foreign]
        if len(keep) == len(streams):
            continue
        for stream in streams:
            identifier = build_lineup.video_id(stream.get("url"))
            if identifier in foreign:
                print("  [%s] %-22s %s" % (foreign[identifier] or "--",
                                           station.get("network_name", "?")[:22],
                                           (stream.get("title") or "")[:52]))
        removed += len(streams) - len(keep)
        if not args.dry:
            station["streams"] = keep
            confs.save(path, conf)

    print("\n%d clips removed%s" % (removed, " (dry run)" if args.dry else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
