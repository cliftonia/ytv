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
- **Compose for TV, as the spec says.** An earlier draft of this plan deviated to classic
  Views on the grounds that Compose would cost too much memory on a 1.5 GB Chromecast. That
  claim was checked and did not hold, so the deviation is withdrawn.
  **Measured** (`~/Repos/fs42-bench/RESULTS.md`, two release APKs, 111 identical rows, 3 runs):

      APK         844 KB  vs  1.53 MB
      TOTAL PSS   21.7 MB vs  32.1 MB   (+10 MB, about 2% of a 1.5 GB device)
      jank        0.54%   vs  0.54%     (tied)
      95th frame  17 ms   vs  18 ms
      cold start  126 ms  vs  169 ms

  Compose is consistently heavier and consistently immaterial at this scale.
  Caveat recorded honestly: the emulator refused a 1536 MB setting and force-bumped to 2048 MB,
  so memory PRESSURE was never simulated — only consumption measured.
- **The toolchain is already proven.** The benchmark built a working Compose-for-TV release
  APK. Reference source: `~/Repos/fs42-bench/composeapp/`. Copy its configuration rather than
  rediscovering it.
- Use a plain Compose Foundation `LazyColumn`, **not** `androidx.tv:tv-foundation` — that
  artifact stopped at 1.0.0 because `TvLazyColumn` was absorbed into Foundation once it gained
  TV focus handling. `androidx.tv:tv-material:1.1.0` supplies `Surface`, `Text` and
  `ClickableSurfaceDefaults` for focus colours.
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


### Task 2: Move to Compose, and prove it with the corner indicator

**Files:**
- Modify: `build.gradle.kts` (root), `app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`
- Create: `app/src/main/java/com/cliftonia/fs42tv/ui/Overlays.kt`

**Interfaces:**
- Consumes: `ChannelLabels.indicator` (Task 1), `onAir` (existing)
- Produces: `@Composable fun ChannelIndicator(text: String)` in `ui/Overlays.kt`

This task exists to absorb the toolchain risk before anything is built on top — the same
reason phase 1's first task was a scaffold that only proved the build worked. Moving from
Kotlin 1.9.24 / AGP 8.5.2 / compileSdk 34 to the versions Compose needs is a real upgrade that
can break the existing 72 tests. Find that out here, not in Task 4.

**`ui/Overlays.kt` will contain `@Composable` functions and therefore Compose imports.** That
is the one place the Android-free rule does not apply — `ChannelLabels.kt` stays pure and keeps
all the formatting logic; `Overlays.kt` only draws. Reviews should expect exactly this split.

- [ ] **Step 1: Upgrade the toolchain**

Copy the working configuration from `~/Repos/fs42-bench/`. Root `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
```

In `app/build.gradle.kts`: add `id("org.jetbrains.kotlin.plugin.compose")` to the plugins
block, raise `compileSdk` and `targetSdk` to 35, add `buildFeatures { compose = true }`, and
replace the `kotlinOptions` block with:

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
```

Add to `dependencies`, keeping everything already there:

```kotlin
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.tv:tv-material:1.1.0")
```

AGP 8.13.2 needs a newer Gradle than 8.7. Update `distributionUrl` in
`gradle/wrapper/gradle-wrapper.properties` to `gradle-8.13-bin.zip` and let the wrapper fetch
it. If AGP demands a different minimum, use what it asks for and say so in your report.

- [ ] **Step 2: Confirm the existing suite still passes BEFORE writing any Compose**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: **72 tests, all passing.**

This is the single most important checkpoint in the task. A Kotlin 1.9 to 2.4 jump can change
warnings into errors and alter serialization plugin behaviour. If anything fails here, STOP and
report it — a broken suite after a toolchain bump is a finding, not something to work around.

- [ ] **Step 3: Write the indicator composable**

`app/src/main/java/com/cliftonia/fs42tv/ui/Overlays.kt`:

```kotlin
package com.cliftonia.fs42tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** The green the existing FieldStation42 box draws its OSD in, so this reads as one product. */
val OsdGreen = Color(0xFF33FF33)

/**
 * The persistent corner channel indicator.
 *
 * Shows what is ON AIR, which is not the same as where the dial navigator points - they differ
 * whenever a tune fails and the previous picture stays up.
 */
@Composable
fun ChannelIndicator(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Text(
            text = text,
            color = OsdGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            modifier = Modifier.padding(start = 48.dp, top = 36.dp),
        )
    }
}
```

- [ ] **Step 4: Host Compose over the player**

`MainActivity` currently calls `setContentView(PlayerView(this)...)`. Replace that with a
`FrameLayout` holding the `PlayerView` and, above it, a `ComposeView`. Keep
`useController = false`.

Hold the on-air indicator text in a Compose state so the overlay recomposes when it changes:

```kotlin
    private val indicatorText = mutableStateOf("")
```

Set `composeView.setContent { ChannelIndicator(indicatorText.value) }` once in `onCreate`, and
update `indicatorText.value` from `ChannelLabels.indicator(...)` at the same point the tune
already sets `onAir` — inside the existing `runOnUiThread` block, so no new threading appears.
When `onAir` is null the text stays empty.

`ComposeView` must not steal focus from the D-pad handling: set
`composeView.isFocusable = false` and `composeView.descendantFocusability =
ViewGroup.FOCUS_BLOCK_DESCENDANTS` for now. Task 4 changes this deliberately when the picker
needs focus.

- [ ] **Step 5: Build, install and look at it**

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
adb logcat -d -s fs42:I | tail -3
```

**Look at both screenshots.** Expected: green `CH nn` top-left over the video, a DIFFERENT
number in the second, and surfing still working. Report both numbers you can read and confirm
they match the log. If surfing broke, the `ComposeView` is taking focus — say so.

- [ ] **Step 6: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
feat: move to Compose for TV and show the channel on air

- Update `build.gradle.kts` Kotlin 2.4.10, AGP 8.13.2 and the Compose compiler plugin
- Update `app/build.gradle.kts` enable Compose, add the BOM and tv-material
- Create `Overlays.kt` the corner channel indicator
- Update `MainActivity.kt` host Compose over the player and drive the indicator from onAir

The toolkit choice was settled by measurement rather than argument: Compose costs about 10MB
more resident memory than Views for this screen, roughly 2% of the smallest target device,
with identical jank. The indicator reads onAir rather than the navigator's position, because
the two differ when a tune fails and the previous picture is still up.
MSG
```

---

### Task 3: The channel banner

**Files:**
- Modify: `app/src/main/java/com/cliftonia/fs42tv/ui/Overlays.kt`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`

**Interfaces:**
- Consumes: `ChannelLabels.bannerLines` (Task 1)
- Produces: `@Composable fun ChannelBanner(channelLine: String, titleLine: String, visible: Boolean)`

The banner appears on each successful tune and hides after a few seconds. Surfing quickly must
not leave a stale banner — and in Compose the natural way to get that right is a `LaunchedEffect`
keyed on the tune, which cancels its predecessor automatically rather than needing manual
callback removal.

- [ ] **Step 1: Add the banner composable**

Append to `Overlays.kt`:

```kotlin
/**
 * The tune banner: channel line above, programme title below.
 *
 * Auto-hides via a LaunchedEffect keyed on [generation], so a new tune cancels the previous
 * timer rather than letting an earlier one hide a later banner. That bug needed explicit
 * callback removal under Views; here the key does it.
 */
@Composable
fun ChannelBanner(
    channelLine: String,
    titleLine: String,
    generation: Int,
    holdMillis: Long = 5000,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(generation) {
        if (channelLine.isEmpty()) return@LaunchedEffect
        visible = true
        delay(holdMillis)
        visible = false
    }
    if (!visible) return

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Column(
            modifier = Modifier
                .padding(start = 48.dp, bottom = 56.dp)
                .background(Color(0xB0000000))
                .padding(20.dp),
        ) {
            Text(
                text = channelLine,
                color = OsdGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
            )
            if (titleLine.isNotEmpty()) {
                Text(
                    text = titleLine,
                    color = Color(0xFFCCFFCC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 900.dp),
                )
            }
        }
    }
}
```

Add the imports this needs: `androidx.compose.foundation.background`,
`androidx.compose.foundation.layout.Column`, `androidx.compose.foundation.layout.widthIn`,
`androidx.compose.runtime.*`, `androidx.compose.ui.text.style.TextOverflow`,
`kotlinx.coroutines.delay`.

- [ ] **Step 2: Wire it**

In `MainActivity`, add state for the two banner lines and an `Int` generation that increments
on every successful tune. Render `ChannelIndicator` and `ChannelBanner` together inside the
existing `setContent` block, stacked in a `Box`.

Set all three — indicator text, banner lines, generation — in the same `runOnUiThread` block
that already sets `onAir`, so the overlay always agrees with what is playing.

- [ ] **Step 3: Build, install and verify — including the stale-timer case**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb exec-out screencap -p > /tmp/fs42tv-banner-1.png    # banner visible
sleep 8
adb exec-out screencap -p > /tmp/fs42tv-banner-2.png    # banner GONE
adb shell input keyevent KEYCODE_DPAD_UP
sleep 3
adb shell input keyevent KEYCODE_DPAD_UP
sleep 3
adb exec-out screencap -p > /tmp/fs42tv-banner-3.png    # second banner STILL visible
```

**Look at all three.** The third is the one that matters: if the banner has vanished, the first
tune's timer hid the second tune's banner and the `LaunchedEffect` key is wrong.

- [ ] **Step 4: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
feat: show a banner on every tune

- Update `Overlays.kt` add the two-line tune banner
- Update `MainActivity.kt` drive the banner from the same state that sets onAir

The auto-hide is a LaunchedEffect keyed on the tune, so a new tune cancels the previous
timer automatically - the stale-banner bug that would need explicit callback removal under
Views cannot arise. An empty title omits its line rather than leaving a gap.
MSG
```

---

### Task 4: The channel picker

**Files:**
- Modify: `app/src/main/java/com/cliftonia/fs42tv/ui/Overlays.kt`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`

**Interfaces:**
- Consumes: `ChannelLabels.listRow` (Task 1), `DialNavigator.channels` / `currentIndex` /
  `jumpTo` (existing)
- Produces: `@Composable fun ChannelPicker(rows: List<String>, startIndex: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit)`

This is the direct-entry mechanism. The spec was revised to a list because a numeric keypad is
a layout for ten discrete keys and a D-pad has none.

Reference implementation for the list and focus colours: `~/Repos/fs42-bench/composeapp/`.
It uses a plain `LazyColumn` with `androidx.tv.material3.Surface` rows and
`ClickableSurfaceDefaults` for the focused background — copy that shape.

Behaviour:
- `KEYCODE_DPAD_CENTER`/`KEYCODE_ENTER` or `KEYCODE_GUIDE` opens it
- Opens scrolled to and focused on the channel currently **on air**
- Up/down move within the list and must NOT change channel while it is open
- OK selects: `navigator.jumpTo(number)` then tune, and it closes
- `KEYCODE_BACK` closes without changing channel

- [ ] **Step 1: Add the picker composable**

Follow the benchmark's shape. Use `rememberLazyListState()` seeded so the on-air row is
visible, a `FocusRequester` on the initially focused row, and `items(rows)` over the strings.
Give each row an `androidx.tv.material3.Surface` with
`ClickableSurfaceDefaults.colors(focusedContainerColor = ...)` so the focused row is obvious,
and `onClick` reporting the index via `onPick`.

Handle BACK inside the composable with `androidx.activity.compose.BackHandler` so dismissal is
Compose's concern rather than another branch in `onKeyDown`.

- [ ] **Step 2: Wire it**

In `MainActivity`, hold `pickerVisible` state. When it becomes true, the `ComposeView` must
take focus — flip `descendantFocusability` back to `FOCUS_AFTER_DESCENDANTS` and call
`requestFocus()`; restore the blocking value when it closes. **The channel-change keys must be
inert while the picker is open**, otherwise pressing down to scroll would also tune.

`onPick` maps the row index to `navigator.channels[index]`, calls
`navigator.jumpTo(channel.number)`, tunes it, and closes the picker.

- [ ] **Step 3: Verify every interaction**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb logcat -c

adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 2
adb exec-out screencap -p > /tmp/fs42tv-picker-open.png

adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
sleep 2
adb exec-out screencap -p > /tmp/fs42tv-picker-scrolled.png
adb logcat -d -s fs42:I | tail -5          # expect NO new tune lines

adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 15
adb exec-out screencap -p > /tmp/fs42tv-picker-selected.png
adb logcat -d -s fs42:I | tail -3          # expect ONE tune, to the selected channel

adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 2
adb exec-out screencap -p > /tmp/fs42tv-picker-dismissed.png
adb logcat -d -s fs42:I | tail -3          # expect NO tune from the dismissal
```

**Look at every screenshot.** The two that matter most: the scrolled one must show the focus
highlight moved with NO tune in the log, and the dismissed one must show video with no channel
change. Report the channel it opened on and confirm it matches what was on air.

- [ ] **Step 4: Measure the real memory cost**

The benchmark measured a standalone picker. This measures the actual app with video decoding
alongside it — the number that matters.

```bash
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 25
adb shell dumpsys meminfo com.cliftonia.fs42tv | grep -E "TOTAL PSS|Java Heap|Native Heap|Graphics"
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 3
for i in $(seq 1 40); do adb shell input keyevent KEYCODE_DPAD_DOWN; done
sleep 3
adb shell dumpsys meminfo com.cliftonia.fs42tv | grep -E "TOTAL PSS|Java Heap|Native Heap|Graphics"
```

Record both readings. The Chromecast with Google TV HD has 1.5 GB total; report what fraction
this uses. If TOTAL PSS exceeds roughly 250 MB, say so prominently — that would be a genuine
concern rather than the immaterial cost the benchmark predicted.

- [ ] **Step 5: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -F- <<'MSG'
feat: pick a channel from a list

- Update `Overlays.kt` add the channel picker over the picture
- Update `MainActivity.kt` open, focus, select and dismiss the picker

A numeric keypad is a layout for ten discrete keys and a directional pad has none, so the
list is the direct-entry mechanism rather than a second path beside one. It opens focused on
the channel actually on air, and the channel-change keys are inert while it is open so
scrolling cannot tune.
MSG
```

---

## Self-Review

**Spec coverage.** The revised spec calls for a scrollable channel list opened by OK or the
guide key, a persistent corner indicator, and a banner. Task 1 formats all three, Task 2
migrates the toolchain and adds the indicator, Task 3 the banner, Task 4 the picker.

**Deliberately not in this plan.** No preload manager, no reverse slot, no `sourceFor` split,
no cache write-back — those form the next plan, and the preload budget measured there sizes the
server-side widening after it. No settings toggles.

**Where the Android-free rule bends, deliberately.** `ui/ChannelLabels.kt` stays pure and holds
every formatting decision. `ui/Overlays.kt` contains `@Composable` functions and therefore
Compose imports — it only draws. Reviewers should expect that split rather than flag it.

**Known soft spots.** Two. Task 2's toolchain jump from Kotlin 1.9.24 to 2.4.10 is the riskiest
step in the plan, which is why Step 2 verifies the existing 72 tests BEFORE any Compose is
written. And Tasks 2–4 are verified visually, as previous UI phases were — the formatting
carries the tests, and what remains genuinely needs eyes on a screen.
