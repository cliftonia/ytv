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

  python3 language_sweep.py --dry     say what would go
  python3 language_sweep.py           remove it
"""
import argparse
import json
import sys
import urllib.request

import confs

SERVER = "http://100.74.3.68:4243"


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
    parser.add_argument("--server", default=SERVER)
    parser.add_argument("--confs", default=confs.default_dir())
    args = parser.parse_args()

    foreign = foreign_ids(args.server)
    if foreign is None:
        # Not a failure of the dial, so not a failure of the run: the nightly should carry on and
        # publish, with the title rules doing what they always did.
        print("no language data available; leaving the dial alone")
        return 0
    print("the accelerator reports %d clips in another language" % len(foreign))

    removed = 0
    for path in confs.youtube_paths(args.confs):
        conf = confs.load(path)
        station = conf["station_conf"]
        streams = station.get("streams", [])
        keep = [s for s in streams if s.get("id") not in foreign]
        if len(keep) == len(streams):
            continue
        for stream in streams:
            if stream.get("id") in foreign:
                print("  [%s] %-22s %s" % (foreign[stream["id"]],
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
