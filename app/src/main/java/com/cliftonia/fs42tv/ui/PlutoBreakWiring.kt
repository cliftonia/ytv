package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.pluto.BreakPoller
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.tune.Tuned

/*
 * [PlutoBreak.create]: the break wired to the device - the poller's own thread, the main-thread
 * timer, the clocks, the row, the music and the commercials.
 *
 * Out of PlutoBreak because that file is the break's rules, which its tests drive through
 * [PlutoBreak.Deps] with a hand-cranked clock, and this is the one place those seams are filled
 * with the real thing. A companion extension, so the director still says `PlutoBreak.create`.
 */

/** Construction in one call for the director, which is at its size limit. */
fun PlutoBreak.Companion.create(
    extras: ScreenExtras,
    music: GuideMusic,
    channels: () -> List<Channel>,
    player: () -> ChannelPlayback?,
    runOnUi: (() -> Unit) -> Unit,
    halted: () -> Boolean,
    guideOpen: () -> Boolean,
    overlayOpen: () -> Boolean,
    stoppedNow: () -> Boolean,
    volumeChanged: () -> Unit,
    picture: () -> Unit,
    uncovered: () -> Unit,
    /** Back to a channel from its break's reel - see [PlutoBreak.Deps.retune]. */
    retune: (Tuned) -> Unit,
): PlutoBreak {
    // Its own daemon thread, not the prefetch thread: a read can take its full five
    // seconds of timeouts, and neighbour resolves and the guide's fetches queue there.
    val executor = BreakPoller.daemonExecutor()
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val later: (Long, () -> Unit) -> (() -> Unit) = { delay, block ->
        val runnable = Runnable { if (!halted()) block() }
        main.postDelayed(runnable, delay)
        ({ main.removeCallbacks(runnable) })
    }
    return PlutoBreak(PlutoBreak.Deps(
        enabled = { extras.features.isOn(Features.Flag.BREAK_CARD) },
        poller = { read ->
            BreakPoller(BreakPoller::httpFetch, BreakPoller.scheduleOn(executor), read)
        },
        later = later,
        wallMillis = System::currentTimeMillis,
        elapsedMillis = android.os.SystemClock::elapsedRealtime,
        exactInstant = { player()?.programDateTimeMillis() },
        joinsThirdFromLast = { player()?.joinsLiveAtThirdFromLast == true },
        runOnUi = runOnUi,
        halted = halted,
        guideOpen = guideOpen,
        overlayOpen = overlayOpen,
        stoppedNow = stoppedNow,
        // The music on a Pluto channel is already on the 'beside' session: GuideMusic
        // resolves through ScreenExtras.besideTuned, never the dial's token.
        playMusic = { wanted -> music.play(channels(), wanted) },
        releaseMusic = music::release,
        nowTitle = { channel, onUpdate -> extras.bannerLines(channel, onUpdate)?.first },
        volumeChanged = volumeChanged,
        picture = picture,
        uncovered = uncovered,
        shutdown = { executor.shutdownNow() },
        ads = { changed -> BreakAds.create(extras, player, later, changed) },
        retune = retune,
        windowAt = extras::prejoinWindowAt,
    ))
}
