# Instant Channel Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the gap between pressing channel-up and seeing a picture, using Media3's preload manager plus a reserved reverse slot, and prove the gap closed with a measured before-and-after.

**Architecture:** One `ExoPlayer` plus N preloaded neighbours. Media3 preloading buffers sources *without allocating a decoder*, which is fundamentally cheaper than the box's mpv shadow pool where each instance held a decoder and 300-500 MB. One slot of the budget is reserved for the channel *behind*, because priming purely forwards made every reversal a cold open on the box — 5,359 ms, dropping to 350 ms once a reverse slot existed.

**Tech Stack:** Kotlin 2.4.10, AGP 8.13.2, Gradle 8.14.5, compileSdk/targetSdk 35, minSdk 30, Compose BOM 2026.06.01, `androidx.tv:tv-material:1.1.0`, Media3 (upgraded in Task 2).

## Global Constraints

- Never mention Claude, Anthropic, or AI in commits, code, comments, or any file. No `Co-Authored-By` trailers.
- Commit format: `<prefix>: short description`, blank line, then `- Action \`Filename\` what changed` lines listing **every** file the commit touches. Prefixes: `feat|fix|refactor|chore|test|docs|perf`. Actions: `Create|Delete|Update`.
- `schedule/`, `sync/` parsing, `resolver/`, and `ui/ChannelLabels.kt` must contain **zero Android imports** — they test on the JVM with no device, and that boundary is the whole testability story. `ui/Overlays.kt` and `player/` are the sanctioned exceptions.
- The suite is **64 tests** at the start of this plan. Every task reports the count; a drop must be explained, not absorbed.
- Emulator AVD is `fs42tv`. Use `./gradlew`; `ANDROID_HOME` is unset, the SDK path is in `local.properties`.
- Write screenshots and measurement output to `/tmp`, not the session scratchpad.
- The server (`http://192.168.4.203:4243`) is a hard dependency by design. Do not add an offline mode.

## Why this plan starts with a ruler

The baseline for this phase was measured before it was written, and the measurement failed
in an instructive way. Timing keypress → the `fs42` tune log line gave a median of **70 ms**
across 8 presses. That number is real but it is not the latency anyone experiences: the log
line is emitted when the *URL is resolved*, before ExoPlayer has opened a socket. Everything
this phase exists to improve happens after it.

**The app currently has no first-frame instrumentation at all**, so the number that matters
has never been measured once. Task 1 therefore builds the ruler before anything is optimised,
and every later task re-reads it. A performance phase whose first act is a code change is a
phase that will end in an argument about whether it helped.

## File Structure

| File | Responsibility |
|---|---|
| `player/ChannelPlayer.kt` (modify) | Owns the ExoPlayer. Gains `sourceFor()` and first-frame timing. |
| `player/SwitchTiming.kt` (create) | Pure Kotlin: turns raw timing samples into a summary. JVM-testable, no Android imports. |
| `player/PreloadPlan.kt` (create) | Pure Kotlin: decides *which* channels to preload and in what priority order, given a budget and a dial position. JVM-testable, no Android imports. |
| `player/ChannelPreloader.kt` (create) | The Android half: owns the Media3 preload manager and applies a `PreloadPlan`. |
| `player/DeviceBudget.kt` (create) | Reads device RAM and returns a preload budget. |
| `resolver/ResolvedCache.kt` (create) | In-memory write-back for server-resolved URLs, with expiry. |
| `MainActivity.kt` (modify) | Threads the keypress timestamp through, and drives the preloader. |

The split between `PreloadPlan` (pure, tested) and `ChannelPreloader` (Android, verified on
device) is the same boundary the rest of the app uses: the decisions are testable on the JVM,
the plumbing is not, so all the decisions go on the pure side.

---

### Task 1: Build the ruler, and split the source factory out

**Files:**
- Modify: `app/src/main/java/com/cliftonia/fs42tv/player/ChannelPlayer.kt`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`
- Create: `app/src/main/java/com/cliftonia/fs42tv/player/SwitchTiming.kt`
- Create: `app/src/test/java/com/cliftonia/fs42tv/player/SwitchTimingTest.kt`

**Interfaces:**
- Produces: `ChannelPlayer.sourceFor(playable: Playable): MediaSource?` — used by the preloader in Task 4
- Produces: `ChannelPlayer.play(playable: Playable, startAtSeconds: Double, requestedAtMillis: Long)`
- Produces: `SwitchTiming.summarise(samplesMillis: List<Long>): String`

The `sourceFor` split has been outstanding since phase 1 and is a hard prerequisite for
Task 4: a preload manager needs `MediaSource`s built the same way the player builds them.
Doing it here, while the file is small and nothing depends on it yet, is five lines; doing
it after the preloader exists is a rework.

- [ ] **Step 1: Split `sourceFor` out of `play`**

In `ChannelPlayer.kt`, extract the `when (playable)` block verbatim into its own method that
returns `MediaSource?`, and have `play` call it. `NeedsResolving` and `Unplayable` return
`null`, keeping their existing `Log.w` lines exactly as they are — those two log lines are
the only thing making a failed tune legible rather than a silent black screen.

```kotlin
    /**
     * The MediaSource for a playable, or null when there is nothing to play.
     *
     * Split out of [play] because the preload manager needs sources built exactly the way the
     * player builds them - a preloaded source that differs from the played one buffers bytes
     * that are then thrown away.
     */
    fun sourceFor(playable: Playable): MediaSource? = when (playable) {
        is Hls -> HlsMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(playable.url))

        is Progressive -> {
            val video = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(playable.videoUrl))
            // YouTube serves video and audio separately above 360p, so they are merged
            // rather than played one after the other.
            if (playable.audioUrl == null) video else MergingMediaSource(
                video,
                ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri(playable.audioUrl)),
            )
        }

        is NeedsResolving -> {
            Log.w("fs42", "no cached stream for video id ${playable.videoId}; needs server resolve")
            null
        }

        is Unplayable -> {
            Log.w("fs42", "cannot play: ${playable.reason}")
            null
        }
    }
```

Keep the `when` exhaustive with **no `else` branch**. That exhaustiveness check is what
guarantees a fifth `Playable` case cannot be added without the compiler pointing here.

- [ ] **Step 2: Time the press, not the resolve**

Add to `ChannelPlayer`:

```kotlin
    /** Set when a tune starts, cleared when its first frame lands. Main thread only. */
    private var requestedAtMillis = 0L

    init {
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val requested = requestedAtMillis
                if (requested > 0L) {
                    // Measured from the KEYPRESS, not from setMediaSource. The resolve step
                    // ahead of this is only ~70ms on a cache hit, but it is part of what the
                    // viewer waits through, and a ruler that starts after the expensive part
                    // would flatter every change made in this phase.
                    Log.i("fs42", "first frame ${android.os.SystemClock.elapsedRealtime() - requested} ms")
                    requestedAtMillis = 0L
                }
            }
        })
    }
```

Change `play` to take `requestedAtMillis: Long` and set the field before `setMediaSource`.
Import `androidx.media3.common.Player`.

- [ ] **Step 3: Thread the keypress time through**

In `MainActivity.onKeyDown`, capture `SystemClock.elapsedRealtime()` at the moment of the
press and pass it into `tuneTo`, which passes it to `play`. Do this for channel up, channel
down, the picker's `onPickChannel`, and the initial tune in `onCreate` (for the initial tune,
pass the value captured just before `executor.execute`).

Use `SystemClock.elapsedRealtime()`, not `System.currentTimeMillis()` — the wall clock can
step under NTP and produce a negative or absurd duration in the middle of a measurement run.

- [ ] **Step 4: Write the summariser and its test**

`player/SwitchTiming.kt` — pure Kotlin, no Android imports, so it tests on the JVM:

```kotlin
package com.cliftonia.fs42tv.player

/**
 * Turns raw first-frame samples into a line worth pasting into a report.
 *
 * The median rather than the mean: one 12-second outlier from a CDN hiccup should not be
 * able to move the headline number that decides whether a change to the preloader helped.
 */
object SwitchTiming {

    fun summarise(samplesMillis: List<Long>): String {
        if (samplesMillis.isEmpty()) return "no samples"
        val sorted = samplesMillis.sorted()
        val median = sorted[sorted.size / 2]
        return "n=${sorted.size} min=${sorted.first()} median=$median max=${sorted.last()}"
    }
}
```

Test it with an **even-sized** list as well as an odd one, and with a list containing a
single huge outlier — the outlier case is the one that would catch someone quietly switching
the median back to a mean.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: **64 + your new tests**, all passing.

- [ ] **Step 6: Measure the baseline — this is the deliverable**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb logcat -c
for i in $(seq 1 12); do adb shell input keyevent KEYCODE_DPAD_UP; sleep 8; done
adb logcat -d -s fs42:I | grep "first frame" | tee /tmp/fs42tv-baseline.txt
```

Eight seconds between presses so each tune completes rather than superseding the last —
this is measuring one switch at a time, not a burst.

**Report the median, min and max, and say how many of the 12 presses produced no first-frame
line at all.** A press that never renders is a failed tune, and its absence from the sample
would otherwise make the baseline look better than it is. Note which channels were live HLS
versus YouTube, because they behave very differently and the mix affects the number.

- [ ] **Step 7: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
feat: measure how long a channel change actually takes

- Update `ChannelPlayer.kt` split sourceFor out of play, time press to first frame
- Update `MainActivity.kt` thread the keypress timestamp through every tune path
- Create `SwitchTiming.kt` summarise samples by median
- Create `SwitchTimingTest.kt` cover even, odd and outlier-heavy sample sets

The only latency figure this app had was keypress to the resolve log line - a median of
70ms that stops before ExoPlayer opens a socket, so it flattered every part of the switch
this phase exists to improve. The clock now starts at the keypress and stops at the first
rendered frame. sourceFor comes out of play in the same commit because the preload manager
needs sources built exactly the way the player builds them.
MSG
```

---

### Task 2: Upgrade Media3, gated

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:** none new — this task exists to change nothing observable.

Media3 **1.3.1 does not have `DefaultPreloadManager`**. Verified by unpacking the AAR: the
`androidx.media3.exoplayer.source.preload` package contains `PreloadMediaSource` and
`PreloadMediaPeriod` only. `DefaultPreloadManager` arrived in 1.4.0. The design spec assumed
an API this build does not have, so the upgrade is a prerequisite rather than an optional
tidy-up.

Latest stable at time of writing is **1.10.1** (checked against Google's maven metadata).
This is a seven-minor-version jump and it gets its own task for the same reason the Kotlin
1.9 → 2.4 move did: find out what it breaks *before* anything is built on top of it. That
task is the reason the Compose migration went smoothly; this one earns its keep the same way.

- [ ] **Step 1: Bump Media3, and the other dependency that is behind**

In `app/build.gradle.kts`, change all three Media3 artifacts from `1.3.1` to `1.10.1`:

```kotlin
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
```

`androidx.activity` is also behind — the project pins `activity:1.9.0` and
`activity-compose:1.10.1` against a current stable of **1.13.0**. Raise both here, in the
task that already exists to absorb dependency risk, rather than leaving a second upgrade to
ambush a later task:

```kotlin
    implementation("androidx.activity:activity-compose:1.13.0")
```

Checked at the same time and already current, so leave them alone: `androidx.tv:tv-material`
is at 1.1.0 (latest) and the Compose BOM is at 2026.06.01 (latest). If a bare
`androidx.activity:activity` pin is still present and now redundant because
`activity-compose` pulls it transitively at the same version, remove it and say so.

- [ ] **Step 2: Build and read the warnings**

Run: `./gradlew assembleDebug`

If it demands a higher `compileSdk` or `minSdk`, raise `compileSdk`/`targetSdk` to what it
asks for and **report the number**. Do NOT raise `minSdk` above 30 without saying so
prominently — the Chromecast with Google TV HD is the floor this app is built for, and
raising `minSdk` past it would silently drop the device in the car.

Media3 marks a great deal of its API `@UnstableApi`. If the build now fails on unstable-API
usage, add `@OptIn(androidx.media3.common.util.UnstableApi::class)` at the narrowest scope
that compiles — the class or function that uses it, never the whole file or a module-wide
compiler flag. Report which symbols needed it.

- [ ] **Step 3: Confirm nothing observable changed**

Run: `./gradlew :app:testDebugUnitTest`
Expected: the same count as the end of Task 1, all passing.

Then on the device:

```bash
cd ~/Repos/fieldstation42-tv
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb shell input keyevent KEYCODE_DPAD_UP
sleep 8
adb exec-out screencap -p > /tmp/fs42tv-media3-surf.png
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 3
adb exec-out screencap -p > /tmp/fs42tv-media3-picker.png
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 10
adb exec-out screencap -p > /tmp/fs42tv-media3-picked.png
adb logcat -d -s fs42:I | tail -5
```

**Look at all three.** Surfing, the picker, and selection from the picker must all still
work. A live HLS channel is worth checking specifically (channels 103-112) — the HLS
extractor is the part of Media3 most likely to have changed behaviour across seven versions.

- [ ] **Step 4: Re-measure, because the upgrade alone may have moved the number**

Re-run Task 1 Step 6's measurement loop verbatim, writing to `/tmp/fs42tv-media3.txt`.
Report the new median beside Task 1's. If the upgrade alone improved or regressed the
switch, that is worth knowing *now* rather than being attributed to the preloader later.

- [ ] **Step 5: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
chore: upgrade Media3 to 1.10.1 and activity to 1.13.0

- Update `app/build.gradle.kts` raise the three media3 artifacts and androidx.activity

1.3.1 ships PreloadMediaSource but not DefaultPreloadManager, which arrived in 1.4.0 -
verified by unpacking the AAR rather than assumed. 1.10.1 also brings TargetPreloadStatusControl
and RankingDataComparator, which are what let the reverse slot outrank a nearer forward
channel instead of tying with it on distance.

activity went up in the same commit because it was two minors behind and this is the task
that exists to absorb dependency risk. tv-material and the Compose BOM were checked at the
same time and are already current.

This task deliberately changes nothing observable, so that anything it does break surfaces
here instead of being blamed on the preloader.
MSG
```

---

### Task 3: Stop re-resolving the same video

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/resolver/ResolvedCache.kt`
- Create: `app/src/test/java/com/cliftonia/fs42tv/resolver/ResolvedCacheTest.kt`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`

**Interfaces:**
- Consumes: `Progressive`, and `Tier.isFresh()` from the existing `resolver/TierFreshness.kt`
- Produces: `ResolvedCache.get(videoId, nowSeconds): Progressive?`, `ResolvedCache.put(videoId, progressive, expiresAtSeconds)`

`urls.json` covers roughly 46% of the YouTube clips on the dial, so a bit over half of all
tunes currently hit `GET /resolve`. Every later pass over the same channel hits it again,
because a server-resolved URL is never written back anywhere. Task 4 makes this materially
worse: the preloader tunes neighbours too, multiplying the round trips by the preload
fan-out on every press.

**In memory only, deliberately.** Resolved googlevideo URLs are signed and expire in roughly
six hours. Persisting them to disk would mean the app starts up holding URLs that may already
be dead, and the failure mode of a stale signed URL is a channel that plays nothing. The
session-lifetime win is the one that matters here; widening `urls.json` server-side is the
proper fix and belongs to the server plan.

- [ ] **Step 1: Write the failing tests first**

`app/src/test/java/com/cliftonia/fs42tv/resolver/ResolvedCacheTest.kt`:

```kotlin
package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedCacheTest {

    private fun progressive(url: String) = Progressive(videoUrl = url, audioUrl = null)

    @Test
    fun `a stored entry comes back before it expires`() {
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/1"), expiresAtSeconds = 1_000)
        assertEquals("a hit here is the whole point - it removes a server round trip",
            "https://v/1", cache.get("abcdefghijk", nowSeconds = 999)?.videoUrl)
    }

    @Test
    fun `an expired entry is not returned`() {
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/1"), expiresAtSeconds = 1_000)
        assertNull("a signed URL past its expiry plays nothing, which is worse than a miss",
            cache.get("abcdefghijk", nowSeconds = 1_001))
    }

    @Test
    fun `an entry expiring within the safety margin is treated as already gone`() {
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/1"), expiresAtSeconds = 1_000)
        assertNull("a URL with seconds left will expire mid-buffer; the margin is the point",
            cache.get("abcdefghijk", nowSeconds = 1_000 - ResolvedCache.SAFETY_MARGIN_SECONDS + 1))
    }

    @Test
    fun `an unknown id misses`() {
        assertNull(ResolvedCache().get("zzzzzzzzzzz", nowSeconds = 0))
    }
}
```

Note the third test: it is positioned *inside* the margin deliberately. A fixture set to
"grossly expired" would pass whether the margin existed or not — that exact defect was found
three times in an earlier phase of this project, in tests that were all green.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ResolvedCacheTest*'`
Expected: FAIL — `ResolvedCache` does not exist yet.

- [ ] **Step 3: Write it**

`app/src/main/java/com/cliftonia/fs42tv/resolver/ResolvedCache.kt` — pure Kotlin, no Android
imports:

```kotlin
package com.cliftonia.fs42tv.resolver

import java.util.concurrent.ConcurrentHashMap

/**
 * Server-resolved URLs, remembered for as long as they are usable.
 *
 * Roughly half of all tunes miss `urls.json` and fall through to `GET /resolve`. Without this,
 * every later pass over the same channel pays that round trip again - and the preloader makes
 * it worse, because it resolves neighbours too, multiplying the trips by the preload fan-out
 * on every press.
 *
 * In memory only, on purpose. These URLs are signed and expire in about six hours; persisting
 * them would mean starting up holding URLs that may already be dead, and a stale signed URL
 * is a channel that plays nothing rather than an honest miss.
 *
 * Concurrent because the preloader resolves neighbours off the same executor the tune runs on
 * today, but that is not a promise the next change has to keep.
 */
class ResolvedCache {

    private data class Entry(val playable: Progressive, val expiresAtSeconds: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(videoId: String, nowSeconds: Long): Progressive? {
        val entry = entries[videoId] ?: return null
        if (nowSeconds + SAFETY_MARGIN_SECONDS >= entry.expiresAtSeconds) {
            entries.remove(videoId)
            return null
        }
        return entry.playable
    }

    fun put(videoId: String, playable: Progressive, expiresAtSeconds: Long) {
        entries[videoId] = Entry(playable, expiresAtSeconds)
    }

    companion object {
        /**
         * A URL with only seconds left will expire part-way through buffering, which surfaces
         * as a stall rather than a clean miss. Matches the margin `TierFreshness` already
         * applies to published tiers, so both paths give up at the same point.
         */
        const val SAFETY_MARGIN_SECONDS = 300L
    }
}
```

Before settling on `300L`, **read `resolver/TierFreshness.kt` and use whatever margin it
already applies.** Two different margins for the same expiry problem is the kind of drift
this codebase has been careful to avoid; if they differ, say so and pick one.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all passing, count up by four.

- [ ] **Step 5: Wire it in**

In `MainActivity`, hold one `ResolvedCache`. In `tuneTo`, consult it *before* calling
`resolver.resolve(...)`, and `put` the result after a successful resolve. The expiry to
store is the one the server reports for the tier; if the resolve response carries no expiry,
say so and store a conservative fixed lifetime, stating what you chose and why.

- [ ] **Step 6: Prove the round trip is actually gone**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb logcat -c
# surf up to a YouTube channel that misses the cache, then come back to it
for i in $(seq 1 4); do adb shell input keyevent KEYCODE_DPAD_UP; sleep 8; done
for i in $(seq 1 4); do adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 8; done
adb logcat -d -s fs42:D | grep -iE "resolve|first frame"
```

The second pass over the same four channels must show **fewer resolve calls than the first**,
and the returning first-frame times should be lower. If you cannot tell resolve hits from
the log, add a `Log.d` line at the resolve call site as part of this task — an unmeasurable
optimisation is not finished.

- [ ] **Step 7: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
perf: remember server-resolved URLs for the session

- Create `ResolvedCache.kt` in-memory store with an expiry safety margin
- Create `ResolvedCacheTest.kt` cover hit, expiry, the margin itself and a miss
- Update `MainActivity.kt` consult the cache before resolving, store after

urls.json covers about 46% of the dial's clips, so most tunes fall through to GET /resolve
and paid it again on every later pass. The preloader lands next and would have multiplied
those trips by its fan-out on every press.

In memory rather than on disk on purpose: these URLs are signed and expire in about six
hours, so a persisted cache would start up holding URLs that may already be dead, and a
stale signed URL is a channel that plays nothing rather than an honest miss.
MSG
```

---

### Task 4: Preload the neighbours, and keep one slot behind

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/player/PreloadPlan.kt`
- Create: `app/src/test/java/com/cliftonia/fs42tv/player/PreloadPlanTest.kt`
- Create: `app/src/main/java/com/cliftonia/fs42tv/player/DeviceBudget.kt`
- Create: `app/src/main/java/com/cliftonia/fs42tv/player/ChannelPreloader.kt`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`

**Interfaces:**
- Consumes: `ChannelPlayer.sourceFor` (Task 1), `ResolvedCache` (Task 3), `DialNavigator`, `Tuner`
- Produces: `PreloadPlan.forPosition(size: Int, index: Int, budget: Int): List<Int>`
- Produces: `DeviceBudget.forDevice(totalRamBytes: Long): Int`
- Produces: `ChannelPreloader.applyPlan(...)`

**All the decisions go in `PreloadPlan`, which is pure Kotlin and has no Android imports.**
The Media3 wiring goes in `ChannelPreloader` and is verified on the device. That split is
what makes the interesting part — *which* channels, in *what* order — testable without a
device, and it is the same boundary the rest of this app uses.

- [ ] **Step 1: Write the plan tests first**

`app/src/test/java/com/cliftonia/fs42tv/player/PreloadPlanTest.kt`:

```kotlin
package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadPlanTest {

    @Test
    fun `the channel behind is reserved even at the smallest useful budget`() {
        val plan = PreloadPlan.forPosition(size = 100, index = 50, budget = 2)
        assertTrue("priming purely forwards made every reversal a cold open on the box - " +
            "5359ms against 350ms once a reverse slot existed, and the reserve matters far " +
            "more at a budget of 2 than at 8",
            plan.contains(49))
    }

    @Test
    fun `the reverse slot outranks the second channel ahead`() {
        val plan = PreloadPlan.forPosition(size = 100, index = 50, budget = 3)
        assertTrue("a reverse slot that gets dropped first is not reserved at all",
            plan.indexOf(49) < plan.indexOf(52))
    }

    @Test
    fun `the channel ahead is still first`() {
        val plan = PreloadPlan.forPosition(size = 100, index = 50, budget = 4)
        assertEquals("surfing forwards is the common case and must stay the cheapest",
            51, plan.first())
    }

    @Test
    fun `the plan wraps at both ends of the dial`() {
        val atEnd = PreloadPlan.forPosition(size = 10, index = 9, budget = 3)
        assertTrue("the dial wraps when surfing, so the neighbours of the last channel " +
            "include the first", atEnd.contains(0))
        val atStart = PreloadPlan.forPosition(size = 10, index = 0, budget = 3)
        assertTrue(atStart.contains(9))
    }

    @Test
    fun `the plan never exceeds the budget`() {
        for (budget in 1..8) {
            assertEquals("the budget is a memory ceiling on a 1.5GB device, not a suggestion",
                budget, PreloadPlan.forPosition(size = 100, index = 50, budget = budget).size)
        }
    }

    @Test
    fun `the plan never includes the channel already playing`() {
        val plan = PreloadPlan.forPosition(size = 100, index = 50, budget = 6)
        assertTrue("a slot spent on what is already on screen is a slot wasted",
            !plan.contains(50))
    }

    @Test
    fun `a dial smaller than the budget yields no duplicates`() {
        val plan = PreloadPlan.forPosition(size = 3, index = 1, budget = 6)
        assertEquals("wrapping around a short dial must not preload the same channel twice",
            plan.size, plan.toSet().size)
    }
}
```

The last test is the one worth writing carefully. A wrap-around implementation that looks
correct on a 111-channel dial will happily emit the same index three times on a dial of
three, and nothing else in this suite would catch it.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*PreloadPlanTest*'`
Expected: FAIL — `PreloadPlan` does not exist yet.

- [ ] **Step 3: Write `PreloadPlan`**

`app/src/main/java/com/cliftonia/fs42tv/player/PreloadPlan.kt` — pure Kotlin, no Android
imports. Return channel **indices** in priority order, highest priority first:

- position 1: the channel ahead (`index + 1`)
- position 2: the channel behind (`index - 1`) — the reserved reverse slot
- then alternating further ahead and further behind, ahead first
- wrap at both ends, never emit `index` itself, never emit a duplicate, never exceed `budget`

Document *why* the reverse slot sits at position 2 rather than last, quoting the measured
numbers from the box: 5,359 ms cold reversal against 350 ms once the slot existed.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all passing, count up by seven.

- [ ] **Step 5: Size the budget from the device**

`app/src/main/java/com/cliftonia/fs42tv/player/DeviceBudget.kt`:

```kotlin
package com.cliftonia.fs42tv.player

/**
 * How many channels to hold preloaded, from how much RAM the device has.
 *
 * The two targets are a TCL television with room to spare and a Chromecast with Google TV HD
 * with 1.5 GB total, where the decoder, the app and the system are already sharing a small
 * pool. Preloading buffers rather than decoders is cheap by comparison with the box's mpv
 * shadow pool - 300-500 MB per instance there - but it is not free, and the small device is
 * the one that decides this number.
 */
object DeviceBudget {

    private const val GB = 1_024L * 1_024L * 1_024L

    fun forDevice(totalRamBytes: Long): Int = when {
        totalRamBytes >= 3 * GB -> 4
        totalRamBytes >= 2 * GB -> 2
        else -> 1
    }
}
```

Add a test covering each boundary **exactly on the threshold and one byte either side** — an
off-by-one here silently gives the 1.5 GB device a budget meant for the television.

Read the real figure with `ActivityManager.MemoryInfo.totalMem` in `MainActivity` and pass it
in; `DeviceBudget` itself stays free of Android imports so it tests on the JVM.

- [ ] **Step 6: Write `ChannelPreloader`**

This is the Android half. The 1.10.1 API below was read out of the AAR with `javap`, not
recalled — but **confirm it against the version Task 2 actually landed on** before building
on it.

```java
// androidx.media3.exoplayer.source.preload
public interface TargetPreloadStatusControl<T, PreloadStatusT> {
    PreloadStatusT getTargetPreloadStatus(T rankingData);
}

public final class DefaultPreloadManager extends BasePreloadManager<Integer, PreloadStatus> {
    public void setCurrentPlayingIndex(int index);
}

// DefaultPreloadManager.Builder
Builder(Context, TargetPreloadStatusControl<Integer, PreloadStatus>);
Builder setMediaSourceFactory(MediaSource.Factory);
Builder setDataSourceFactory(DataSource.Factory);
Builder setPreloadLooper(Looper);
Builder setCache(Cache);
ExoPlayer buildExoPlayer();          // player sharing this manager's resources
DefaultPreloadManager build();

// BasePreloadManager, inherited
public final void add(MediaSource, T rankingData);
public final void invalidate();
public final boolean remove(MediaSource);
public final void reset();
public final void release();
```

**Three things this changes about the design, all worth reading before writing code:**

1. **Build the player *from* the preload manager.** `Builder.buildExoPlayer()` returns an
   `ExoPlayer` sharing the manager's track selector, load control and bandwidth meter. That
   sharing is the whole mechanism — a preloaded source is only reusable by a player that
   selected tracks the same way. So `ChannelPlayer` must be constructed from the builder
   rather than from its own `ExoPlayer.Builder`. Expect this to touch `ChannelPlayer`'s
   constructor.

2. **Preloading has its own thread.** `setPreloadLooper` means the manager owns its
   threading; it does not run on the app's executor. Ignore the earlier instinct to force it
   onto the single-threaded executor — what still must stay on that executor is anything
   touching `DialNavigator`, whose index is documented as single-writer.

3. **`PreloadPlan`'s job is narrower than it first appears.** `DefaultPreloadManager` already
   ranks by distance from `setCurrentPlayingIndex`, so it will preload neighbours on its own.
   What it will *not* do unaided is rank the channel *behind* above the second channel
   *ahead* — a plain distance metric ties them. That asymmetry is the reverse slot, and
   `TargetPreloadStatusControl.getTargetPreloadStatus(Integer)` is where it gets expressed:
   return a fuller preload status for the reverse neighbour than for the more distant forward
   ones. Use `PreloadPlan` to decide those statuses; do not reimplement neighbour selection
   on top of a manager that already does it.

**Deliberately not used: `Builder.setCache(...)` and `PreCacheHelper`.** 1.10.1 can persist
preloaded media to disk. This project already rejected disk caching on the box — it worked,
and it was abandoned because of how much storage it consumed. Do not reintroduce it here
without asking. Preloading into memory is the goal.

Remaining requirements:

- Sources come from `ChannelPlayer.sourceFor`, so a preloaded source is byte-for-byte the one
  that will be played. A preloaded source built differently buffers bytes that get discarded.
- Preload at the **clock position the channel will actually be at**, via `Tuner`. This is the
  point the spec is most emphatic about: preload channel 64 at offset 1200 s, tune three
  minutes later, and the clock wants 1380 s — the buffered bytes are wrong, though DNS, TLS
  and the connection stay warm. Task 5 keeps these fresh.
- Resolving a neighbour must go through `ResolvedCache` (Task 3) so the fan-out does not
  multiply server round trips.
- Call `setCurrentPlayingIndex` then `invalidate()` after each successful tune; that pair is
  how the manager is told the dial moved.
- Respect `destroyed`. A preload completing after `onDestroy` must not touch the player, and
  `release()` must be called on the manager in `onDestroy`.
- Much of this API is `@UnstableApi`. Opt in at the narrowest scope that compiles, never
  file-wide or via a compiler flag.

- [ ] **Step 7: Drive it from `MainActivity`**

After each successful tune, recompute the plan for the new position and apply it. Preloading
is best-effort: a failure to preload must never affect the channel actually playing, so every
preload path swallows its own errors and logs them rather than propagating.

- [ ] **Step 8: Measure — the whole point of the phase**

Re-run Task 1 Step 6's measurement loop verbatim, to `/tmp/fs42tv-preload.txt`. Then measure
the reversal case specifically, since that is what the reserve slot exists for:

```bash
adb logcat -c
adb shell input keyevent KEYCODE_DPAD_UP;   sleep 8
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 8
adb shell input keyevent KEYCODE_DPAD_UP;   sleep 8
adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 8
adb logcat -d -s fs42:I | grep "first frame"
```

Report three numbers against Task 1's baseline: forward median, reverse median, and
`dumpsys meminfo` TOTAL PSS with the preloader running. State the fraction of 1.5 GB.

**If the median does not improve, say so plainly.** A preloader that does not help is a
finding worth having — the levers are the budget, the position freshness (Task 5) and
whether the buffered position is actually the one being started from. Do not quietly declare
victory on a number that did not move.

- [ ] **Step 9: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
perf: preload the neighbouring channels

- Create `PreloadPlan.kt` which channels to preload, in priority order
- Create `PreloadPlanTest.kt` cover the reverse slot, wrapping, budget and short dials
- Create `DeviceBudget.kt` size the pool from device RAM
- Create `DeviceBudgetTest.kt` cover each threshold and one byte either side
- Create `ChannelPreloader.kt` apply a plan through Media3
- Update `MainActivity.kt` recompute and apply the plan after each successful tune

One slot is reserved for the channel behind, at priority 2 rather than last. Priming
purely forwards made every reversal a cold open on the box - 5359ms, against 350ms once a
reverse slot existed - and the reserve matters far more at a budget of 2 than at 8.

The decisions live in PreloadPlan, which is pure Kotlin and tested on the JVM; only the
Media3 wiring needs a device.
MSG
```

---

### Task 5: Keep the preloaded positions honest, and report the phase

**Files:**
- Modify: `app/src/main/java/com/cliftonia/fs42tv/player/ChannelPreloader.kt`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`
- Create: `docs/superpowers/reports/2026-08-07-instant-switching.md`

**Interfaces:** no new public API — this task changes when existing work runs.

A preloaded buffer only helps at the position you actually start from. Every channel on this
dial is on a wall clock, so a buffer preloaded at 1200 s is wrong three minutes later when
the clock wants 1380 s. The connection stays warm — DNS and TLS are still worth having — but
the bytes are not the bytes that will be played. Without refresh, preloading decays into
"connection warming" and the measured gain shrinks over a session, which is exactly how a
team concludes preloading "doesn't seem to help".

- [ ] **Step 1: Refresh on a timer**

Re-apply the current plan periodically — start at every 60 seconds — recomputing each
neighbour's clock offset through `Tuner` rather than reusing the offsets from when the plan
was first applied.

Use the existing executor, and cancel cleanly in `onDestroy`. **Do not refresh a channel
whose preloaded position is still within a few seconds of correct**; a refresh that discards
a good buffer and re-fetches it is strictly worse than leaving it alone, and on the
Chromecast it is worse again.

Make the interval a named constant with the reasoning beside it, not a bare `60_000`.

- [ ] **Step 2: Verify the decay is actually gone**

This needs a long enough run for the effect to exist at all:

```bash
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb logcat -c
# tune once, then leave it alone for four minutes, then surf
adb shell input keyevent KEYCODE_DPAD_UP
sleep 240
for i in $(seq 1 6); do adb shell input keyevent KEYCODE_DPAD_UP; sleep 8; done
adb logcat -d -s fs42:I | grep "first frame"
```

Compare against Task 4's numbers, which were taken with a freshly-applied plan. **If the
four-minute-idle figures match the fresh ones, the refresh is working.** If they are worse,
the refresh is not running, is running on stale offsets, or is being skipped by the
still-good check — say which, with evidence, rather than adjusting the interval until the
number looks acceptable.

- [ ] **Step 3: Write the phase report**

`docs/superpowers/reports/2026-08-07-instant-switching.md`, covering:

- the baseline from Task 1 and why the previously-quoted 70 ms was not it
- what the Media3 upgrade alone changed, separated from the preloader's effect
- forward and reverse medians before and after, and memory at each stage
- what the budget resolved to on the emulator, and what it will resolve to on each real target
- **what did not work**, in as much detail as what did
- the honest gap to the box's ~200 ms, and which lever is most likely to close it next

- [ ] **Step 4: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
perf: refresh preloaded positions so they stay the right ones

- Update `ChannelPreloader.kt` re-apply the plan on an interval, recomputing clock offsets
- Update `MainActivity.kt` start and stop the refresh with the activity
- Create `2026-08-07-instant-switching.md` the phase's measurements, including what failed

Every channel here runs on a wall clock, so a buffer preloaded at 1200s is the wrong bytes
three minutes later when the clock wants 1380s. The connection stays warm either way, which
is why unrefreshed preloading decays quietly into connection warming instead of failing
visibly - and why a team measuring once at the start concludes preloading does not help.

A neighbour whose preloaded position is still nearly right is left alone: discarding a good
buffer to re-fetch it is worse than doing nothing, and worse again on the 1.5GB device.
MSG
```

---

## Self-Review

**Spec coverage.** The design spec's "Switching" section asks for `DefaultPreloadManager`
with one ExoPlayer and N preloaded neighbours (Task 4), a reserved reverse slot at priority 2
(Task 4, Step 3), a device-derived budget of roughly 4-6 on the TCL and 1-2 on the Chromecast
(Task 4, Step 5), and refreshed preload positions (Task 5). `setMediaSource(source,
startPositionMs)` is already in place from phase 1 and is not re-litigated here.

**Corrections to the spec, found while planning rather than during implementation.** The spec
names `DefaultPreloadManager`, which **does not exist in the Media3 version this app is on** —
1.3.1 ships `PreloadMediaSource` only. Task 2 exists solely because of that. The spec also
implies switching latency was known; it was not, in any form that includes the buffering, so
Task 1 builds the ruler first.

**Deliberately not in this plan.** Adaptive display mode switching (`preferredDisplayModeId`)
is spec'd but is a picture-quality feature, not a latency one, and belongs in its own plan.
Keeping a second `ExoPlayer` alive to reproduce the box's window-swap technique is explicitly
the *next* lever if preloading alone falls short — Task 5's report is where that call gets
made, on numbers. Widening `urls.json` server-side is the server plan.

**Where the Android-free rule bends, deliberately.** `PreloadPlan`, `DeviceBudget`,
`SwitchTiming` and `ResolvedCache` are all pure Kotlin and JVM-tested. `ChannelPreloader` and
the `ChannelPlayer` changes need Android and are verified on the device. Reviewers should
expect that split rather than flag it.

**Known soft spots.** Two, both in Task 4. The preload budget thresholds in `DeviceBudget`
are a first guess, and Task 4's memory measurement should be allowed to overturn them rather
than being read as confirmation. And the whole phase could legitimately end with "preloading
did not move the number" — Task 4 Step 8 and Task 5's report both say so explicitly, because
the alternative is a phase that declares victory on a median that never moved.

A third soft spot was removed while writing this plan rather than left for the implementer.
The Media3 preload API was originally left unquoted as unverifiable, which would have made
the largest task the least specified. It was then read directly out of the 1.10.1 AAR with
`javap`, and doing so changed the design in three ways that would otherwise have surfaced
mid-implementation: the player must be built from the preload manager's builder to share its
track selector, preloading owns its own `Looper` rather than borrowing the app's executor,
and `DefaultPreloadManager` already ranks neighbours by distance — so `PreloadPlan`'s real
job is the reverse-slot asymmetry a distance metric cannot express, not neighbour selection.
