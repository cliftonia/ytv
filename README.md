# YTV

A retro cable-TV dial for Android TV. Around 130 channels of YouTube clips and live news, each
one playing at the offset the wall clock implies — tune to channel 34 at a quarter past eight and
you join whatever "should" be airing, partway through. Flip up, flip down, no menus, no browsing.

It runs on a television in a lounge room and on a Chromecast in a car, and it needs nothing else
running anywhere.

## How it works

```
  channels.json  ──  what is on each channel, rebuilt nightly by a workflow in this repo
        │
        ▼
  the app  ──  picks the clip the clock implies, resolves it on the device, plays it
```

Three things worth knowing:

**The lineup is a file, not a service.** `channels.json` lives in this repository and is rebuilt
every night by [`.github/workflows/lineup.yml`](.github/workflows/lineup.yml), which re-searches a
slice of the dial with yt-dlp and commits the result. The televisions fetch that one file. There
is no server, and a device that has been switched off for a month catches up by fetching it again.

**YouTube is resolved on the device.** Signed stream URLs expire within hours, so the lineup
cannot carry them — a clip that airs at nine in the evening has to be resolved at nine in the
evening. [`DeviceResolver`](app/src/main/java/com/cliftonia/fs42tv/resolver/DeviceResolver.kt) does
that with NewPipeExtractor. This replaced an endpoint running yt-dlp on a mini-PC at home, and it
turned out to be about five times faster, because the expensive part — deciphering YouTube's
signature JavaScript — happens once per process rather than once per clip.

**There are two video engines, chosen by the display.** ExoPlayer judders on panels that report a
single display mode, because there is no refresh rate for it to switch to and no way to pace
23.976fps content against 60Hz. libmpv's `video-sync=display-resample` handles it. So
[`PlayerEngine`](app/src/main/java/com/cliftonia/fs42tv/player/PlayerEngine.kt) counts the display
modes: one mode gets mpv, several get ExoPlayer with frame-rate switching. The television in the
lounge reports one; the Chromecast reports nineteen.

## Pluto TV: the second dial

Settings → **SOURCE** switches the whole dial between YOUTUBE (`channels.json`) and PLUTO TV
(`pluto.json`); OK relaunches into the other one, and each remembers its own last channel. The
Pluto dial is 219 themed English channels - no single-show loops, no news - hand-picked from
[iptv-org](https://github.com/iptv-org/iptv)'s UK and US playlists and grouped by genre.

`curation/pluto_lineup.json` is the allowlist; `curation/build_pluto.py` matches it against
iptv-org nightly for current stream urls. A channel Pluto retires drops off on its own; a new one
appears only when added to the allowlist.

## Sponsor skips

YouTube clips skip their sponsor reads, self-promotion and "like and subscribe" stretches, going
by [SponsorBlock](https://sponsor.ajay.app). Intros and outros are left alone. The nightly job
(`curation/sponsor.py`) asks by hash prefix, so SponsorBlock never learns which videos are on the
dial, and caches each answer in the confs as `skip` + `skip_checked` - re-checked after 30 days,
at most 1000 requests a night. `channels.json` carries `skip` on a stream only when there is
something to skip; `duration` stays the raw length. If SponsorBlock is unreachable nothing
fails - the clips just play in full.

Sponsor segment data comes from [SponsorBlock](https://sponsor.ajay.app) and is used under
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/); this project is
non-commercial.

## Time-of-day mixes

A handful of channels change with the clock: lighter comedy at breakfast and the uncensored
specials late, Saturday-morning cartoons in the morning, night markets on Food at prime time. The
day has four parts, local time: **breakfast** 06-12, **afternoon** 12-18, **prime** 18-23,
**late** 23-06. A channel opts in with a search per part in `PARTS` in `curation/dial.py` (any
part may be left out); `apply_dial.py` copies them into the conf and the nightly refresh fills
each part to half the channel's target, tagging those clips. `channels.json` carries
`parts: ["prime"]` on a tagged stream; untagged streams are the all-day pool, which is also what a
part with no search of its own plays. The publish gate holds every part to the same
no-collapse rule as a whole channel, and warns (without refusing) about channels with too few
clips under five minutes to top up the half-hour schedule. A new mix arrives with the channel's
next turn in the rotation - within a fortnight.

## Media channels: local files and streamed links

Beyond the YouTube dial sits a block of `file` channels (numbers 91-99) that schedule ordinary
video files the same clock-driven way — a movie channel is just a channel whose clips are files
instead of clips to resolve. Two shapes:

- **Local** (`91 Movies`, `92 Series`, any new ones you create): files on a home server,
  served read-only over plain HTTP by `tools/media-server/` (caddy, port 4244, LAN+tailnet
  only). Durations come from ffprobe; the scanner is `curation/scan_media.py`.
- **Remote** (`93 Cinema Stream`): the channel's streams are https URLs hosted elsewhere
  — nothing is stored or re-served locally. Listed in the conf as `remote_urls`
  (`"https://host/file.mp4|Title"` per entry), probed by range request, played by the client
  directly from the host. Seeds are gated to what the televisions can decode (h264/hevc, max
  3840×2160).

## Layout

| Path | What |
|---|---|
| `app/` | The Android app |
| `curation/confs/` | One file per channel: number, name, search query, current clips |
| `curation/refresh_channels.py` | Re-searches channels and writes their confs back |
| `curation/build_lineup.py` | Turns the confs into `channels.json` |
| `curation/sponsor.py` | Looks clips up in SponsorBlock and caches what to skip in the confs |
| `curation/pluto_lineup.json` | The hand-picked Pluto TV channels |
| `curation/build_pluto.py` | Matches that list against iptv-org and writes `pluto.json` |
| `curation/scan_media.py` | Rebuilds file-channel streams from the media folders + remote urls |
| `tools/media-server/` | Caddy static file service for the media folders + deploy script |
| `channels.json` | The published dial, committed nightly |
| `pluto.json` | The Pluto TV dial, committed nightly |
| `tools/deploy.sh` | Build and install to televisions on the local network |

## Building

```sh
./gradlew :app:testDebugUnitTest    # the suite, no network needed
./gradlew :app:assembleRelease      # unsigned unless YTV_KEYSTORE is set
```

Signing is read from the environment (`YTV_KEYSTORE`, `YTV_KEYSTORE_PASSWORD`, `YTV_KEY_ALIAS`).
Without it you get an unsigned APK, which is the right outcome for anyone who has cloned this and
just wants to build it.

## When the dial goes quiet

If every channel stops playing at once, YouTube has probably changed something and the extractor
needs updating rather than anything here being broken. That distinction is worth minutes, so
there is a test for it:

```sh
# delete the @Ignore first
./gradlew :app:testDebugUnitTest --tests '*DeviceResolverLiveCheck*'
```

It resolves real videos against the real YouTube. If it fails, bump the NewPipeExtractor version
in `app/build.gradle.kts`.

## Prior art

The scheduling idea and the original channel definitions come from
[FieldStation42](https://github.com/shane-mason/FieldStation42), which does the same thing with a
Raspberry Pi and a CRT. This is a rewrite for a device that is already plugged into the television.
