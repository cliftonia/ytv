package com.cliftonia.fs42tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
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
    color: Color = OsdGreen,
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
            style = base.copy(color = color),
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

/**
 * A near-opaque backdrop behind the list, not the transparent-over-video look the banner uses.
 * The banner is a couple of short lines over a single frame; a scrolling list of ~111 rows read
 * against moving footage underneath would be a wall of flicker, so the picker gets its own
 * background rather than relying on [OsdText]'s outline alone.
 */
private val PickerBackground = Color(0xE6000000)

/** A dark tint of [OsdGreen] rather than a neutral grey, so the focus highlight reads as part of the same product as the outline text sitting on it. */
private val PickerRowFocusedBackground = Color(0xFF1A3B1A)

/**
 * What-is-on text, at about half the luminance of the channel name beside it.
 *
 * Contrast rather than size carries the hierarchy here. A guide is skimmed, not read, and the
 * bright channel names have to form a clean vertical column for the eye to run down; changing
 * the type size would change the row height and break exactly that. Dimming instead lets the
 * titles sit in the same rhythm and recede until the eye stops on one.
 *
 * Not pure white or full green either - maximum luminance is tiring in a dark room, which is
 * where a television lives.
 */
private val PickerSubtitle = Color(0xFF1E9E1E)

/**
 * Row height in the fs42-bench reference was tuned for a static demo list with nothing else on
 * screen. Here the row is read at a glance while surfing, not studied, so it sits below the
 * 27.5.sp heading line (that is a single line meant to dominate) and above the 11.sp title line
 * (secondary text, read at leisure once you already know the channel) - big enough to read from
 * a couch, small enough that ~8 rows are visible at once on a 1080p canvas without the list
 * feeling like a single long scroll to find anything.
 */
private val PickerRowFontSize = 20.sp

/** Rows scrolled above the on-air row when the picker opens, so it lands with context rather than pinned to the very top edge. */
private const val ON_AIR_LEAD_ROWS = 3

@Composable
private fun PickerRow(
    text: String,
    subtitle: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val rowModifier = if (focusRequester != null) {
        Modifier.fillMaxWidth().focusRequester(focusRequester)
    } else {
        Modifier.fillMaxWidth()
    }
    Surface(
        onClick = onClick,
        modifier = rowModifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = PickerRowFocusedBackground,
        ),
        // tv-material3's own default grows the focused row to 1.1x. A row that already spans
        // fillMaxWidth has nowhere for that growth to go but past both edges of the screen, so
        // the focused row's own leading text clips off the left edge - the same rendering
        // hazard the OSD banner ran into, caught the same way, from a screenshot rather than by
        // trusting the parameter. Scale is switched off; the container-colour change on focus
        // carries the highlight instead.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OsdText(text = text, fontSize = PickerRowFontSize)
            if (subtitle.isNotEmpty()) {
                OsdText(
                    text = "  $subtitle",
                    fontSize = PickerRowFontSize,
                    color = PickerSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The direct-entry mechanism: a scrollable list rather than a numeric keypad, because a keypad
 * is a layout for ten discrete keys and a D-pad has none.
 *
 * Opens seeded [ON_AIR_LEAD_ROWS] rows above [startIndex] so the channel actually on air lands
 * with context rather than pinned to the top edge, and requests focus on that row so up/down
 * moves the highlight through the list rather than doing anything to the channel underneath -
 * the caller is responsible for making the channel-change keys inert while this is on screen,
 * since a focused [Surface] row already consumes D-pad up/down/centre before the activity ever
 * sees them.
 *
 * [onDismiss] is wired to [BackHandler] here rather than a branch in the activity's `onKeyDown`,
 * so dismissal is this composable's concern rather than a second key-handling path to keep in
 * sync with this one.
 */
@Composable
fun ChannelPicker(
    rows: List<Pair<String, String>>,
    startIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val lastIndex = (rows.size - 1).coerceAtLeast(0)
    val onAirIndex = startIndex.coerceIn(0, lastIndex)
    val seedIndex = (onAirIndex - ON_AIR_LEAD_ROWS).coerceIn(0, lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = seedIndex)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (rows.isNotEmpty()) {
            focusRequester.requestFocus()
        }
    }

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(PickerBackground)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows) { index, row ->
                    PickerRow(
                        text = row.first,
                        subtitle = row.second,
                        focusRequester = if (index == onAirIndex) focusRequester else null,
                        onClick = { onPick(index) },
                    )
                }
            }
        }
    }
}

/**
 * The "please stand by" card, shown when a channel cannot put a picture up.
 *
 * Copied from the box, which drops `runtime/standby.png` in after two seconds stuck
 * (`field_player.py:575`) and overlays "TECHNICAL DIFFICULTIES" alongside it
 * (`station_player.py:1182`). The image is the box's own file, not a lookalike.
 *
 * Why a card rather than leaving the picture black: a black screen is indistinguishable from a
 * dead app, a dead TV, or a channel that simply has nothing on it. Every one of those prompts a
 * different reaction from whoever is watching, and only one of them is right. The card says
 * "this is the app, it knows, it is working on it".
 *
 * [reason] is shown small beneath the card. The box does not do this - it has a terminal for
 * that - but a sideloaded television app has no other way to say WHY, and "playback error
 * ERROR_CODE_IO_BAD_HTTP_STATUS" is the difference between an expired URL and a dead network.
 */
@Composable
fun StandBy(visible: Boolean, reason: String) {
    if (!visible) return
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(id = com.cliftonia.fs42tv.R.drawable.standby),
            contentDescription = "Technical difficulties - please stand by",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 30.dp, top = 25.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            OsdText(text = "TECHNICAL DIFFICULTIES", fontSize = 27.5.sp)
            if (reason.isNotEmpty()) {
                OsdText(text = reason, fontSize = 11.sp)
            }
        }
    }
}
