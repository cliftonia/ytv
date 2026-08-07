# TV App Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android TV app that syncs the published dial, computes the clock-rotation
position locally, and plays one channel at the correct offset — proving the data contract,
the resolver and Media3 work together before any UI exists.

**Architecture:** One Gradle module with strict package boundaries. `schedule`, `sync`
parsing and `resolver` are pure Kotlin with no Android imports, so they are tested on the
JVM with no device. Only `player` and the activity touch Android. The server at
`192.168.4.203:4243` publishes `channels.json` and `urls.json`; the app never extracts from
YouTube.

**Tech Stack:** Kotlin, Gradle (KTS), AGP, JDK 17, kotlinx-serialization, AndroidX Media3
(ExoPlayer), JUnit 4 on the JVM.

## Global Constraints

- **Repo:** `~/Repos/fieldstation42-tv`, branch `main`. All work is LOCAL to the Mac —
  unlike the server-side plan, nothing here runs over ssh.
- **SDK:** already installed at `~/Library/Android/sdk`. `ANDROID_HOME` is UNSET in the
  shell — export it, or write `sdk.dir=/Users/cliftonbaggerman/Library/Android/sdk` into
  `local.properties` (preferred: it is per-project and needs no shell setup).
- **`local.properties` must be gitignored** — it contains an absolute machine path.
- Platforms available: android-33, 34, 35. Build-tools up to 34.0.0. JDK 17 (Corretto).
- **compileSdk 34, targetSdk 34, minSdk 30.** minSdk 30 covers the Chromecast with Google
  TV HD (Android 12) with headroom.
- **Emulator image:** `system-images;android-34;android-tv;arm64-v8a` — native arm64 on this
  Apple Silicon Mac. Do NOT use an x86 image; it will be unusably slow.
- `gradle` is NOT on PATH. Use the Gradle wrapper (`./gradlew`) that Task 1 generates.
- Unit tests are JVM tests under `src/test/`, run with `./gradlew test`. Do not add
  Robolectric — the pure packages are designed to need no Android runtime.
- Test convention, carried over from the server side: assertion messages state the
  **consequence** of failure, not the expectation.
- Commit format: `<prefix>: short description`, then `- Action \`Filename.kt\` what changed`.
  Prefixes: `feat` `fix` `refactor` `chore` `test` `docs` `perf`. Actions: `Create` `Delete` `Update`.
- Never mention AI assistance in commits, code or comments.
- **The live server contract** (verified against `http://192.168.4.203:4243/channels.json`):
  a channel is `{number:int, name:string, kind:"youtube"|"live", rotation:string|null,
  streams:[{id:string|null, url:string, duration:int, title:string}]}`. Titles are UTF-8 and
  frequently non-Latin — never assume ASCII.

---

### Task 1: Scaffold a TV app that launches on the emulator

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`
- Create: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: nothing
- Produces: a buildable, installable app. Package id `com.cliftonia.fs42tv`.

This task exists to de-risk the toolchain before any logic is written. Gradle/AGP/emulator
setup is where projects like this die, and discovering that on Task 5 would waste four tasks
of work.

- [ ] **Step 1: Create the TV emulator first, before any code**

```bash
export ANDROID_HOME=~/Library/Android/sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "system-images;android-34;android-tv;arm64-v8a"
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
  -n fs42tv -k "system-images;android-34;android-tv;arm64-v8a" -d tv_1080p
```

Expected: image downloads, AVD `fs42tv` created. If `-d tv_1080p` is rejected, run
`avdmanager list device | grep -i tv` and use whatever TV device id it reports.

- [ ] **Step 2: Boot it and confirm adb sees it**

```bash
$ANDROID_HOME/emulator/emulator -avd fs42tv -no-snapshot -netdelay none -netspeed full &
adb wait-for-device
adb shell getprop sys.boot_completed
```

Expected: `1`. This can take a couple of minutes on first boot. Leave the emulator running
for the rest of the task.

- [ ] **Step 3: Write the project files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "fieldstation42-tv"
include(":app")
```

`build.gradle.kts` (root):

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

`.gitignore`:

```
local.properties
.gradle/
build/
*.iml
.idea/
.DS_Store
```

`local.properties` (NOT committed):

```properties
sdk.dir=/Users/cliftonbaggerman/Library/Android/sdk
```

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.cliftonia.fs42tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cliftonia.fs42tv"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.activity:activity:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
```

`app/src/main/AndroidManifest.xml` — the leanback bits are what make it a TV app rather
than a phone app that happens to run:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-feature android:name="android.software.leanback" android:required="true" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />

    <application
        android:label="@string/app_name"
        android:banner="@android:drawable/star_big_on"
        android:supportsRtl="true">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape"
            android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">FieldStation42</string>
</resources>
```

`app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`:

```kotlin
package com.cliftonia.fs42tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Placeholder so the toolchain can be proven before any logic exists. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "FieldStation42"
            setTextColor(Color.parseColor("#33FF33"))
            setBackgroundColor(Color.BLACK)
            textSize = 48f
            gravity = Gravity.CENTER
        })
    }
}
```

- [ ] **Step 4: Generate the Gradle wrapper and build**

```bash
cd ~/Repos/fieldstation42-tv
gradle wrapper --gradle-version 8.7 2>/dev/null || \
  /Applications/Android\ Studio.app/Contents/gradle/gradle-*/bin/gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. If `gradle` cannot be found at all, download the wrapper jar
from an existing Android project on this machine (`~/Repos` has several) rather than
installing Gradle globally.

- [ ] **Step 5: Install and launch on the emulator**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 3
adb exec-out screencap -p > /tmp/fs42tv-launch.png
```

Expected: install succeeds, and the screenshot shows green "FieldStation42" on black.
**Look at the screenshot** — do not assume. A blank or black-only image means it did not
render and the task is not done.

- [ ] **Step 6: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -m "feat: scaffold an Android TV app that launches

- Create \`build.gradle.kts\` root build with AGP, Kotlin and serialization plugins
- Create \`app/build.gradle.kts\` minSdk 30, compileSdk 34, Media3 and serialization
- Create \`AndroidManifest.xml\` leanback launcher category and internet permission
- Create \`MainActivity.kt\` placeholder proving the toolchain end to end

The leanback launcher category is what makes this a TV app rather than a phone app
that happens to run on one. Verified installed and rendering on an arm64 Android TV
emulator."
```

---

### Task 2: Clock rotation, the heart of the product

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/schedule/ClockRotation.kt`
- Test: `app/src/test/java/com/cliftonia/fs42tv/schedule/ClockRotationTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class PlayPoint(val index: Int, val offsetSeconds: Double)`
  - `object ClockRotation { fun playPointFor(durations: List<Int>, nowSeconds: Long): PlayPoint? }`

Pure Kotlin, no Android imports, so it runs as a JVM test. This is a direct port of the
Python `_build_stream_point`: `elapsed = now % cycle`, walk the list accumulating durations.

Returns `null` when there is nothing sensible to play (empty list, or all durations zero) —
the caller decides what to do, rather than this returning a fake index 0 that would silently
play the wrong thing.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cliftonia.fs42tv.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The clock rotation is what makes every channel feel live: you join a programme
 * already in progress, at the point it would be if it had been broadcasting all day.
 * Getting it wrong is not a crash - it is a channel that starts every clip from zero,
 * which is exactly the bug that plagued the box this was ported from.
 */
class ClockRotationTest {

    @Test
    fun `lands inside the first clip`() {
        val point = ClockRotation.playPointFor(listOf(100, 200, 300), 50)
        assertEquals(0, point!!.index)
        assertEquals(50.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `lands inside a later clip with the offset relative to that clip`() {
        val point = ClockRotation.playPointFor(listOf(100, 200, 300), 250)
        assertEquals(1, point!!.index)
        assertEquals("the offset must be relative to the clip on air, not to the whole cycle",
            150.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `wraps around the cycle`() {
        // cycle is 600; 1250 = two full cycles plus 50
        val point = ClockRotation.playPointFor(listOf(100, 200, 300), 1250)
        assertEquals("a channel must loop seamlessly, not stop at the end of its list",
            0, point!!.index)
        assertEquals(50.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `a boundary lands at the start of the next clip`() {
        val point = ClockRotation.playPointFor(listOf(100, 200), 100)
        assertEquals("an exact boundary belongs to the next clip, not the end of the previous",
            1, point!!.index)
        assertEquals(0.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `a single clip channel always plays that clip`() {
        val point = ClockRotation.playPointFor(listOf(300), 1000)
        assertEquals(0, point!!.index)
        assertEquals(100.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `an empty channel yields nothing to play`() {
        assertNull("returning index 0 for an empty list would index out of bounds downstream",
            ClockRotation.playPointFor(emptyList(), 100))
    }

    @Test
    fun `all-zero durations yield nothing rather than dividing by zero`() {
        assertNull("a modulo by a zero cycle would throw and take the channel down",
            ClockRotation.playPointFor(listOf(0, 0), 100))
    }

    @Test
    fun `a zero duration clip in the middle is skipped, not played for no time`() {
        val point = ClockRotation.playPointFor(listOf(100, 0, 200), 100)
        assertEquals("a zero-length clip can never be on air, so the clock belongs to the next",
            2, point!!.index)
        assertEquals(0.0, point.offsetSeconds, 0.001)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: compilation failure — `ClockRotation` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cliftonia.fs42tv.schedule

/** Where a channel is in its rotation: which clip, and how far into it. */
data class PlayPoint(val index: Int, val offsetSeconds: Double)

/**
 * Deterministic position in a channel's clip list, derived from the wall clock.
 *
 * Every device computes the same answer for the same instant with no coordination,
 * which is why the app needs no server at play time. Ported from the Python player's
 * `_build_stream_point`.
 */
object ClockRotation {

    /**
     * @param durations clip lengths in seconds, in playlist order
     * @param nowSeconds wall clock as a Unix timestamp
     * @return the clip and offset now on air, or null if the channel can never be on air
     */
    fun playPointFor(durations: List<Int>, nowSeconds: Long): PlayPoint? {
        val cycle = durations.sumOf { maxOf(it, 0).toLong() }
        if (cycle <= 0L) return null

        var elapsed = Math.floorMod(nowSeconds, cycle)
        for ((index, duration) in durations.withIndex()) {
            if (duration <= 0) continue
            if (elapsed < duration) return PlayPoint(index, elapsed.toDouble())
            elapsed -= duration
        }
        // Unreachable while cycle > 0, but a total rather than a crash if it ever is.
        return null
    }
}
```

`Math.floorMod` rather than `%`: Kotlin's `%` returns a negative result for a negative
left operand, and a clock skewed before the epoch would otherwise index backwards.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: 8 tests, all passing.

- [ ] **Step 5: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add app/src/main/java/com/cliftonia/fs42tv/schedule app/src/test
git commit -m "feat: compute the clock rotation position locally

- Create \`ClockRotation.kt\` derive the on-air clip and offset from the wall clock
- Create \`ClockRotationTest.kt\` cover wraparound, boundaries and degenerate playlists

Pure Kotlin with no Android imports, so it is tested on the JVM with no device. Every
device computes the same answer for the same instant with no coordination, which is why
the app needs no server at play time. Returns null rather than a fake index 0 when a
channel can never be on air."
```

---

### Task 3: Parse and cache the published dial

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/sync/DialContract.kt`
- Create: `app/src/main/java/com/cliftonia/fs42tv/sync/DialRepository.kt`
- Test: `app/src/test/java/com/cliftonia/fs42tv/sync/DialContractTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces:
  - `@Serializable data class Stream(val id: String?, val url: String, val duration: Int, val title: String)`
  - `@Serializable data class Channel(val number: Int, val name: String, val kind: String, val rotation: String?, val streams: List<Stream>)`
  - `@Serializable data class Dial(val generated: Long, val channels: List<Channel>)`
  - `@Serializable data class Tier(val video: String, val audio: String? = null, val expires: Long)`
  - `@Serializable data class UrlCache(val generated: Long, val urls: Map<String, Map<String, Tier>>)`
  - `object DialContract { fun parseDial(json: String): Dial; fun parseUrls(json: String): UrlCache }`
  - `class DialRepository(private val fetch: (String) -> String, private val cacheDir: File)`
    with `fun sync(baseUrl: String): Dial` and `fun cachedDial(): Dial?`

`fetch` is injected as a plain function so the repository is testable on the JVM with no
network and no Android.

- [ ] **Step 1: Capture real fixtures from the live server**

```bash
mkdir -p ~/Repos/fieldstation42-tv/app/src/test/resources
curl -s http://192.168.4.203:4243/channels.json \
  > ~/Repos/fieldstation42-tv/app/src/test/resources/channels-sample.json
curl -s http://192.168.4.203:4243/urls.json \
  > ~/Repos/fieldstation42-tv/app/src/test/resources/urls-sample.json
wc -c ~/Repos/fieldstation42-tv/app/src/test/resources/*.json
```

Expected: two files, roughly 500 KB and a few hundred KB. If the server is unreachable,
STOP and report — the whole point is testing against the real contract, and a hand-written
fixture would only prove the parser matches itself.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.cliftonia.fs42tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These parse the REAL published files, captured from the live server. A contract
 * mismatch is not a crash at the boundary - it is a dial that renders and will not
 * tune, discovered on a television with no debugger attached.
 */
class DialContractTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `parses the real published dial`() {
        val dial = DialContract.parseDial(fixture("channels-sample.json"))
        assertTrue("the real dial has around 111 channels; far fewer means a parse that silently dropped some",
            dial.channels.size > 50)
        assertTrue("channels must arrive sorted, because surfing walks the list in order",
            dial.channels.map { it.number } == dial.channels.map { it.number }.sorted())
    }

    @Test
    fun `a youtube channel carries ids and a clock rotation`() {
        val dial = DialContract.parseDial(fixture("channels-sample.json"))
        val youtube = dial.channels.first { it.kind == "youtube" }
        assertEquals("clock", youtube.rotation)
        assertNotNull("without an id the app cannot look up a pre-resolved URL",
            youtube.streams.first().id)
    }

    @Test
    fun `a live channel has no id and no rotation`() {
        val dial = DialContract.parseDial(fixture("channels-sample.json"))
        val live = dial.channels.first { it.kind == "live" }
        assertNull("a live stream has no video id and must not fake one", live.streams.first().id)
        assertNull("a live stream has no clock position to compute", live.rotation)
    }

    @Test
    fun `non-latin titles survive parsing`() {
        val dial = DialContract.parseDial(fixture("channels-sample.json"))
        val titles = dial.channels.flatMap { it.streams }.map { it.title }
        assertTrue("titles are UTF-8 and often non-Latin; mangling them would show as garbage on screen",
            titles.any { it.any { ch -> ch.code > 0x7F } })
    }

    @Test
    fun `parses the real url cache and its tiers`() {
        val cache = DialContract.parseUrls(fixture("urls-sample.json"))
        assertTrue("an empty cache would mean every tune pays a server round trip",
            cache.urls.isNotEmpty())
        val tiers = cache.urls.values.first()
        assertTrue("hd is the tier every device can play and must always be present",
            tiers.containsKey("hd"))
    }

    @Test
    fun `an unknown field does not break parsing`() {
        val json = """{"generated":1,"channels":[{"number":1,"name":"X","kind":"live",
            "rotation":null,"streams":[],"somethingNew":true}]}"""
        val dial = DialContract.parseDial(json)
        assertEquals("the server must be free to add fields without breaking every installed app",
            1, dial.channels.size)
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: compilation failure — `DialContract` does not exist.

- [ ] **Step 4: Write the implementation**

`DialContract.kt`:

```kotlin
package com.cliftonia.fs42tv.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Stream(
    val id: String? = null,
    val url: String,
    val duration: Int,
    val title: String = "",
)

@Serializable
data class Channel(
    val number: Int,
    val name: String,
    val kind: String,
    val rotation: String? = null,
    val streams: List<Stream> = emptyList(),
)

@Serializable
data class Dial(val generated: Long = 0, val channels: List<Channel> = emptyList())

@Serializable
data class Tier(val video: String, val audio: String? = null, val expires: Long = 0)

@Serializable
data class UrlCache(
    val generated: Long = 0,
    val urls: Map<String, Map<String, Tier>> = emptyMap(),
)

/**
 * The wire format published by the server.
 *
 * `ignoreUnknownKeys` is deliberate: the server must be able to add a field without
 * breaking every app already installed on a television, where updating means sideloading
 * an APK by hand.
 */
object DialContract {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseDial(text: String): Dial = json.decodeFromString(Dial.serializer(), text)

    fun parseUrls(text: String): UrlCache = json.decodeFromString(UrlCache.serializer(), text)
}
```

`DialRepository.kt`:

```kotlin
package com.cliftonia.fs42tv.sync

import java.io.File

/**
 * Fetches the published dial and keeps the last good copy on disk.
 *
 * `fetch` is injected rather than hard-wired so this is testable on the JVM with no
 * network and no Android runtime.
 */
class DialRepository(
    private val fetch: (String) -> String,
    private val cacheDir: File,
) {
    private val dialFile get() = File(cacheDir, "channels.json")
    private val urlsFile get() = File(cacheDir, "urls.json")

    /** Fetch both files and cache them. Throws if the server cannot be reached. */
    fun sync(baseUrl: String): Dial {
        val dialText = fetch("$baseUrl/channels.json")
        val urlsText = fetch("$baseUrl/urls.json")
        // Parse BEFORE writing: caching a malformed response would poison the fallback
        // that exists precisely for when the server is unavailable.
        val dial = DialContract.parseDial(dialText)
        DialContract.parseUrls(urlsText)
        cacheDir.mkdirs()
        dialFile.writeText(dialText)
        urlsFile.writeText(urlsText)
        return dial
    }

    fun cachedDial(): Dial? =
        runCatching { DialContract.parseDial(dialFile.readText()) }.getOrNull()

    fun cachedUrls(): UrlCache? =
        runCatching { DialContract.parseUrls(urlsFile.readText()) }.getOrNull()
}
```

- [ ] **Step 5: Add repository tests**

Append to `DialContractTest.kt`, above the closing brace:

```kotlin
    @Test
    fun `sync caches what it fetched`() {
        val dir = java.nio.file.Files.createTempDirectory("fs42").toFile()
        val repo = DialRepository(
            fetch = { url -> if (url.endsWith("channels.json")) fixture("channels-sample.json")
                             else fixture("urls-sample.json") },
            cacheDir = dir,
        )
        val dial = repo.sync("http://example")
        assertTrue(dial.channels.isNotEmpty())
        assertNotNull("without a cached copy the app is dead the moment the server is unreachable",
            repo.cachedDial())
    }

    @Test
    fun `a malformed response is not cached over a good one`() {
        val dir = java.nio.file.Files.createTempDirectory("fs42").toFile()
        val good = DialRepository({ if (it.endsWith("channels.json")) fixture("channels-sample.json")
                                    else fixture("urls-sample.json") }, dir)
        good.sync("http://example")
        val bad = DialRepository({ "{ this is not json" }, dir)
        runCatching { bad.sync("http://example") }
        assertTrue("a bad response must never destroy the last good cache",
            bad.cachedDial()!!.channels.isNotEmpty())
    }
```

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: 14 tests, all passing.

- [ ] **Step 7: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -m "feat: parse and cache the published dial

- Create \`DialContract.kt\` wire types and parsing for channels.json and urls.json
- Create \`DialRepository.kt\` fetch, validate then cache, with a last-good fallback
- Create \`DialContractTest.kt\` parse the REAL published files captured from the server

Tested against fixtures captured from the live server rather than hand-written ones, so
the tests prove the contract rather than proving the parser matches itself. Unknown keys
are ignored so the server can add fields without breaking an app that is updated by
sideloading an APK by hand."
```

---

### Task 4: Choose what to actually play

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/resolver/StreamResolver.kt`
- Test: `app/src/test/java/com/cliftonia/fs42tv/resolver/StreamResolverTest.kt`

**Interfaces:**
- Consumes: `Stream`, `Tier`, `UrlCache` from Task 3
- Produces:
  - `sealed interface Playable`
    - `data class Progressive(val videoUrl: String, val audioUrl: String?) : Playable`
    - `data class Hls(val url: String) : Playable`
    - `data class NeedsResolving(val videoId: String) : Playable`
  - `object StreamResolver { fun resolve(stream: Stream, cache: UrlCache?, preferUhd: Boolean, nowSeconds: Long): Playable }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import com.cliftonia.fs42tv.sync.UrlCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking the wrong source is rarely a crash. It is 480p on a 4K panel, silence where
 * there should be audio, or a round trip to the server that a cached URL would have
 * avoided - all of which look like "the app is bad" rather than a bug with a location.
 */
class StreamResolverTest {

    private val hd = Tier(video = "https://v/hd", audio = "https://a/hd", expires = 10_000)
    private val uhd = Tier(video = "https://v/uhd", audio = "https://a/uhd", expires = 10_000)
    private val yt = Stream(id = "abc12345678", url = "https://youtube.com/watch?v=abc12345678",
        duration = 100, title = "t")
    private val live = Stream(id = null, url = "https://x/stream.m3u8", duration = 600, title = "t")

    private fun cacheOf(vararg tiers: Pair<String, Tier>) =
        UrlCache(generated = 0, urls = mapOf("abc12345678" to tiers.toMap()))

    @Test
    fun `a live stream plays directly as HLS`() {
        val result = StreamResolver.resolve(live, null, preferUhd = false, nowSeconds = 0)
        assertEquals("a live stream needs no resolution and must not wait on the server",
            Hls("https://x/stream.m3u8"), result)
    }

    @Test
    fun `a 4K device gets the uhd tier`() {
        val result = StreamResolver.resolve(yt, cacheOf("hd" to hd, "uhd" to uhd),
            preferUhd = true, nowSeconds = 0)
        assertEquals(Progressive("https://v/uhd", "https://a/uhd"), result)
    }

    @Test
    fun `a 1080p device gets hd even when uhd exists`() {
        val result = StreamResolver.resolve(yt, cacheOf("hd" to hd, "uhd" to uhd),
            preferUhd = false, nowSeconds = 0)
        assertEquals("sending 4K to a 1080p device wastes bandwidth it may be paying for",
            Progressive("https://v/hd", "https://a/hd"), result)
    }

    @Test
    fun `a 4K device falls back to hd when there is no uhd tier`() {
        val result = StreamResolver.resolve(yt, cacheOf("hd" to hd), preferUhd = true, nowSeconds = 0)
        assertEquals("not every video offers 4K; refusing to play would be worse than 1080p",
            Progressive("https://v/hd", "https://a/hd"), result)
    }

    @Test
    fun `an expired tier is not used`() {
        val stale = Tier(video = "https://v/old", audio = "https://a/old", expires = 100)
        val result = StreamResolver.resolve(yt, cacheOf("hd" to stale),
            preferUhd = false, nowSeconds = 500)
        assertEquals("a signed URL past its expiry returns 403 and shows as a dead channel",
            NeedsResolving("abc12345678"), result)
    }

    @Test
    fun `a missing cache entry needs resolving`() {
        val result = StreamResolver.resolve(yt, UrlCache(), preferUhd = false, nowSeconds = 0)
        assertEquals(NeedsResolving("abc12345678"), result)
    }

    @Test
    fun `a null cache needs resolving rather than throwing`() {
        val result = StreamResolver.resolve(yt, null, preferUhd = false, nowSeconds = 0)
        assertTrue("before the first sync the app must still know what to ask for",
            result is NeedsResolving)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: compilation failure — `StreamResolver` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import com.cliftonia.fs42tv.sync.UrlCache

/** What the player should be handed. */
sealed interface Playable

/** Separate video and audio streams, as YouTube serves them above 360p. */
data class Progressive(val videoUrl: String, val audioUrl: String?) : Playable

/** A live HLS feed, played as-is. */
data class Hls(val url: String) : Playable

/** Nothing usable is cached; the caller must ask the server to resolve this id. */
data class NeedsResolving(val videoId: String) : Playable

/**
 * Decides what to play from what has already been resolved.
 *
 * Deliberately pure: it performs no I/O and makes no network call, so every branch is
 * testable on the JVM. Asking the server is represented as a RESULT, not performed here.
 */
object StreamResolver {

    /** Treat a tier as dead slightly before its stated expiry, since signing is not exact. */
    private const val SAFETY_MARGIN_SECONDS = 300L

    fun resolve(
        stream: Stream,
        cache: UrlCache?,
        preferUhd: Boolean,
        nowSeconds: Long,
    ): Playable {
        val id = stream.id ?: return Hls(stream.url)
        val tiers = cache?.urls?.get(id) ?: return NeedsResolving(id)

        val order = if (preferUhd) listOf("uhd", "hd") else listOf("hd")
        for (name in order) {
            val tier = tiers[name] ?: continue
            if (tier.expires - SAFETY_MARGIN_SECONDS <= nowSeconds) continue
            return Progressive(tier.video, tier.audio)
        }
        return NeedsResolving(id)
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: 21 tests, all passing.

Note the expiry test uses `expires = 100, now = 500`, which is dead by any margin. If you
add a case near the boundary, remember `SAFETY_MARGIN_SECONDS` is 300.

- [ ] **Step 5: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -m "feat: decide what to play from the resolved cache

- Create \`StreamResolver.kt\` choose a quality tier, pass HLS through, or ask for resolution
- Create \`StreamResolverTest.kt\` cover tier preference, expiry and absent cache

Pure and I/O free: asking the server is returned as a RESULT rather than performed, so
every branch is testable on the JVM. A tier is treated as dead five minutes before its
stated expiry, because signed URLs are not exact and a 403 shows as a dead channel."
```

---

### Task 5: Play one channel at the right moment

**Files:**
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`
- Create: `app/src/main/java/com/cliftonia/fs42tv/player/ChannelPlayer.kt`

**Interfaces:**
- Consumes: `ClockRotation` (Task 2), `DialRepository` (Task 3), `StreamResolver` (Task 4)
- Produces: a running app that plays a channel

This is the integration proof. There are no unit tests here — the value is demonstrating
the pieces work together on a real Android runtime, which is what the emulator is for.

- [ ] **Step 1: Write the player**

```kotlin
package com.cliftonia.fs42tv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive

/**
 * Turns a Playable into something ExoPlayer can start, at a given offset.
 *
 * The start position is passed to setMediaSource rather than applied as a seek afterwards.
 * That is deliberate: on the box this was ported from, seeking straight after starting
 * playback silently did nothing, because playback had not begun and the seek was dropped -
 * every channel then started its clip from 00:00. Media3 makes the start position part of
 * the load, so that class of bug cannot happen here.
 */
class ChannelPlayer(context: Context) {

    private val factory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    val exo: ExoPlayer = ExoPlayer.Builder(context).build()

    fun play(playable: Playable, startAtSeconds: Double) {
        val source = when (playable) {
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

            else -> return
        }
        exo.setMediaSource(source as MediaSource, (startAtSeconds * 1000).toLong())
        exo.prepare()
        exo.playWhenReady = true
    }

    fun release() = exo.release()
}
```

Add the HLS dependency to `app/build.gradle.kts`:

```kotlin
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
```

- [ ] **Step 2: Wire the activity**

Replace `MainActivity.kt`:

```kotlin
package com.cliftonia.fs42tv

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.media3.ui.PlayerView
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.resolver.StreamResolver
import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.sync.DialRepository
import java.net.URL
import kotlin.concurrent.thread

private const val SERVER = "http://192.168.4.203:4243"
private const val CHANNEL_NUMBER = 2

class MainActivity : Activity() {

    private var player: ChannelPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = PlayerView(this).apply { useController = false }
        setContentView(view)

        val player = ChannelPlayer(this).also { this.player = it }
        view.player = player.exo

        thread {
            val repo = DialRepository(
                fetch = { url -> URL(url).readText() },
                cacheDir = cacheDir,
            )
            val dial = runCatching { repo.sync(SERVER) }.getOrNull() ?: repo.cachedDial()
            val urls = repo.cachedUrls()
            val channel = dial?.channels?.firstOrNull { it.number == CHANNEL_NUMBER }
            if (channel == null) { Log.e("fs42", "channel $CHANNEL_NUMBER not on the dial"); return@thread }

            val now = System.currentTimeMillis() / 1000
            val point = ClockRotation.playPointFor(channel.streams.map { it.duration }, now)
            if (point == null) { Log.e("fs42", "${channel.name} has nothing on air"); return@thread }

            val stream = channel.streams[point.index]
            val playable = StreamResolver.resolve(stream, urls, preferUhd = false, nowSeconds = now)
            Log.i("fs42", "${channel.name}: clip ${point.index} at ${point.offsetSeconds}s -> $playable")

            runOnUiThread { player.play(playable, point.offsetSeconds) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
```

`android:usesCleartextTraffic="true"` must be added to the `<application>` tag in
`AndroidManifest.xml` — the server is plain HTTP on the LAN, and Android blocks cleartext
by default from API 28.

- [ ] **Step 3: Build and install**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
```

- [ ] **Step 4: Verify it actually plays**

```bash
sleep 20
adb logcat -d -s fs42:I ExoPlayerImpl:I | tail -20
adb exec-out screencap -p > /tmp/fs42tv-playing.png
```

Expected: an `fs42` log line naming the channel, the clip index and the offset, and a
screenshot showing **video, not a black rectangle**. Look at the screenshot. A black frame
with a healthy log means it is not rendering, and the task is not done.

If the emulator cannot reach `192.168.4.203`, check that the Mac itself can
(`curl -s http://192.168.4.203:4243/channels.json | head -c 80`). The emulator shares the
host's network, so if the Mac can reach it the emulator should too.

- [ ] **Step 5: Prove the offset is honoured**

```bash
adb shell dumpsys media_session | grep -i position || true
adb logcat -d -s fs42:I | grep "at "
```

Cross-check the logged offset against the clock by hand: the clip's offset should be a
plausible fraction of its duration, and re-launching a minute later should give an offset
about 60 seconds larger (or a new clip index if it rolled over). Record both readings in
your report — this is the property the whole architecture exists to deliver.

- [ ] **Step 6: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -m "feat: play a channel at its clock position

- Create \`ChannelPlayer.kt\` build an ExoPlayer source and start it at an offset
- Update \`MainActivity.kt\` sync the dial, compute the position, resolve and play
- Update \`AndroidManifest.xml\` allow cleartext for the LAN server

Video and audio arrive as separate streams above 360p and are merged rather than
sequenced. The start position is given to setMediaSource rather than applied as a seek
afterwards: on the box this was ported from, a seek issued before playback began was
silently dropped and every channel started its clip from 00:00."
```

---

## Self-Review

**Spec coverage.** The design's phase 1 is "sync the two files, play one hard-coded channel
at the correct clock offset, proving the data contract, the resolver and Media3 together."
Task 3 covers sync and the contract, Task 2 the clock, Task 4 the resolver, Task 5 Media3
and the integration. Task 1 exists to de-risk the toolchain first.

**Deliberately not in this plan.** No channel surfing, banner, guide, direct entry, preload
manager, device capability detection or 4K tier selection — all are later phases. `preferUhd`
is threaded through as a parameter but hard-coded `false` at the call site, so the seam
exists without the detection work.

**Type consistency.** `Stream`, `Tier` and `UrlCache` are defined once in Task 3 and consumed
unchanged by Tasks 4 and 5. `PlayPoint.offsetSeconds` is a `Double` throughout and is
converted to milliseconds only at the ExoPlayer boundary. `Playable` and its three subtypes
are defined in Task 4 and matched exhaustively in Task 5.

**Known soft spot.** Task 5's verification is partly visual — read a screenshot, read a log
line. That is deliberate: the box taught us that status fields lie and only the framebuffer
tells the truth. Task 5 has no unit tests because its value is integration, and a mocked
ExoPlayer would prove nothing about whether video appears on a screen.
