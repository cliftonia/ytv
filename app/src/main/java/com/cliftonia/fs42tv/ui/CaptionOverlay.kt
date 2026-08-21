package com.cliftonia.fs42tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cliftonia.fs42tv.resolver.VttCues
import kotlinx.coroutines.delay

/**
 * The width the caption plate is held to, as a fraction of the screen.
 *
 * Not the full width. A caption that runs the whole way across a 4K panel makes the eye travel
 * the width of the room to read one line, which is why broadcast captions have always been boxed
 * near the middle. It also keeps the text clear of the horizontal overscan.
 */
private const val CAPTION_MAX_WIDTH_FRACTION = 0.82f

/**
 * How far up from the bottom edge the plate sits, as a fraction of the screen height.
 *
 * Televisions overscan. The safe area every broadcaster works to is 5% in from each edge, and the
 * channel banner already keeps clear of the right-hand 5% for that reason. Anything drawn flush
 * to the bottom of the surface is a caption the viewer never sees - and that looks exactly like a
 * caption that was never drawn, which is the failure this feature has already been misdiagnosed
 * as several times over.
 */
private const val CAPTION_BOTTOM_INSET_FRACTION = 0.08f

/** Behind the words, so they stay readable over a bright frame without a heavy outline. */
private val CaptionPlate = Color(0xCC000000)

/**
 * How often the player is asked where it is.
 *
 * Cheap - one property read - and the error it buys is bounded by the interval, so a cue can be
 * up to this late. A tenth of a second is well inside what anyone notices reading along with
 * speech, and polling slower starts to feel like the subtitles are lagging the mouths.
 */
private const val CAPTION_POLL_MILLIS = 100L

/**
 * White, not the OSD's green.
 *
 * Everything else this app draws is `#33FF33` because it is imitating the box's ASS overlay. A
 * subtitle is not part of that OSD: it belongs to the programme, it is on screen for minutes at a
 * time rather than seconds, and green over video for that long is tiring to read. White on a dark
 * plate is what every set-top box and every broadcaster settled on.
 *
 * Monospace to match the rest of the app's type, and sized for a sofa - 24.sp is 48px at this
 * app's 2.0 density, comfortably above the 24px floor for anything read at distance and a little
 * under the banner's channel line, which should still be the louder thing on screen.
 */
private val CaptionTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 24.sp,
    color = Color.White,
    textAlign = TextAlign.Center,
)

/**
 * The subtitle for wherever playback has reached, drawn in the overlay rather than by the player.
 *
 * This exists because mpv will not put a caption on THIS panel. It loads the track, selects it,
 * decodes the line into `sub-text`, reports `sub-visibility=yes sid=1 sub-pos=100` inside a full
 * 1920x1080 OSD - and the screen stays clean. Everything mpv says about the subtitle is true and
 * none of it reaches the display; MpvChannelPlayer's subtitle probe carries the full reading and
 * the explanation. Four mpv-side theories were tried before that measurement and all four failed,
 * because every one of them assumed something about the track was wrong.
 *
 * So the caption is drawn in the same Compose layer as the channel banner, which is known to
 * reach the panel because the viewer sees a banner on every channel change. It is also engine
 * independent, so the Chromecast's Media3 path gets it for nothing.
 *
 * [positionSeconds] is polled rather than passed as state: the position changes continuously and
 * hoisting it into a `mutableState` would recompose this tree at the player's frame rate, for a
 * string that changes every couple of seconds.
 */
@Composable
fun CaptionLine(
    cues: List<VttCues.Cue>,
    positionSeconds: () -> Double?,
    visible: Boolean,
) {
    var text by remember { mutableStateOf("") }
    // Keyed on the cue list so a channel change clears the previous clip's caption at once rather
    // than leaving it up until the next poll - which over a fresh channel is a line of dialogue
    // from a programme that is no longer on.
    LaunchedEffect(cues, visible) {
        text = ""
        if (!visible || cues.isEmpty()) return@LaunchedEffect
        while (true) {
            val at = positionSeconds()
            text = if (at == null) "" else VttCues.activeAt(cues, at).orEmpty()
            delay(CAPTION_POLL_MILLIS)
        }
    }

    if (!visible || text.isEmpty()) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val plateWidth = maxWidth * CAPTION_MAX_WIDTH_FRACTION
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = maxHeight * CAPTION_BOTTOM_INSET_FRACTION),
            contentAlignment = Alignment.BottomCenter,
        ) {
            BasicText(
                text = text,
                style = CaptionTextStyle,
                modifier = Modifier
                    .widthIn(max = plateWidth)
                    .background(CaptionPlate)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}
