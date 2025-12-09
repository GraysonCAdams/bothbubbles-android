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
 * Gentle bubble effect - message fades in very slowly from tiny.
 *
 * - Duration: ~2500ms total
 * - Scale: 0.3 → 1.08 (1000ms) → 1.0 (1200ms)
 * - Alpha: 0 → 1 (1000ms)
 * - Subtle, soft, dreamy appearance
 */
@Composable
fun GentleEffect(
    isNewMessage: Boolean,
    onEffectComplete: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scale = remember { Animatable(if (isNewMessage) 0.3f else 1f) }
    val alpha = remember { Animatable(if (isNewMessage) 0f else 1f) }

    LaunchedEffect(isNewMessage) {
        if (isNewMessage) {
            // Animate alpha and scale in parallel - slow and gentle
            launch {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 1000,
                        easing = LinearOutSlowInEasing
                    )
                )
            }

            // Scale up with very slight overshoot, then settle slowly
            scale.animateTo(
                targetValue = 1.08f,
                animationSpec = tween(
                    durationMillis = 1000,
                    easing = LinearOutSlowInEasing
                )
            )
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1200,
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
