package com.cliftonia.fs42tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    color: Color = OsdGreen,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    /**
     * Whether to draw the outline pass.
     *
     * The outline exists to keep text legible over moving footage, and costs a SECOND full text
     * layout and draw of the same string. Where there is already a background behind the text -
     * the picker's near-opaque backdrop - it buys nothing and doubles the work for every row
     * that scrolls into view, on a 32-bit SoC, twice per row because each row has two of these.
     */
    outline: Boolean = true,
) {
    val outlineWidthPx = with(LocalDensity.current) { OsdOutlineWidth.toPx() }
    // Remembered rather than rebuilt: these are allocated on every recomposition otherwise, and
    // a scrolling list recomposes constantly.
    val fill = remember(fontWeight, fontStyle, fontSize, color) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontSize = fontSize,
            color = color,
        )
    }
    val stroke = remember(fontWeight, fontStyle, fontSize, outlineWidthPx) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontSize = fontSize,
            color = OsdOutline,
            drawStyle = Stroke(width = outlineWidthPx),
        )
    }
    if (!outline) {
        BasicText(text = text, modifier = modifier, style = fill,
            maxLines = maxLines, overflow = overflow)
        return
    }
    Box(modifier = modifier) {
        BasicText(text = text, style = stroke, maxLines = maxLines, overflow = overflow)
        BasicText(text = text, style = fill, maxLines = maxLines, overflow = overflow)
    }
}

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
private val PickerBackground = Color(0xE6000000)

/** A dark tint of [OsdGreen] rather than a neutral grey, so the focus highlight reads as part of the same product as the outline text sitting on it. */
private val PickerRowFocusedBackground = Color(0xFF1A3B1A)

/** How much dimmer each row gets per step away from the highlight. */
private const val FALLOFF_PER_ROW = 0.16f

/** Rows never fade out entirely - a guide you cannot read the edges of is a worse guide. */
private const val MIN_ROW_ALPHA = 0.25f

/** How much smaller each row gets per step away, capped so distant rows do not vanish. */
private const val SHRINK_PER_ROW = 0.03f

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
 * About half the heading size, which is the point - but NOT literally half.
 *
 * Half of 20.sp is 10.sp, which is 20px on this panel, and TV guidance puts the legibility
 * floor at 24px for anything meant to be read from a sofa. 13.sp is 26px: clearly subordinate
 * to the channel name, still above the floor. Going lower makes the guide decorative rather
 * than useful, which defeats showing what is on at all.
 */
private val PickerSubtitleFontSize = 13.sp

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
    selected: Boolean,
    /** Rows away from the highlight; 0 is the centre. Drives the wheel's fade and shrink. */
    distance: Int,
) {
    // No focus, no clickable, no interaction source. The list owns selection and key handling,
    // so a row is only ever drawn - which is the point: 113 rows each carrying their own focus
    // machinery is what made this list heavy, and driving a scroll from focus changes deadlocked
    // it outright (scrolling moves focus, which scrolled again - an ANR, not a crash).
    // A wheel's look, done the cheap way: driven by the SELECTED index rather than by scroll
    // position. Reading the scroll offset every frame would recompose every visible row on every
    // frame, which is exactly the cost this picker was just rebuilt to remove - and the scroll is
    // instant anyway, so there is no intermediate position to track.
    //
    // animateFloatAsState gives the movement its smoothness; only the dozen or so rows the list
    // has actually composed are ever animating.
    val target = (1f - distance * FALLOFF_PER_ROW).coerceIn(MIN_ROW_ALPHA, 1f)
    val fade by animateFloatAsState(targetValue = target, label = "pickerRowFade")
    val shrink by animateFloatAsState(
        targetValue = 1f - distance * SHRINK_PER_ROW.coerceAtMost(0.06f),
        label = "pickerRowScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = fade
                scaleX = shrink
                scaleY = shrink
                // Shrink toward the left edge so the channel numbers stay in one column and the
                // eye can still run down them - scaling about the centre would make them wander.
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .background(if (selected) PickerRowFocusedBackground else Color.Transparent)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The heading takes exactly what it needs; the title takes the rest and ellipsises
            // at the real edge.
            OsdText(text = text, fontSize = PickerRowFontSize, outline = false)
            if (subtitle.isNotEmpty()) {
                OsdText(
                    modifier = Modifier.weight(1f, fill = false),
                    text = " $subtitle",
                    fontSize = PickerSubtitleFontSize,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = PickerSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    outline = false,
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
    var selected by remember { mutableStateOf(onAirIndex) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The highlight stays put and the LIST moves under it.
    //
    // Driven by `selected`, which only key presses change - never by focus. Scrolling moves
    // focus, so scrolling in response to focus is a loop that hangs the main thread; that is
    // exactly what an earlier attempt did, and it ANR'd. scrollToItem rather than
    // animateScrollToItem: with the highlight pinned there is nothing to animate, and it is the
    // queued, interrupted bring-into-view animations that made this feel heavy.
    LaunchedEffect(selected) {
        // Keep the highlight in the MIDDLE of the screen, and let it travel only at the two ends.
        //
        // Half the visible rows, measured rather than assumed - row height depends on the font
        // size and the panel, and a guessed constant put the highlight near the top instead.
        // scrollToItem cannot scroll past the last row, so at the bottom of the dial the list
        // stops and the highlight walks down to meet it; the same happens in reverse at the top.
        // That is the behaviour worth having: padding the list by half a screen would centre the
        // ends too, at the cost of a guide showing half a screen of nothing.
        val half = (listState.layoutInfo.visibleItemsInfo.size / 2).coerceAtLeast(ON_AIR_LEAD_ROWS)
        listState.scrollToItem((selected - half).coerceIn(0, lastIndex))
    }

    MaterialTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(PickerBackground)
                // ONE focus target for the whole guide, rather than one per row. It consumes
                // D-pad up/down/centre before the activity's onKeyDown sees them, which is what
                // keeps the channel underneath from changing while this is open.
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                        return@onKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            selected = (selected + 1).coerceAtMost(lastIndex); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            selected = (selected - 1).coerceAtLeast(0); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER -> { onPick(selected); true }
                        else -> false
                    }
                },
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    rows,
                    key = { index, _ -> index },
                    contentType = { _, _ -> "channel" },
                ) { index, row ->
                    PickerRow(
                        text = row.first,
                        subtitle = row.second,
                        selected = index == selected,
                        distance = kotlin.math.abs(index - selected),
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
