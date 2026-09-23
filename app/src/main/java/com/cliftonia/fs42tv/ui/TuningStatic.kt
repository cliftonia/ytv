package com.cliftonia.fs42tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Snow over the tuning blank - the STATIC row's picture half. Drawn exactly where [TuningBlank]
 * is and exactly as long, so switched off the blank is what it always was.
 *
 * The cost is deliberately fixed and small: [FRAME_COUNT] frames of 160x90 made once per process
 * (see [SnowFrames]), and each tick is one scaled bitmap draw with nearest-neighbour filtering -
 * no per-pixel work at panel resolution, nothing in mpv. The tick is read inside the draw block
 * only, so an animation frame is a redraw, never a recomposition.
 *
 * [animate] false holds one frame: under the guide or settings the snow is barely visible through
 * the backdrop, and redrawing it 22 times a second there would be spending the SoC on nothing.
 */
@Composable
fun TuningStatic(visible: Boolean, animate: Boolean) {
    if (!visible) return
    val frames = remember { snowFrames }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(animate) {
        while (animate) {
            delay(FRAME_MILLIS)
            tick += 1
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        // A scrambled order that never repeats a frame twice running: cycling in order at 22fps
        // shows the loop as a pattern, and a repeated frame reads as a stutter.
        val index = (tick * 5 + (tick % 3)) % frames.size
        drawImage(
            image = frames[index],
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(SNOW_WIDTH, SNOW_HEIGHT),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

/** About 22fps: snow reads as motion well below the panel's rate, and each tick costs a draw. */
private const val FRAME_MILLIS = 45L
private const val FRAME_COUNT = 8
private const val SNOW_WIDTH = 160
private const val SNOW_HEIGHT = 90

/** 8 x 57KB, made on first use and kept: re-generating per tune would be allocation for nothing. */
private val snowFrames: List<ImageBitmap> by lazy {
    val random = Random.Default
    List(FRAME_COUNT) {
        Bitmap.createBitmap(
            SnowFrames.generate(SNOW_WIDTH, SNOW_HEIGHT, random),
            SNOW_WIDTH, SNOW_HEIGHT, Bitmap.Config.ARGB_8888,
        ).asImageBitmap()
    }
}
