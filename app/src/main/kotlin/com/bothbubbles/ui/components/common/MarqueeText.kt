package com.bothbubbles.ui.components.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

/**
 * A text composable that scrolls horizontally (marquee effect) when the text
 * is too long to fit in the available space.
 *
 * The text scrolls to the end with pauses at each end, then calls onScrollComplete.
 * This allows the parent to trigger vertical cycling after the full text has been shown.
 *
 * @param text The text to display
 * @param modifier Modifier for the container
 * @param style Text style to apply
 * @param color Text color
 * @param scrollDurationMs Duration for one full scroll (start to end)
 * @param pauseDurationMs Duration to pause at each end
 * @param onScrollComplete Called when text has fully scrolled (shown all content).
 *        Also called after [noScrollDelayMs] if text fits without scrolling.
 * @param noScrollDelayMs Delay before calling [onScrollComplete] when no scroll is needed
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    scrollDurationMs: Int = 3000,
    pauseDurationMs: Int = 1500,
    onScrollComplete: (() -> Unit)? = null,
    noScrollDelayMs: Long = 5000L
) {
    val textMeasurer = rememberTextMeasurer()
    var containerWidth by remember { mutableFloatStateOf(0f) }
    var textWidth by remember { mutableFloatStateOf(0f) }
    var shouldScroll by remember { mutableStateOf(false) }

    // Marquee animation state
    val marqueeOffset = remember { Animatable(0f) }

    // Calculate text width when text or style changes
    val measuredTextWidth = remember(text, style) {
        textMeasurer.measure(
            text = text,
            style = style,
            constraints = Constraints(maxWidth = Int.MAX_VALUE)
        ).size.width.toFloat()
    }

    // Update text width
    textWidth = measuredTextWidth

    // Animation effect - runs the marquee cycle
    LaunchedEffect(text, shouldScroll, containerWidth, textWidth) {
        if (containerWidth <= 0) return@LaunchedEffect

        if (shouldScroll && containerWidth > 0) {
            val scrollDistance = textWidth - containerWidth

            while (true) {
                // Pause at start
                delay(pauseDurationMs.toLong())

                // Scroll to end
                marqueeOffset.animateTo(
                    targetValue = scrollDistance,
                    animationSpec = tween(
                        durationMillis = scrollDurationMs,
                        easing = LinearEasing
                    )
                )

                // Pause at end - full text is now visible
                delay(pauseDurationMs.toLong())

                // Scroll back to start
                marqueeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = scrollDurationMs,
                        easing = LinearEasing
                    )
                )

                // Pause at start before notifying
                delay(pauseDurationMs.toLong())

                // Notify that full cycle is complete (scrolled to end and back)
                onScrollComplete?.invoke()
            }
        } else {
            // No scroll needed - wait then notify
            delay(noScrollDelayMs)
            onScrollComplete?.invoke()
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                containerWidth = size.width.toFloat()
                shouldScroll = textWidth > containerWidth && containerWidth > 0
            }
    ) {
        if (shouldScroll && containerWidth > 0) {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .graphicsLayer {
                        translationX = -marqueeOffset.value
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
