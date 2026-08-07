# TV App Overlays Implementation Plan (Phase 2b)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** See what you are watching and pick a channel from a list — a persistent corner
channel indicator, a banner on every tune, and a scrollable channel picker driven by the D-pad.

**Architecture:** All text formatting lives in pure Kotlin (`ui/ChannelLabels.kt`) tested on the
JVM; the Views only render strings. The picker is a framework `ListView` inside a `FrameLayout`
over the existing `PlayerView`. No new dependencies.

**Tech Stack:** Kotlin, Android framework Views, Media3, JUnit 4 on the JVM.

## Global Constraints

- **Repo:** `~/Repos/fieldstation42-tv`, branch `main`, HEAD `5796d87`. All work is LOCAL.
- Build `./gradlew assembleDebug`; test `./gradlew :app:testDebugUnitTest`. **59 tests pass
  today** — every task must leave the suite green.
- Emulator AVD `fs42tv` (android-34 TV, arm64). Start with
  `~/Library/Android/sdk/emulator/emulator -avd fs42tv -no-snapshot &` and wait for
  `adb shell getprop sys.boot_completed` to return `1`.
- Publisher at `http://192.168.4.203:4243`.
- **`schedule`, `sync` parsing, `resolver`, `tune` and the new `ui` package must stay free of
  Android imports.** That boundary is verified by grep at every review. The Views live in
  `MainActivity` and any view classes, NOT in `ui`.
- **DELIBERATE DEVIATION FROM THE SPEC:** the spec's module table says "Compose for TV". This
  plan uses classic Views instead. Reason: the overlays are a label, a two-line banner and a
  list; Compose would add a substantial runtime to an app whose binding constraint is a 1.5 GB
  Chromecast, where memory already caps the preload budget. Everything built so far is plain
  Views, so this is also the consistent choice. Do not add Compose.
- **NO NEW DEPENDENCIES.** Use the framework `ListView`; do not add RecyclerView, Leanback or
  Compose artifacts.
- Test convention: assertion messages state the **consequence** of failure.
- **Test discrimination is mandatory.** Four "test cannot fail" defects have been found on this
  project, all from fixtures positioned at a default state where correct and buggy behaviour
  produce the same value. For every test: *what mutation would this catch?* Position fixtures
  so the outcomes differ — off index 0, inside a margin rather than far past it, more than one
  candidate.
- Commit format: `<prefix>: short description`, then a NEWLINE, then one `- Action \`File.kt\`
  what changed` per line. A previous commit collapsed onto one line; do not repeat that.
  Prefixes: feat fix refactor chore test docs perf. Actions: Create Delete Update.
- Never mention AI assistance in commits, code or comments.

## What already exists that you will build on

- `MainActivity` — `setContentView(PlayerView)` directly; `@Volatile onAir: Tuned?` holds what
  is genuinely playing (null before the first success); `navigator: DialNavigator`;
  `tuneTo(channel, generation)` on a single-thread executor with a generation counter that
  abandons superseded presses.
- `DialNavigator` — exposes `channels` (a defensive copy), `currentIndex`, `currentNumber`,
  `current`, `up()`, `down()`, `jumpTo(number)`. `index` is `@Volatile`.
- `Tuned(channel, streamIndex, stream, playable, offsetSeconds)` — `stream.title` is the raw
  YouTube title, UTF-8 and frequently non-Latin.

---

### Task 1: Pure label formatting

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/ui/ChannelLabels.kt`
- Test: `app/src/test/java/com/cliftonia/fs42tv/ui/ChannelLabelsTest.kt`

**Interfaces:**
- Consumes: `Channel` (sync), `Tuned` (tune)
- Produces:
  - `object ChannelLabels`
  - `fun indicator(number: Int): String` — `"CH 04"`, zero-padded to two digits, wider if needed
  - `fun bannerLines(tuned: Tuned): Pair<String, String>` — first line `"04  ARCHITECTURE"`,
    second line the cleaned programme title (possibly empty)
  - `fun listRow(channel: Channel): String` — `"CHANNEL 04   ARCHITECTURE & INTERIORS"`
  - `fun cleanTitle(raw: String): String`

Pure Kotlin, no Android imports. This is where the only real logic in the overlays lives, so it
is the part worth testing.

`cleanTitle` exists because published titles are raw YouTube titles: they carry channel-name
prefixes, `| Full Episode` style suffixes, and occasional `[4K]`/`(Official Video)` noise that
reads badly on a TV banner. Keep it conservative — a title that is merely long is fine; the goal
is removing boilerplate, not summarising.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the viewer reads on screen. These are the only overlay decisions with logic in them,
 * so they are the only part worth a test - the views below just draw the strings.
 */
class ChannelLabelsTest {

    private fun tunedWith(title: String, number: Int = 4, name: String = "Architecture") =
        Channel(number = number, name = name, kind = "live", rotation = null,
            streams = listOf(Stream(id = null, url = "u", duration = 1, title = title)))
            .let { ch ->
                Tuned(ch, 0, ch.streams[0], Hls("u"), 0.0)
            }

    @Test
    fun `indicator pads a single digit to two`() {
        assertEquals("a jittering-width indicator draws the eye on every channel change",
            "CH 04", ChannelLabels.indicator(4))
    }

    @Test
    fun `indicator does not truncate three digits`() {
        assertEquals("the dial runs past 100, and a truncated number is a wrong number",
            "CH 103", ChannelLabels.indicator(103))
    }

    @Test
    fun `banner leads with the channel number and name`() {
        val (first, _) = ChannelLabels.bannerLines(tunedWith("Some Programme"))
        assertTrue("the banner must answer 'what am I watching' before anything else",
            first.contains("4") && first.contains("Architecture", ignoreCase = true))
    }

    @Test
    fun `banner second line carries the programme title`() {
        val (_, second) = ChannelLabels.bannerLines(tunedWith("Inside The Bear"))
        assertEquals("Inside The Bear", second)
    }

    @Test
    fun `a channel-name prefix is stripped from the title`() {
        assertEquals("the banner already shows the channel; repeating it wastes the line",
            "Inside The Bear", ChannelLabels.cleanTitle("Architectural Digest: Inside The Bear"))
    }

    @Test
    fun `trailing boilerplate after a pipe is dropped`() {
        assertEquals("Inside The Bear",
            ChannelLabels.cleanTitle("Inside The Bear | Open Door | Architectural Digest"))
    }

    @Test
    fun `bracketed noise is removed`() {
        assertEquals("Chanel Fall Winter Show",
            ChannelLabels.cleanTitle("Chanel Fall Winter Show [4K] (Official Video)"))
    }

    @Test
    fun `a title that is merely long is left alone`() {
        val long = "A Very Long But Entirely Legitimate Programme Title About Something"
        assertEquals("truncating here would lose information the viewer wants",
            long, ChannelLabels.cleanTitle(long))
    }

    @Test
    fun `non-latin titles survive cleaning`() {
        val telugu = "కాపులకు క్లారిటీ"
        assertEquals("the real dial carries Telugu and Malayalam titles; mangling them shows as garbage",
            telugu, ChannelLabels.cleanTitle(telugu))
    }

    @Test
    fun `an empty title yields an empty second line rather than a placeholder`() {
        val (_, second) = ChannelLabels.bannerLines(tunedWith(""))
        assertEquals("a placeholder like 'Unknown' is worse than showing nothing", "", second)
    }

    @Test
    fun `a list row shows number and name`() {
        val row = ChannelLabels.listRow(
            Channel(number = 4, name = "Architecture & Interiors", kind = "youtube",
                rotation = "clock", streams = emptyList()))
        assertTrue("the picker is how you find a channel, so both fields must be present",
            row.contains("04") && row.contains("Architecture & Interiors"))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: compilation failure — `ChannelLabels` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.tune.Tuned

/**
 * The strings the overlays draw.
 *
 * Pure Kotlin with no Android imports: this is the only part of the overlay work with
 * decisions in it, so it is the part worth testing. The views render what this returns.
 */
object ChannelLabels {

    /** Zero-padded so the indicator does not change width as you surf. */
    fun indicator(number: Int): String = "CH %02d".format(number)

    fun bannerLines(tuned: Tuned): Pair<String, String> =
        "%02d  %s".format(tuned.channel.number, tuned.channel.name.uppercase()) to
            cleanTitle(tuned.stream.title)

    fun listRow(channel: Channel): String =
        "CHANNEL %02d   %s".format(channel.number, channel.name)

    /**
     * Strip the boilerplate YouTube titles carry, conservatively.
     *
     * A long title is fine - the goal is removing the uploader's furniture, not summarising.
     * Anything that would empty the title is skipped, because showing the raw title beats
     * showing nothing.
     */
    fun cleanTitle(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        // "Uploader: Real Title" - drop the prefix, but only when something survives.
        val colon = text.indexOf(": ")
        if (colon in 1..40 && text.length > colon + 2) text = text.substring(colon + 2).trim()

        // "Real Title | Series | Uploader" - the first segment is the programme.
        text = text.substringBefore('|').trim().ifEmpty { text }

        // "[4K]", "(Official Video)" and friends.
        text = text.replace(Regex("""\s*[\[(][^\])]*[\])]"""), "").trim()

        return text.ifEmpty { raw.trim() }
    }
}
```

**NOTE TO THE IMPLEMENTER:** `cleanTitle` is a heuristic. If any part of it fails a test,
report that rather than reshaping the tests — the tests define what it must do, and a
heuristic that needs the tests bent to fit is the wrong heuristic.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: 70 tests (59 plus 11 new), all passing.

- [ ] **Step 5: Verify against real titles**

```bash
curl -s http://192.168.4.203:4243/channels.json | python3 -c "
import json,sys
d=json.load(sys.stdin)
seen=[]
for c in d['channels']:
    for s in c['streams'][:1]:
        if s['title']: seen.append(s['title'])
for t in seen[:15]: print(repr(t))
"
```

Take five of those real titles, run them through `cleanTitle` in a scratch test or a Kotlin
scratch file, and record the before/after in your report. **If any result is worse than the
input** — empty, mangled, or with the meaningful part removed — say so plainly. A heuristic
that damages real titles is worse than none.

- [ ] **Step 6: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
feat: format the strings the overlays draw

- Create `ChannelLabels.kt` indicator, banner lines, list rows and title cleanup
- Create `ChannelLabelsTest.kt` cover padding, boilerplate stripping and non-Latin titles

Pure Kotlin with no Android imports: this is the only part of the overlay work with
decisions in it, so it is the part worth testing. Title cleanup is deliberately
conservative - a long title is fine, the goal is removing the uploader's furniture.
MSG
```

---

### Task 2: The corner indicator

**Files:**
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: `ChannelLabels.indicator` (Task 1), `onAir` (existing)
- Produces: an on-screen channel indicator

No unit tests — this is view wiring. The logic it displays is already covered by Task 1.

The content view becomes a `FrameLayout` holding the `PlayerView` plus overlay views, rather
than the `PlayerView` alone. This layout is the scaffold Tasks 3 and 4 build on.

The indicator shows what is **on air**, not where the navigator points — those differ when a
tune fails. Read `onAir`, not `navigator.currentNumber`.

- [ ] **Step 1: Create the layout**

`app/src/main/res/layout/activity_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000">

    <androidx.media3.ui.PlayerView
        android:id="@+id/player"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <TextView
        android:id="@+id/indicator"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|start"
        android:layout_marginStart="48dp"
        android:layout_marginTop="36dp"
        android:fontFamily="monospace"
        android:textSize="34sp"
        android:textStyle="bold"
        android:textColor="#33FF33"
        android:shadowColor="#001100"
        android:shadowRadius="6"
        android:text="" />

</FrameLayout>
```

`#33FF33` is the green the existing FieldStation42 box draws its OSD in — the app should look
like the same product.

- [ ] **Step 2: Wire it in MainActivity**

Replace the `setContentView(PlayerView(this)...)` lines with `setContentView(R.layout.activity_main)`,
then obtain the `PlayerView` and the indicator `TextView` via `findViewById`. Keep
`useController = false` on the player.

Add a private method that updates the indicator from `onAir` on the UI thread, and call it at
the same point the tune already sets `onAir` — inside the existing `runOnUiThread` block, so no
new threading appears. When `onAir` is null, the indicator stays empty.

- [ ] **Step 3: Build, install and look at it**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb exec-out screencap -p > /tmp/fs42tv-indicator.png
adb shell input keyevent KEYCODE_DPAD_UP
sleep 15
adb exec-out screencap -p > /tmp/fs42tv-indicator-2.png
```

**Look at both screenshots.** Expected: green `CH nn` at top-left over the video, and a
DIFFERENT number in the second after the channel change. Report both numbers you can read in
the images and confirm they match the log. If the indicator is absent, clipped, or unreadable
against bright video, say so — legibility is the point of the task.

- [ ] **Step 4: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
feat: show which channel is on air

- Create `activity_main.xml` a frame holding the player with room for overlays
- Update `MainActivity.kt` render the channel indicator from onAir

The indicator reads onAir rather than the navigator's position, because the two differ when
a tune fails and the previous picture is still up. Green matches the OSD the existing box
draws, so the app reads as the same product.
MSG
```

---

### Task 3: The channel banner

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`

**Interfaces:**
- Consumes: `ChannelLabels.bannerLines` (Task 1), `onAir`
- Produces: a banner shown briefly on every tune

No unit tests — view wiring, with the formatting already covered.

The banner appears on each successful tune and hides itself after a few seconds. Surfing
quickly must not leave a stale banner or stack timers.

- [ ] **Step 1: Add the banner to the layout**

Add, inside the same `FrameLayout`, after the indicator:

```xml
    <LinearLayout
        android:id="@+id/banner"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|start"
        android:layout_marginStart="48dp"
        android:layout_marginBottom="56dp"
        android:orientation="vertical"
        android:background="#B0000000"
        android:padding="20dp"
        android:visibility="gone">

        <TextView
            android:id="@+id/bannerChannel"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:textSize="30sp"
            android:textStyle="bold"
            android:textColor="#33FF33"
            android:text="" />

        <TextView
            android:id="@+id/bannerTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:maxWidth="900dp"
            android:maxLines="2"
            android:ellipsize="end"
            android:fontFamily="monospace"
            android:textSize="18sp"
            android:textColor="#CCFFCC"
            android:text="" />
    </LinearLayout>
```

- [ ] **Step 2: Wire it**

In `MainActivity`, alongside the indicator update, add a method that fills both banner text
views from `ChannelLabels.bannerLines(tuned)`, makes the banner visible, and hides it after
`BANNER_MILLIS = 5000`.

Use a single `Handler(Looper.getMainLooper())` held as a field, and **remove any pending hide
callback before posting a new one** — otherwise surfing quickly leaves an earlier timer to hide
a banner that a later tune has just shown. Hide the banner immediately in `onDestroy` by
removing callbacks, so nothing fires against a destroyed activity.

When the second line is empty, hide that TextView rather than leaving an empty gap.

- [ ] **Step 3: Build, install and verify — including the stale-timer case**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb exec-out screencap -p > /tmp/fs42tv-banner-1.png    # banner should be visible
sleep 8
adb exec-out screencap -p > /tmp/fs42tv-banner-2.png    # banner should be GONE
```

Then the timer case — two presses about three seconds apart, so the first banner's hide timer
is still pending when the second appears:

```bash
adb shell input keyevent KEYCODE_DPAD_UP
sleep 3
adb shell input keyevent KEYCODE_DPAD_UP
sleep 3
adb exec-out screencap -p > /tmp/fs42tv-banner-3.png    # second banner STILL visible
```

**Look at all three.** The third is the one that matters: if the banner has vanished, the first
tune's timer hid the second tune's banner and the callback removal is missing.

- [ ] **Step 4: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
feat: show a banner on every tune

- Update `activity_main.xml` add a two-line banner over the picture
- Update `MainActivity.kt` fill the banner from onAir and auto-hide it

Pending hide callbacks are removed before a new one is posted, so surfing quickly cannot
leave an earlier timer to hide a later tune's banner. An empty title hides its line rather
than leaving a gap.
MSG
```

---

### Task 4: The channel picker

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/layout/row_channel.xml`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`

**Interfaces:**
- Consumes: `ChannelLabels.listRow` (Task 1), `DialNavigator.channels` / `currentIndex` /
  `jumpTo` (existing)
- Produces: a scrollable channel list you can pick from

No unit tests — view wiring over already-tested logic.

This is the direct-entry mechanism. The spec was revised to a list precisely because a numeric
keypad is a layout for ten discrete keys and a D-pad has none.

Behaviour:
- `KEYCODE_DPAD_CENTER`/`KEYCODE_ENTER` or `KEYCODE_GUIDE` opens the list
- The list opens **scrolled to and highlighting the channel currently on air**
- Up/down move within the list; they must NOT change channel while it is open
- OK selects: `navigator.jumpTo(number)` then tune, and the list closes
- `KEYCODE_BACK` closes without changing channel

- [ ] **Step 1: Add the list to the layout**

Add last inside the `FrameLayout` so it draws on top:

```xml
    <ListView
        android:id="@+id/picker"
        android:layout_width="720dp"
        android:layout_height="match_parent"
        android:layout_gravity="center_horizontal"
        android:background="#E6000000"
        android:paddingTop="48dp"
        android:paddingBottom="48dp"
        android:divider="@null"
        android:visibility="gone" />
```

`row_channel.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingStart="32dp"
    android:paddingEnd="32dp"
    android:paddingTop="14dp"
    android:paddingBottom="14dp"
    android:fontFamily="monospace"
    android:textSize="22sp"
    android:textColor="#DDDDDD" />
```

- [ ] **Step 2: Wire it**

In `MainActivity`:
- Build an `ArrayAdapter<String>` over `navigator.channels.map { ChannelLabels.listRow(it) }`
  using `R.layout.row_channel`. Build it once, after the dial has synced.
- `showPicker()` — set visibility visible, `requestFocus()`, and
  `setSelection(indexOfOnAirChannel)` so it opens on the channel actually playing. Fall back to
  `navigator.currentIndex` when `onAir` is null.
- `hidePicker()` — visibility gone, return focus to the root view.
- In `onKeyDown`, when the picker is visible: let the `ListView` handle up/down itself by
  returning `super.onKeyDown`, handle BACK by hiding, and handle OK by reading the selected
  position, resolving the channel, calling `navigator.jumpTo(channel.number)`, tuning it, and
  hiding.
- When the picker is NOT visible, the existing up/down surfing behaviour is unchanged.

**The channel-change keys must be inert while the picker is open** — otherwise pressing down to
scroll would also tune, which is both wrong and expensive.

Keep the highlight of the on-air channel legible: setting the selection is enough for the
ListView's own focus highlight on TV. Do not build a custom selection drawable.

- [ ] **Step 3: Build, install and verify every interaction**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb logcat -c

adb shell input keyevent KEYCODE_DPAD_CENTER   # open
sleep 2
adb exec-out screencap -p > /tmp/fs42tv-picker-open.png

adb shell input keyevent KEYCODE_DPAD_DOWN     # scroll, must NOT tune
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
sleep 2
adb exec-out screencap -p > /tmp/fs42tv-picker-scrolled.png
adb logcat -d -s fs42:I | tail -5                # expect NO new tune lines

adb shell input keyevent KEYCODE_DPAD_CENTER   # select
sleep 15
adb exec-out screencap -p > /tmp/fs42tv-picker-selected.png
adb logcat -d -s fs42:I | tail -3                # expect ONE tune, to the selected channel
```

Then BACK:

```bash
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 2
adb exec-out screencap -p > /tmp/fs42tv-picker-dismissed.png
adb logcat -d -s fs42:I | tail -3                # expect NO tune from the dismissal
```

**Look at every screenshot** and report what each shows. The two that matter most: the
scrolled one must show the highlight moved with NO tune in the log, and the dismissed one must
show video with no channel change. Report the channel it opened on and confirm it matches what
was on air.

- [ ] **Step 4: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
feat: pick a channel from a list

- Create `row_channel.xml` a monospace row for the picker
- Update `activity_main.xml` add the channel list over the picture
- Update `MainActivity.kt` open, scroll, select and dismiss the picker

A numeric keypad is a layout for ten discrete keys and a directional pad has none, so the
list is the direct-entry mechanism rather than a second path beside one. It opens on the
channel actually on air, and the channel-change keys are inert while it is open so scrolling
cannot tune.
MSG
```

---

## Self-Review

**Spec coverage.** The revised spec calls for a scrollable channel list opened by OK or the
guide key, a persistent corner indicator, and a banner. Task 1 formats all three, Task 2 is the
indicator, Task 3 the banner, Task 4 the picker.

**Deliberately not in this plan.** No preload manager, no reverse slot, no `sourceFor` split, no
cache write-back — those form the next plan, and the preload budget measured there is what sizes
the server-side widening after it. No settings toggles: ytch exposes channel-name/captions/title
as options, which is a reasonable v2 surface but not needed to make the dial usable.

**Type consistency.** `ChannelLabels` takes `Tuned` and `Channel` and returns `String` /
`Pair<String, String>` — no new types. The layout ids (`player`, `indicator`, `banner`,
`bannerChannel`, `bannerTitle`, `picker`) are introduced in Tasks 2–4 and referenced only in
`MainActivity`.

**Known soft spots.** Two. First, `cleanTitle` is a heuristic over real-world YouTube titles;
Task 1 Step 5 runs it against live data precisely because tests written from imagination would
not catch it damaging real input. Second, Tasks 2–4 are verified visually, as the previous
phases' UI work was — that is deliberate, since the formatting carries the tests and what
remains genuinely needs eyes on a screen.
