package com.cliftonia.fs42tv.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay

/** What the corner logo shows; a new [generation] is a new appearance. */
data class BugState(val label: String, val number: String, val logo: ImageBitmap?, val generation: Int)

/**
 * The station bug: a semi-transparent corner logo for [HOLD_MILLIS] after a tune lands.
 *
 * Top-right, inside the 5% title-safe margin a television may overscan away (48dp of a 960dp-wide
 * canvas, 27dp of 540dp), clear of the banner (top-left), the captions (bottom) and the update
 * prompt (bottom-left). It shares the top-right with the BUFFERING pill, so it steps aside while
 * that is up - a stall is the more useful thing to say. Fades rather than cuts, and never takes
 * more than [MAX_WIDTH_FRACTION] of the width so a long name cannot reach the banner.
 *
 * With a Pluto logo in hand the image stands in for the name, number beside it; without one the
 * bug is text, which is also what it falls back to if the logo never arrives.
 */
@Composable
fun StationBugOverlay(state: BugState?, suppressed: Boolean) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state?.generation) {
        if (state == null) {
            shown = false
            return@LaunchedEffect
        }
        shown = true
        delay(HOLD_MILLIS)
        shown = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown && !suppressed && state != null) BUG_ALPHA else 0f,
        animationSpec = tween(FADE_MILLIS),
        label = "station bug",
    )
    if (state == null || alpha == 0f) return
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(modifier = Modifier.widthIn(max = maxWidth * MAX_WIDTH_FRACTION).graphicsLayer { this.alpha = alpha }) {
            val logo = state.logo
            if (logo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OsdText(text = state.number, fontSize = 14.sp, color = Color.White)
                    Image(
                        bitmap = logo,
                        contentDescription = state.label,
                        modifier = Modifier.height(28.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                OsdText(text = state.label, fontSize = 14.sp, color = Color.White,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A small logo, fetched and decoded on the calling (background) thread; null on any failure.
 *
 * Plain HttpURLConnection and BitmapFactory - no image library for a dozen 280x80 PNGs. Bounds
 * are read first and anything large is subsampled, so a full-size PNG slipping through as the
 * logo url can never put megabytes of bitmap on a 2.3GB television.
 */
fun loadLogo(url: String): ImageBitmap? {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val bytes = connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < MAX_LOGO_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_LOGO_WIDTH_PX) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (e: Exception) {
        android.util.Log.i("fs42", "logo $url failed: $e")
        null
    } finally {
        connection.disconnect()
    }
}

/** About ten seconds, as asked: long enough to register, short enough never to annoy. */
private const val HOLD_MILLIS = 10_000L
private const val FADE_MILLIS = 600
/** Semi-transparent, the way a station bug sits over the picture rather than on it. */
private const val BUG_ALPHA = 0.7f
private const val MAX_WIDTH_FRACTION = 0.28f
private const val MAX_LOGO_BYTES = 1024 * 1024
private const val MAX_LOGO_WIDTH_PX = 600
