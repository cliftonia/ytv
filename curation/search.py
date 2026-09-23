#!/usr/bin/env python3
"""Asking YouTube for clips, and deciding what to ask it.

Two things live here: the yt-dlp call itself, and the ORDER of the queries a channel is filled
from. The order is what decides what a viewer actually finds on the dial, and until it was a
function it was an inline block in the middle of refresh() that nothing could reach without a
network.

`filters` says whether a clip belongs; this says where to go looking for one.
"""
import subprocess

import filters


# How many search results to sift per target clip. Three is enough for a broad query and not
# so many that a single channel's refresh takes minutes.
SEARCH_DEPTH = 3


def ytdlp(target, timeout=300):
    """Flat-list a search or playlist. Returns (id, duration, title) rows."""
    try:
        out = subprocess.run(
            ["yt-dlp", target, "--flat-playlist", "--no-warnings",
             "--print", "%(id)s\t%(duration)s\t%(title).90s"],
            capture_output=True, text=True, timeout=timeout)
    except Exception as exc:
        print("    [warn] %s: %s" % (target[:60], exc), flush=True)
        return []
    if out.returncode != 0 and not out.stdout.strip():
        # Distinguishes "this search legitimately found nothing" from "yt-dlp could not run".
        print("    [warn] yt-dlp exited %d: %s"
              % (out.returncode, (out.stderr or "").strip()[:120]), flush=True)
    rows = []
    for line in out.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        try:
            rows.append((parts[0], int(float(parts[1])), parts[2]))
        except ValueError:
            continue
    return rows


def excluded(title, exclude):
    """True when the title contains any of the channel's exclude terms, ignoring case.

    Substring rather than word match on purpose: the terms are phrases a curator wrote for one
    channel ("state of origin"), and "NRL2026" in a hashtag should go too.
    """
    lowered = title.lower()
    return any(term.lower() in lowered for term in exclude or ())


def collect(target, lo, hi, seen, keys, out, want, exclude=()):
    """Add clips from one search or playlist to `out`, up to `want`. Returns how many.

    `exclude` is the channel's own list of title terms that never belong on it (dial.EXCLUDE) -
    the per-channel counterpart to filters.usable(), for the cases where YouTube's idea of the
    query is a different sport. Checked before the dedupe sets, so a rejected clip does not use
    up the title key a clean upload of the same thing might need.
    """
    added = 0
    for vid, seconds, title in ytdlp(target):
        if len(out) >= want:
            break
        if not (lo <= seconds <= hi) or not filters.usable(title) or excluded(title, exclude):
            continue
        url = "https://www.youtube.com/watch?v=%s" % vid
        key = filters.title_key(title)
        if url in seen or key in keys:
            continue
        seen.add(url)
        keys.add(key)
        out.append({"url": url, "duration": seconds, "title": title.strip()})
        added += 1
    return added


def queries_for(query, slug, extra_queries, year):
    """The searches to run for one channel, in the order they should be tried.

    The list is walked until the channel is full, so earlier entries are a bias rather than a
    filter - a quiet channel that has nothing from this year still fills from the ones below.

    `year` is a parameter rather than read from the clock, so this stays a pure function and a
    test does not have to be rewritten every January.
    """
    attempts = [query]
    if not filters.is_period(slug):
        # This year first, then unqualified. A bias, not a filter: a quiet channel that has
        # nothing from this year still fills rather than emptying itself.
        attempts.insert(0, "%s %d" % (query, year))
    attempts.append("%s full" % query)
    # Named programmes and named people, which beat a genre word every time - see EXTRA_QUERIES
    # in dial.py. They come after the general query so the channel still leads with the broad
    # sweep, and they are what actually carries a thin channel to a hundred.
    attempts.extend(extra_queries or [])
    return attempts
