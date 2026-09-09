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

## Media channels: local files, streamed links, and torrent ingest

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

### The ingest CLI

```sh
python3 curation/ingest.py
```

Lawful sources only: it downloads what you point it at, and what lands plays from disk,
permanently, like everything else on these channels. The tool's job is transport; the choice
is the operator's.

1. Paste a **magnet** or a **.torrent URL**.
2. It verifies first: the server fetches the torrent metadata and shows the name, total size
   and file list. Nothing downloads until you confirm this is the thing you meant.
3. Choose **1) Movies**, **2) Series** (asks for the show name; files go to
   `Series/<Show>/` and sort in S01E01 order), or **3) a new channel** — it asks a name,
   creates the folder, takes the next free channel number, and wires the dial entry itself.
4. The server downloads with aria2, with live progress on your terminal. Archive-style
   torrents default to video-files-only (a 24 GB source dump stays on the shelf). A dead or
   tracker-less swarm is told as such instead of a silent stall; if a healthy torrent stalls
   at 98%, the missing tail is completed over https from the host's own CDN when one is
   known (archive.org). Ctrl-C is safe — pasting the same link resumes.
5. On completion it re-serves, re-scans, rebuilds and publishes the lineup. Televisions pick
   it up on next launch.

Rules of the road, learned by experiment:

- **Check the seeder count at the source.** Zero seeders is a dead torrent — no tool revives
  it. A magnet without `&tr=` tracker params lives on DHT alone and is the fragile form.
- **Deleted files leave with the same pipeline.** Remove them anywhere (Nextcloud UI counts)
  then run `tools/media-server/deploy.sh` followed by the build & push below; the scanner
  rebuilds every channel list from disk truth.
- Everything the ingest CLI does by the end is: `tools/media-server/deploy.sh`, then in
  `curation/`: `python3 build_lineup.py && git add -A && git commit && git push`.
- Server addresses and folder paths are defaults in `curation/ingest.py`,
  `curation/scan_media.py` and `tools/media-server/deploy.sh` — all three name the same box.

## Layout

| Path | What |
|---|---|
| `app/` | The Android app |
| `curation/confs/` | One file per channel: number, name, search query, current clips |
| `curation/refresh_channels.py` | Re-searches channels and writes their confs back |
| `curation/build_lineup.py` | Turns the confs into `channels.json` |
| `curation/scan_media.py` | Rebuilds file-channel streams from the media folders + remote urls |
| `curation/ingest.py` | Interactive magnet/.torrent → channel ingest on the server |
| `tools/media-server/` | Caddy static file service for the media folders + deploy script |
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
