package com.bothbubbles.ui.settings.socialmedia

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Animated preview card showing the Reels experience concept with gesture instructions.
 * Displays a simple phone mockup with scrolling video cards and explains the controls.
 */
@Composable
fun ReelsConceptPreview(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Reels Experience",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Animated phone mockup
                PhoneMockup()

                // Gesture instructions
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GestureItem(
                        icon = Icons.Default.KeyboardArrowUp,
                        text = "Swipe up for next video"
                    )
                    GestureItem(
                        icon = Icons.Default.KeyboardArrowDown,
                        text = "Swipe down for previous"
                    )
                    GestureItem(
                        icon = Icons.Default.TouchApp,
                        text = "Double-tap to react"
                    )
                    GestureItem(
                        icon = Icons.Default.Close,
                        text = "Close button to exit"
                    )
                }
            }

            Text(
                "Browse received videos full-screen, like TikTok or Instagram Reels",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PhoneMockup() {
    val infiniteTransition = rememberInfiniteTransition(label = "reels_scroll")

    // Scroll animation: pause -> swipe up full screen -> pause -> reset
    val scrollProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3000
                0f at 0              // Start - showing video 1
                0f at 800            // Pause at start
                1f at 1800           // Swipe complete - showing video 2
                1f at 2600           // Pause at new position
                0f at 3000           // Quick reset to video 1
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "scroll_progress"
    )

    // Pulsing arrow indicator
    val arrowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrow_pulse"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Phone frame
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(3.dp)
        ) {
            // Screen area with clipping
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(6.dp))
            ) {
                // Full-screen video transitions
                FullScreenVideoTransition(scrollProgress = scrollProgress)
            }
        }

        // Swipe up indicator
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = "Swipe up",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = arrowAlpha),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun FullScreenVideoTransition(scrollProgress: Float) {
    val screenHeight = 94.dp // Phone screen height (100 - 6 padding)
    val offsetY = (-scrollProgress * 94).dp

    // Two full-screen videos stacked vertically
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer
    )

    Column(
        modifier = Modifier.offset(y = offsetY)
    ) {
        colors.forEach { color ->
            // Each video takes the full screen
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(screenHeight)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                // Play button indicator in center
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Triangle play icon
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                shape = androidx.compose.foundation.shape.GenericShape { size, _ ->
                                    moveTo(0f, 0f)
                                    lineTo(size.width, size.height / 2)
                                    lineTo(0f, size.height)
                                    close()
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureItem(
    icon: ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
