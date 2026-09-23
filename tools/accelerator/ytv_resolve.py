#!/usr/bin/env python3
"""Resolve YouTube ids to playable urls, ahead of anyone asking for them.

This is an ACCELERATOR, never a dependency. The televisions resolve perfectly well on their own
with NewPipeExtractor; they simply take a couple of seconds doing it, and a channel change should
not take a couple of seconds. So this does the same work in advance, and a set that cannot reach
it carries on exactly as before, a little slower. Nothing here is allowed to become load-bearing -
the last machine that was died, and took the whole dial with it.

The win is not the endpoint, it is the PRE-WARMING. The dial is deterministic: what is on air on
every channel at any instant is a pure function of the wall clock and channels.json, both of which
this can read. So it resolves what is on air across the whole dial before a viewer touches the
remote, and a channel change becomes a lookup - including a jump from the picker, which no amount
of on-device prefetching can help because the app cannot know where you are about to go.

  GET /health          instant, so the app can decide in milliseconds whether to bother
  GET /resolve?v=<id>  the tiers for one clip, warm if it was pre-resolved

Tailnet only. The televisions reach it over Tailscale and nothing else can.
"""
import io
import tempfile
import glob
import json
import os
import re
import subprocess
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import JSONResponse

LINEUP_URL = "https://raw.githubusercontent.com/cliftonia/ytv/main/channels.json"

# An id is eleven characters of a known alphabet. Anything else is not a request worth making a
# subprocess for, and this is the only untrusted input the service takes.
ID_PATTERN = r"^[A-Za-z0-9_-]{11}$"

# googlevideo signs urls for about six hours. A tier is retired early so a set is never handed
# something that dies while it is being played.
SAFETY_MARGIN_SECONDS = 300

# Heights, matching TierLadder on the device. Keep the two in step: a rung named there and not
# published here is simply skipped, which is a silent loss of quality.
TIERS = (("uhd", 1081, 2160), ("hd", 721, 1080), ("sd", 0, 720))

# Four at a time. yt-dlp is network-bound so concurrency helps, and more than a handful is what
# gets an address throttled - which would make this slower than the televisions doing it alone.
WORKERS = 4

app = FastAPI(title="ytv-resolve")

_cache = {}          # video id -> {"tiers": {...}, "resolved_at": epoch}
_cache_lock = threading.Lock()
_dial = {"channels": [], "fetched_at": 0}
_stats = {"hits": 0, "misses": 0, "resolved": 0, "failed": 0}

# Whether extraction actually works right now, re-checked periodically.
#
# This exists because the copy of yt-dlp on this machine sat broken for six months and nothing
# said so: YouTube had changed, extraction returned four storyboards and no streams, and the only
# reason it surfaced was somebody happening to build a service on top of it. A tool that silently
# stops working is worse than one that is absent, because absence is obvious.
_extractor = {"ok": None, "checked_at": 0, "detail": "not checked yet", "version": "?"}

# Big Buck Bunny, from Blender: Creative Commons, no age gate, no region block, every rendition
# from 144p to 4K. If extraction works at all it works on this, so a failure here is the tool
# rather than the clip.
CANARY_ID = "aqz-KE-bpKQ"

# What language each clip declares, as YouTube reports it.
#
# This is the only authoritative answer to "is this in English". Four separate title-based
# detectors were tried first - script ranges, foreign stopwords, two-tier markers, absence of
# English - and each failed differently, because a title is a name rather than a sentence:
# "Sarah McLachlan: Tiny Desk Concert" contains no English function word, and "Kung Fu Chaos"
# contains a Filipino one. The declared language cannot be fooled by a loanword or a surname.
#
# It is not complete. Measured over a real search, 8 clips in 14 declared a language and 6
# declared nothing, so this REPLACES nothing - it catches what titles cannot, and the title rules
# still handle everything that stays silent.
_languages = {}
_languages_lock = threading.Lock()

# On disk, because a full survey of nine thousand clips takes hours and this service restarts
# whenever it is updated. Held only in memory, every restart threw the work away and the count
# began again from nothing - which is why the sweep never accumulated enough to be useful.
LANGUAGES_FILE = os.path.expanduser("~/.ytv-languages.json")

# What counts as English. YouTube writes regional tags, so the prefix is what matters.
ENGLISH_PREFIX = "en"


def _play_point(durations, now):
    """Which clip is on air, and how far into it. The same arithmetic the televisions run.

    Kept identical on purpose: if this drifted from ClockRotation the server would pre-warm the
    wrong clip on every channel and be worse than useless, because every lookup would miss.
    """
    cycle = sum(max(d, 0) for d in durations)
    if cycle <= 0:
        return None
    elapsed = now % cycle
    for index, duration in enumerate(durations):
        if duration <= 0:
            continue
        if elapsed < duration:
            return index, elapsed
        elapsed -= duration
    return None


def _load_languages():
    try:
        with io.open(LANGUAGES_FILE, encoding="utf-8") as handle:
            loaded = json.load(handle)
    except Exception:
        return
    with _languages_lock:
        _languages.update(loaded)
    print("[lang] restored %d from disk" % len(loaded), flush=True)


def _save_languages():
    """Write the whole map out, atomically.

    A temporary file and a rename, because this is written from a worker thread while the survey
    keeps going: a reader arriving mid-write would otherwise find a truncated file and lose
    everything, which is the failure this persistence exists to prevent.
    """
    with _languages_lock:
        snapshot = dict(_languages)
    try:
        partial = LANGUAGES_FILE + ".part"
        with io.open(partial, "w", encoding="utf-8") as handle:
            json.dump(snapshot, handle)
        os.replace(partial, LANGUAGES_FILE)
    except Exception as exc:
        print("[warn] could not save languages: %s" % exc, flush=True)


def _remember_language(video_id, language):
    if language:
        with _languages_lock:
            _languages[video_id] = language


def _resolve(video_id):
    """Ask yt-dlp for every progressive rendition, and sort them into tiers."""
    # --write-pages keeps YouTube's raw player reply beside the json, in a scratch directory that
    # is gone again before this returns. It is the only place YouTube's loudness figure appears -
    # yt-dlp reads the reply but does not carry the figure into its json - and the televisions
    # need it to play every clip at the same level. Costs a file write, no extra request.
    with tempfile.TemporaryDirectory(prefix="ytv-pages-") as pages:
        try:
            out = subprocess.run(
                ["yt-dlp", "https://www.youtube.com/watch?v=%s" % video_id,
                 "--dump-single-json", "--no-warnings", "--no-playlist", "--write-pages"],
                capture_output=True, text=True, timeout=90, cwd=pages)
        except Exception as exc:
            return None, "yt-dlp failed: %s" % exc
        loudness = _loudness(pages)
    if out.returncode != 0:
        return None, (out.stderr or "").strip()[:200]
    try:
        info = json.loads(out.stdout)
    except ValueError as exc:
        return None, "unreadable json: %s" % exc

    _remember_language(video_id, info.get("language"))

    formats = info.get("formats") or []
    video = [f for f in formats
             if f.get("vcodec") not in (None, "none") and f.get("acodec") == "none"
             and f.get("protocol") in ("https", "http")]
    audio = [f for f in formats
             if f.get("acodec") not in (None, "none") and f.get("vcodec") == "none"
             and f.get("protocol") in ("https", "http")]
    if not audio:
        return None, "no progressive audio"

    # ENGLISH first, then bitrate. YouTube ships several audio tracks on one video now - an
    # English original with German, Spanish and Hindi dubs beside it - and sorting on bitrate
    # alone took whichever dub happened to be fattest. A creator who is always in English would
    # arrive in German with nothing about the dial having changed, which is what was reported from
    # the sofa and what no amount of filtering the lineup could ever have fixed.
    #
    # An untagged track counts as English: a video with one audio track does not label it, and
    # that is most videos. Only an explicit claim of another language demotes a track.
    #
    # `descriptive` is refused outright - it is the audio-description track for blind viewers, a
    # narrator talking over the action, and unmistakably wrong on a television nobody asked on.
    def english(fmt):
        tag = (fmt.get("language") or "").lower()
        return not tag or tag == "en" or tag.startswith("en-") or tag.startswith("en_")

    def descriptive(fmt):
        note = ((fmt.get("format_note") or "") + " " + (fmt.get("language") or "")).lower()
        return "descript" in note

    audio = [f for f in audio if not descriptive(f)]
    if not audio:
        return None, "only a descriptive audio track"
    audio.sort(key=lambda f: (1 if english(f) else 0,
                              (f.get("abr") or 0),
                              1 if f.get("ext") == "m4a" else 0))
    best_audio = audio[-1]

    caption = _english_caption(info)

    tiers = {}
    for name, low, high in TIERS:
        band = [f for f in video if low <= (f.get("height") or 0) <= high]
        if not band:
            continue
        # Highest in the band, then h264 ahead of an equal-height vp9 - every device has decoded
        # h264 in hardware for fifteen years, and preferring it costs nothing.
        band.sort(key=lambda f: ((f.get("height") or 0),
                                 1 if str(f.get("vcodec", "")).startswith("avc") else 0))
        chosen = band[-1]
        tiers[name] = {
            "video": chosen["url"],
            "audio": best_audio["url"],
            "expires": _expiry(chosen["url"], best_audio["url"]),
        }
    if not tiers:
        return None, "no progressive video"
    if caption:
        tiers["caption"] = caption
    if loudness is not None:
        tiers["loudness_db"] = loudness
    return tiers, None


def _loudness(pages):
    """YouTube's loudness for the clip, in dB above its -14 LKFS reference, or None.

    Read from the player reply yt-dlp saved. `playerConfig.audioConfig.loudnessDb` when present;
    otherwise derived from `perceptualLoudnessDb` against the target, which agree exactly where
    both appear. The television turns it into a gain of min(1, 10^(-dB/20)) - quieter clips are
    never boosted - so a wrong or missing figure costs at most the clip playing unlevelled.
    """
    for path in glob.glob(os.path.join(pages, "*player*.dump")):
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        found = re.search(r'"audioConfig"\s*:\s*\{[^{}]*?"loudnessDb"\s*:\s*(-?\d+(?:\.\d+)?)', text)
        if found:
            return round(float(found.group(1)), 2)
        perceptual = re.search(r'"perceptualLoudnessDb"\s*:\s*(-?\d+(?:\.\d+)?)', text)
        target = re.search(r'"loudnessTargetLkfs"\s*:\s*(-?\d+(?:\.\d+)?)', text)
        if perceptual:
            return round(float(perceptual.group(1)) - float(target.group(1) if target else -14), 2)
    return None


def _english_caption(info):
    """An English subtitle track, asked for as WebVTT.

    The televisions cannot do this for themselves once this server is reachable: the accelerator
    answers every resolve, so the on-device caption picking never runs. Captions were switched on
    in front of a television and nothing appeared for exactly that reason - the fast path had no
    field to carry them in.

    Manual before automatic, but automatic is not a last resort: measured over sixteen clips on
    the channels that need captions, fourteen had English and only four were hand-authored.

    yt-dlp hands these back as ttml, which mpv cannot read at all. The `fmt` parameter is
    REPLACED rather than appended, because a second one is ignored.
    """
    manual = (info.get("subtitles") or {})
    auto = (info.get("automatic_captions") or {})
    for source in (manual, auto):
        for tag, tracks in source.items():
            if not tag.lower().startswith("en"):
                continue
            for track in tracks or []:
                url = track.get("url")
                if not url:
                    continue
                return re.sub(r"([?&])fmt=[^&]*", r"\1fmt=vtt", url) if "fmt=" in url \
                    else url + ("&" if "?" in url else "?") + "fmt=vtt"
    return None


EXPIRE = re.compile(r"[?&]expire=(\d+)")


def _expiry(video_url, audio_url):
    """The earlier of the two stated expiries - the pair is useless the moment either dies."""
    stated = [int(m.group(1)) for m in
              (EXPIRE.search(video_url), EXPIRE.search(audio_url)) if m]
    return min(stated) if stated else int(time.time()) + 3600


def _fresh(entry, now):
    # `caption` sits alongside the tiers and is a plain string, so it is skipped here - it has no
    # expiry of its own and treating it as a tier would raise on the first lookup.
    tiers = [t for k, t in entry["tiers"].items() if isinstance(t, dict)]
    return bool(tiers) and min(t["expires"] for t in tiers) - SAFETY_MARGIN_SECONDS > now


def _remember(video_id, tiers):
    with _cache_lock:
        _cache[video_id] = {"tiers": tiers, "resolved_at": int(time.time())}


def _lookup(video_id, now):
    with _cache_lock:
        entry = _cache.get(video_id)
        if entry and not _fresh(entry, now):
            # Dropped rather than served: a url past its expiry is a channel that plays nothing,
            # which is worse than the two seconds of resolving it again.
            del _cache[video_id]
            entry = None
    return entry


def _check_extractor():
    """Prove extraction still works, on a clip that cannot itself be the problem."""
    tiers, error = _resolve(CANARY_ID)
    try:
        version = subprocess.run(["yt-dlp", "--version"], capture_output=True,
                                 text=True, timeout=30).stdout.strip()
    except Exception:
        version = "?"
    _extractor.update(
        ok=bool(tiers), checked_at=int(time.time()), version=version,
        detail="ok" if tiers else (error or "no tiers"))
    if not tiers:
        print("[ALERT] extraction is broken: %s (yt-dlp %s)" % (error, version), flush=True)
    else:
        print("[canary] extraction ok, yt-dlp %s" % version, flush=True)


def _refresh_dial():
    """Re-read the published lineup. It is rebuilt nightly, so this is checked hourly."""
    try:
        with urllib.request.urlopen(LINEUP_URL, timeout=30) as response:
            dial = json.loads(response.read().decode("utf-8"))
    except Exception as exc:
        print("[warn] could not fetch the lineup: %s" % exc, flush=True)
        return
    _dial["channels"] = [c for c in dial.get("channels", []) if c.get("kind") == "youtube"]
    _dial["fetched_at"] = int(time.time())
    print("[dial] %d youtube channels" % len(_dial["channels"]), flush=True)


def _wanted_now(now):
    """Every id that is on air right now, or about to be, across the whole dial.

    Current and next, because a clip rolling over is the other moment a set needs a url in a
    hurry and the one it cannot anticipate itself.
    """
    wanted = []
    for channel in _dial["channels"]:
        streams = channel.get("streams") or []
        point = _play_point([s["duration"] for s in streams], now)
        if not point:
            continue
        index, _ = point
        for step in (0, 1):
            stream = streams[(index + step) % len(streams)]
            if stream.get("id"):
                wanted.append(stream["id"])
    return wanted


def _warm_forever():
    """Keep the dial resolved, sweeping continuously and cheaply.

    Only ids that are missing or near expiry are resolved, so the steady state is nearly free:
    a clip stays on air for minutes and its url lasts hours, so most sweeps find nothing to do.
    """
    _refresh_dial()
    _check_extractor()
    last_dial_fetch = time.time()
    last_canary = time.time()
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        while True:
            try:
                if time.time() - last_dial_fetch > 3600:
                    _refresh_dial()
                    last_dial_fetch = time.time()
                if time.time() - last_canary > 3600:
                    _check_extractor()
                    last_canary = time.time()
                now = int(time.time())
                todo = [i for i in dict.fromkeys(_wanted_now(now)) if not _lookup(i, now)]
                if todo:
                    print("[warm] %d to resolve, %d cached" % (len(todo), len(_cache)), flush=True)
                    for video_id, (tiers, error) in zip(todo, pool.map(_resolve, todo)):
                        if tiers:
                            _remember(video_id, tiers)
                            _stats["resolved"] += 1
                        else:
                            _stats["failed"] += 1
                            print("[warn] %s: %s" % (video_id, error), flush=True)
                # Sweeping constantly would be a way to get throttled for no gain; a clip is on
                # air for minutes.
                time.sleep(30)
            except Exception as exc:
                print("[warn] sweep failed: %s" % exc, flush=True)
                time.sleep(60)


@app.get("/health")
def health():
    """Instant, and the only thing the app calls to decide whether this is worth using.

    It must never do work: the whole point is that a set on a hotspot in a car discovers in
    milliseconds that this is unreachable and gets on with resolving things itself.
    """
    # `ok` is about whether this service is worth ASKING, which is not the same as whether the
    # process is running. A service whose extractor is broken should be skipped by the
    # televisions in favour of resolving on their own, and saying so here is how they know.
    return {
        "ok": _extractor["ok"] is not False,
        "cached": len(_cache),
        "channels": len(_dial["channels"]),
        "extractor": {k: _extractor[k] for k in ("ok", "version", "detail", "checked_at")},
        **_stats,
    }


@app.get("/languages")
def languages():
    """Every clip whose language this has seen, and which of them are not English.

    Served rather than acted on: this machine is an accelerator and must never be something the
    dial depends on, so it reports what it has learned and the nightly curation decides what to
    do about it.
    """
    with _languages_lock:
        seen = dict(_languages)
    foreign = {k: v for k, v in seen.items() if not v.lower().startswith(ENGLISH_PREFIX)}
    return {"seen": len(seen), "foreign": foreign}


@app.get("/resolve")
def resolve(v: str = Query(..., pattern=ID_PATTERN)):
    now = int(time.time())
    entry = _lookup(v, now)
    if entry:
        _stats["hits"] += 1
        return JSONResponse({"id": v, "warm": True, **entry["tiers"]})

    # A miss is still worth serving: this machine resolving on demand is no slower than the
    # television doing it, and the answer warms the cache for the next set to ask.
    _stats["misses"] += 1
    tiers, error = _resolve(v)
    if not tiers:
        raise HTTPException(status_code=404, detail=error or "could not resolve")
    _remember(v, tiers)
    return JSONResponse({"id": v, "warm": False, **tiers})


def _survey_forever():
    """Walk every clip on the dial, slowly, recording what language each declares.

    The warming loop only ever sees what is on air, which would take the whole rotation to cover
    the dial. This walks all of it - about nine thousand clips - at a rate that finishes in an
    hour or so and then rests. It exists purely to answer the language question; the resolved
    urls it produces are a side effect and are cached like any other.
    """
    # Let the warm loop take the dial first: what is on air matters more than what is being
    # surveyed, and both share the same yt-dlp concurrency budget.
    _load_languages()
    time.sleep(300)
    with ThreadPoolExecutor(max_workers=2) as pool:
        while True:
            try:
                ids = [s["id"] for c in _dial["channels"] for s in (c.get("streams") or [])
                       if s.get("id")]
                with _languages_lock:
                    todo = [i for i in ids if i not in _languages]
                if not todo:
                    # Nothing new until the lineup changes, which is nightly.
                    time.sleep(3600)
                    continue
                print("[survey] %d clips with no language yet" % len(todo), flush=True)
                for _ in pool.map(_resolve, todo[:400]):
                    pass
                # After each batch rather than at the end: a batch is minutes of work and the
                # service can be restarted at any moment.
                _save_languages()
                # A pause between batches, because being throttled would stop the warming loop
                # too - and that one the televisions actually wait on.
                time.sleep(120)
            except Exception as exc:
                print("[warn] survey failed: %s" % exc, flush=True)
                time.sleep(300)


threading.Thread(target=_warm_forever, daemon=True).start()
threading.Thread(target=_survey_forever, daemon=True).start()
