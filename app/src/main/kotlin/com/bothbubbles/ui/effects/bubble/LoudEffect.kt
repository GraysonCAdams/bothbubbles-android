package com.bothbubbles.ui.effects.bubble

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/**
 * Loud bubble effect - message scales up large then settles with rotation shake.
 *
 * - Duration: 900ms total
 * - Scale: 1.0 → 2.5 (300ms) → 1.0 (500ms)
 * - Shake: Rotation oscillation like someone yelling
 * - Creates vibration/shaking effect
 */
@Composable
fun LoudEffect(
    isNewMessage: Boolean,
    onEffectComplete: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scale = remember { Animatable(if (isNewMessage) 1f else 1f) }
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(isNewMessage) {
        if (isNewMessage) {
            // Scale up big then back to normal
            launch {
                scale.animateTo(
                    targetValue = 2.5f,
                    animationSpec = tween(durationMillis = 300)
                )
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 500)
                )
            }

            // Rotation shake effect during the animation
            launch {
                // Wait a bit for the scale to reach peak
                kotlinx.coroutines.delay(150)

                // Shake with rotation - 6 quick oscillations
                repeat(6) { i ->
                    // Decreasing intensity as we go
                    val intensity = 12f - (i * 1.5f)
                    rotation.animateTo(
                        targetValue = intensity,
                        animationSpec = tween(durationMillis = 40)
                    )
                    rotation.animateTo(
                        targetValue = -intensity,
                        animationSpec = tween(durationMillis = 40)
                    )
                }
                rotation.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 50)
                )
            }

            // Wait for effect to complete
            kotlinx.coroutines.delay(900)
            onEffectComplete()
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            rotationZ = rotation.value
        }
    ) {
        content()
    }
}
