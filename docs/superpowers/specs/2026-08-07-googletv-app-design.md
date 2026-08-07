# Google TV app — design

**Date:** 2026-08-07
**Status:** approved design, ready for implementation planning

## Goal

Replace the FieldStation42 mini PC (ThinkCentre M920q) with an Android TV app, so
the dial runs on hardware already owned at both places it is watched.

**Targets, both sideloaded:**

| device | role | display | RAM |
|---|---|---|---|
| TCL TV, built-in Google TV | home | 4K | ample |
| Chromecast with Google TV HD | car | 1080p | 1.5 GB |

One APK, adapting at runtime. Two builds would mean two sideload flows and two
things to drift apart; Android exposes everything needed to decide at runtime.

## What stays, what goes

The M920q is retired. The **CachyOS home server** (192.168.4.58 on the LAN,
100.74.3.68 on Tailscale) becomes the curator, running the Python that already
exists and works:

- `freshen.py` — nightly content rotation, ~200 clips a night
- `set_recency.py` — the current-vs-evergreen policy and the title-year guard
- `yt_cache_daemon.py` — pre-resolves YouTube watch URLs to direct CDN URLs

One new server-side piece: a **publisher** exposing two static files over HTTP,
reachable on the LAN and over Tailscale. nginx serving a directory is enough; no
new service logic.

One server-side change: `yt_cache_daemon` currently resolves only **current +
next** per channel. An app that can jump anywhere on the dial needs broader
coverage, so its wanted-set widens.

## Architecture

```
CachyOS server
  freshen.py / set_recency.py  ->  channels.json   (~500 KB, the dial)
  yt_cache_daemon.py           ->  urls.json       (pre-resolved CDN URLs)
            |
            |  HTTP over LAN, or Tailscale from the car
            v
  Android TV app
     - computes clock rotation locally
     - plays direct googlevideo URLs via Media3
     - on a cache miss, asks the server: GET /resolve?v=<id>
            |
            v
  video streams CDN -> device   (the home upload is NOT in the path)
```

**The app never extracts anything.** It only ever plays URLs. All YouTube
extraction — including poToken generation — stays on the server, where
`pip install -U yt-dlp` fixes breakage in seconds. Putting it in the APK would
mean a rebuild-and-sideload cycle across two devices every time YouTube changes
something.

This is why on-device extraction was rejected outright. poTokens are
platform-bound and mostly per-video, so generating them on Android genuinely
requires running BotGuard in a hidden WebView — a heavy dependency, and heaviest
on the 1.5 GB Chromecast where it would compete with the video decoder. A single
HTTP call to a machine that already has a working yt-dlp and deno is simpler in
every respect.

The server therefore exposes **two** things: the static files below, and one
small endpoint `GET /resolve?v=<video_id>` returning the same
`{video_url, audio_url, expires}` shape for a clip the cache missed.

### App modules

Deliberately narrow boundaries, so each can be understood and tested alone.

| module | responsibility | depends on |
|---|---|---|
| `device` | capability detection: display modes, refresh rates, RAM tier | nothing |
| `schedule` | **pure Kotlin.** `playPointFor(channel, now) -> (index, offset)` | nothing |
| `sync` | fetch and cache the two JSON files; track staleness | network, disk |
| `resolver` | stream URL -> playable source. Cache hit -> direct; miss -> ask the server | `sync`, network |
| `player` | Media3 wrapper: one ExoPlayer plus a preload manager | `resolver` |
| `ui` | Compose for TV: video surface, banner, guide, channel entry | `player`, `schedule` |

`schedule` and `device` having **zero dependencies** is the most important
decision in this design. `schedule` is the heart of the product — the clock
rotation that makes every channel feel live — and as a pure function of
(playlist, wall clock) it is fully testable on a laptop with no device, no
network and no YouTube. On the box the same logic sits inside `liquid_manager.py`
entangled with `StationManager` and the player, and could only be verified by
restarting a service and watching a TV.

## Data contract

Unchanged from the existing conf format, which is already minimal:

```json
{
  "network_name": "AFL",
  "channel_number": 9,
  "network_type": "streaming",
  "stream_rotation": "clock",
  "streams": [
    {"url": "https://www.youtube.com/watch?v=...", "duration": 490,
     "title": "Essendon v GWS Giants Highlights | Round 19, 2026 | AFL"}
  ]
}
```

`channels.json` is an array of these. YouTube and live channels are told apart by
the URL (`youtube.com/watch` versus `.m3u8`); live channels carry a single entry
and no `stream_rotation`.

`urls.json` maps watch URL -> `{video_url, audio_url, expires}`, exactly the shape
`yt_cache.json` already has.

## Playback core

**Clock rotation.** A direct port of `_build_stream_point`: `elapsed = now % cycle`,
walk the stream list accumulating durations, return `(index, offset)`. About
fifteen lines.

**Switching.** Media3's `DefaultPreloadManager` buffers sources **without
allocating a decoder**, which is fundamentally cheaper than the box's mpv pool
where each shadow held a decoder and 300-500 MB. One ExoPlayer plus N preloaded
neighbours. The budget comes from `device`: roughly 4-6 on the TCL, 1-2 on the
Chromecast.

**One slot is reserved for the channel behind.** Priming purely forwards made
every reversal a cold open on the box — measured at 5,359 ms, dropping to 350 ms
once a reverse slot existed. Insert it at priority 2 so it survives when the pool
is small, which matters far more at a budget of 2 than at 8.

**Instant switching is a goal, not a nice-to-have.** The box achieves ~200 ms by
swapping windows between already-decoded streams; preloading buffers rather than
decoders will start slower, likely a few hundred milliseconds to ~1 s. That is
the starting point, not the target. Get the app working first, then close the
gap — the levers are preload budget, preload position freshness, and keeping a
second `ExoPlayer` alive on the TCL where memory allows, which would reproduce
the box's technique directly.

**Start position, not seek.** Use `setMediaSource(source, startPositionMs)`. The
box's worst bug this week was calling `mpv.play()` then seeking immediately —
`play()` is asynchronous, the seek arrived before the stream existed and was
silently discarded, so every pre-tuned instance sat at 00:00 and every channel
change started its clip from the beginning. Media3 makes the start position part
of the load, so there is no window in which to seek the wrong thing.

**Preload positions must be refreshed.** A preloaded buffer only helps at the
position you actually start from. Preload channel 64 at offset 1200 s, tune three
minutes later, and the clock wants 1380 s — the buffered bytes are wrong, though
DNS, TLS and the connection stay warm. Refresh preload positions periodically
rather than discovering later that preloading "doesn't seem to help".

**Adaptive display.** `Display.getSupportedModes()` plus `preferredDisplayModeId`
lets the app switch the panel to match content: 24 Hz for 24 fps, 60 Hz for
60 fps. This solves at the root the judder the box works around, so the
`fps<=30` cap in `YTDL_QUALITY_LADDER` does **not** come across.

**Two quality tiers: 4K for the TCL, 1080p for the Chromecast.** `urls.json`
carries both renditions per clip and the app picks by device capability.

This is much cheaper than it sounds: the expensive part of extraction is fetching
the page and passing the JS challenge, not selecting a format. A single yt-dlp
invocation can print URLs for several formats at once, so resolving both tiers
costs one extraction, not two. The only real cost is a slightly larger JSON.

4K is worth pursuing rather than deferring — if the TCL plays 4K flawlessly, the
M920q is genuinely free for another use, which is the whole point of retiring it.
Format selection keeps the existing `[vcodec!*=av01]` exclusion only where the
target device lacks AV1 decode; the Google TV Streamer class and newer TVs handle
AV1, so `device` decides.

## Degradation ladder

1. **Pre-resolved URL dead.** Roughly 8% return 403 inside their stated expiry.
   Ask the server to re-resolve that clip. The app must **not** evict the shared
   cache on failure — doing exactly that from 8 concurrent threads was a bug
   introduced on the box this week, turning a whole-file rewrite into a
   lost-update race.
2. **URLs expired** (~6 h signed lifetime). Re-sync; use `/resolve` meanwhile.
3. **Server unreachable.** Play whatever cached URLs are still within expiry, and
   skip clips that need resolving. Content ages; the dial keeps working for a few
   hours. This is the one genuine cost of moving extraction off-device, and it is
   accepted deliberately — see the reasoning under Architecture.
4. **Clip genuinely unplayable.** Skip to the next entry. Show the "PLEASE STAND
   BY" card only after a real timeout, **never** while the stream is still
   loading — a 3 s watchdog against a ~7 s cold open put that card over healthy
   content on the box.

**There is no offline mode, by design.** The car device needs internet to stream
YouTube at all, so if it can play video it can reach the server over Tailscale.
Designing for "works with no network" is incoherent: no network means no content
regardless of caching.

## Testing

- **`schedule`** — pure unit tests on a laptop. Port the cases `test_units.py`
  already covers: wraparound, single clip, zero duration, empty playlist.
- **`device`** — unit tests against synthetic display-mode lists, so both the 4K
  and 1080p paths are exercised without owning both devices.
- **`resolver`** — cache hit / miss / expired / dead-URL paths against a fake cache.
- **On-device instrumented tests** — real switch latency and playback.

**A debug overlay from day one**, showing real playback position against the clock
position (drift) and dropped-frame count. This is `measure_drift.py` as an in-app
instrument. The most expensive mistake on the box this week was trusting a status
socket that reported 140 ms for changes that visibly took ten seconds; the truth
came only from the player's own position and the framebuffer. Build the honest
instrument first.

## Scope

**In, for v1:** channel surfing with the banner, clock rotation, last-channel
recall, live HLS channels, the guide, and some form of direct channel entry.

**Jumping to a channel is a LIST, not a number pad.** Revised 7 Aug 2026 after
looking at how ytch.tv solves the same problem.

The original plan here was an on-screen numeric overlay driven by the D-pad. That
was wrong. A numeric keypad is designed for a device with ten discrete keys; on a
D-pad it costs several journeys per digit, and Google TV remotes have no digits at
all. ytch.tv instead opens a **scrollable channel list** — `CHANNEL 04
ARCHITECTURE & INTERIORS`, one row per channel, the current one highlighted — and
that is exactly the right shape for a directional pad: up and down move, OK
selects, BACK dismisses.

So: one **channel list overlay**, opened by OK or the guide key, listing number and
name, scrolled with up/down, committed with OK. The list IS the direct-entry
mechanism; there is no separate numeric path to build or maintain.

Hardware number keys (`KEYCODE_0`-`KEYCODE_9`) are still accepted where a remote
sends them — some TCL remotes have a pad, and it is how the box's Flirc setup works
today — but they are a bonus input into the same "tune to channel N" path, not a
mechanism anything depends on.

This matters because on a 111-channel dial, surfing alone can mean up to 55 presses
to cross the dial.

Two smaller things worth taking from the same source: the channel indicator is a
persistent corner overlay (`CH 04`) rather than only a transient banner, which suits
a simulator where knowing the channel matters more than knowing the programme; and
the on-screen elements it exposes as toggles — channel name, captions, video title —
are a reasonable v2 settings surface rather than decisions to hard-code now.

**Out of scope for v1:** 4K renditions, on-device content curation, WeatherStar,
Radio42 local media, the web remote.

### Phasing

This is larger than one sitting, so implementation should start with a walking
skeleton and grow:

1. **Skeleton** — sync the two files, play one hard-coded channel at the correct
   clock offset. Proves the data contract, the resolver and Media3 together.
2. **Surfing** — channel up/down, banner, last-channel recall, the preload manager
   and the reverse slot.
3. **Breadth** — live HLS channels, adaptive display and frame-rate matching.
4. **Navigation** — the guide and direct channel entry.

## Prior art considered

- **No FieldStation42 Android TV port exists.** It is Linux and Raspberry Pi only.
- **Tunarr / ErsatzTV / dizqueTV** turn a media library into live channels with an
  XMLTV guide consumable by any IPTV client, which would mean writing no app at
  all. Rejected: they assume **local media files** and transcode them with FFmpeg,
  whereas this content is YouTube URLs resolved on demand. Using them would mean
  downloading the library locally again — the storage-hungry approach abandoned
  when `skip_download_cache` was turned on. (ErsatzTV is also archived; Tunarr is
  the maintained fork.)
- **Server-generated IPTV** (the same pattern, built ourselves) was rejected
  separately: every channel change would spin up an FFmpeg session server-side,
  and all car video would route through the home 50 Mbps upload rather than
  streaming from Google's CDN.
- **Nintendo Switch + L4T Ubuntu** was considered as an alternative host. It runs
  the existing Python stack unchanged and has hardware VP9 decode, but 4 GB RAM
  caps the shadow pool and the A57 cores make extraction slower. Viable as a
  portable second node, not as a replacement.

## Risks

- **Server reachability is now a hard dependency** for anything not already in the
  cache. Mitigated by Tailscale and by ~6 h of valid pre-resolved URLs, but if the
  server is genuinely down the dial degrades within a few hours. This is the price
  of keeping extraction off-device, and it buys away the WebView, NewPipeExtractor
  and all poToken handling.
- **Preload effectiveness is unproven** on a 1.5 GB device. If the budget is
  genuinely 1, switching in the car will feel closer to a cold open than to the
  box. Measure early — it decides whether the car target needs a different
  technique from the TCL.
- **4K preloading is memory-hungry.** Buffering several 4K sources on the TCL may
  force a smaller preload budget than 1080p would. The two goals — 4K picture and
  instant switching — pull against each other on the same device, and which wins
  should be measured rather than assumed.
- **Sideloading Google TV** is supported but Google has been tightening it. Worth
  confirming on the TCL before building.
