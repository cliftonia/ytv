package com.cliftonia.fs42tv.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay


/**
 * Black over the picture from the moment a channel is chosen until the new one appears.
 *
 * Without it the banner announces the channel you asked for while the previous channel is still
 * playing underneath - the overlay tells the truth about where you are going and the picture
 * lies about where you are, for the second or two the tune takes. Selecting from the guide made
 * that obvious: the row you picked, its title, and someone else's programme behind it.
 *
 * Televisions have always blanked through a channel change for exactly this reason. The black is
 * not a loading state to be apologised for; it is the honest answer to "what is on this channel"
 * while that is still being worked out.
 */
@Composable
fun TuningBlank(visible: Boolean) {
    if (!visible) return
    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
}

/**
 * The single OSD block, shown on each tune and then gone.
 *
 * This mirrors the box exactly (`field_player.py:158-185`): one ASS overlay,
 * `{\an7\pos(60,50)...}<channel line>\N{\fs22}<title line>`, which vanishes entirely after
 * `BANNER_SECONDS = 8.0` (`field_player.py:106`). [channelLine] (e.g. "28  PANEL SHOWS") sits at
 * 27.5.sp with [titleLine] beneath it at 11.sp, and after [holdMillis] the picture is clean.
 *
 * An earlier revision also kept a persistent "CH 28" in this corner once the banner had gone - the
 * ytch.tv habit. It was dropped after seeing it on screen: because the heading's text changes when
 * the title drops away, the leftover reads as a second widget arriving rather than as the same one
 * shrinking. Nothing lingering beats a lingering thing that looks like residue.
 *
 * Hides via a `LaunchedEffect` keyed on [generation], so a new tune cancels the previous timer
 * rather than letting an earlier one hide a later, still-fresh banner.
 */
@Composable
fun ChannelOsd(
    channelLine: String,
    titleLine: String,
    generation: Int,
    holdMillis: Long = 8000,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(generation) {
        if (channelLine.isEmpty()) return@LaunchedEffect
        visible = true
        delay(holdMillis)
        visible = false
    }

    if (!visible || channelLine.isEmpty()) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        // The title is bounded against this composable's own measured width, not a fixed dp
        // figure: a hardcoded guess only holds at the density it was picked for. Real TVs
        // overscan, so the title is kept clear of the rightmost 5%, on top of the 30.dp left
        // inset already spent getting to the text.
        val titleMaxWidth = maxWidth * 0.95f - 30.dp

        Column(modifier = Modifier.padding(start = 30.dp, top = 25.dp)) {
            OsdText(text = channelLine, fontSize = 27.5.sp)
            if (titleLine.isNotEmpty()) {
                OsdText(
                    text = titleLine,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = titleMaxWidth),
                )
            }
        }
    }
}

/**
 * A near-opaque backdrop behind the list, not the transparent-over-video look the banner uses.
 * The banner is a couple of short lines over a single frame; a scrolling list of ~111 rows read
 * against moving footage underneath would be a wall of flicker, so the picker gets its own
 * background rather than relying on [OsdText]'s outline alone.
 */
internal val PickerBackground = Color(0xE6000000)

/** A dark tint of [OsdGreen] rather than a neutral grey, so the focus highlight reads as part of the same product as the outline text sitting on it. */
internal val PickerRowFocusedBackground = Color(0xFF1A3B1A)
