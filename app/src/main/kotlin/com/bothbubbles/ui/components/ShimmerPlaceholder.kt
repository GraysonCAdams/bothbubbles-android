package com.bothbubbles.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A shimmer loading effect overlay for placeholder states.
 *
 * Creates an animated gradient that sweeps across the component,
 * giving a visual indication that content is loading.
 *
 * @param modifier Modifier to apply to the shimmer container
 * @param baseColor The base background color (default: surface container)
 * @param highlightColor The shimmer highlight color (default: surface)
 * @param durationMs Animation duration in milliseconds (default: 1200)
 */
@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    highlightColor: Color = MaterialTheme.colorScheme.surface,
    durationMs: Int = 1200
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        baseColor,
                        highlightColor,
                        baseColor
                    ),
                    start = Offset(
                        x = -300f + shimmerTranslate * 900f,
                        y = 0f
                    ),
                    end = Offset(
                        x = shimmerTranslate * 900f,
                        y = 0f
                    )
                )
            )
    )
}

/**
 * A shimmer loading effect with rounded corners, suitable for attachment placeholders.
 *
 * @param modifier Modifier to apply to the shimmer container
 * @param baseColor The base background color
 * @param highlightColor The shimmer highlight color
 */
@Composable
fun ShimmerAttachmentPlaceholder(
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    highlightColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    ShimmerPlaceholder(
        modifier = modifier,
        baseColor = baseColor,
        highlightColor = highlightColor,
        durationMs = 1000
    )
}
