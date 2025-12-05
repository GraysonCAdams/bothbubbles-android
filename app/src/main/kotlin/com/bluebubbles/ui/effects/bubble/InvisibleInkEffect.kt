package com.bluebubbles.ui.effects.bubble

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Invisible Ink bubble effect - message is hidden behind shimmer until tapped.
 *
 * From legacy bubble_effects.dart:
 * - Particles: (height × width) / 25 particles
 * - Particle size: 0.5-1.0 random
 * - Particle velocity: 0-10 random
 * - Color: White at 150 alpha
 * - Reveal: Tap/drag gesture clears particles in radius
 *
 * Implementation:
 * - API 31+: Use RenderEffect.createBlurEffect() for proper blur
 * - API 26-30: Use animated shimmer overlay with gradient
 */
@Composable
fun InvisibleInkEffect(
    onReveal: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var revealed by remember { mutableStateOf(false) }

    // Shimmer animation
    val infiniteTransition = rememberInfiniteTransition(label = "invisibleInkShimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    Box(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (!revealed) {
                    revealed = true
                    onReveal()
                }
            }
    ) {
        // Content (potentially blurred)
        Box(
            modifier = Modifier.then(
                if (!revealed) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // API 31+: Native blur effect
                        Modifier.graphicsLayer {
                            renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                20f, 20f,
                                android.graphics.Shader.TileMode.CLAMP
                            ).asComposeRenderEffect()
                        }
                    } else {
                        // API 26-30: Use Compose blur modifier
                        Modifier.blur(20.dp)
                    }
                } else {
                    Modifier
                }
            )
        ) {
            content()
        }

        // Shimmer overlay when not revealed
        AnimatedVisibility(
            visible = !revealed,
            exit = fadeOut(animationSpec = tween(500))
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawContent()

                        // Draw animated shimmer
                        val shimmerColors = listOf(
                            Color.Gray.copy(alpha = 0.6f),
                            Color.LightGray.copy(alpha = 0.8f),
                            Color.Gray.copy(alpha = 0.6f)
                        )

                        drawRect(
                            brush = Brush.linearGradient(
                                colors = shimmerColors,
                                start = Offset(shimmerOffset - 500f, 0f),
                                end = Offset(shimmerOffset, size.height)
                            )
                        )

                        // Add sparkle dots
                        val dotCount = ((size.width * size.height) / 2500).toInt().coerceIn(5, 50)
                        repeat(dotCount) { i ->
                            val dotX = ((shimmerOffset + i * 73) % size.width)
                            val dotY = ((shimmerOffset * 0.7f + i * 47) % size.height)
                            val dotAlpha = ((kotlin.math.sin((shimmerOffset + i * 100) / 100f) + 1) / 2 * 0.8f)
                                .coerceIn(0f, 1f)

                            drawCircle(
                                color = Color.White.copy(alpha = dotAlpha),
                                radius = 2f + (i % 3),
                                center = Offset(dotX, dotY)
                            )
                        }
                    }
                    .background(Color.Gray.copy(alpha = 0.4f))
            )
        }
    }
}
