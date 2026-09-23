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

/** What the "up next" card says. Display-ready strings; the card decides nothing. */
data class UpNextState(
    /** "07 SEVEN" - the banner's own channel line. */
    val channelLine: String,
    /** "8:00", in the device's 12/24-hour style. */
    val time: String,
    val title: String,
)

/**
 * The card between programmes on the half-hour schedule: UP NEXT, the time, the title, and the
 * channel - the station's own "coming up" slide, over the guide's music.
 *
 * Opaque black like the blank it replaces, with the OSD's green and outline, so it reads as the
 * same station as the banner rather than as an app dialog. The text sits left of centre and
 * below the banner's corner, so a banner shown over the card (a surf back, INFO) does not print
 * on top of it.
 */
@Composable
fun UpNextCard(state: UpNextState?) {
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
            OsdText(text = "UP NEXT", fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            // A green rule under the heading: the one graphic, the way a station slide had one.
            Box(Modifier.width(120.dp).height(2.dp).background(OsdGreen))
            Spacer(Modifier.height(14.dp))
            OsdText(text = state.time, fontSize = 27.5.sp)
            if (state.title.isNotEmpty()) {
                OsdText(
                    text = state.title,
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = textWidth),
                )
            }
            Spacer(Modifier.height(18.dp))
            OsdText(text = state.channelLine, fontSize = 11.sp)
        }
    }
}
