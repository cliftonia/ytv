# Pluto TV as a second source

## What

A **SOURCE** row in Settings that switches the whole dial between two lineups:

- **YOUTUBE** — today's dial, `channels.json`, unchanged.
- **PLUTO TV** — a separate dial of 225 themed English Pluto TV channels, `pluto.json`.

The two are never merged. Switching replaces one dial with the other.

## The channel list

Hand-picked, not scraped wholesale. iptv-org lists 587 Pluto channels across its UK (185) and US
(402) playlists; most loop a single show (Matlock, Corner Gas) or are Spanish-language, local US
news or shopping. Those were removed, leaving 257 themed channels, each given a genre. Channels in
both playlists were merged into one entry carrying both stream ids. The owner then dropped 32
(including every news channel), leaving **225**.

That list is committed as `curation/pluto_lineup.json` — an **allowlist**:

```json
[{"id": "5ddf...", "alt": "6176...", "name": "Pluto TV Action", "genre": "Movies"}]
```

`alt` is present only for a channel in both playlists. A channel Pluto adds later does not appear
until it is added to this file by hand — which is the point, because a new Pluto channel is far
more likely to be another single-show loop than a themed channel worth having.

## Nightly build

`curation/build_pluto.py`, run by the existing `lineup` workflow after `build_lineup.py`:

1. Fetch `streams/uk_pluto.m3u` and `streams/us_pluto.m3u` from iptv-org.
2. For each allowlisted channel, find its stream by `id`, falling back to `alt`. A channel in
   neither playlist has been retired by Pluto: skip it and print its name.
3. Order by genre, then name, and number 1..N contiguously, so channels of a kind sit together
   on the dial the way a cable lineup groups them.
4. Write `pluto.json` at the repo root in the existing `Dial` format — every channel
   `kind: "live"`, one stream of `duration: 600` whose `url` is the iptv-org url
   (`https://jmp2.uk/plu-<id>.m3u8`), exactly as Euronews and CBS News are shipped today.

**Guard:** refuse to publish if fewer than half the allowlist resolved, or if the result has
duplicate numbers. A network failure fetching iptv-org leaves the committed `pluto.json` alone.
The workflow's commit step adds `pluto.json`.

## The app

**Where each dial comes from.** `DialLoader` takes the source instead of a hard-wired url:
`YOUTUBE` → `.../main/channels.json`, `PLUTO` → `.../main/pluto.json`. Each source caches to
its own file (`channels.json` / `pluto.json` in `cacheDir`), so switching never overwrites the
other dial's last good copy and an offline switch still works if that dial was ever fetched.

**The setting.** `SOURCE` is the first control row in `SettingsCatalog`, persisted under a new
`SOURCE_KEY`. OK flips it and **relaunches the activity**, which reloads the dial through the
normal boot path. Swapping the navigator under a running engine was considered and rejected: the
boot path already handles no-network, cache fallback and first tune, and switching source is rare.

**Remembered channel, per source.** `CHANNEL_KEY` becomes per-source, so going back to YouTube
lands on the channel you left, not on "Pluto channel 47" reinterpreted as a YouTube number.
The existing key is read as the YouTube one, so nobody's remembered channel resets on upgrade.

**Playback.** No change. `kind: "live"` already routes to `Hls(url)` in `Tuner`, and both engines
already play `jmp2.uk` Pluto streams (channels 110 and 115).

**Diagnostics.** The `LINEUP` age row reads the active source's cache file.

## Testing

- `curation/tests`: `build_pluto` against fixture playlists — id match, `alt` fallback, retired
  channel skipped, genre-then-name numbering, the half-missing guard.
- JVM unit tests: source → url and cache-file mapping; per-source channel key, including reading
  the legacy key as YouTube.
- On both televisions: switch to Pluto, surf several genres, switch back and land on the
  remembered YouTube channel. Media3 and mpv are separate paths; one passing proves nothing about
  the other.

## Out of scope

- **Geo-blocking.** Some Pluto channels may refuse Australia. Deferred: once we see which, add a
  guard that hides channels that fail to play.
- Showing genre in the guide or OSD.
- A picker inside the app for choosing channels.
