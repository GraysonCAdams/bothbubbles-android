package com.bothbubbles.ui.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import com.bothbubbles.ui.chat.SendModeToggleConstants

/**
 * Direction of the flip animation.
 */
enum class FlipDirection {
    /**
     * Text rotates upward (for swipe up gesture).
     */
    UP,

    /**
     * Text rotates downward (for swipe down gesture).
     */
    DOWN
}

/**
 * Placeholder text that flips vertically like an airport departure board when the text changes.
 *
 * The animation creates a 3D rotation effect where:
 * 1. Current text rotates away (0° → 90°)
 * 2. At 90°, text content swaps (invisible as it's edge-on)
 * 3. New text rotates in (90° → 0°)
 *
 * @param text The current text to display
 * @param flipDirection Direction of the flip animation (UP or DOWN)
 * @param modifier Modifier for this composable
 * @param style Text style to apply
 * @param color Text color
 */
@Composable
fun FlipPlaceholderText(
    text: String,
    flipDirection: FlipDirection = FlipDirection.UP,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    val density = LocalDensity.current

    // Track the currently displayed text
    var displayedText by remember { mutableStateOf(text) }
    var targetText by remember { mutableStateOf(text) }

    // Animation state: 0 = showing current, 1 = fully flipped (showing new)
    val flipProgress = remember { Animatable(0f) }

    // Track if we're in the first half (flipping out) or second half (flipping in)
    val isFlippingOut = flipProgress.value < 0.5f

    // Calculate rotation angle based on progress and direction
    // First half: 0° to 90° (current text rotating away)
    // Second half: 90° to 0° (new text rotating in)
    val rotationX = remember(flipProgress.value, flipDirection) {
        val directionMultiplier = if (flipDirection == FlipDirection.UP) 1f else -1f
        if (isFlippingOut) {
            // Rotating out: 0 to 90
            flipProgress.value * 180f * directionMultiplier
        } else {
            // Rotating in: -90 to 0 (for smooth continuation)
            (flipProgress.value - 1f) * 180f * directionMultiplier
        }
    }

    // Launch flip animation when text changes
    LaunchedEffect(text) {
        if (text != displayedText && flipProgress.value == 0f) {
            targetText = text

            // First half: flip out current text
            flipProgress.animateTo(
                targetValue = 0.5f,
                animationSpec = tween(
                    durationMillis = SendModeToggleConstants.TEXT_FLIP_HALF_MS,
                    easing = FastOutSlowInEasing
                )
            )

            // Swap text at midpoint
            displayedText = targetText

            // Second half: flip in new text
            flipProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SendModeToggleConstants.TEXT_FLIP_HALF_MS,
                    easing = FastOutSlowInEasing
                )
            )

            // Reset for next animation
            flipProgress.snapTo(0f)
        }
    }

    // If text changed while idle, update immediately
    LaunchedEffect(text, flipProgress.value) {
        if (text != displayedText && flipProgress.value == 0f && text != targetText) {
            targetText = text
            // Trigger animation in next frame
            flipProgress.animateTo(
                targetValue = 0.5f,
                animationSpec = tween(
                    durationMillis = SendModeToggleConstants.TEXT_FLIP_HALF_MS,
                    easing = FastOutSlowInEasing
                )
            )
            displayedText = targetText
            flipProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SendModeToggleConstants.TEXT_FLIP_HALF_MS,
                    easing = FastOutSlowInEasing
                )
            )
            flipProgress.snapTo(0f)
        }
    }

    Box(
        modifier = modifier.clipToBounds()
    ) {
        Text(
            text = displayedText,
            style = style,
            color = color,
            modifier = Modifier.graphicsLayer {
                this.rotationX = rotationX
                // Increase camera distance for more subtle 3D effect
                cameraDistance = 8f * density.density
                // Hide backface when rotated past 90 degrees
                alpha = if (kotlin.math.abs(rotationX) > 90f) 0f else 1f
            }
        )
    }
}

/**
 * Overload that accepts a drag progress value for real-time preview during swipe gestures.
 * This allows the text to visually tilt/preview the flip as the user drags.
 *
 * @param text Current text
 * @param nextText Text to show after flip completes
 * @param dragProgress Normalized drag progress (-1 to 1), used for preview tilt
 * @param hasPassedThreshold Whether the drag has passed the commit threshold
 * @param modifier Modifier for this composable
 * @param style Text style
 * @param color Text color
 */
@Composable
fun FlipPlaceholderTextWithDragPreview(
    text: String,
    nextText: String,
    dragProgress: Float,
    hasPassedThreshold: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    val density = LocalDensity.current

    // Determine displayed text based on threshold
    val displayedText = if (hasPassedThreshold) nextText else text

    // Calculate preview tilt based on drag progress (max 15 degrees)
    val previewTilt = dragProgress * 15f

    Box(
        modifier = modifier.clipToBounds()
    ) {
        Text(
            text = displayedText,
            style = style,
            color = color,
            modifier = Modifier.graphicsLayer {
                rotationX = previewTilt
                cameraDistance = 8f * density.density
            }
        )
    }
}
