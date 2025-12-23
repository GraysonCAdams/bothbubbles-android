package com.bothbubbles.ui.components.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay

/**
 * A text composable that combines marquee scrolling with vertical flip animation.
 *
 * Behavior:
 * - If text fits: flips vertically after [flipDelayMs] (default 5 seconds)
 * - If text is too long: scrolls with marquee, then flips after completing scroll
 * - After flip, the cycle repeats (flip back)
 * - Icons/inline content flip together with the text
 *
 * @param text The annotated text to display (supports inline content)
 * @param modifier Modifier for the container
 * @param style Text style to apply
 * @param color Text color
 * @param maxLines Maximum lines to display
 * @param inlineContent Map of inline content for annotated string
 * @param flipDelayMs Delay before flipping when text fits (default 5 seconds)
 * @param scrollDurationMs Duration for one full marquee scroll
 * @param pauseDurationMs Duration to pause at each end of marquee
 * @param flipDurationMs Duration of the flip animation
 */
@Composable
fun FlippingMarqueeText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 2,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    flipDelayMs: Long = 5000L,
    scrollDurationMs: Int = 3000,
    pauseDurationMs: Int = 1500,
    flipDurationMs: Int = 400
) {
    val textMeasurer = rememberTextMeasurer()
    var containerWidth by remember { mutableFloatStateOf(0f) }
    var textWidth by remember { mutableFloatStateOf(0f) }
    var needsScroll by remember { mutableStateOf(false) }

    // Flip animation state: 0f = front, 180f = back (flipped)
    val flipRotation = remember { Animatable(0f) }

    // Marquee animation state
    val marqueeOffset = remember { Animatable(0f) }
    var isAtEnd by remember { mutableStateOf(false) }

    // Calculate text width - for single line measurement
    val plainText = text.text
    val measuredTextWidth = remember(plainText, style) {
        textMeasurer.measure(
            text = plainText,
            style = style,
            constraints = Constraints(maxWidth = Int.MAX_VALUE)
        ).size.width.toFloat()
    }

    textWidth = measuredTextWidth

    // Animation cycle
    LaunchedEffect(needsScroll, containerWidth, textWidth) {
        if (containerWidth <= 0) return@LaunchedEffect

        while (true) {
            if (needsScroll && containerWidth > 0) {
                val scrollDistance = textWidth - containerWidth

                // Wait initial pause at start
                delay(pauseDurationMs.toLong())

                // Scroll to end
                marqueeOffset.animateTo(
                    targetValue = scrollDistance,
                    animationSpec = tween(
                        durationMillis = scrollDurationMs,
                        easing = LinearEasing
                    )
                )
                isAtEnd = true

                // Pause at end
                delay(pauseDurationMs.toLong())

                // Now flip
                flipRotation.animateTo(
                    targetValue = if (flipRotation.value < 90f) 180f else 0f,
                    animationSpec = tween(durationMillis = flipDurationMs)
                )

                // Reset marquee to start for next cycle
                marqueeOffset.snapTo(0f)
                isAtEnd = false

                // Small delay before starting next scroll cycle
                delay(pauseDurationMs.toLong())
            } else {
                // Text fits - just wait then flip
                delay(flipDelayMs)

                // Flip
                flipRotation.animateTo(
                    targetValue = if (flipRotation.value < 90f) 180f else 0f,
                    animationSpec = tween(durationMillis = flipDurationMs)
                )
            }
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                containerWidth = size.width.toFloat()
                // For multi-line text, we consider it needs scroll if single-line width exceeds container
                // This is a heuristic - actual overflow depends on line breaking
                needsScroll = textWidth > containerWidth * maxLines && containerWidth > 0
            }
            .graphicsLayer {
                // Vertical flip rotation around X axis
                rotationX = flipRotation.value
                // Keep content visible during flip by adjusting camera distance
                cameraDistance = 12f * density
            }
    ) {
        if (needsScroll && containerWidth > 0 && maxLines == 1) {
            // Single line with marquee scroll
            Text(
                text = text,
                inlineContent = inlineContent,
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
            // Multi-line or fits - show with ellipsis if needed
            Text(
                text = text,
                inlineContent = inlineContent,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Overload for plain String text without inline content.
 */
@Composable
fun FlippingMarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 2,
    flipDelayMs: Long = 5000L,
    scrollDurationMs: Int = 3000,
    pauseDurationMs: Int = 1500,
    flipDurationMs: Int = 400
) {
    FlippingMarqueeText(
        text = AnnotatedString(text),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        inlineContent = emptyMap(),
        flipDelayMs = flipDelayMs,
        scrollDurationMs = scrollDurationMs,
        pauseDurationMs = pauseDurationMs,
        flipDurationMs = flipDurationMs
    )
}
