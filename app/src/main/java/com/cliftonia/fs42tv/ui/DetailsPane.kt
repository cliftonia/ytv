package com.cliftonia.fs42tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The top half of the LIVE TV guide: what is on the highlighted channel.
 *
 * Text on the left - channel, title, rating chips and facts, three lines of description, cast,
 * director - and the picture on the right. Every line is drawn only when it has something to say,
 * so a channel with a bare title is a channel line and a title, not a form with empty boxes.
 *
 * Takes its states as lambdas and reads them here, so a new description or a picture landing
 * recomposes this pane and never the 800-row list beneath it. Sized for a sofa: nothing below the
 * 13.sp (26 px) floor the list itself keeps, the title near the list's channel-name size.
 */
@Composable
internal fun DetailsPane(
    details: () -> PickerDetails?,
    art: () -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val pane = details()
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (pane == null) return@Column
            OsdText(text = pane.channelLine, fontSize = LabelSize, color = Dim, outline = false,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pane.title.isNotEmpty()) {
                OsdText(text = pane.title, fontSize = TitleSize, outline = false,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (pane.ratings.isNotEmpty() || pane.facts.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pane.ratings.forEach { RatingChip(it) }
                    if (pane.facts.isNotEmpty()) {
                        OsdText(text = pane.facts, fontSize = LabelSize, color = Soft, outline = false,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (pane.description.isNotEmpty()) {
                OsdText(text = pane.description, fontSize = BodySize, color = Soft, outline = false,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            listOf(pane.cast, pane.makers).filter { it.isNotEmpty() }.forEach {
                OsdText(text = it, fontSize = LabelSize, color = Dim, outline = false,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Picture(url = pane?.imageUrl, art = art())
    }
}

/**
 * The poster or still, at the pane's full height and its own shape - a portrait poster narrow, a
 * wide still wide, within limits. Loading, a dark slot of poster shape holds the text still;
 * no picture to load, nothing at all, and the text has the width.
 */
@Composable
private fun Picture(url: String?, art: ImageBitmap?) {
    if (url == null) return
    val ratio = art?.let { (it.width.toFloat() / it.height).coerceIn(MIN_RATIO, MAX_RATIO) } ?: POSTER_RATIO
    Box(
        modifier = Modifier
            .padding(start = 24.dp)
            .fillMaxHeight()
            .aspectRatio(ratio)
            .background(Slot),
    ) {
        if (art != null) {
            Image(bitmap = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
    }
}

/** "IMDb 7.4" in an OSD-green outline: a rating reads at a glance as a figure, not prose. */
@Composable
private fun RatingChip(text: String) {
    Box(
        modifier = Modifier
            .border(1.5.dp, OsdGreen, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        OsdText(text = text, fontSize = LabelSize, outline = false)
    }
}

/** Near the list's 20.sp channel names: the one line the eye goes to first. */
private val TitleSize = 22.sp

/** Readable prose from across a room, and three lines of it fit the half. */
private val BodySize = 15.sp

/** The floor the list keeps for its titles - 26 px on this panel. */
private val LabelSize = 13.sp

/** Description and facts: light enough to read at length, not the full green of the title. */
private val Soft = Color(0xFF9BE89B)

/** Channel line, cast, director: the list's own dimmed green. */
private val Dim = Color(0xFF1E9E1E)

private val Slot = Color(0xFF0B1A0B)
private const val POSTER_RATIO = 2f / 3f
private const val MIN_RATIO = 0.6f
private const val MAX_RATIO = 1.5f

