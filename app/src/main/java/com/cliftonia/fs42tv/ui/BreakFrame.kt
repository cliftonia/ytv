package com.cliftonia.fs42tv.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The break card's frame: a slow marching dash while the break's end is unknown, then a line
 * round the frame that drains to nothing exactly as the programme comes back.
 *
 * Cheap on purpose - this SoC decodes 4K under it. Every animated value is read only inside the
 * draw lambda, so a frame of animation is a redraw of one rectangle, never a recomposition; and
 * both animations belong to this composable, so they stop the moment the card is hidden or gone.
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

@Composable
private fun PulseFrame() {
    val transition = rememberInfiniteTransition(label = "break-pulse")
    // The march: one dash-and-gap along every 1.6s. The glow: dim to bright and back over 3.2s.
    val march = transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(MARCH_MILLIS, easing = LinearEasing)), label = "march")
    val glow = transition.animateFloat(0.25f, 0.8f,
        infiniteRepeatable(tween(GLOW_MILLIS, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    Box(Modifier.fillMaxSize().drawBehind { drawMarching(march, glow) })
}

private fun DrawScope.drawMarching(march: State<Float>, glow: State<Float>) {
    val inset = INSET.toPx()
    val dash = 18.dp.toPx()
    val gap = 12.dp.toPx()
    drawRect(
        color = OsdGreen.copy(alpha = glow.value),
        topLeft = Offset(inset, inset),
        size = size.copy(width = size.width - 2 * inset, height = size.height - 2 * inset),
        style = Stroke(
            width = WIDTH.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), -march.value * (dash + gap)),
        ),
    )
}

@Composable
private fun CountdownFrame(border: BreakBorder.Countdown) {
    val left = remember(border) { Animatable(border.remaining(SystemClock.elapsedRealtime())) }
    LaunchedEffect(border) {
        val now = SystemClock.elapsedRealtime()
        left.snapTo(border.remaining(now))
        val millis = (border.untilMillis - now).coerceAtLeast(0L).toInt()
        left.animateTo(0f, tween(millis, easing = LinearEasing))
    }
    Box(Modifier.fillMaxSize().drawBehind { drawCountdown(left.value) })
}

/** A faint whole frame, and over it the part still to run, clockwise from the top left. */
private fun DrawScope.drawCountdown(left: Float) {
    val inset = INSET.toPx()
    val stroke = WIDTH.toPx()
    val w = size.width - 2 * inset
    val h = size.height - 2 * inset
    drawRect(
        color = OsdGreen.copy(alpha = 0.15f),
        topLeft = Offset(inset, inset),
        size = size.copy(width = w, height = h),
        style = Stroke(width = stroke),
    )
    var length = left * (2 * w + 2 * h)
    val corners = listOf(
        Offset(inset, inset) to Offset(inset + w, inset),
        Offset(inset + w, inset) to Offset(inset + w, inset + h),
        Offset(inset + w, inset + h) to Offset(inset, inset + h),
        Offset(inset, inset + h) to Offset(inset, inset),
    )
    for ((from, to) in corners) {
        if (length <= 0f) break
        val edge = (to - from).getDistance()
        val part = minOf(length, edge) / edge
        drawLine(OsdGreen, from, from + (to - from) * part, strokeWidth = stroke)
        length -= edge
    }
}

private const val MARCH_MILLIS = 1_600
private const val GLOW_MILLIS = 3_200
