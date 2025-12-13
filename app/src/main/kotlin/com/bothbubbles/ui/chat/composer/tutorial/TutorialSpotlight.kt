package com.bothbubbles.ui.chat.composer.tutorial

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Spotlight overlay that dims the screen except for a highlighted area.
 *
 * This creates the characteristic tutorial overlay with a "spotlight" cutout
 * that draws attention to the send button. The spotlight includes:
 * - Semi-transparent scrim covering the entire screen
 * - Circular transparent cutout for the target element
 * - Subtle glow ring around the spotlight
 * - Animated entrance/exit for smooth transitions
 *
 * @param isVisible Whether the spotlight is currently visible
 * @param targetBounds The bounding rectangle of the element to spotlight (in screen coordinates)
 * @param scrimAlpha Alpha value for the scrim overlay (0.0 to 1.0)
 * @param spotlightPadding Extra padding around the target element
 * @param glowColor Color for the glow ring around the spotlight
 * @param onDismiss Optional callback when the scrim is tapped (for skip functionality)
 * @param modifier Modifier for this composable
 * @param content Content to display on top of the spotlight (arrows, cards, etc.)
 */
@Composable
fun TutorialSpotlight(
    isVisible: Boolean,
    targetBounds: Rect,
    scrimAlpha: Float = 0.7f,
    spotlightPadding: Float = 8f,
    glowColor: Color = Color.White.copy(alpha = 0.4f),
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    // Animated visibility alpha
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing
        ),
        label = "spotlightAlpha"
    )

    // Animated spotlight radius with spring for organic feel
    val spotlightRadius = remember { Animatable(0f) }

    LaunchedEffect(isVisible, targetBounds) {
        if (isVisible && targetBounds != Rect.Zero) {
            // Calculate radius from target bounds
            val targetRadius = maxOf(targetBounds.width, targetBounds.height) / 2f + spotlightPadding
            spotlightRadius.animateTo(
                targetValue = targetRadius,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        } else {
            spotlightRadius.animateTo(
                targetValue = 0f,
                animationSpec = tween(200)
            )
        }
    }

    if (alpha > 0f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha
                    // Required for BlendMode.Clear to work properly
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawBehind {
                    drawSpotlightScrim(
                        scrimAlpha = scrimAlpha,
                        spotlightCenter = targetBounds.center,
                        spotlightRadius = spotlightRadius.value,
                        glowColor = glowColor
                    )
                }
        ) {
            content()
        }
    }
}

/**
 * Draws the scrim with spotlight cutout.
 */
private fun DrawScope.drawSpotlightScrim(
    scrimAlpha: Float,
    spotlightCenter: Offset,
    spotlightRadius: Float,
    glowColor: Color
) {
    // Draw semi-transparent scrim over entire canvas
    drawRect(
        color = Color.Black.copy(alpha = scrimAlpha),
        size = size
    )

    if (spotlightRadius > 0f) {
        // Draw glow ring around spotlight (before clearing)
        drawCircle(
            color = glowColor,
            radius = spotlightRadius + 4.dp.toPx(),
            center = spotlightCenter,
            style = Stroke(width = 2.dp.toPx())
        )

        // Cut out transparent spotlight using BlendMode.Clear
        drawCircle(
            color = Color.Transparent,
            radius = spotlightRadius,
            center = spotlightCenter,
            blendMode = BlendMode.Clear
        )
    }
}

/**
 * Extension to create a Rect from center point and radius.
 */
fun Rect.Companion.fromCenter(center: Offset, radius: Float): Rect {
    return Rect(
        left = center.x - radius,
        top = center.y - radius,
        right = center.x + radius,
        bottom = center.y + radius
    )
}
