package com.cliftonia.fs42tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** The green the existing FieldStation42 box draws its OSD in, so this reads as one product. */
val OsdGreen = Color(0xFF33FF33)

/**
 * The persistent corner channel indicator.
 *
 * Shows what is ON AIR, which is not the same as where the dial navigator points - they differ
 * whenever a tune fails and the previous picture stays up.
 */
@Composable
fun ChannelIndicator(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Text(
            text = text,
            color = OsdGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            modifier = Modifier.padding(start = 48.dp, top = 36.dp),
        )
    }
}

/**
 * The tune banner: channel line above, programme title below.
 *
 * Auto-hides via a LaunchedEffect keyed on [generation], so a new tune cancels the previous
 * timer rather than letting an earlier one hide a later banner. That bug needed explicit
 * callback removal under Views; here the key does it.
 */
@Composable
fun ChannelBanner(
    channelLine: String,
    titleLine: String,
    generation: Int,
    holdMillis: Long = 5000,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(generation) {
        if (channelLine.isEmpty()) return@LaunchedEffect
        visible = true
        delay(holdMillis)
        visible = false
    }
    if (!visible) return

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Column(
            modifier = Modifier
                .padding(start = 48.dp, bottom = 56.dp)
                .background(Color(0xB0000000))
                .padding(20.dp),
        ) {
            Text(
                text = channelLine,
                color = OsdGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
            )
            if (titleLine.isNotEmpty()) {
                Text(
                    text = titleLine,
                    color = Color(0xFFCCFFCC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 900.dp),
                )
            }
        }
    }
}
