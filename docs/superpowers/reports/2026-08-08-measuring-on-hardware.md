# Measuring channel-switch latency on real hardware

A follow-up to `2026-08-07-instant-switching.md`. That phase optimised against an emulator. This
one moved to the TCL and found that most of what the emulator taught us was wrong — including,
eventually, that the measurement method itself was the thing most in need of fixing.

## The device

| | |
|---|---|
| Model | TCL Smart TV Pro (G08_4K_GB), Android 14 / SDK 34 |
| Panel | 3840×2160, **one mode only**: 60 Hz. HDR types 1–4 |
| UI layer | 1920×1080 at density 320 (upscaled to the panel) |
| RAM | **2.34 GB** |
| Cores | 4 |

Three things follow from that table.

**The TCL is not roomy.** The design spec assumed "roughly 4–6 preload slots on the TCL, 1–2 on
the Chromecast". At 2.34 GB the TV falls below `DeviceBudget`'s 3 GB threshold and gets 2 — the
same as the Chromecast. The four-slot branch is unreachable on both real targets.

**The 1080p UI layer does not cap video.** The app's UI and the video surface are composited
separately; a `SurfaceView` renders at panel resolution regardless. 4K video is available.

**Judder cannot be fixed by mode switching.** `supportedModes` has exactly one entry, 60 Hz, so
`preferredDisplayModeId` has nothing to switch to. 24 fps content will always be pulled down.
This independently confirms what the box already found.

## Hardware is roughly twice as fast as the emulator

| surface | median | render rate |
|---|---|---|
| Emulator, best it ever gave | 3,892 ms | ~61% |
| Emulator, final control | 9,971 ms | 50% |
| **TCL over Wi-Fi** | **1,779 ms** | **72%** |

## Preloading is worth keeping, and the emulator said otherwise

The single clearest result of either phase:

| | median | render rate |
|---|---|---|
| Preloading ON | 1,779 ms | **13/18 = 72%** |
| Preloading OFF | 2,472 ms | **5/18 = 27%** |

On the emulator this comparison was ambiguous — 4,342 against 3,892 ms, well inside the noise —
because that machine's bandwidth was so constrained that preloading *stole* throughput from
playback. Real hardware has headroom, and preloading converts most failures into successes.

Task 4's entire tuning exercise was therefore optimising around an artefact.

## Four sweeps could not rank the preload settings, and that is the finding

Two sweeps of four configurations each, on the TV, every one including a repeat of the first
configuration as a bracket.

**Unpinned** (live clock, so each run watches different clips):

| run | config | median | render |
|---|---|---|---|
| 1 | budget 2 / 2 s | 4,483 ms | 72% |
| 2 | budget 2 / 5 s | 6,617 ms | 72% |
| 3 | budget 4 / 5 s | 3,294 ms | 55% |
| 4 | budget 2 / 2 s ← same as 1 | 2,736 ms | 66% |

Runs 1 and 4 differ by 1,747 ms on identical settings. That band swallows three of the four
rows. Uninterpretable.

**Pinned** (clock frozen, so every run watches identical clips at identical offsets — verified
directly: channel 3 reported `clip 33 at 835.0s` in both run 1 and run 2, three minutes apart):

| run | config | render | median | max |
|---|---|---|---|---|
| 1 | budget 2 / 2 s | 61% | 3,480 ms | 10,714 |
| 2 | budget 2 / 5 s | 66% | 3,377 ms | 16,566 |
| 3 | budget 4 / 5 s | 77% | 3,341 ms | 8,337 |
| 4 | budget 2 / 2 s ← same as 1 | 94% | 2,766 ms | 3,812 |

Pinning worked as designed — the body of the distribution tightened from a 20× spread to about
2×. But look at the render rate down the column: **61 → 66 → 77 → 94**, monotonic with run
*order*, not with configuration. Max follows the same trend downward.

**Pinning removed content variance and introduced order variance.** Every run now requests
byte-for-byte identical URLs, so each one benefits from whatever the previous run warmed: DNS,
TCP, TLS session resumption, CDN edge caching of those exact byte ranges. By run 4 the path is
thoroughly primed.

The fix is in the experiment, not the app: randomise run order, or repeat each configuration
several times interleaved (A,B,A,B,A,B) so warming distributes across both arms.

**Conclusion: preload window and budget are second-order on this hardware.** Four sweeps across
two surfaces failed to separate them. The shipped setting — budget 2, 2 s window — has no
evidence against it and none for its alternatives.

## What survived every noise regime

Only two effects were ever large enough to measure reliably:

- **Live HLS ~215 ms against YouTube Progressive ~4,000 ms**, same build, same session. A 17×
  gap. The bottleneck is specific to the Progressive path: two googlevideo connections merged
  for video and audio, seeking into a large MP4, against HLS fetching one manifest.
- **Preloading on against off**, 72% versus 27% render rate.

Everything else this project has measured has been smaller than the noise of the surface it was
measured on.

## Four ways the apparatus lied

Worth recording, because each produced a plausible wrong answer rather than an obvious error.

1. **Emulator drift.** Identical committed code measured 3,892 ms one hour and 9,971 ms the
   next. Nearly caused a load-control change to be reverted on evidence that did not support it.
2. **Log buffer eviction.** The TV's 256 KiB ring is already full of its own system logging, so
   `logcat -d` after an 18-press run returned 2 samples and a `beginning of main` marker. That
   reads as "the presses did not land". Fixed by streaming the log for the run's duration.
3. **The uninstalled build.** `measure-switch.sh` deliberately does not install, which is right
   for sweeping flags and wrong the moment the code changes. The first pinned sweep ran against
   a build with no pin support and reported nothing amiss — the older extras kept working, so
   overrides *appeared* to be honoured. Caught by noticing clip offsets advancing exactly in step
   with wall time.
4. **Order-dependent warming**, above.

The common thread: **verify the instrument responded to the change, not merely that the change
compiled.** Every one of these was caught by looking at raw evidence rather than the summary.

## 4K: closer than it looked

- The panel is 4K60 with HDR, and the video surface is not capped by the 1080p UI layer.
- `GET /resolve` **already returns a `uhd` tier** — itag 313, 2160p VP9.
- `urls.json` publishes only `hd`, because `resolve_tiers` was never wired into `yt_cache_daemon`.
  Since most tunes hit the cache rather than `/resolve`, this is the binding constraint.
- The app hard-wires `preferUhd = false`. `Display.getMode()` reports the physical 3840×2160
  (unlike `DisplayMetrics`, which reports the 1080p override), so detection is a few lines.

**The tension worth deciding deliberately:** itag 313 is VP9; the dial is 92% H.264 (itag 137 and
299). ExoPlayer can only reuse a decoder across a switch when the codec matches, and the
ExoPlayer team's own figure puts decoder setup at up to 60% of startup latency. Going 4K means
mandatory codec teardown on every switch between 4K and the H.264 channels. The design spec
predicted 4K and instant switching would conflict, and named memory as the reason — the real
mechanism is codec uniformity.

## Where to go next

Not more parameter tuning. The two levers with mechanisms behind them, both from Media3's own
documentation:

1. **Playlist preloading** — `ExoPlayer.setPreloadConfiguration`. Google's docs state preloading
   "is only started when no media is being loaded that is required for the ongoing playback",
   which is precisely the contention that made `DefaultPreloadManager` regress by 61% on the
   emulator. A dial is a playlist; channel up is `seekToNextMediaItem()`.
2. **Decoder reuse**, which is best supported across playlist transitions — so the same change
   buys both.

And before either: **fix the experiment design**. Interleave or randomise run order, and repeat
each configuration, or the next set of numbers will mislead exactly as the last four did.
