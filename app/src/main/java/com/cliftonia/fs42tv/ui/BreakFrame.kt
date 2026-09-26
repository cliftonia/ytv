package com.cliftonia.fs42tv.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The break card's frame: a slow marching dash while the break's end is unknown, then a line
 * round the frame that drains to nothing exactly as the programme comes back.
 *
 * Cheap on purpose - this SoC decodes 4K under it:
 *  - Every animated value is read only inside the draw lambda, so a step is a redraw of one
 *    shape, never a recomposition.
 *  - The march STEPS, ten times a second, through a fixed set of dash effects built once; a
 *    march at every vsync with a new PathEffect per frame was sixty allocations a second for
 *    movement nobody can tell from ten.
 *  - The countdown is the one thing drawn per vsync, because it is the one the eye follows to
 *    the end: four lines and a rectangle, with its strokes built once.
 *  - Both belong to this composable, so they stop the moment the card is hidden or gone.
 */
@Composable
fun BreakFrame(border: BreakBorder) {
    when (border) {
        BreakBorder.Pulse -> PulseFrame()
        is BreakBorder.Countdown -> CountdownFrame(border)
    }
}

private val INSET = 28.dp
private val WIDTH = 3.dp
private val DASH = 18.dp
private val GAP = 12.dp

/** Ten steps a second; one dash-and-gap marched every 16 steps (1.6s); glow in and out over 32. */
private const val STEP_MILLIS = 100L
private const val MARCH_STEPS = 16
private const val GLOW_STEPS = 32

@Composable
private fun PulseFrame() {
    val density = LocalDensity.current
    val strokes = remember(density) {
        with(density) {
            val dash = DASH.toPx()
            val gap = GAP.toPx()
            List(MARCH_STEPS) { i ->
                Stroke(
                    width = WIDTH.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(dash, gap), -(dash + gap) * i / MARCH_STEPS),
                )
            }
        }
    }
    val step = remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STEP_MILLIS)
            step.value = step.value + 1
        }
    }
    Box(Modifier.fillMaxSize().drawBehind {
        val n = step.value
        // A triangle wave, 0.25 to 0.8 and back.
        val phase = n % GLOW_STEPS
        val rising = if (phase < GLOW_STEPS / 2) phase else GLOW_STEPS - phase
        val glow = 0.25f + 0.55f * rising / (GLOW_STEPS / 2)
        frameRect(OsdGreenAlpha[(glow * ALPHA_LEVELS).toInt().coerceIn(0, ALPHA_LEVELS)],
            strokes[n % MARCH_STEPS])
    })
}

/** The OSD green at each alpha the glow can take, built once: no Color per draw. */
private const val ALPHA_LEVELS = 20
private val OsdGreenAlpha = List(ALPHA_LEVELS + 1) { OsdGreen.copy(alpha = it.toFloat() / ALPHA_LEVELS) }

private fun DrawScope.frameRect(color: androidx.compose.ui.graphics.Color, stroke: Stroke) {
    val inset = INSET.toPx()
    drawRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = size.copy(width = size.width - 2 * inset, height = size.height - 2 * inset),
        style = stroke,
    )
}

@Composable
private fun CountdownFrame(border: BreakBorder.Countdown) {
    val density = LocalDensity.current
    val stroke = remember(density) { Stroke(width = with(density) { WIDTH.toPx() }) }
    val left = remember(border) { Animatable(border.remaining(SystemClock.elapsedRealtime())) }
    LaunchedEffect(border) {
        val now = SystemClock.elapsedRealtime()
        left.snapTo(border.remaining(now))
        val millis = (border.untilMillis - now).coerceAtLeast(0L).toInt()
        left.animateTo(0f, tween(millis, easing = LinearEasing))
    }
    Box(Modifier.fillMaxSize().drawBehind { drawCountdown(left.value, stroke) })
}

/** A faint whole frame, and over it the part still to run, clockwise from the top left. */
private fun DrawScope.drawCountdown(left: Float, stroke: Stroke) {
    frameRect(OsdGreenAlpha[3], stroke)
    val inset = INSET.toPx()
    val w = size.width - 2 * inset
    val h = size.height - 2 * inset
    var length = left * (2 * w + 2 * h)
    // Clockwise: top, right, bottom, left - each edge drawn whole or in part, in turn.
    length = edge(Offset(inset, inset), Offset(inset + w, inset), length, stroke.width)
    length = edge(Offset(inset + w, inset), Offset(inset + w, inset + h), length, stroke.width)
    length = edge(Offset(inset + w, inset + h), Offset(inset, inset + h), length, stroke.width)
    edge(Offset(inset, inset + h), Offset(inset, inset), length, stroke.width)
}

/** Draw as much of the edge [from]-[to] as [length] covers; what is left over. */
private fun DrawScope.edge(from: Offset, to: Offset, length: Float, width: Float): Float {
    if (length <= 0f) return 0f
    val span = (to - from).getDistance()
    drawLine(OsdGreen, from, from + (to - from) * (minOf(length, span) / span), strokeWidth = width)
    return length - span
}
