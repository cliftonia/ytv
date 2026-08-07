# Instant channel switching — phase report

All figures are milliseconds from keypress to first rendered frame, measured on the `fs42tv`
emulator across channels 3–13, which are all YouTube/Progressive. Live HLS is reported
separately because it behaves nothing like them.

## The headline

| | forward | reverse |
|---|---|---|
| Baseline, no preloading | 4,342 | 4,317 |
| Final, preloading two neighbours | **3,892** | **4,402** |

Forward improved about 10%. Reverse held level. The box this app replaces does roughly 200 ms,
and this app's own live HLS channels do 215 ms, so the goal is **not** met and the gap is a
factor of about twenty.

## The number everyone had been quoting was the wrong number

Before this phase the only latency figure in the project was 70 ms — keypress to the `fs42`
resolve log line. That line is emitted before ExoPlayer opens a socket, so it measured the
cheap part and flattered everything downstream. The app had no first-frame instrumentation at
all. Measured properly the baseline is 4,342 ms, roughly 66× larger.

Task 1 built the ruler before anything was optimised, and every later task re-read it. That
ordering is the single most useful decision in this phase.

## What the Media3 upgrade contributed: nothing, as intended

Media3 1.3.1 → 1.10.1 moved the median from 4,342 to 4,342-ish across samples spanning
3,698–8,010 ms. That is noise, and it is the correct outcome for a task whose purpose was to
absorb dependency risk without changing behaviour. Isolating it meant the preloader's effect
could not later be confused with the upgrade's.

1.3.1 did not have `DefaultPreloadManager` at all — it arrived in 1.4.0. The design spec named
an API the project could not have called. Verified by unpacking the AAR rather than assumed.

The upgrade forced `compileSdk` to 36. `targetSdk` stayed at 35 deliberately: `compileSdk`
only changes what the app compiles against, while `targetSdk` opts into new runtime platform
behaviour, which is exactly the observable change that task existed not to make.

## The finding that reframed the phase

Verifying that live HLS still worked after the upgrade produced the most useful number in the
whole effort:

| stream type | first frame |
|---|---|
| Live HLS (channels 103–112) | **215–500 ms** |
| YouTube Progressive (channels 3–13) | **3,600–4,600 ms** |

Same build, same device, same session. Seventeen times apart.

So the problem is not ExoPlayer, not Compose, not the app's plumbing, and not decode — all of
those would penalise HLS equally and do not. It is specific to the Progressive path: two
separate googlevideo connections merged for video and audio, seeking to a clock offset inside a
large MP4, against HLS fetching one manifest.

## What did not work, in as much detail as what did

**Caching resolved URLs saved nothing measurable.** The premise was that `urls.json` covers 46%
of the dial's clips, so most tunes pay a `GET /resolve` round trip. Instrumenting hit and miss
showed the resolve path barely fires — 1 miss across 8 tunes on channels 2–6, and zero across 4
tunes on channel 43, which has an 87% miss rate across its clip list. Coverage is not uniformly
random: the clips actually on air are the ones the daemon has already resolved. The 46% figure
badly overstates how often a tune pays for anything.

**Preloading generously made switching dramatically worse.**

| configuration | forward | reverse |
|---|---|---|
| no preloading | 4,342 | 4,317 |
| two neighbours, 5 s window | **6,982** | — |
| one neighbour, 5 s window | 3,753 | **5,789** |
| two neighbours, 2 s window | 3,892 | 4,402 |

The second row is a 61% regression, and the share of presses that rendered at all fell from
11-in-12 to 7-in-12. The design spec justified preloading over the box's mpv shadow pool on the
grounds that buffers are cheaper than decoders — 300–500 MB per mpv instance against a few MB
of buffer. **That is true for memory and false for bandwidth**, and bandwidth is the scarce
resource here. Each YouTube channel opens two googlevideo connections, so two buffering
neighbours put six concurrent fetches against the one stream being watched. Preloading has to
take throughput from somewhere and the only place available is the picture on screen.

**The best forward number of the phase was the wrong configuration.** Row three — one neighbour
at a 5 s window — produced 3,753 ms forward, the fastest measured. It got there by starving the
reverse slot, which degraded to 5,789 ms. That is precisely the trade the box's forward-only
shadow pool made and that the reverse slot exists to refuse. It was only visible because both
directions were measured; a forward-only benchmark would have shipped it.

## What did work

- **Refreshing preloaded positions.** Every channel runs on a wall clock, so a buffer preloaded
  at 1,200 s is the wrong bytes three minutes later when the clock wants 1,380 s — and because
  DNS, TLS and the connection stay warm, unrefreshed preloading decays quietly into connection
  warming rather than failing visibly. After four minutes idle the median was 3,722 ms against
  3,892 ms fresh: no decay.
- **A floor of two on the preload budget.** The emulator reports 1,978 MB, which fell just under
  a 2 GB threshold and produced a budget of one — a single forward-only slot, reproducing the
  box's worst bug on the device least able to afford it. The floor is now two, because the whole
  reason for reserving a reverse slot is that it matters most when the pool is small.
- **Memory was never the constraint.** TOTAL PSS 120 MB with the preloader running, 8% of the
  Chromecast with Google TV HD's 1.5 GB.

## The gap, and the lever most likely to close it

Researching what other people do turned up an API this phase should have used and did not.
Media3 has **two** preloading mechanisms:

- `DefaultPreloadManager` — built for dynamic UIs such as vertical feeds, where the next item is
  not known ahead of time. This is what Task 4 used, and it will fetch in parallel with playback.
- `ExoPlayer.setPreloadConfiguration(PreloadConfiguration(targetPreloadDurationUs))` — preloads
  the **next playlist item**, and per Google's documentation *"preloading is only started when no
  media is being loaded that is required for the ongoing playback, so preloading doesn't compete
  for bandwidth with the primary playback."*

That second sentence describes the exact regression measured above, already solved. A TV dial is
a playlist, not a feed — channel up is `seekToNextMediaItem()`. The feed solution was built for a
dial because the spec named `DefaultPreloadManager` and the check performed was whether that
class existed, not whether it fitted.

Two changes follow, in order of cost:

1. **Tune `DefaultLoadControl`.** 1.10.1 defaults to `bufferForPlaybackMs = 1000` — a flat
   one-second tax on every switch before playback may begin — and `minBufferMs = 50000`, so the
   playing channel loads hard for fifty seconds and starves everything else. Both are adjustable
   via `setBufferDurationsMsForStreaming`, cost nothing in memory, and the 50 s minimum is a
   plausible cause of the contention measured above.
2. **Move the dial onto a playlist** with `setPreloadConfiguration`, deleting `ChannelPreloader`
   rather than extending it.

## Caveats that cut against these numbers

- **Emulator only.** Software decode and emulated networking inflate everything here. Nothing in
  this phase has run on the TCL or the Chromecast. Measure on hardware before optimising further,
  because the target may be an artefact.
- **Roughly one tune in ten never renders** within the 8 s sampling window and is excluded from
  every median above. The medians therefore flatter the real experience.
- **The samples are entirely YouTube.** Blending in live HLS would drag every figure down hard
  and mean nothing.
