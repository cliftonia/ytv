package com.cliftonia.fs42tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One line of the settings list.
 *
 * [action] is what OK does. A row without one is a reading - the version, how many channels are
 * loaded - and is skipped over rather than focused, because a highlight you cannot act on is a
 * dead end that makes the list feel broken.
 */
data class SettingRow(
    val label: String,
    val value: String,
    val action: (() -> Unit)? = null,
)

/**
 * Settings, and the diagnostics you would otherwise need a laptop to read.
 *
 * It exists for one setting in particular. The choice between mpv and Media3 is the entire escape
 * hatch for the judder problem, and until this screen it could only be changed with
 * `adb shell am start --es engine mpv` - which is fine at a desk and useless in a car.
 *
 * The readings matter nearly as much. When the dial goes quiet the first question is always
 * whether the lineup is stale or the extractor has broken, and those are opposite problems with
 * opposite fixes. Showing both here turns a debugging session into a glance.
 *
 * Deliberately the same interaction as the channel picker: one focus target, up and down move the
 * highlight, OK acts, Back leaves. A second set of habits for a second screen is a worse cost than
 * anything this screen saves.
 */
@Composable
fun SettingsScreen(rows: List<SettingRow>, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)

    // Every row except a separator, NOT just the ones OK can act on.
    //
    // This used to visit only actionable rows, and since the list scrolls to follow the
    // highlight, the read-only rows below them could never be reached - the diagnostics existed
    // and were unreachable, which is worse than a highlight that does nothing when pressed. Every
    // television settings screen lets you move onto a reading; none of them hide it.
    val selectable = remember(rows) {
        rows.indices.filter { !rows[it].label.startsWith("---") }
    }
    var selected by remember { mutableStateOf(selectable.firstOrNull() ?: 0) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Re-requested whenever focus is lost, not merely once on open.
    //
    // Requesting it once was enough until the caption overlay arrived: it updates its own state
    // ten times a second, and something in that recomposition takes focus away from this screen.
    // The symptom is that the highlight vanishes and the remote stops working entirely, which
    // reads as the settings screen being broken rather than as a focus problem.
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus) {
        if (!hasFocus) focusRequester.requestFocus()
    }

    // Keep the highlighted row on screen as it moves past the bottom, which is the whole point of
    // the change: the rows below the fold were unreachable rather than merely out of sight.
    LaunchedEffect(selected) { listState.animateScrollToItem(selected) }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PickerBackground)
                .focusRequester(focusRequester)
                .onFocusChanged { hasFocus = it.isFocused }
                .focusable()
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                        return@onKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        // Moves between ACTIONABLE rows only, stepping over the readings. Without
                        // this the highlight lands on a version number and OK does nothing, which
                        // reads as a broken button rather than as a deliberate non-control.
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            selected = nextSelectable(selectable, selected, forward = true); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            selected = nextSelectable(selectable, selected, forward = false); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER -> {
                            rows.getOrNull(selected)?.action?.invoke(); true
                        }
                        // Left got us here, so left goes back. The picker uses Back for the same
                        // job and both work here; this simply makes the way in also the way out.
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onDismiss(); true
                        }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.7f).fillMaxHeight().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OsdText(text = "SETTINGS", fontSize = 27.5.sp)
                // SCROLLS. This was a plain Column, and as diagnostic rows were added the
                // controls at the bottom - the captions toggle and the update check - simply
                // stopped being on the screen. Nothing indicated that; they were gone, and a
                // control you cannot see is a control that does not exist.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(rows) { index, row ->
                        SettingsRowView(row, focused = index == selected)
                    }
                }
                OsdText(text = "OK TO CHANGE - BACK TO RETURN", fontSize = 11.sp)
            }
        }
    }
}

/** The next row the highlight may rest on, wrapping at both ends. Separators are stepped over. */
internal fun nextSelectable(selectable: List<Int>, current: Int, forward: Boolean): Int {
    if (selectable.isEmpty()) return current
    val position = selectable.indexOf(current)
    if (position < 0) return selectable.first()
    val step = if (forward) 1 else -1
    return selectable[(position + step + selectable.size) % selectable.size]
}

@Composable
private fun SettingsRowView(row: SettingRow, focused: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) PickerRowFocusedBackground else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OsdText(text = row.label, fontSize = 20.sp, outline = false)
        OsdText(text = row.value, fontSize = 20.sp, outline = false)
    }
}
