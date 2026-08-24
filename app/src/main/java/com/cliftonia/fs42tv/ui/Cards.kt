package com.cliftonia.fs42tv.ui
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


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

/**
 * A quiet line offering the build that has already been downloaded.
 *
 * Bottom-left, in the same green as everything else, and deliberately not a dialog: an update is
 * never urgent on a television, and a modal that steals focus from a channel someone is watching
 * would be a worse interruption than the update is worth. It waits until asked.
 */
@Composable
fun UpdatePrompt(visible: Boolean) {
    if (!visible) return
    Box(modifier = Modifier.fillMaxSize().padding(start = 48.dp, bottom = 48.dp),
        contentAlignment = Alignment.BottomStart) {
        OsdText(text = "UPDATE READY - PRESS OK", fontSize = 20.sp)
    }
}
