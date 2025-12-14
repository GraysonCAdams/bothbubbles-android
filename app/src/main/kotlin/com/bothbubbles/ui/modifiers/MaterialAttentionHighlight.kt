package com.bothbubbles.ui.modifiers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Material Design 3 "Attention" highlight modifier.
 *
 * Instead of an external glow/shadow, this tints the content itself with the
 * TertiaryContainer color using a "breathing" animation. The blend mode ensures
 * the highlight respects the exact shape of the content (bubble corners, tails, etc.)
 * without needing explicit shape parameters.
 *
 * Animation timeline ("Fast In, Hold, Slow Out"):
 * - 0-300ms:    Fade in to 0.6 alpha (FastOutSlowIn - attention grab)
 * - 300-900ms:  Hold at 0.6 alpha (user locates message)
 * - 900-2000ms: Fade out to 0 (FastOutLinearIn - gentle relaxation)
 *
 * @param shouldHighlight When true, triggers the one-shot highlight animation
 * @param onHighlightFinished Callback invoked when animation completes
 */
fun Modifier.materialAttentionHighlight(
    shouldHighlight: Boolean,
    onHighlightFinished: (() -> Unit)? = null
): Modifier = composed {
    // MD3: TertiaryContainer adapts to system theme/wallpaper (Dynamic Color)
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer

    // Track animation state to ensure one-shot behavior
    var hasAnimated by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(shouldHighlight) {
        if (shouldHighlight && !hasAnimated) {
            hasAnimated = true

            // Phase 1: Fast attention grab (0 -> 0.6 in 300ms)
            alpha.animateTo(
                targetValue = 0.6f,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing
                )
            )

            // Phase 2: Hold for visual scanning (600ms at 0.6)
            alpha.animateTo(
                targetValue = 0.6f,
                animationSpec = tween(
                    durationMillis = 600,
                    easing = LinearEasing
                )
            )

            // Phase 3: Slow relaxation fade out (0.6 -> 0 in 1100ms)
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 1100,
                    easing = FastOutLinearInEasing
                )
            )

            onHighlightFinished?.invoke()
        }
    }

    // Reset when highlight is cleared (allows re-triggering if needed)
    LaunchedEffect(shouldHighlight) {
        if (!shouldHighlight) {
            hasAnimated = false
            alpha.snapTo(0f)
        }
    }

    this
        .graphicsLayer {
            // Enable off-screen compositing for BlendMode to work correctly
            // Without this, SrcAtop blends with the entire screen, not just our content
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .drawWithContent {
            // Draw the original content first (destination)
            drawContent()

            // Overlay the highlight color only where content exists (SrcAtop)
            if (alpha.value > 0f) {
                drawRect(
                    color = highlightColor.copy(alpha = alpha.value),
                    blendMode = BlendMode.SrcAtop
                )
            }
        }
}
