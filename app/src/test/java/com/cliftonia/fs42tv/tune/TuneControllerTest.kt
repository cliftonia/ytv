package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.resolver.ClipResolver
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.RefusalLedger
import com.cliftonia.fs42tv.schedule.Timetable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The supersede rules, on the JVM at last.
 *
 * Every behaviour here was previously only observable as a race on the television: a burst of
 * presses collapsing to the last one, a stale tune refused permission to paint, a failed tune
 * refused permission to claim it is on air. The executors are hand-cranked queues so a test can
 * put a keypress exactly between any two steps of a tune - the one thing the device never lets
 * you do deliberately.
 */
class TuneControllerTest {

    /** An executor whose work runs only when the test turns the crank. */
    private class Crank : java.util.concurrent.Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun turn() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private class FakeResolver(
        var answer: (String) -> ClipResolver.Resolved? = { id ->
            ClipResolver.Resolved(
                Progressive("https://v/$id", "https://a/$id"), expiresAtSeconds = 900_000, "hd")
        },
    ) : ClipResolver {
        val asked = mutableListOf<String>()

        override fun resolveDetailed(
            videoId: String, nowSeconds: Long, ladder: List<String>, refused: Set<String>,
        ): ClipResolver.Resolved? {
            asked.add(videoId)
            return answer(videoId)
        }
    }

    private class Fixture(val timetable: Timetable = Timetable.PLAIN) {
        var now = 50L
        val cards = mutableListOf<Int>()
        val work = Crank()
        val ui = Crank()
        val prefetch = Crank()
        val resolver = FakeResolver()
        val blanked = mutableListOf<Int>()
        val painted = mutableListOf<Pair<Int, Playable>>()
        val unavailable = mutableListOf<Int>()
        val remembered = mutableListOf<Int>()
        var navigator: DialNavigator? = null

        val ledger = RefusalLedger(nowElapsedSeconds = { 0 })
        val tune = TuneController(TuneController.Deps(
            executor = work,
            prefetchExecutor = prefetch,
            resolver = resolver,
            ledger = ledger,
            urls = null,
            ladder = { listOf("hd", "sd") },
            navigator = { navigator },
            nowSeconds = { now },
            elapsedMillis = { 1_000 },
            halted = { false },
            runOnUi = { block -> ui.execute { block() } },
            rememberChannel = { remembered.add(it) },
            screen = TuneController.Screen(
                startBlank = { blanked.add(it.number) },
                paint = { tuned, playable, _, _, _ -> painted.add(tuned.channel.number to playable) },
                channelUnavailable = { unavailable.add(it.number) },
                card = { cards.add(it.channel.number) },
            ),
            timetable = timetable,
        ))

        /** Run everything that is queued, in the order the device would: work, then UI. */
        fun settle() {
            work.turn()
            ui.turn()
        }
    }

    private fun channel(number: Int, vararg ids: String) = Channel(
        number = number,
        name = "CH$number",
        kind = "youtube",
        rotation = "clock",
        streams = ids.map { Stream(id = it, url = "https://youtube.com/watch?v=$it", duration = 100) },
    )

    @Test
    fun `a successful tune blanks, commits and paints`() {
        val f = Fixture()
        f.tune.surfTo(channel(3, "aaaaaaaaaaa"))
        f.settle()
        assertEquals(listOf(3), f.blanked)
        assertEquals(3, f.painted.single().first)
        assertEquals("only a painted tune may claim to be on air", 3, f.tune.onAir?.channel?.number)
        assertEquals("and only a painted tune may become the resume channel", listOf(3), f.remembered)
    }

    @Test
    fun `a burst of presses collapses to the last channel`() {
        // The whole point of the generation counter. Three presses queue three tunes; only the
        // last may reach the player, or surfing snaps back to channels already left behind.
        val f = Fixture()
        f.tune.surfTo(channel(1, "aaaaaaaaaaa"))
        f.tune.surfTo(channel(2, "bbbbbbbbbbb"))
        f.tune.surfTo(channel(3, "ccccccccccc"))
        f.settle()
        assertEquals(listOf(1, 2, 3), f.blanked)
        assertEquals("the superseded tunes must not paint", 1, f.painted.size)
        assertEquals(3, f.painted.single().first)
        assertEquals(3, f.tune.onAir?.channel?.number)
    }

    @Test
    fun `a keypress between resolve and paint stops the stale tune at the door`() {
        // The UI-side re-check. Tune A passes every executor-side check and posts its paint;
        // before the UI runs it, a keypress dispatches tune B. A must abandon INSIDE the posted
        // runnable - this is the interleaving that used to snap the picture back to a channel
        // the viewer had already left.
        val f = Fixture()
        f.tune.surfTo(channel(1, "aaaaaaaaaaa"))
        f.work.turn()          // A resolves and posts its paint; the UI has not run yet
        f.tune.surfTo(channel(2, "bbbbbbbbbbb"))
        f.settle()
        assertTrue("channel 1's paint arrived after the keypress and must be refused",
            f.painted.none { it.first == 1 })
        assertEquals(2, f.tune.onAir?.channel?.number)
        assertEquals("the resume pref must never name the abandoned channel", listOf(2), f.remembered)
    }

    @Test
    fun `a tune that cannot resolve says so instead of going quietly black`() {
        val f = Fixture()
        f.resolver.answer = { null }
        f.tune.surfTo(channel(7, "aaaaaaaaaaa"))
        f.settle()
        assertEquals(listOf(7), f.unavailable)
        assertNull("a channel that never painted must not claim to be on air", f.tune.onAir)
        assertTrue(f.remembered.isEmpty())
    }

    @Test
    fun `a dead clip is substituted under its own name from its own beginning`() {
        val f = Fixture()
        f.resolver.answer = { id ->
            if (id == "deaddeaddea") null
            else ClipResolver.Resolved(Progressive("https://v/$id", null), 900_000, "hd")
        }
        // nowSeconds=50 inside clip 0 of a 100s rotation, so the clock chooses the dead clip.
        f.tune.surfTo(channel(4, "deaddeaddea", "liveliveliv"))
        f.settle()
        val onAir = f.tune.onAir!!
        assertEquals("the substitute's own identity, not the dead clip's", 1, onAir.streamIndex)
        assertEquals("liveliveliv", onAir.stream.id)
        assertEquals("a programme never scheduled for now has nothing to seek to",
            0.0, onAir.offsetSeconds, 0.0)
    }

    @Test
    fun `the end-of-clip marker only steers the channel whose clip ended`() {
        // An end-of-clip retune can be superseded by a channel change; the marker must not leak
        // into the next channel's tune and skip a clip that never played.
        val f = Fixture()
        f.tune.surfTo(channel(5, "aaaaaaaaaaa", "bbbbbbbbbbb"))
        f.settle()
        f.tune.clipEnded()          // channel 5's clip 0 reports ending...
        f.tune.surfTo(channel(6, "ccccccccccc", "ddddddddddd"))  // ...but the viewer surfs away
        f.settle()
        val onAir = f.tune.onAir!!
        assertEquals(6, onAir.channel.number)
        assertEquals("channel 6's scheduled clip, not one skipped by channel 5's marker",
            0, onAir.streamIndex)
    }

    @Test
    fun `ending a clip re-tunes past it on the same channel`() {
        val f = Fixture()
        f.tune.surfTo(channel(5, "aaaaaaaaaaa", "bbbbbbbbbbb"))
        f.settle()
        assertEquals(0, f.tune.onAir?.streamIndex)
        f.tune.clipEnded()
        f.settle()
        assertEquals("the rotation still names the finished clip, so the next one plays",
            1, f.tune.onAir?.streamIndex)
    }

    @Test
    fun `supersede kills a tune in flight without starting another`() {
        // Opening the picker must freeze the dial: a tune already queued may not land under the
        // open list. Reproduced on device before the supersede existed.
        val f = Fixture()
        f.tune.surfTo(channel(8, "aaaaaaaaaaa"))
        f.tune.supersede()
        f.settle()
        assertTrue("the tune was superseded before painting", f.painted.isEmpty())
        assertNull(f.tune.onAir)
    }

    @Test
    fun `the first tune after loading loses to a keypress made while loading`() {
        // The lineup fetch takes seconds, and a viewer can be surfing before it finishes. The
        // remembered channel may flash up, but the channel the viewer ASKED for must win - and
        // it only does because tuneFirst runs inline on the executor rather than queueing
        // behind the surf: queued, it would pass the same generation check the surf just
        // passed and steal the screen back. This test caught exactly that regression.
        val f = Fixture()
        f.tune.surfTo(channel(2, "bbbbbbbbbbb"))       // queued while the lineup "fetched"
        f.tune.tuneFirst(channel(1, "aaaaaaaaaaa"), requestedAtMillis = 0)  // inline, loses
        f.settle()
        assertEquals(2, f.tune.onAir?.channel?.number)
        assertEquals("the surf must be the last thing painted", 2, f.painted.last().first)
    }

    @Test
    fun `a clip with every rung refused is skipped without extracting it`() {
        // condemn() returning null means every rung is refused, so any resolve must come back
        // null - 2.4s of black on the device before the skip ran anyway.
        val f = Fixture()
        f.ledger.condemn("deaddeaddea", listOf("hd", "sd"))
        f.ledger.condemn("deaddeaddea", listOf("hd", "sd"))
        f.tune.surfTo(channel(4, "deaddeaddea", "liveliveliv"))
        f.settle()
        assertEquals("liveliveliv", f.tune.onAir?.stream?.id)
        assertTrue("the condemned clip is never sent to a resolver",
            "deaddeaddea" !in f.resolver.asked)
    }

    @Test
    fun `a painted channel prefetches its neighbours`() {
        val f = Fixture()
        val dial = listOf(channel(1, "aaaaaaaaaaa"), channel(2, "bbbbbbbbbbb"), channel(3, "ccccccccccc"))
        f.navigator = DialNavigator(dial, 2)
        f.tune.surfTo(dial[1])
        f.settle()
        f.prefetch.turn()
        assertTrue(f.resolver.asked.containsAll(listOf("aaaaaaaaaaa", "ccccccccccc")))
    }

    @Test
    fun `a prefetch queued before the viewer moved on is skipped`() {
        // Two resolves are queued per channel passed through; without the check the prefetch
        // thread spent seconds on the neighbours of channels already left behind.
        val f = Fixture()
        val dial = listOf(channel(1, "aaaaaaaaaaa"), channel(2, "bbbbbbbbbbb"), channel(3, "ccccccccccc"))
        f.navigator = DialNavigator(dial, 2)
        f.tune.surfTo(dial[1])
        f.settle()                  // channel 2 painted; its neighbours are queued
        f.tune.surfTo(dial[2])      // the viewer moves on before the prefetch thread gets there
        f.work.turn()
        f.prefetch.turn()
        assertTrue("channel 1 was channel 2's neighbour, and nobody is on channel 2 any more",
            "aaaaaaaaaaa" !in f.resolver.asked)
    }

    // --- the half-hour schedule ----------------------------------------------------------------

    /** A UTC slot boundary, so the schedule's arithmetic is plain. */
    private val slot = 1_790_191_800L

    private val halfHour = Timetable(
        skipsOn = { false }, halfHourOn = { true }, zone = { java.time.ZoneOffset.UTC })

    private fun scheduled(number: Int, vararg durations: Int) = Channel(
        number = number, name = "CH$number", kind = "youtube", rotation = "clock",
        streams = durations.mapIndexed { i, d ->
            val id = "s$number$i".padEnd(11, 'x')
            Stream(id = id, url = "https://youtube.com/watch?v=$id", duration = d, title = "t$i")
        },
    )

    @Test
    fun `a card claims the air but hands nothing to the player`() {
        // One 1700s programme a slot and nothing to top up with: the last 100s is a card.
        val f = Fixture(halfHour)
        f.now = slot + 1750
        f.tune.surfTo(scheduled(5, 1700))
        f.settle()
        assertEquals(listOf(5), f.cards)
        assertTrue("a card is not a clip; nothing may reach the player", f.painted.isEmpty())
        assertTrue("and nothing is resolved on the tune thread", f.resolver.asked.isEmpty())
        val onAir = f.tune.onAir!!
        assertEquals(5, onAir.channel.number)
        assertEquals(slot + 1800, onAir.card!!.until)
        assertEquals(listOf(5), f.remembered)
        // The prefetch resolves what the card announces, so the programme after it is instant.
        f.prefetch.turn()
        assertTrue(f.resolver.asked.contains(onAir.stream.id))
    }

    @Test
    fun `a card superseded before it lands never claims the air`() {
        val f = Fixture(halfHour)
        f.now = slot + 1750
        f.tune.surfTo(scheduled(5, 1700))
        f.work.turn()
        f.tune.surfTo(scheduled(6, 1700))
        f.settle()
        assertEquals("channel 5's card must not land after the viewer moved on", listOf(6), f.cards)
        assertEquals(6, f.tune.onAir?.channel?.number)
    }

    @Test
    fun `on the schedule, a programme that ends early moves on to what the schedule has next`() {
        // Two 25-minute programmes and a 240s short. Clip 0 ends ten seconds early: the schedule
        // still has it on, and what follows is the top-up - not clip 1, the next in the list.
        val f = Fixture(halfHour)
        f.now = slot + 1490
        f.tune.surfTo(scheduled(5, 1500, 1500, 240))
        f.settle()
        val programme = f.tune.onAir!!.streamIndex
        assertTrue(programme in 0..1)
        f.tune.clipEnded()
        f.settle()
        assertEquals(2, f.tune.onAir?.streamIndex)
        assertEquals("joined from the top-up's own start", 0.0, f.tune.onAir!!.offsetSeconds, 0.0)
    }

    @Test
    fun `on the schedule, the same programme in the next slot is a new showing, not the old one`() {
        // One programme that fills its slot exactly: it airs every half hour. When one showing
        // ends and the next has begun, the index matches - but it is a different showing, and
        // skipping past it would put the channel half an hour off its own schedule.
        val f = Fixture(halfHour)
        f.now = slot + 1795
        f.tune.surfTo(scheduled(5, 1800))
        f.settle()
        assertEquals(slot + 1800, f.tune.onAir!!.endsAt)
        f.now = slot + 1801
        f.tune.clipEnded()
        f.settle()
        assertEquals(slot + 3600, f.tune.onAir!!.endsAt)
        assertEquals(1.0, f.tune.onAir!!.offsetSeconds, 0.0)
    }
}
