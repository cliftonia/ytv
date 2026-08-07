package com.cliftonia.fs42tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The fill colour the FieldStation42 box's own OSD draws in - see `field_player.py:158-185`,
 * which renders the whole OSD as a single ASS overlay: `{\an7\pos(60,50)\bord3\3c&H001100&
 * \c&H33FF33&\fs55}<channel line>\N{\fs22}<title line>`. ASS colour literals are BGR: `&H33FF33&`
 * is palindromic so it reads the same either way, but the outline below is not - do not "fix" it
 * back to a guessed colour without re-decoding the literal.
 */
val OsdGreen = Color(0xFF33FF33)

/** `&H001100&` decoded as BGR (B=00 G=11 R=00) - a near-black green, not the dark blue it would be as RGB. */
private val OsdOutline = Color(0xFF001100)

/** `\bord3` is 3 px at the box's native 1920x1080 ASS canvas - 1.5.dp at this app's 2.0 density. */
private val OsdOutlineWidth = 1.5.dp

/**
 * One line of OSD text, drawn twice - a stroked back copy then a filled front copy - because
 * Compose's `Text` has no outline property of its own. The outline is functional, not
 * decorative: without it `#33FF33` disappears over a snow scene or a bright studio background,
 * exactly the footage this app plays. There is no backing rectangle behind either copy - the box's
 * own OSD has none, and the outline alone is what keeps the text legible over bright footage.
 *
 * `fontWeight` defaults to [FontWeight.Normal]: the box's ASS string carries no `\b1` and mpv's
 * `--osd-bold` defaults to `no`, so the real OSD it is matching is regular weight, not bold.
 *
 * `BasicText` rather than `androidx.tv.material3.Text`: the latter's `maxLines`/`overflow`
 * wiring wasn't reaching the box that draws it (a long title used to run to the physical screen
 * edge and clip mid-word, no ellipsis, no wrap) - `BasicText` is the primitive both build on, so
 * this bypasses that entirely, and it is also the layer that exposes `TextStyle.drawStyle` for
 * the stroke pass in the first place.
 */
@Composable
private fun OsdText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val outlineWidthPx = with(LocalDensity.current) { OsdOutlineWidth.toPx() }
    val base = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = fontWeight, fontSize = fontSize)
    Box(modifier = modifier) {
        BasicText(
            text = text,
            style = base.copy(color = OsdOutline, drawStyle = Stroke(width = outlineWidthPx)),
            maxLines = maxLines,
            overflow = overflow,
        )
        BasicText(
            text = text,
            style = base.copy(color = OsdGreen),
            maxLines = maxLines,
            overflow = overflow,
        )
    }
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
