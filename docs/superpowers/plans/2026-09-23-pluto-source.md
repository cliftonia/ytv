# Pluto TV Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A SOURCE setting that switches the dial between the YouTube lineup and a hand-picked Pluto TV lineup.

**Architecture:** A curation script turns a committed allowlist plus iptv-org's playlists into `pluto.json`, in the existing `Dial` format with every channel `kind: "live"`. The app gains a `LineupSource` enum that names each dial's url, cache file and remembered-channel key; the SOURCE row flips it and relaunches the activity through the normal boot path.

**Tech Stack:** Python 3.12 (curation, unittest), Kotlin/Android (JUnit 4), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-23-pluto-source-design.md`

## Global Constraints

- The `Dial` wire format does not change; `pluto.json` must parse with `DialContract.parseDial`.
- Existing installs keep their remembered YouTube channel: the legacy pref key `"channel"` is the YouTube key.
- YouTube behaviour is unchanged when SOURCE is YOUTUBE (the default).
- Commits: `<prefix>: short description` then `- Action \`File\` what changed` lines; no mention of AI tooling.

---

### Task 1: Allowlist and `build_pluto.py`

**Files:**
- Create: `curation/pluto_lineup.json` (225 entries from the picker, `{id, alt?, name, genre}`)
- Create: `curation/build_pluto.py`
- Test: `curation/tests/test_build_pluto.py`

**Interfaces:**
- Produces: `parse_m3u(text) -> dict[str, str]` (Pluto id → url); `build(allow, streams) -> (channels, missing)`; CLI `python3 build_pluto.py [--out ../pluto.json]` exiting non-zero on the guard.

- [ ] **Step 1:** Generate `curation/pluto_lineup.json` from the picker data: every picker row not marked `drop`, keeping `id`, `alt` when present, `name`, and `cat` renamed `genre`, sorted by name.
- [ ] **Step 2: Write the failing tests** — `parse_m3u` reads ids and urls; `build` matches by id, falls back to `alt`, reports a channel in neither as missing, numbers 1..N ordered by `GENRE_ORDER` then name, emits `kind: "live"`, no `rotation`, one stream `{url, duration: 600, title: name}` with no `id`; `guard` refuses when fewer than half resolved.
- [ ] **Step 3:** Run `cd curation && python3 -m unittest tests.test_build_pluto -v` — expect import failure.
- [ ] **Step 4:** Implement `build_pluto.py` (fetch `streams/uk_pluto.m3u` and `streams/us_pluto.m3u` from `raw.githubusercontent.com/iptv-org/iptv/master`; on any fetch failure exit non-zero without writing).
- [ ] **Step 5:** Run the tests — expect PASS. Run the whole curation suite — expect PASS.
- [ ] **Step 6:** Run `python3 build_pluto.py` for real; confirm ~225 channels and that `DialContract` fields are present.
- [ ] **Step 7: Commit** `feat: pluto lineup built from a hand-picked allowlist` with `pluto.json`.

### Task 2: Nightly workflow

**Files:**
- Modify: `.github/workflows/lineup.yml` (step after "Rebuild channels.json"; commit step adds `pluto.json`)

- [ ] **Step 1:** Add step `Rebuild pluto.json` running `python3 build_pluto.py` in `curation`, `continue-on-error: true` — a failed iptv-org fetch must not stop the YouTube dial publishing, and leaves the committed `pluto.json` as it was.
- [ ] **Step 2:** `git add channels.json pluto.json curation/confs` in the commit step.
- [ ] **Step 3: Commit** `chore: nightly job rebuilds pluto.json`.

### Task 3: `LineupSource` and per-source loading

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/sync/LineupSource.kt`
- Modify: `sync/DialRepository.kt` (cache file name parameter), `sync/DialLoader.kt` (take a `LineupSource`)
- Test: `app/src/test/java/com/cliftonia/fs42tv/sync/LineupSourceTest.kt`, and a `pluto-sample.json` fixture parse in `DialContractTest`

**Interfaces:**
- Produces:
  ```kotlin
  enum class LineupSource(val label: String, val url: String, val cacheFile: String, val channelKey: String) {
      YOUTUBE, PLUTO;
      fun next(): LineupSource
      companion object { const val KEY = "source"; fun parse(value: String?): LineupSource }
  }
  class DialRepository(fetch: (String) -> String, cacheDir: File, cacheFile: String = "channels.json")
  class DialLoader(source: LineupSource, cacheDir: File, ...)
  ```

- [ ] **Step 1: Write the failing tests** — unknown/null parses to YOUTUBE; `next()` cycles; YOUTUBE keeps key `"channel"` and file `channels.json`; PLUTO uses `pluto.json` for both url and cache and a different key; a `pluto.json` sample parses with every channel `live`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*LineupSource*' --tests '*DialContract*'` — expect FAIL.
- [ ] **Step 3:** Implement; `DialLoader` uses `source.url` and `DialRepository(..., cacheFile = source.cacheFile)`.
- [ ] **Step 4:** Run the tests — expect PASS.
- [ ] **Step 5: Commit** `feat: lineup source names each dial's url, cache and channel key`.

### Task 4: SOURCE setting

**Files:**
- Modify: `ui/SettingsCatalog.kt` (row first among controls; `Deps.source`, `Deps.switchSource`; `lineupAge` reads the active source's file)
- Modify: `MainActivity.kt` (read source in `onCreate`; per-source channel key for read and `rememberChannel`; `switchSource` saves the pref and calls `recreate()`)

- [ ] **Step 1:** Add the row: label `SOURCE`, value `source.label`, action `deps.switchSource(source.next())`.
- [ ] **Step 2:** Wire `MainActivity`: `source = LineupSource.parse(prefs.getString(LineupSource.KEY, null))`, pass to `DialLoader`, replace `CHANNEL_KEY` uses with `source.channelKey`.
- [ ] **Step 3:** Run `./gradlew :app:testReleaseUnitTest` and `./gradlew :app:assembleDebug` — expect PASS.
- [ ] **Step 4: Commit** `feat: SOURCE setting switches between the YouTube and Pluto TV dials`.

### Task 5: Release

- [ ] **Step 1:** Update `HANDOVER.md` (untracked) and README with the SOURCE setting and `build_pluto.py`.
- [ ] **Step 2:** Push `main`; confirm `pluto.json` is served at `raw.githubusercontent.com/cliftonia/ytv/main/pluto.json`.
- [ ] **Step 3:** Tag `v$(date -u '+%y%j%H%M')`, push the tag, watch the `release` workflow to a published release.
- [ ] **Step 4:** Owner updates both televisions and checks: switch to Pluto, surf several genres, switch back to the remembered YouTube channel.
