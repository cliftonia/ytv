package com.cliftonia.fs42tv

import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import com.cliftonia.fs42tv.sync.UrlCache

/**
 * The channels every test builds its dial out of.
 *
 * Each test class used to hand-roll its own [Channel] and [Stream] literals, and they drifted:
 * two of them gave a live channel a duration of 1 where every live channel published in
 * `channels-sample.json` carries the placeholder 600. A live fixture with the wrong duration is
 * not a cosmetic difference, because the clock rotation divides by the cycle length, so a test
 * built on duration 1 exercises a rotation that cannot happen on the real dial.
 */
object TestDial {

    /**
     * What a live channel's streams carry instead of a real length.
     *
     * A broadcast feed has no end, so the lineup publishes 600 for every one of them. Nothing
     * reads it as a duration; it exists so the rest of the pipeline can treat a live stream and
     * a clip the same shape.
     */
    const val LIVE_PLACEHOLDER_DURATION = 600

    fun clip(title: String, duration: Int, id: String = "x") =
        Stream(id = id, url = "https://www.youtube.com/watch?v=$id", duration = duration,
               title = title)

    /** A clock-rotating youtube channel of [durations], with ids `vid0xxxxxxx`, `vid1xxxxxxx`... */
    fun ytChannel(vararg durations: Int, number: Int = 9, name: String = "AFL") = Channel(
        number = number, name = name, kind = "youtube", rotation = "clock",
        streams = durations.mapIndexed { i, d ->
            Stream(id = "vid$i".padEnd(11, 'x'), url = "https://youtube.com/watch?v=vid$i",
                   duration = d, title = "clip $i")
        },
    )

    /** A clock-rotating youtube channel built from clips whose titles the test cares about. */
    fun ytChannelOf(number: Int, name: String, vararg streams: Stream) = Channel(
        number = number, name = name, kind = "youtube", rotation = "clock",
        streams = streams.toList(),
    )

    /** A live channel: no rotation, no video ids, and [LIVE_PLACEHOLDER_DURATION] throughout. */
    fun liveChannel(vararg titles: String, number: Int = 103, name: String = "ABC TV QLD") =
        Channel(
            number = number, name = name, kind = "live", rotation = null,
            streams = titles.map {
                Stream(id = null, url = "https://x/abc.m3u8",
                       duration = LIVE_PLACEHOLDER_DURATION, title = it)
            },
        )

    fun cacheOf(id: String, vararg tiers: Pair<String, Tier>) =
        UrlCache(urls = mapOf(id to tiers.toMap()))
}
