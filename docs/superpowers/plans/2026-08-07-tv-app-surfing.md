# TV App Surfing Implementation Plan (Phase 2a)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change channels with the D-pad and have each one play at its correct clock
position, resuming on the channel you left it on — with a server round trip covering the 54%
of clips that have no cached URL.

**Architecture:** Extract the tuning composition that currently sits inline in
`MainActivity.onCreate` into a pure `tune()` function, add a pure `DialNavigator` for
position, and add a `ServerResolver` for cache misses. All three are pure Kotlin tested on
the JVM; only the activity and persistence touch Android.

**Tech Stack:** Kotlin, Media3, kotlinx-serialization, JUnit 4 on the JVM. No new dependencies.

## Global Constraints

- **Repo:** `~/Repos/fieldstation42-tv`, branch `main`, HEAD `00c5284`. All work is LOCAL.
- Build with `./gradlew assembleDebug`; test with `./gradlew :app:testDebugUnitTest`.
  26 tests pass today — every task must leave the suite green.
- Emulator AVD `fs42tv` (android-34 TV, arm64). If not running:
  `~/Library/Android/sdk/emulator/emulator -avd fs42tv -no-snapshot &` then wait for
  `adb shell getprop sys.boot_completed` to return `1`.
- Publisher at `http://192.168.4.203:4243` — `channels.json`, `urls.json`, `GET /resolve?v=<id>`.
- **`schedule`, `sync` parsing and `resolver` must stay free of Android imports.** That
  boundary is what keeps the product logic JVM-testable; a leak there is an Important defect.
- Cleartext is permitted ONLY for `192.168.4.203` via `res/xml/network_security_config.xml`.
  Do not add a blanket `usesCleartextTraffic`, and do not widen that config.
- Test convention: assertion messages state the **consequence** of failure.
- **Test discrimination is mandatory.** Three "test cannot fail" defects were found in phase 1.
  For every test you write, ask: *what mutation would this catch?* If you cannot name one, the
  fixture is not adversarial enough. Where a plan step says to verify discrimination against a
  deliberately broken implementation, actually do it.
- Commit format: `<prefix>: short description`, then `- Action \`Filename.kt\` what changed`.
  Prefixes: feat fix refactor chore test docs perf. Actions: Create Delete Update.
- Never mention AI assistance in commits, code or comments.
- **Measured facts about the live data**, so you are not surprised: 111 channels, 2,918 YouTube
  clips, but only **1,352 cached URL entries (46%)**, all of them `hd` — there is no `uhd`
  tier yet. A cache miss is the common case, not an edge case.

---

### Task 1: Extract the tuning seam

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/tune/Tuner.kt`
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`
- Test: `app/src/test/java/com/cliftonia/fs42tv/tune/TunerTest.kt`

**Interfaces:**
- Consumes: `Channel`, `Stream`, `UrlCache` (sync), `ClockRotation` (schedule),
  `StreamResolver`, `Playable` (resolver)
- Produces:
  - `data class Tuned(val channel: Channel, val streamIndex: Int, val stream: Stream, val playable: Playable, val offsetSeconds: Double)`
  - `object Tuner { fun tune(channel: Channel, cache: UrlCache?, nowSeconds: Long, preferUhd: Boolean = false): Tuned? }`

The composition `ClockRotation → streams[index] → StreamResolver.resolve` currently lives
inline in `MainActivity.onCreate`. Phase 2b's preload manager, banner and reverse slot all
need that same composition for channels other than the current one, so it becomes a pure
function now rather than being duplicated three times later.

Behaviour, carried over from the phase-1 activity and from its review findings:
- A channel with `rotation == "clock"` gets its position from `ClockRotation`.
- Any other channel — live feeds carry a placeholder `duration` of 600 per stream, which is
  not a clip length — plays stream 0 at offset 0.
- A channel whose `kind == "live"` yields `Hls(stream.url)` directly, using the server's
  discriminator rather than inferring from a null id.
- Returns `null` when the channel can never be on air (no streams, or `ClockRotation` says so).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import com.cliftonia.fs42tv.sync.UrlCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tuning seam. Phase 2b's preload manager, banner and reverse slot all compose the same
 * three steps, so getting this wrong is not one bug - it is the same bug in three places.
 */
class TunerTest {

    private fun ytChannel(vararg durations: Int) = Channel(
        number = 9, name = "AFL", kind = "youtube", rotation = "clock",
        streams = durations.mapIndexed { i, d ->
            Stream(id = "vid$i".padEnd(11, 'x'), url = "https://youtube.com/watch?v=vid$i",
                duration = d, title = "clip $i")
        },
    )

    private val liveChannel = Channel(
        number = 103, name = "ABC TV QLD", kind = "live", rotation = null,
        streams = listOf(Stream(id = null, url = "https://x/abc.m3u8", duration = 600,
            title = "ABC")),
    )

    private fun cacheFor(index: Int) = UrlCache(
        urls = mapOf("vid$index".padEnd(11, 'x') to
            mapOf("hd" to Tier(video = "https://v/$index", audio = "https://a/$index",
                expires = 9_999_999_999))),
    )

    @Test
    fun `picks the clip the clock says is on air`() {
        val tuned = Tuner.tune(ytChannel(100, 200, 300), cacheFor(1), nowSeconds = 250)
        assertEquals("tuning the wrong clip means the channel is not where the schedule says",
            1, tuned!!.streamIndex)
        assertEquals(150.0, tuned.offsetSeconds, 0.001)
        assertEquals(Progressive("https://v/1", "https://a/1"), tuned.playable)
    }

    @Test
    fun `a live channel ignores the clock entirely`() {
        val tuned = Tuner.tune(liveChannel, null, nowSeconds = 5000)
        assertEquals("a live channel carries a placeholder duration, so a computed offset " +
            "would seek into the middle of a live window", 0.0, tuned!!.offsetSeconds, 0.001)
        assertEquals(Hls("https://x/abc.m3u8"), tuned.playable)
    }

    @Test
    fun `kind decides live, not a null stream id`() {
        // A youtube channel whose id is missing must NOT be treated as live: handing a watch
        // page to an HLS parser produces a confusing failure far from the cause.
        val oddball = Channel(number = 5, name = "Odd", kind = "youtube", rotation = "clock",
            streams = listOf(Stream(id = null, url = "https://youtube.com/watch?v=x",
                duration = 100, title = "t")))
        val tuned = Tuner.tune(oddball, null, nowSeconds = 10)
        assertTrue("the server publishes a discriminator; guessing from a null id contradicts it",
            tuned!!.playable !is Hls)
    }

    @Test
    fun `a cache miss reports what needs resolving rather than failing`() {
        val tuned = Tuner.tune(ytChannel(100), UrlCache(), nowSeconds = 10)
        assertEquals("a miss is the common case at 46% coverage and must stay recoverable",
            NeedsResolving("vid0xxxxxxx"), tuned!!.playable)
    }

    @Test
    fun `an empty channel yields nothing to tune`() {
        val empty = Channel(number = 1, name = "Empty", kind = "youtube", rotation = "clock",
            streams = emptyList())
        assertNull("returning a Tuned with no stream would crash the caller downstream",
            Tuner.tune(empty, null, nowSeconds = 10))
    }

    @Test
    fun `a non-clock youtube channel plays its first clip from the start`() {
        val noRotation = ytChannel(100, 200).copy(rotation = null)
        val tuned = Tuner.tune(noRotation, null, nowSeconds = 5000)
        assertEquals("without a clock rotation there is no schedule to join mid-way",
            0, tuned!!.streamIndex)
        assertEquals(0.0, tuned.offsetSeconds, 0.001)
    }

    @Test
    fun `the tuned stream matches the tuned index`() {
        val tuned = Tuner.tune(ytChannel(100, 200, 300), null, nowSeconds = 250)
        assertEquals("a mismatched index and stream would show one programme and title another",
            tuned!!.channel.streams[tuned.streamIndex], tuned.stream)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: compilation failure — `Tuner` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.StreamResolver
import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.UrlCache

/** Everything needed to start a channel: which clip, how far in, and what to hand the player. */
data class Tuned(
    val channel: Channel,
    val streamIndex: Int,
    val stream: Stream,
    val playable: Playable,
    val offsetSeconds: Double,
)

/**
 * Composes the three steps that decide what a channel is showing right now.
 *
 * Pure and I/O free, so the preload manager, the banner and the reverse slot can all call it
 * for channels other than the one on screen - and so every branch is testable on the JVM.
 */
object Tuner {

    fun tune(
        channel: Channel,
        cache: UrlCache?,
        nowSeconds: Long,
        preferUhd: Boolean = false,
    ): Tuned? {
        val streams = channel.streams
        if (streams.isEmpty()) return null

        // Only a clock-rotating channel has a schedule to join part-way through. Live feeds
        // carry a placeholder duration of 600 per stream, so computing a position from it
        // would seek an arbitrary distance into a live window.
        val point = if (channel.rotation == "clock") {
            ClockRotation.playPointFor(streams.map { it.duration }, nowSeconds) ?: return null
        } else {
            null
        }

        val index = point?.index ?: 0
        val offset = point?.offsetSeconds ?: 0.0
        val stream = streams[index]

        // The server publishes an explicit discriminator; trust it rather than inferring from
        // a null id, so a youtube clip with a missing id never reaches an HLS parser.
        val playable: Playable = if (channel.kind == "live") {
            Hls(stream.url)
        } else {
            StreamResolver.resolve(stream, cache, preferUhd, nowSeconds)
        }

        return Tuned(channel, index, stream, playable, offset)
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: 33 tests, all passing (26 existing plus 7 new).

- [ ] **Step 5: Use the seam from the activity**

In `MainActivity`, replace the inline composition (the `ClockRotation.playPointFor` call, the
`channel.kind` branch, the `streams[point.index]` lookup and the `StreamResolver.resolve` call)
with a single `Tuner.tune(channel, urls, now)` and use the returned `Tuned`. Keep the existing
log line, but log from the `Tuned` fields so it still reports channel name, clip index and offset.

Behaviour must be identical — this is a refactor, not a change.

- [ ] **Step 6: Verify nothing regressed on the emulator**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb logcat -d -s fs42:I fs42:W | tail -10
adb exec-out screencap -p > /tmp/fs42tv-task1.png
```

Expected: the same kind of log line as before, and a screenshot showing video. **Look at the
screenshot.** Channel 2 has only 13 of 52 clips cached, so a `NeedsResolving` warning is a
legitimate outcome here — if you get one, relaunch a few times until you land on a cached clip,
and say in your report how many attempts it took. That number is useful evidence for Task 3.

- [ ] **Step 7: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -m "refactor: extract the tuning seam

- Create \`Tuner.kt\` compose clock position, stream choice and resolution as one pure function
- Create \`TunerTest.kt\` cover clock, live, cache miss and empty channels
- Update \`MainActivity.kt\` tune through the seam instead of composing inline

The preload manager, banner and reverse slot all need this same composition for channels
other than the one on screen, so it becomes a pure function now rather than being duplicated
three times later. Live channels bypass the clock because their duration is a placeholder."
```

---

### Task 2: Know where you are on the dial

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/tune/DialNavigator.kt`
- Test: `app/src/test/java/com/cliftonia/fs42tv/tune/DialNavigatorTest.kt`

**Interfaces:**
- Consumes: `Channel` (sync)
- Produces:
  - `class DialNavigator(private val channels: List<Channel>, startNumber: Int? = null)`
  - `val current: Channel`, `fun up(): Channel`, `fun down(): Channel`,
    `fun jumpTo(number: Int): Channel?`, `val currentNumber: Int`

Pure Kotlin. Persistence is the activity's job in Task 4 — this only knows position.

Surfing walks the LIST in order, not the numbers, so gaps in numbering cost nothing. Both
directions wrap.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.sync.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Position on the dial. Surfing is the primary way anyone uses this, so an off-by-one or a
 * failure to wrap is not a subtle bug - it is the channel button not working.
 */
class DialNavigatorTest {

    private fun dial(vararg numbers: Int) = numbers.map {
        Channel(number = it, name = "ch$it", kind = "youtube", rotation = "clock",
            streams = emptyList())
    }

    @Test
    fun `starts at the first channel when no start is given`() {
        assertEquals(2, DialNavigator(dial(2, 9, 63)).currentNumber)
    }

    @Test
    fun `starts at the remembered channel`() {
        assertEquals("last-channel recall is the difference between a TV and a media player",
            63, DialNavigator(dial(2, 9, 63), startNumber = 63).currentNumber)
    }

    @Test
    fun `a remembered channel that has left the dial falls back to the first`() {
        assertEquals("the nightly conveyor can retire a channel; recall must not strand the app",
            2, DialNavigator(dial(2, 9, 63), startNumber = 999).currentNumber)
    }

    @Test
    fun `up moves to the next channel in list order`() {
        val nav = DialNavigator(dial(2, 9, 63))
        assertEquals(9, nav.up().number)
        assertEquals(63, nav.up().number)
    }

    @Test
    fun `up wraps from the end to the start`() {
        val nav = DialNavigator(dial(2, 9, 63), startNumber = 63)
        assertEquals("a dial that stops at the end is not a dial", 2, nav.up().number)
    }

    @Test
    fun `down wraps from the start to the end`() {
        val nav = DialNavigator(dial(2, 9, 63))
        assertEquals(63, nav.down().number)
    }

    @Test
    fun `surfing walks list order, not channel numbers`() {
        // Numbers are sparse and non-contiguous in the real dial; stepping numerically would
        // land on channels that do not exist.
        val nav = DialNavigator(dial(2, 40, 41, 900))
        assertEquals(40, nav.up().number)
        assertEquals(41, nav.up().number)
        assertEquals(900, nav.up().number)
    }

    @Test
    fun `jumpTo moves to a channel by number`() {
        val nav = DialNavigator(dial(2, 9, 63))
        assertEquals(63, nav.jumpTo(63)!!.number)
        assertEquals("a jump must move the position, not just report a channel",
            63, nav.currentNumber)
    }

    @Test
    fun `jumpTo an unknown number changes nothing`() {
        val nav = DialNavigator(dial(2, 9, 63))
        assertNull(nav.jumpTo(404))
        assertEquals("a failed jump must leave the viewer where they were, not reset the dial",
            2, nav.currentNumber)
    }

    @Test
    fun `a single channel dial wraps to itself`() {
        val nav = DialNavigator(dial(7))
        assertEquals(7, nav.up().number)
        assertEquals(7, nav.down().number)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: compilation failure — `DialNavigator` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.sync.Channel

/**
 * Where the viewer is on the dial.
 *
 * Steps through the LIST rather than the numbers: the real dial is sparse and non-contiguous,
 * so stepping numerically would land on channels that do not exist. Both directions wrap,
 * because a dial that stops at the end is not a dial.
 *
 * Pure: persistence belongs to the caller.
 */
class DialNavigator(private val channels: List<Channel>, startNumber: Int? = null) {

    init {
        require(channels.isNotEmpty()) { "a dial with no channels cannot be navigated" }
    }

    private var index: Int = channels.indexOfFirst { it.number == startNumber }
        .let { if (it >= 0) it else 0 }

    val current: Channel get() = channels[index]
    val currentNumber: Int get() = current.number

    fun up(): Channel {
        index = (index + 1) % channels.size
        return current
    }

    fun down(): Channel {
        index = (index - 1 + channels.size) % channels.size
        return current
    }

    /** Move to a channel by number, or return null and stay put if it is not on the dial. */
    fun jumpTo(number: Int): Channel? {
        val found = channels.indexOfFirst { it.number == number }
        if (found < 0) return null
        index = found
        return current
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: 43 tests, all passing (33 plus 10 new).

- [ ] **Step 5: Verify a test actually discriminates**

Temporarily change `down()` to `(index - 1) % channels.size` — dropping the `+ channels.size`
that makes the modulo safe for a negative operand. Run the tests.

Expected: `down wraps from the start to the end` FAILS (Kotlin's `%` returns a negative index,
throwing on the list access). Restore the correct version and confirm the tests pass again.
Report both outcomes, and confirm `git status` is clean afterwards.

- [ ] **Step 6: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -m "feat: track position on the dial

- Create \`DialNavigator.kt\` step through channels in list order, wrapping both ways
- Create \`DialNavigatorTest.kt\` cover wrapping, recall and sparse numbering

Steps through the list rather than the numbers: the dial is sparse, so stepping numerically
would land on channels that do not exist. A remembered channel that has since left the dial
falls back to the first rather than stranding the app."
```

---

### Task 3: Resolve a cache miss from the server

**Files:**
- Create: `app/src/main/java/com/cliftonia/fs42tv/resolver/ServerResolver.kt`
- Test: `app/src/test/java/com/cliftonia/fs42tv/resolver/ServerResolverTest.kt`

**Interfaces:**
- Consumes: `Tier` (sync), `Playable`/`Progressive` (resolver)
- Produces:
  - `class ServerResolver(private val fetch: (String) -> String, private val baseUrl: String)`
  - `fun resolve(videoId: String, preferUhd: Boolean = false): Progressive?`

`fetch` is injected so this is testable on the JVM with no network.

This is the single most valuable task in the plan. Only 46% of clips have a cached URL, so
without it more than half of all tunes end in a logged warning and a black screen. The server
endpoint already exists and works.

The response shape is `{"id": "...", "hd": {"video": ..., "audio": ..., "expires": ...}, "uhd": {...}}`
where `uhd` may be absent. Note the `id` key sits alongside the tiers, so parsing must not
assume every value is a tier object.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback for a cache miss. At 46% cache coverage this path runs more often than the
 * cached one, so a failure here is not an edge case - it is most of the dial going dark.
 */
class ServerResolverTest {

    private val hdOnly = """{"id":"abc12345678",
        "hd":{"video":"https://v/hd","audio":"https://a/hd","expires":9999999999}}"""

    private val bothTiers = """{"id":"abc12345678",
        "hd":{"video":"https://v/hd","audio":"https://a/hd","expires":9999999999},
        "uhd":{"video":"https://v/uhd","audio":"https://a/uhd","expires":9999999999}}"""

    private fun resolver(body: String, capture: MutableList<String>? = null) =
        ServerResolver(fetch = { url -> capture?.add(url); body }, baseUrl = "http://server")

    @Test
    fun `returns the hd tier`() {
        assertEquals(Progressive("https://v/hd", "https://a/hd"),
            resolver(hdOnly).resolve("abc12345678"))
    }

    @Test
    fun `a 4K device prefers uhd when the server offers it`() {
        assertEquals(Progressive("https://v/uhd", "https://a/uhd"),
            resolver(bothTiers).resolve("abc12345678", preferUhd = true))
    }

    @Test
    fun `a 1080p device takes hd even when uhd is offered`() {
        assertEquals("sending 4K to a 1080p device wastes bandwidth it may be paying for",
            Progressive("https://v/hd", "https://a/hd"),
            resolver(bothTiers).resolve("abc12345678", preferUhd = false))
    }

    @Test
    fun `a 4K device falls back to hd when the server has no uhd`() {
        assertEquals("not every video offers 4K; refusing to play would be worse than 1080p",
            Progressive("https://v/hd", "https://a/hd"),
            resolver(hdOnly).resolve("abc12345678", preferUhd = true))
    }

    @Test
    fun `the id key alongside the tiers does not break parsing`() {
        assertTrue("the endpoint returns id next to the tiers; treating it as a tier would throw",
            resolver(hdOnly).resolve("abc12345678") != null)
    }

    @Test
    fun `asks the server for the right video`() {
        val urls = mutableListOf<String>()
        resolver(hdOnly, urls).resolve("abc12345678")
        assertEquals("http://server/resolve?v=abc12345678", urls.single())
    }

    @Test
    fun `a server error yields null rather than throwing`() {
        val failing = ServerResolver(fetch = { throw java.io.IOException("unreachable") },
            baseUrl = "http://server")
        assertNull("an unreachable server must skip the clip, not crash the app",
            failing.resolve("abc12345678"))
    }

    @Test
    fun `a malformed response yields null rather than throwing`() {
        assertNull("a half-written response must not take the player down",
            resolver("{ not json").resolve("abc12345678"))
    }

    @Test
    fun `a 404 body with no tiers yields null`() {
        assertNull("the server returns 404 when it cannot resolve; that is a skip, not a crash",
            resolver("""{"detail":"could not resolve"}""").resolve("abc12345678"))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: compilation failure — `ServerResolver` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.sync.Tier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Asks the publisher to resolve a clip the cache missed.
 *
 * At 46% cache coverage this runs more often than the cached path. `fetch` is injected so the
 * whole class is testable on the JVM with no network.
 *
 * Every failure returns null rather than throwing: the caller's correct response to "cannot
 * resolve" is to skip the clip, and an exception here would take the player down instead.
 */
class ServerResolver(
    private val fetch: (String) -> String,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun resolve(videoId: String, preferUhd: Boolean = false): Progressive? {
        val body = runCatching { fetch("$baseUrl/resolve?v=$videoId") }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

        val order = if (preferUhd) listOf("uhd", "hd") else listOf("hd")
        for (name in order) {
            val tier = tierAt(root, name) ?: continue
            return Progressive(tier.video, tier.audio)
        }
        return null
    }

    /** The response carries `id` alongside the tiers, so a non-object value is expected. */
    private fun tierAt(root: JsonObject, name: String): Tier? {
        val element = root[name] ?: return null
        return runCatching { json.decodeFromJsonElement(Tier.serializer(), element) }.getOrNull()
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `cd ~/Repos/fieldstation42-tv && ./gradlew :app:testDebugUnitTest`
Expected: 52 tests, all passing (43 plus 9 new).

- [ ] **Step 5: Prove it works against the real server**

```bash
cd ~/Repos/fieldstation42-tv
ID=$(curl -s http://192.168.4.203:4243/channels.json | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(next(s['id'] for c in d['channels'] if c['kind']=='youtube' for s in c['streams'] if s['id']))")
echo "resolving $ID"
curl -s -w "\nHTTP %{http_code}\n" "http://192.168.4.203:4243/resolve?v=$ID" | head -c 400
```

Expected: HTTP 200 and a JSON body containing an `hd` object with `video`, `audio` and
`expires`. Record the actual shape in your report — if it differs from what the tests assume,
say so loudly rather than adjusting the tests to match a guess.

- [ ] **Step 6: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -m "feat: resolve a cache miss from the server

- Create \`ServerResolver.kt\` ask the publisher to resolve a clip the cache missed
- Create \`ServerResolverTest.kt\` cover tier preference, the id key and every failure path

Only 46% of clips on the dial have a cached URL, so this path runs more often than the
cached one. Every failure returns null rather than throwing: the correct response to
'cannot resolve' is to skip the clip, not to take the player down."
```

---

### Task 4: Surf the dial

**Files:**
- Modify: `app/src/main/java/com/cliftonia/fs42tv/MainActivity.kt`

**Interfaces:**
- Consumes: `Tuner` (Task 1), `DialNavigator` (Task 2), `ServerResolver` (Task 3),
  `DialRepository`, `ChannelPlayer`
- Produces: an app you can actually surf

No unit tests: the value is integration on a real runtime, and the pure pieces are already
covered by 52 JVM tests.

Behaviour:
- D-pad **up** and **down** change channel; **the same key codes a TV remote sends**
  (`KEYCODE_DPAD_UP` / `KEYCODE_DPAD_DOWN`, plus `KEYCODE_CHANNEL_UP` / `KEYCODE_CHANNEL_DOWN`
  which real TV remotes send instead).
- Tuning a channel whose `playable` is `NeedsResolving` asks `ServerResolver`; if that also
  fails, log clearly and leave the screen as it is rather than stopping playback.
- The current channel number is persisted and restored on next launch.

- [ ] **Step 1: Wire it up**

Replace `MainActivity` with a version that:

1. Reads the remembered channel number from `SharedPreferences` (`getSharedPreferences("fs42", MODE_PRIVATE)`,
   key `"channel"`, default `-1` meaning "no memory").
2. Syncs the dial on a background thread, builds a `DialNavigator(dial.channels, remembered.takeIf { it > 0 })`.
3. Has a `tuneTo(channel: Channel)` that calls `Tuner.tune(channel, urls, System.currentTimeMillis()/1000)`,
   and when the result's `playable` is `NeedsResolving`, calls `ServerResolver.resolve(videoId)`
   on the background thread and substitutes the returned `Progressive`. Then plays on the UI thread.
4. Overrides `onKeyDown` to map `KEYCODE_DPAD_UP`/`KEYCODE_CHANNEL_UP` to `navigator.up()` and
   `KEYCODE_DPAD_DOWN`/`KEYCODE_CHANNEL_DOWN` to `navigator.down()`, tuning the result and
   returning `true`; anything else defers to `super`.
5. Persists `navigator.currentNumber` to `SharedPreferences` on every successful tune.
6. Logs each tune as `channel <number> <name>: clip <index> at <offset>s -> <playable class>`.

Keep all network and resolution work off the UI thread. A single-thread executor is fine and
simpler to reason about than raw threads — it also means a rapid burst of channel presses
queues rather than racing.

- [ ] **Step 2: Build and install**

```bash
cd ~/Repos/fieldstation42-tv
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am force-stop com.cliftonia.fs42tv
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
```

- [ ] **Step 3: Surf, and look at what happens**

```bash
for i in 1 2 3 4 5; do
  adb shell input keyevent KEYCODE_DPAD_UP
  sleep 12
  adb exec-out screencap -p > /tmp/fs42tv-surf-$i.png
done
adb logcat -d -s fs42:I fs42:W | tail -30
```

Expected: five log lines showing five DIFFERENT channel numbers and names, and five
screenshots. **Look at every screenshot.** Report for each: the channel it logged, and what
the image actually shows — video, the stand-by state, or black.

It is entirely acceptable for some channels not to play. What matters is that the channel
CHANGES each time and each failure is logged with a reason. A silent black screen is a defect;
a logged "server could not resolve" is the system working as designed.

- [ ] **Step 4: Prove last-channel recall**

```bash
adb logcat -d -s fs42:I | tail -1          # note the channel number
adb shell am force-stop com.cliftonia.fs42tv
adb logcat -c
adb shell am start -n com.cliftonia.fs42tv/.MainActivity
sleep 20
adb logcat -d -s fs42:I | head -3          # must be the SAME channel number
```

Expected: the app resumes on the channel it was left on. Record both numbers verbatim. If they
differ, recall is broken and the task is not done.

- [ ] **Step 5: Prove down works and wraps**

```bash
adb shell input keyevent KEYCODE_DPAD_DOWN
sleep 10
adb logcat -d -s fs42:I | tail -1
```

Expected: the channel moves backwards through the dial. Also confirm from Task 2's tests that
wrapping is covered — you need not force a wrap on the device.

- [ ] **Step 6: Commit**

```bash
cd ~/Repos/fieldstation42-tv
git add -A
git commit -m "feat: surf the dial with the D-pad

- Update \`MainActivity.kt\` navigate channels, resolve misses from the server, remember position

Channel up and down accept both the D-pad codes an emulator sends and the channel codes a
real TV remote sends. A cache miss falls back to the server rather than showing nothing, and
a failure to resolve leaves the current picture up rather than stopping playback. The current
channel is persisted on every successful tune."
```

---

## Self-Review

**Spec coverage.** The design's phase 2 is "channel up/down, banner, last-channel recall, the
preload manager and the reverse slot". This plan (2a) covers channel up/down and recall, plus
the `/resolve` fallback that the degradation ladder specifies. The banner, preload manager and
reverse slot are plan 2b — they all depend on Task 1's seam, which is why that comes first.

**Deliberately not in this plan.** No banner, no preloading, no reverse slot, no direct channel
entry, no device capability detection. `preferUhd` remains a parameter hard-coded `false` at
the call sites, because the server publishes no `uhd` tier yet — the seam exists, unfed.

**Type consistency.** `Tuned` is defined in Task 1 and consumed in Task 4. `Progressive` comes
from the existing resolver package and is produced by both `StreamResolver` and
`ServerResolver`, so Task 4 substitutes one for the other without a conversion.
`DialNavigator.currentNumber` is the `Int` persisted in Task 4.

**Known soft spot.** Task 4's verification is visual and manual, as Task 5 of phase 1 was.
That is deliberate — the pure logic carries 52 JVM tests, and what remains genuinely needs
eyes on a screen. The one thing I have asked for explicitly is that every failure be *logged
with a reason*, so "it didn't play" can always be distinguished from "it played nothing
silently".
