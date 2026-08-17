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

## Layout

| Path | What |
|---|---|
| `app/` | The Android app |
| `curation/confs/` | One file per channel: number, name, search query, current clips |
| `curation/refresh_channels.py` | Re-searches channels and writes their confs back |
| `curation/build_lineup.py` | Turns the confs into `channels.json` |
| `channels.json` | The published dial, committed nightly |
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
