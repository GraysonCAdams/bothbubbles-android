package com.bluebubbles.ui.effects.bubble

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
 * Loud bubble effect - message scales up large then settles with vibration.
 *
 * From legacy bubble_effects.dart:
 * - Duration: 900ms total
 * - Scale: 1.0 → 3.0 (300ms) → 1.0 (500ms)
 * - Shake: Horizontal oscillation during scale-down
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
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(isNewMessage) {
        if (isNewMessage) {
            // Scale up big then back to normal
            launch {
                scale.animateTo(
                    targetValue = 3f,
                    animationSpec = tween(durationMillis = 300)
                )
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 500)
                )
            }

            // Shake effect during the animation
            launch {
                // Wait a bit for the scale to reach peak
                kotlinx.coroutines.delay(200)

                // Shake 4 times
                repeat(4) {
                    shakeOffset.animateTo(
                        targetValue = 8f,
                        animationSpec = tween(durationMillis = 50)
                    )
                    shakeOffset.animateTo(
                        targetValue = -8f,
                        animationSpec = tween(durationMillis = 50)
                    )
                }
                shakeOffset.animateTo(
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
            translationX = shakeOffset.value
        }
    ) {
        content()
    }
}
