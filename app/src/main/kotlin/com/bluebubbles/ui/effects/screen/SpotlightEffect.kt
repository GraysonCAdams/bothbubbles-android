package com.bluebubbles.ui.effects.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.max
import kotlin.random.Random

/**
 * Spotlight screen effect - dark overlay with spotlight on message.
 *
 * From legacy spotlight_classes.dart:
 * - Dark overlay (0.85 alpha)
 * - Circular cutout around message bubble
 * - Position wiggles slightly (random ±0.5)
 * - After 3 seconds, fade out begins (stop decrements by 0.05)
 * - Size is max(width, height) + 50
 * - Tap to dismiss early
 */

@Composable
fun SpotlightEffect(
    messageBounds: Rect? = null,
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var spotlightX by remember { mutableFloatStateOf(0f) }
    var spotlightY by remember { mutableFloatStateOf(0f) }
    var originalX by remember { mutableFloatStateOf(0f) }
    var originalY by remember { mutableFloatStateOf(0f) }
    var spotlightRadius by remember { mutableFloatStateOf(150f) }
    var startTime by remember { mutableLongStateOf(0L) }
    var initialized by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    val overlayAlpha = remember { Animatable(0f) }
    val spotlightAlpha = remember { Animatable(1f) }

    // Fade in animation
    LaunchedEffect(Unit) {
        overlayAlpha.animateTo(0.85f, tween(300))
    }

    // Handle dismissal
    LaunchedEffect(dismissed) {
        if (dismissed) {
            overlayAlpha.animateTo(0f, tween(300))
            onComplete()
        }
    }

    // Animation loop
    LaunchedEffect(Unit) {
        startTime = withFrameNanos { it }

        while (!dismissed) {
            val frameTime = withFrameNanos { it }
            val elapsed = (frameTime - startTime) / 1_000_000_000f

            if (!initialized) continue

            // Wiggle position slightly for first 3 seconds
            if (elapsed < 3f) {
                spotlightX = originalX + (Random.nextFloat() - 0.5f)
                spotlightY = originalY + (Random.nextFloat() - 0.5f)
            } else {
                // Start fading out after 3 seconds
                val fadeProgress = (elapsed - 3f) / 1f // Fade over 1 second
                if (fadeProgress >= 1f) {
                    dismissed = true
                    break
                }
                spotlightAlpha.snapTo(1f - fadeProgress)
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                dismissed = true
            }
    ) {
        if (!initialized) {
            initialized = true

            // Use message bounds if provided, otherwise center of screen
            if (messageBounds != null) {
                originalX = messageBounds.center.x
                originalY = messageBounds.center.y
                spotlightRadius = max(messageBounds.width, messageBounds.height) / 2f + 50f
            } else {
                originalX = size.width / 2f
                originalY = size.height / 2f
                spotlightRadius = 150f
            }

            spotlightX = originalX
            spotlightY = originalY
        }

        // Draw dark overlay
        drawRect(
            color = Color.Black.copy(alpha = overlayAlpha.value)
        )

        // Cut out the spotlight circle using BlendMode.DstOut
        // This creates a transparent "hole" in the dark overlay

        // Outer glow (soft edge)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Black,
                    Color.Black.copy(alpha = 0.5f),
                    Color.Transparent
                ),
                center = Offset(spotlightX, spotlightY),
                radius = spotlightRadius * 1.5f
            ),
            radius = spotlightRadius * 1.5f,
            center = Offset(spotlightX, spotlightY),
            blendMode = BlendMode.DstOut
        )

        // Main spotlight (clear center)
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Black,
                0.7f to Color.Black,
                0.9f to Color.Black.copy(alpha = 0.8f),
                1f to Color.Transparent,
                center = Offset(spotlightX, spotlightY),
                radius = spotlightRadius
            ),
            radius = spotlightRadius,
            center = Offset(spotlightX, spotlightY),
            blendMode = BlendMode.DstOut
        )

        // Add subtle light ring at edge of spotlight
        if (spotlightAlpha.value > 0) {
            drawCircle(
                brush = Brush.radialGradient(
                    0.85f to Color.Transparent,
                    0.95f to Color.White.copy(alpha = 0.1f * spotlightAlpha.value),
                    1f to Color.Transparent,
                    center = Offset(spotlightX, spotlightY),
                    radius = spotlightRadius * 1.1f
                ),
                radius = spotlightRadius * 1.1f,
                center = Offset(spotlightX, spotlightY)
            )
        }
    }
}
