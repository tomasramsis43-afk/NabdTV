package com.nabd.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** One shared phase (0..1) so every placeholder on screen sweeps in sync and costs one animation. */
@Composable
fun rememberShimmerPhase(): Float {
    val p by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "shimmerPhase"
    )
    return p
}

/** A light band sweeping diagonally across a placeholder. Apply after .background(). */
fun Modifier.shimmer(phase: Float): Modifier = drawWithContent {
    drawContent()
    val w = size.width
    val x = -w + phase * 3f * w
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent),
            start = Offset(x, 0f), end = Offset(x + w, size.height)
        )
    )
}
