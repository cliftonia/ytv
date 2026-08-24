package com.cliftonia.fs42tv.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface


@Composable
private fun PickerRow(text: String, subtitle: String, selected: Boolean) {
    // No focus, no clickable, no interaction source. The list owns selection and key handling,
    // so a row is only ever drawn - which is the point: 113 rows each carrying their own focus
    // machinery is what made this list heavy, and driving a scroll from focus changes deadlocked
    // it outright (scrolling moves focus, which scrolled again - an ANR, not a crash).
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
        // Put the SELECTED row exactly on the viewport's centre line, by measuring where it
        // actually is rather than estimating from a row count.
        //
        // "Half the visible rows" was the estimate, and it left the highlight wandering: rows
        // are not all the same height once a title wraps, and partially visible rows count too.
        // Measuring removes the guess.
        //
        // scrollBy clamps at both ends of the list, which is exactly the behaviour wanted: in
        // the middle of the dial the channels move and the highlight stays put; at channel 2 and
        // channel 114 the list has nowhere left to go, so the highlight travels to meet the end
        // instead of the guide showing empty space.
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == selected }
        if (row == null) {
            // Not composed yet - a first open, or a jump. Get it on screen, and the next pass
            // centres it precisely.
            listState.scrollToItem(selected)
        } else {
            val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val rowCentre = row.offset + row.size / 2f
            listState.scrollBy(rowCentre - viewportCentre)
        }
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
                    PickerRow(text = row.first, subtitle = row.second, selected = index == selected)
                }
            }
        }
    }
}
