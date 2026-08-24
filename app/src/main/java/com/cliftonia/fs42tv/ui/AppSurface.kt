package com.cliftonia.fs42tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cliftonia.fs42tv.update.UpdateFlow

/**
 * The whole overlay stack, in one place so its z-order can be read top to bottom.
 *
 * The order IS the design: the blank sits under everything so the banner stays readable
 * through a channel change; captions sit above the blank so they are not drawn over black, and
 * below everything else so a banner, a card or the guide always wins the bottom of the screen.
 */
@Composable
fun AppSurface(
    director: ScreenDirector,
    guide: GuidePicker,
    update: UpdateFlow,
    /** Last run's crash, which outranks the live card until a keypress clears it. */
    crashNotice: String,
    settingsVisible: Boolean,
    settingsRows: List<SettingRow>,
    positionSeconds: () -> Double?,
    onCloseSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TuningBlank(director.tuning.value)
        // Hidden whenever something is up in front of the programme: subtitles for a channel
        // nobody is currently looking at are noise.
        CaptionLine(
            cues = director.captionCues.value,
            positionSeconds = positionSeconds,
            visible = !director.tuning.value && !guide.visible.value && !settingsVisible,
        )
        UpdatePrompt(update.ready.value)
        ChannelOsd(
            channelLine = director.bannerChannelLine.value,
            titleLine = director.bannerTitleLine.value,
            generation = director.bannerGeneration.value,
        )
        // One card, two sources: a live playback failure, or last run's crash. The crash wins
        // while it is showing, since a channel that is currently failing will say so again in
        // four seconds anyway.
        val standByText = crashNotice.ifEmpty { director.standByReason.value }
        StandBy(standByText.isNotEmpty(), standByText)
        if (settingsVisible) {
            SettingsScreen(rows = settingsRows, onDismiss = onCloseSettings)
        }
        if (guide.visible.value) {
            ChannelPicker(
                rows = guide.rows.value,
                startIndex = guide.startIndex.value,
                onPick = guide::pick,
                onDismiss = guide::dismiss,
            )
        }
    }
}
