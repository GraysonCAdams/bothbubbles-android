package com.bothbubbles.ui.effects.bubble

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Invisible Ink bubble effect.
 *
 * For text: Black redaction bars, tap to toggle reveal/hide.
 * For media: Blur effect, hold to gradually reveal, hold again to re-blur.
 *
 * @param hasMedia If true, uses blur + hold-to-reveal for images. If false, uses text redaction bars.
 * @param onMediaClickBlocked Called when user tries to click media while still hidden (to block fullscreen).
 */
@Composable
fun InvisibleInkEffect(
    isRevealed: Boolean = false,
    onRevealStateChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    text: String? = null,
    textStyle: TextStyle = TextStyle.Default,
    hasMedia: Boolean = false,
    onMediaClickBlocked: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (hasMedia) {
        InvisibleInkMediaEffect(
            isRevealed = isRevealed,
            onRevealStateChanged = onRevealStateChanged,
            onMediaClickBlocked = onMediaClickBlocked,
            modifier = modifier,
            content = content
        )
    } else {
        InvisibleInkTextEffect(
            isRevealed = isRevealed,
            onRevealStateChanged = onRevealStateChanged,
            modifier = modifier,
            content = content
        )
    }
}

/**
 * Text redaction effect - black bars over text lines, tap to toggle.
 */
@Composable
private fun InvisibleInkTextEffect(
    isRevealed: Boolean,
    onRevealStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var localRevealed by remember { mutableStateOf(isRevealed) }

    // Smooth fade animation for reveal
    val revealProgress by animateFloatAsState(
        targetValue = if (localRevealed) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "revealProgress"
    )

    // Sync with external state
    LaunchedEffect(isRevealed) {
        localRevealed = isRevealed
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // Toggle reveal state
                        localRevealed = !localRevealed
                        onRevealStateChanged(localRevealed)
                    }
                )
            }
            .drawWithContent {
                drawContent()

                // Draw black redaction bars over text lines
                if (revealProgress < 1f) {
                    val overlayAlpha = 1f - revealProgress

                    val lineHeight = 20.dp.toPx()
                    val barHeight = 16.dp.toPx()
                    val horizontalPadding = 12.dp.toPx()
                    val verticalPadding = 8.dp.toPx()
                    val cornerRadius = 4.dp.toPx()

                    val contentHeight = size.height - (verticalPadding * 2)
                    val numLines = ((contentHeight / lineHeight) + 0.5f).toInt().coerceAtLeast(1)

                    for (i in 0 until numLines) {
                        val y = verticalPadding + (i * lineHeight) + ((lineHeight - barHeight) / 2)
                        val barWidth = if (i == numLines - 1 && numLines > 1) {
                            (size.width - horizontalPadding * 2) * 0.7f
                        } else {
                            size.width - horizontalPadding * 2
                        }

                        drawRoundRect(
                            color = Color.Black.copy(alpha = overlayAlpha),
                            topLeft = Offset(horizontalPadding, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                        )
                    }
                }
            }
    ) {
        content()
    }
}

/**
 * Media blur effect - hold to gradually reveal, hold again to re-blur.
 */
@Composable
private fun InvisibleInkMediaEffect(
    isRevealed: Boolean,
    onRevealStateChanged: (Boolean) -> Unit,
    onMediaClickBlocked: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var localRevealed by remember { mutableStateOf(isRevealed) }
    var blurAmount by remember { mutableFloatStateOf(if (isRevealed) 0f else 1f) }
    var isHolding by remember { mutableStateOf(false) }
    var holdStartTime by remember { mutableLongStateOf(0L) }

    // Duration to fully reveal/hide (in seconds)
    val revealDuration = 1.0f

    // Sync with external state
    LaunchedEffect(isRevealed) {
        localRevealed = isRevealed
        if (!isRevealed) {
            blurAmount = 1f
        }
    }

    // Animate blur while holding
    LaunchedEffect(isHolding) {
        if (isHolding) {
            holdStartTime = withFrameNanos { it }
            val startBlur = blurAmount
            val targetBlur = if (localRevealed) 1f else 0f // If revealed, re-blur; if hidden, reveal

            while (isHolding) {
                val frameTime = withFrameNanos { it }
                val elapsed = (frameTime - holdStartTime) / 1_000_000_000f
                val progress = (elapsed / revealDuration).coerceIn(0f, 1f)

                // Interpolate blur
                blurAmount = startBlur + (targetBlur - startBlur) * progress

                // Check if fully transitioned
                if (progress >= 1f) {
                    localRevealed = targetBlur == 0f
                    onRevealStateChanged(localRevealed)
                    break
                }
            }
        } else {
            // Released before fully transitioned
            // Snap to nearest state
            if (blurAmount < 0.3f) {
                // Close to revealed - snap to revealed
                blurAmount = 0f
                localRevealed = true
                onRevealStateChanged(true)
            } else if (blurAmount > 0.7f) {
                // Close to hidden - snap to hidden
                blurAmount = 1f
                localRevealed = false
                onRevealStateChanged(false)
            }
            // Otherwise stay where it is (will animate on next hold)
        }
    }

    // Convert blur amount to dp (max 20dp blur)
    val blurDp = (blurAmount * 20f).dp

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        try {
                            awaitRelease()
                        } finally {
                            isHolding = false
                        }
                    },
                    onTap = {
                        // If not revealed, block the tap (don't open fullscreen)
                        if (!localRevealed && blurAmount > 0.5f) {
                            onMediaClickBlocked?.invoke()
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (blurAmount > 0.01f) {
                        Modifier.blur(blurDp)
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
        }
    }
}
