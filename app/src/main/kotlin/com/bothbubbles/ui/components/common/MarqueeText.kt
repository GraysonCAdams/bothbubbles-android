package com.bothbubbles.ui.components.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * A text composable that scrolls horizontally (marquee effect) when the text
 * is too long to fit in the available space.
 *
 * The text scrolls back and forth with pauses at each end, creating a smooth
 * reading experience for long text.
 *
 * @param text The text to display
 * @param modifier Modifier for the container
 * @param style Text style to apply
 * @param color Text color
 * @param scrollDurationMs Duration for one full scroll (start to end or end to start)
 * @param pauseDurationMs Duration to pause at each end before reversing
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    scrollDurationMs: Int = 3000,
    pauseDurationMs: Int = 1500
) {
    val textMeasurer = rememberTextMeasurer()
    var containerWidth by remember { mutableFloatStateOf(0f) }
    var textWidth by remember { mutableFloatStateOf(0f) }
    var shouldScroll by remember { mutableStateOf(false) }

    // Calculate text width when text or style changes
    val measuredTextWidth = remember(text, style) {
        textMeasurer.measure(
            text = text,
            style = style,
            constraints = Constraints(maxWidth = Int.MAX_VALUE)
        ).size.width.toFloat()
    }

    // Update text width and determine if scrolling is needed
    textWidth = measuredTextWidth

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                containerWidth = size.width.toFloat()
                shouldScroll = textWidth > containerWidth && containerWidth > 0
            }
    ) {
        if (shouldScroll && containerWidth > 0) {
            // Calculate the scroll distance (how far to move)
            val scrollDistance = textWidth - containerWidth

            // Animation: 0 -> 1 represents start to end, then 1 -> 0 for end to start
            val infiniteTransition = rememberInfiniteTransition(label = "marquee")
            val animationProgress by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = scrollDurationMs + pauseDurationMs * 2,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "marqueeScroll"
            )

            // Convert progress to actual offset with pause zones
            // 0-pauseRatio: paused at start
            // pauseRatio to (1-pauseRatio): scrolling
            // (1-pauseRatio) to 1: paused at end
            val totalDuration = scrollDurationMs + pauseDurationMs * 2
            val pauseRatio = pauseDurationMs.toFloat() / totalDuration

            val offset = when {
                animationProgress < pauseRatio -> 0f
                animationProgress > (1 - pauseRatio) -> scrollDistance
                else -> {
                    val scrollProgress = (animationProgress - pauseRatio) / (1 - 2 * pauseRatio)
                    scrollProgress * scrollDistance
                }
            }

            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .graphicsLayer {
                        translationX = -offset
                    }
            )
        } else {
            // No scrolling needed - just show text with ellipsis if needed
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
