package com.cliftonia.fs42tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the break card says. Display-ready strings; the card decides nothing. */
data class BreakCardState(
    /** "07 FLICKS OF FURY" - the banner's own channel line. */
    val channelLine: String,
    /** "BACK TO: Enter the Dragon", or empty when Pluto's guide has nothing cached. */
    val backTo: String,
    /** The frame: a pulse while the break's end is unknown, then a countdown to it. */
    val border: BreakBorder = BreakBorder.Pulse,
)

/**
 * The card's frame - the owner's choice, "pulse, then real countdown". While the playlist has not
 * yet shown where the break ends, the frame marches slowly; once it has, the frame drains to
 * nothing exactly as the programme comes back.
 */
sealed interface BreakBorder {

    object Pulse : BreakBorder

    /** Draining from [fromMillis] to [untilMillis], both on elapsedRealtime. */
    data class Countdown(val fromMillis: Long, val untilMillis: Long) : BreakBorder {

        /** How much of the frame is left at [nowMillis]: 1 at the start, 0 at the end. */
        fun remaining(nowMillis: Long): Float {
            val span = untilMillis - fromMillis
            if (span <= 0) return 0f
            return ((untilMillis - nowMillis).toFloat() / span).coerceIn(0f, 1f)
        }
    }
}

/**
 * The card over a Pluto ad break: WE'LL BE RIGHT BACK, what the channel comes back to, and the
 * channel - the station's own slide in place of Pluto's logo bumper, over the guide's music.
 *
 * The up-next card's look on purpose - opaque black, the OSD's green and outline, the one green
 * rule, left of centre and below the banner's corner - so the two read as the same station, and a
 * banner shown over it (INFO, a surf back) does not print on top of the text.
 */
@Composable
fun BreakCard(state: BreakCardState?) {
    if (state == null) return
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.CenterStart,
    ) {
        val textWidth = maxWidth * 0.8f
        Column(
            modifier = Modifier.padding(start = 60.dp, top = 40.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            OsdText(text = "WE'LL BE", fontSize = 27.5.sp)
            OsdText(text = "RIGHT BACK", fontSize = 27.5.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(120.dp).height(2.dp).background(OsdGreen))
            Spacer(Modifier.height(14.dp))
            if (state.backTo.isNotEmpty()) {
                OsdText(
                    text = state.backTo,
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = textWidth),
                )
                Spacer(Modifier.height(18.dp))
            }
            OsdText(text = state.channelLine, fontSize = 11.sp)
        }
        BreakFrame(state.border)
    }
}
