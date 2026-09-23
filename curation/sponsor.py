#!/usr/bin/env python3
"""Look YouTube clips up in SponsorBlock and store what to skip on each stream.

  python3 sponsor.py                      the nightly: the most overdue clips, up to the cap
  python3 sponsor.py --only blues         one channel, for checking by hand
  python3 sponsor.py --max-requests 50    a smaller bite

SponsorBlock (https://sponsor.ajay.app) is a crowd-sourced list of the stretches of a video that
are a read ad, a plug for the creator's merch or a "like and subscribe". Skipping those turns a
YouTube clip back into a programme. Its data is CC BY-NC-SA 4.0 and credited in the README; this
dial is non-commercial.

What lands in a conf, per YouTube stream:

  skip          [[start, end], ...] in media seconds - merged, clamped, sorted, possibly empty
  skip_checked  when SponsorBlock last answered for this clip, as an epoch

build_lineup publishes `skip` only when it is non-empty; `duration` stays the raw length and the
app derives the watch time from the two.

Privacy. The lookup uses the hash-prefix endpoint: the request names only the first four hex of
sha256(video id), and the answer covers every video sharing that prefix (~60-90 of them, measured
23 Sep 2026). SponsorBlock never learns which clips are on the dial. It also means one request
answers for every clip of ours that shares a prefix, so requests are grouped by prefix - though
with 65536 prefixes and ~9000 clips, most clips have one to themselves.

Failure is "no information", never an error. A clip whose lookup failed keeps whatever it had
(nothing, or last month's answer), is not stamped, and is asked about again tomorrow; it plays in
full meanwhile, which is what it did before this existed. SponsorBlock being down, slow or
rate-limiting must never cost the dial its nightly refresh, so this exits 0 whatever happens and
the workflow runs it with continue-on-error as well.
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import build_lineup
import confs

ENDPOINT = "https://sponsor.ajay.app/api/skipSegments/"

# Ads read by the creator, plugs for their own things, and "like and subscribe". Intros and
# outros are deliberately NOT here: a title sequence is part of the programme on television too,
# and cutting a channel's opening music makes a clip feel like it starts mid-sentence.
CATEGORIES = ("sponsor", "selfpromo", "interaction")

# Segments get submitted after upload - most arrive in the first days, but a clip first looked up
# the hour it was published has nothing yet. A month is often enough to catch those and rare
# enough that re-checking the whole dial costs ~300 requests a night.
RECHECK_SECONDS = 30 * 86400

# Requests per run. Measured from Brisbane at ~0.8s a request (a US runner is nearer the server);
# with the delay below that is ~17 minutes at worst, inside BUDGET_SECONDS anyway. The first run
# faces ~9000 never-checked clips on ~8500 distinct prefixes, so the backlog clears in about nine
# nights. After that a night needs the ~300 monthly re-checks plus whatever the refresh slice
# brought in that was not already known (carry() keeps the answers for clips a refresh re-finds),
# which fits comfortably.
MAX_REQUESTS = 1000

# Between requests. SponsorBlock is a volunteer-run service; a thousand requests back to back from
# one address is the kind of thing that gets an address blocked, and there is no hurry.
DELAY_SECONDS = 0.25

# Wall-clock ceiling on a run, independent of the request cap: a slow server must not stretch the
# nightly towards its 50-minute job timeout. The workflow step has its own timeout above this, so
# a run always gets to save what it learned before it is stopped.
BUDGET_SECONDS = 12 * 60

# Consecutive failed requests before the run stops asking. Five failures in a row is the service
# being down or refusing us, and a thousand timeouts against a dead host helps nobody.
MAX_FAILURES_IN_A_ROW = 5

# Save every this many requests, so a run cut short still keeps most of what it learned.
SAVE_EVERY = 100

TIMEOUT_SECONDS = 15

# A range shorter than this is not worth a seek: a seek itself costs more than a second of picture
# on either engine, and slivers are usually two submissions that nearly-but-not-quite meet.
MIN_RANGE_SECONDS = 1.0

# A segment carries the video's length at submission time (0 when unknown, "+- 1 second" per the
# API docs). A clearly different length means the video was re-cut since, and the times point at
# the wrong picture. yt-dlp rounds our durations to whole seconds, hence the slack.
DURATION_SLACK_SECONDS = 3

USER_AGENT = "ytv-lineup/1.0 (+https://github.com/cliftonia/ytv)"


def prefix(video_id):
    """The first four hex of sha256(id): all SponsorBlock is told about a clip."""
    return hashlib.sha256(video_id.encode("utf-8")).hexdigest()[:4]


def url_for(hash_prefix):
    categories = json.dumps(list(CATEGORIES), separators=(",", ":"))
    return "%s%s?categories=%s" % (ENDPOINT, hash_prefix, urllib.parse.quote(categories, safe=""))


def fetch(hash_prefix, opener=urllib.request.urlopen, timeout=TIMEOUT_SECONDS):
    """SponsorBlock's answer for a prefix: a list of {videoID, segments}, or None on any failure.

    A 404 is not a failure - it is the API saying no video under this prefix has a segment in
    these categories, and that is a real answer worth caching. Everything else that goes wrong
    (429, 5xx, timeout, a body that is not a list) is None: no information.
    """
    request = urllib.request.Request(url_for(hash_prefix), headers={"User-Agent": USER_AGENT})
    try:
        with opener(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return []
        print("sponsorblock %s: HTTP %d" % (hash_prefix, exc.code), file=sys.stderr)
        return None
    except Exception as exc:
        print("sponsorblock %s: %s" % (hash_prefix, exc), file=sys.stderr)
        return None
    if not isinstance(body, list):
        print("sponsorblock %s: unexpected body" % hash_prefix, file=sys.stderr)
        return None
    return body


def _counts(seg, duration):
    """Whether one segment from the API is something we skip."""
    if not isinstance(seg, dict) or seg.get("category") not in CATEGORIES:
        return False
    # The request asks for the default action type, skip, but a mute or full-video label skipped
    # would cut picture the viewer was meant to see - so check rather than trust.
    if seg.get("actionType", "skip") != "skip":
        return False
    # The live API already leaves hidden and shadow-hidden segments out (neither field appears in
    # a real response); checked anyway because the spec names them and it costs nothing.
    if seg.get("hidden") or seg.get("shadowHidden"):
        return False
    # Locked segments were reviewed by a moderator and always count. Otherwise the crowd must not
    # have voted it down; a fresh submission sits at 0 and counts.
    try:
        if not seg.get("locked") and float(seg.get("votes", 0)) < 0:
            return False
        submitted_for = float(seg.get("videoDuration") or 0)
    except (TypeError, ValueError):
        return False
    if submitted_for > 0 and abs(submitted_for - duration) > DURATION_SLACK_SECONDS:
        return False
    span = seg.get("segment")
    return (isinstance(span, list) and len(span) == 2
            and all(isinstance(t, (int, float)) and not isinstance(t, bool) for t in span))


def ranges_for(body, video_id, duration):
    """The raw [start, end] ranges in `body` that this clip should skip. Uncleaned."""
    found = []
    for entry in body:
        if not isinstance(entry, dict) or entry.get("videoID") != video_id:
            continue
        for seg in entry.get("segments") or []:
            if _counts(seg, duration):
                found.append(list(seg["segment"]))
    return found


def clean(ranges, duration):
    """Merge overlapping or touching ranges, clamp to [0, duration], sort, drop sub-second ones.

    Merged BEFORE the sub-second rule, so two slivers that meet become one range worth keeping.
    Rounded to a tenth of a second: finer than any seek lands, and it keeps the committed confs
    and channels.json readable and free of float noise that would churn the diff.
    """
    if duration <= 0:
        return []
    clamped = sorted([max(0.0, float(a)), min(float(duration), float(b))] for a, b in ranges)
    merged = []
    for start, end in clamped:
        if end <= start:
            continue
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    return [[round(a, 1), round(b, 1)] for a, b in merged if b - a >= MIN_RANGE_SECONDS]


def due(stream, now):
    """True when a stream has never been looked up, or its answer is over a month old."""
    return now - stream.get("skip_checked", 0) > RECHECK_SECONDS


def carry(old_streams, new_streams):
    """Copy lookups from yesterday's streams onto today's, matched by url.

    refresh_channels rebuilds a channel's list from search results, which know nothing of skips.
    Without this every refresh would throw away the answers for the clips it found again, and
    the next night would spend requests re-asking for them.
    """
    known = {s.get("url"): s for s in old_streams if "skip_checked" in s}
    for stream in new_streams:
        before = known.get(stream.get("url"))
        if before:
            stream["skip"] = before.get("skip", [])
            stream["skip_checked"] = before["skip_checked"]


def _gather(confs_dir, now, only=None):
    """Every due YouTube stream, grouped by prefix, plus the confs they live in.

    Returns ({prefix: [(path, stream, video id)]}, {path: conf}). Streams are the conf's own
    dicts, so answering one updates the conf in place.
    """
    groups, loaded = {}, {}
    for path in confs.youtube_paths(confs_dir):
        if only and confs.slug_for(path) not in only:
            continue
        try:
            conf = confs.load(path)
        except Exception as exc:
            print("skipping %s: %s" % (path, exc), file=sys.stderr)
            continue
        loaded[path] = conf
        for stream in (conf.get("station_conf") or {}).get("streams") or []:
            identifier = build_lineup.video_id(stream.get("url"))
            if identifier and due(stream, now):
                groups.setdefault(prefix(identifier), []).append((path, stream, identifier))
    return groups, loaded


def sweep(confs_dir, now, fetch_prefix=fetch, sleep=time.sleep, clock=time.monotonic,
          max_requests=MAX_REQUESTS, delay=DELAY_SECONDS, budget_seconds=BUDGET_SECONDS,
          only=None):
    """Look up the most overdue clips, write the answers into their confs. Never raises.

    Most overdue first - never-checked clips before stale ones, oldest check first, then by
    prefix so the order is the same on every machine. With the cap, a large backlog is worked
    through over successive nights, each picking up exactly where the last stopped, because the
    cursor is `skip_checked` in the committed confs rather than anything the runner remembers.
    """
    try:
        groups, loaded = _gather(confs_dir, now, only)
    except Exception as exc:
        print("sponsor skips: could not read the confs (%s); skipping" % exc, file=sys.stderr)
        return {"asked": 0, "answered": 0, "found": 0, "due": 0}

    order = sorted(groups, key=lambda p: (min(s.get("skip_checked", 0) for _, s, _ in groups[p]),
                                          p))
    stats = {"asked": 0, "answered": 0, "found": 0, "due": sum(map(len, groups.values()))}
    touched = set()
    failures = 0
    start = clock()
    try:
        for hash_prefix in order[:max(0, max_requests)]:
            if clock() - start > budget_seconds:
                print("time budget spent; the rest waits for the next run")
                break
            if stats["asked"]:
                sleep(delay)
            stats["asked"] += 1
            try:
                body = fetch_prefix(hash_prefix)
            except Exception as exc:
                print("sponsorblock %s: %s" % (hash_prefix, exc), file=sys.stderr)
                body = None
            if body is None:
                failures += 1
                if failures >= MAX_FAILURES_IN_A_ROW:
                    print("%d failures in a row; SponsorBlock looks unavailable, stopping"
                          % failures)
                    break
                continue
            failures = 0
            stats["answered"] += 1
            for path, stream, identifier in groups[hash_prefix]:
                duration = int(stream.get("duration") or 0)
                stream["skip"] = clean(ranges_for(body, identifier, duration), duration)
                stream["skip_checked"] = now
                stats["found"] += bool(stream["skip"])
                touched.add(path)
            if stats["asked"] % SAVE_EVERY == 0:
                _save(loaded, touched)
    finally:
        _save(loaded, touched)
    return stats


def _save(loaded, touched):
    for path in sorted(touched):
        try:
            confs.save(path, loaded[path])
        except Exception as exc:
            print("could not save %s: %s" % (path, exc), file=sys.stderr)
    touched.clear()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--confs", default=confs.default_dir())
    parser.add_argument("--only", action="append",
                        help="a channel slug; repeat for several")
    parser.add_argument("--max-requests", type=int, default=MAX_REQUESTS)
    args = parser.parse_args(argv)

    if args.only:
        # By-hand use only, so a typo may fail loudly: "0 clips due" for a slug that does not
        # exist reads exactly like a channel that is fully up to date.
        known = {confs.slug_for(p) for p in confs.youtube_paths(args.confs)}
        unknown = sorted(set(args.only) - known)
        if unknown:
            print("no channel called %s" % ", ".join(unknown), file=sys.stderr)
            return 2

    try:
        stats = sweep(args.confs, int(time.time()), max_requests=args.max_requests,
                      only=set(args.only) if args.only else None)
    except Exception as exc:
        # sweep() already contains its own failures; this is the belt to its braces. The dial
        # must publish whether or not SponsorBlock was any help tonight.
        print("sponsor skips failed: %s; leaving the confs as they were" % exc, file=sys.stderr)
        return 0
    print("sponsor skips: %d clips due, %d requests, %d answered, %d clips with something to skip"
          % (stats["due"], stats["asked"], stats["answered"], stats["found"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
