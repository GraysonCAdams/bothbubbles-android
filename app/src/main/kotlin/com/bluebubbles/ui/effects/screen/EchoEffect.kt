package com.bluebubbles.ui.effects.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Echo screen effect - message text echoes/repeats outward.
 *
 * From plan specification:
 * - 5-7 duplicates of message text
 * - Scale: 1 → 2 (each copy grows)
 * - Alpha: 1 → 0 (each copy fades out)
 * - Stagger: Each copy starts 200ms after previous
 * - Duration: ~2.5 seconds
 *
 * This creates a ripple-like effect where the text appears
 * to pulse outward from the message location.
 */

private data class EchoCopy(
    val scale: Animatable<Float, *>,
    val alpha: Animatable<Float, *>
)

@Composable
fun EchoEffect(
    messageText: String,
    messageBounds: Rect? = null,
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val copyCount = 6

    // Create animatables for each echo copy
    val echoCopies = remember {
        List(copyCount) {
            EchoCopy(
                scale = Animatable(1f),
                alpha = Animatable(0f)
            )
        }
    }

    // Staggered animation launch
    LaunchedEffect(Unit) {
        echoCopies.forEachIndexed { index, copy ->
            launch {
                // Stagger start by 200ms per copy
                delay(index * 200L)

                // Fade in quickly
                copy.alpha.snapTo(0.8f - index * 0.1f)

                // Animate scale and fade out together
                launch {
                    copy.scale.animateTo(
                        targetValue = 1.5f + index * 0.15f,
                        animationSpec = tween(
                            durationMillis = 800,
                            easing = LinearEasing
                        )
                    )
                }
                launch {
                    delay(200) // Short delay before fade starts
                    copy.alpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = 600,
                            easing = LinearEasing
                        )
                    )
                }
            }
        }

        // Wait for all animations to complete
        delay((copyCount * 200L) + 800L + 200L)
        onComplete()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val centerX = messageBounds?.center?.x ?: (maxW / 2f)
        val centerY = messageBounds?.center?.y ?: (maxH / 2f)
        val offsetX = (centerX - maxW / 2f).toInt()
        val offsetY = (centerY - maxH / 2f).toInt()

        // Draw each echo copy
        echoCopies.forEachIndexed { index, copy ->
            if (copy.alpha.value > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = copy.scale.value
                            scaleY = copy.scale.value
                            alpha = copy.alpha.value
                            // Transform from center of message
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                                centerX / maxW,
                                centerY / maxH
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = messageText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary.copy(
                            alpha = copy.alpha.value
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.offset { IntOffset(offsetX, offsetY) }
                    )
                }
            }
        }
    }
}
