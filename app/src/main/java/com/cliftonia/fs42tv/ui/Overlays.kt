package com.cliftonia.fs42tv.ui

import androidx.compose.foundation.background
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
 * The fill colour the FieldStation42 box's own OSD draws in - see `field_player.py:164-167`,
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
 * exactly the footage this app plays. Factored out once and shared by the indicator and both
 * banner lines rather than repeating the two-layer trick three times.
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
    fontWeight: FontWeight = FontWeight.Bold,
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
 * The persistent corner channel indicator.
 *
 * Shows what is ON AIR, which is not the same as where the dial navigator points - they differ
 * whenever a tune fails and the previous picture stays up.
 *
 * Position and size match the box's OSD exactly: `\pos(60,50)` at its native 1920x1080 canvas is
 * 30.dp/25.dp here, `\fs55` is 27.5.sp. These are proven on the real TV, so the on-screen-safe
 * area is already accounted for - no separate overscan margin needed here.
 */
@Composable
fun ChannelIndicator(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        OsdText(
            text = text,
            fontSize = 27.5.sp,
            modifier = Modifier.padding(start = 30.dp, top = 25.dp),
        )
    }
}

/**
 * The tune banner: channel line above, programme title below.
 *
 * Auto-hides via a LaunchedEffect keyed on [generation], so a new tune cancels the previous
 * timer rather than letting an earlier one hide a later banner. That bug needed explicit
 * callback removal under Views; here the key does it.
 *
 * Sized to match the box's OSD: `\fs55`/`\fs22` are 27.5.sp/11.sp here. Unlike the indicator this
 * stays at bottom-left rather than the box's own top-left position - the corner indicator already
 * lives there (the ytch.tv pattern this app deliberately layers on top of what the box does), so
 * the two would collide if the banner used the box's position too. It shares the indicator's
 * 30.dp left inset so the two align on a common left margin. [holdMillis] defaults to the box's
 * own `BANNER_SECONDS = 8.0` from `field_player.py:106`.
 */
@Composable
fun ChannelBanner(
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
    if (!visible) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        // The title is bounded against this composable's own measured width, not a fixed dp
        // figure: a hardcoded guess only holds at the density it was picked for. `maxWidth` here
        // is BoxWithConstraints' real measured width for this screen, already density-correct.
        // Real TVs overscan - the outer edge of the panel may not be visible at all - so the
        // title is kept clear of the rightmost 5%, on top of the 30.dp + 20.dp already spent on
        // the box's left inset and its own padding.
        val titleMaxWidth = maxWidth * 0.95f - 30.dp - 20.dp

        Column(
            modifier = Modifier
                .padding(start = 30.dp, bottom = 56.dp)
                .background(Color(0xB0000000))
                .padding(20.dp),
        ) {
            OsdText(text = channelLine, fontSize = 27.5.sp)
            if (titleLine.isNotEmpty()) {
                OsdText(
                    text = titleLine,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = titleMaxWidth),
                )
            }
        }
    }
}
