package com.cliftonia.fs42tv.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.cliftonia.fs42tv.details.ArtCache
import com.cliftonia.fs42tv.details.ArtLoader
import com.cliftonia.fs42tv.details.Details
import com.cliftonia.fs42tv.pluto.PlutoGuide
import com.cliftonia.fs42tv.pluto.PlutoIds
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.FastGuideStore
import com.cliftonia.fs42tv.sync.PublishedFile
import java.io.File
import java.util.concurrent.Executor

/**
 * The sources behind the LIVE TV picker's details pane, asked about one channel at a time.
 *
 * What is on comes from where the guide rows already get it - `fast_guide.json` for a FAST
 * channel, Pluto's per-channel guide for a Pluto one (the same cached reply the rows use, so a
 * highlighted row costs no extra request) - and what is known about it from `details.json`, keyed
 * by the title. Every source answers from memory first; a miss starts a load and calls
 * [forChannel]'s `onUpdate` on the UI thread when it lands, so the pane can ask again.
 *
 * Behind GUIDE DETAILS: off, [enabled] is false and the picker is the plain list it was.
 */
class GuideDetails(
    private val features: Features,
    private val fastGuide: FastGuideStore?,
    private val plutoGuide: PlutoGuide,
    private val details: PublishedFile<Details>?,
    private val art: ArtCache<ImageBitmap>?,
    private val nowMillis: () -> Long,
    private val runOnUi: (() -> Unit) -> Unit,
    private val halted: () -> Boolean,
) {

    val enabled: Boolean get() = features.isOn(Features.Flag.GUIDE_DETAILS)

    /** The pane for [channel] as far as it is known now. [onUpdate] runs when more arrives. */
    fun forChannel(channel: Channel, onUpdate: () -> Unit): PickerDetails {
        val update = { runOnUi { if (!halted()) onUpdate() } }
        val line = ChannelLabels.listRow(channel).first.removeSuffix(":")
        val fast = fastGuide?.programmeOn(channel) { update() }
        var title = fast?.title
        var description = fast?.description
        var image = fast?.imageUrl
        if (fast == null && features.isOn(Features.Flag.PLUTO_GUIDE)) {
            PlutoIds.of(channel)?.let { id ->
                val schedule = plutoGuide.cached(id)
                if (schedule == null) plutoGuide.request(id) { update() }
                schedule?.onAt(nowMillis())?.let {
                    title = it.title
                    description = it.description
                    image = it.imageUrl
                }
            }
        }
        val enrichment = title?.let { details?.get { update() }?.forTitle(it) }
        return PickerDetails.of(line, title, description, image, enrichment)
    }

    /**
     * The picture at [url] if it is in memory; otherwise [onReady] gets it on the UI thread, or
     * [onFailed] does when it cannot be had.
     */
    fun art(url: String, onFailed: () -> Unit, onReady: (ImageBitmap) -> Unit): ImageBitmap? {
        val cache = art ?: return null
        cache.cached(url)?.let { return it }
        cache.request(url, onFailed = { runOnUi { if (!halted()) onFailed() } }) { picture ->
            runOnUi { if (!halted()) onReady(picture) }
        }
        return null
    }

    companion object {
        /** Six hours, as the server publishes. */
        const val REFRESH_MILLIS = 6L * 60 * 60 * 1000
        const val RETRY_MILLIS = 10L * 60 * 1000
        const val FILE_NAME = "details.json"

        fun create(
            features: Features,
            fastGuide: FastGuideStore?,
            plutoGuide: PlutoGuide,
            cacheDir: File?,
            executor: Executor,
            runOnUi: (() -> Unit) -> Unit,
            halted: () -> Boolean,
        ): GuideDetails {
            val now = { System.currentTimeMillis() }
            val details = cacheDir?.let {
                PublishedFile(
                    file = File(it, FILE_NAME), url = Details.URL, fetch = { url -> PublishedFile.httpFetch(url) },
                    parse = Details::parse, executor = executor, nowMillis = now,
                    refreshMillis = REFRESH_MILLIS, retryMillis = RETRY_MILLIS,
                    label = "programme details", describe = { d -> "${d.size} titles" },
                )
            }
            return GuideDetails(features, fastGuide, plutoGuide, details, ArtLoader.create(cacheDir),
                now, runOnUi, halted)
        }
    }
}
