package com.bothbubbles.ui.effects.bubble

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/**
 * Gentle bubble effect - message fades in slowly from tiny.
 *
 * From legacy bubble_effects.dart:
 * - Duration: 1800ms total
 * - Scale: 0.2 → 1.2 (500ms) → 1.0 (800ms)
 * - Alpha: 0 → 1 (500ms)
 * - Subtle, soft appearance
 */
@Composable
fun GentleEffect(
    isNewMessage: Boolean,
    onEffectComplete: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scale = remember { Animatable(if (isNewMessage) 0.2f else 1f) }
    val alpha = remember { Animatable(if (isNewMessage) 0f else 1f) }

    LaunchedEffect(isNewMessage) {
        if (isNewMessage) {
            // Animate alpha and scale in parallel
            launch {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 500,
                        easing = LinearOutSlowInEasing
                    )
                )
            }

            // Scale up with slight overshoot, then settle
            scale.animateTo(
                targetValue = 1.2f,
                animationSpec = tween(
                    durationMillis = 500,
                    easing = LinearOutSlowInEasing
                )
            )
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 800,
                    easing = FastOutSlowInEasing
                )
            )

            onEffectComplete()
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            this.alpha = alpha.value
        }
    ) {
        content()
    }
}
