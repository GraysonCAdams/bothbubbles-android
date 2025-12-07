package com.bothbubbles.ui.effects.bubble

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import kotlin.math.PI

/**
 * Slam bubble effect - message scales up huge then slams down with rotation.
 *
 * From legacy bubble_effects.dart:
 * - Duration: 500ms total
 * - Scale Keyframes:
 *   - 0ms: scale 1.0
 *   - 200ms: scale 5.0 (Curves.easeIn)
 *   - 350ms: scale 0.8
 *   - 500ms: scale 1.0
 * - Rotation: 0 → ±π/16 radians (direction based on isFromMe)
 * - Easing: Curves.easeIn
 */
@Composable
fun SlamEffect(
    isNewMessage: Boolean,
    isFromMe: Boolean,
    onEffectComplete: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scale = remember { Animatable(if (isNewMessage) 1f else 1f) }
    val rotation = remember { Animatable(0f) }

    // Rotation direction based on sender
    val rotationDirection = if (isFromMe) 1f else -1f
    val rotationAmount = (PI / 16f).toFloat() // ~11.25 degrees

    LaunchedEffect(isNewMessage) {
        if (isNewMessage) {
            // Phase 1: Scale up big (200ms)
            scale.animateTo(
                targetValue = 5f,
                animationSpec = tween(
                    durationMillis = 200,
                    easing = FastOutSlowInEasing
                )
            )

            // Phase 2: Scale down with overshoot (150ms) + add rotation
            launch {
                rotation.animateTo(
                    targetValue = rotationDirection * rotationAmount,
                    animationSpec = tween(durationMillis = 100)
                )
            }
            scale.animateTo(
                targetValue = 0.8f,
                animationSpec = tween(durationMillis = 150)
            )

            // Phase 3: Settle to normal (150ms) + reset rotation
            launch {
                rotation.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 150)
                )
            }
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 150)
            )

            onEffectComplete()
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            rotationZ = Math.toDegrees(rotation.value.toDouble()).toFloat()
        }
    ) {
        content()
    }
}
