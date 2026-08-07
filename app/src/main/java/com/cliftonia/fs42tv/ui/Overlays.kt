package com.cliftonia.fs42tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

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
