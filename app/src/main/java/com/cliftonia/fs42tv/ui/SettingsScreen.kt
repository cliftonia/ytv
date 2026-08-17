package com.cliftonia.fs42tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

    val actionable = remember(rows) { rows.indices.filter { rows[it].action != null } }
    var selected by remember { mutableStateOf(actionable.firstOrNull() ?: 0) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PickerBackground)
                .focusRequester(focusRequester)
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
                            selected = nextActionable(actionable, selected, forward = true); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            selected = nextActionable(actionable, selected, forward = false); true
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
                modifier = Modifier.fillMaxWidth(0.7f).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OsdText(text = "SETTINGS", fontSize = 27.5.sp)
                Box(modifier = Modifier.padding(top = 16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows.forEachIndexed { index, row ->
                            SettingsRowView(row, focused = index == selected)
                        }
                    }
                }
                Box(modifier = Modifier.padding(top = 24.dp)) {
                    OsdText(text = "OK TO CHANGE - BACK TO RETURN", fontSize = 11.sp)
                }
            }
        }
    }
}

/** The next row that OK can do something with, wrapping at both ends. */
internal fun nextActionable(actionable: List<Int>, current: Int, forward: Boolean): Int {
    if (actionable.isEmpty()) return current
    val position = actionable.indexOf(current)
    if (position < 0) return actionable.first()
    val step = if (forward) 1 else -1
    return actionable[(position + step + actionable.size) % actionable.size]
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
