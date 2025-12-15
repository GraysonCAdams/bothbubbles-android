package com.bothbubbles.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Prominent banner shown at the top of the chat screen when navigation is detected.
 * Provides one-tap access to start ETA sharing - safe for use while driving.
 *
 * Two modes:
 * 1. "Offer" mode: Navigation detected, user can tap to start sharing
 * 2. "Active" mode: Currently sharing, shows status and stop button
 */
@Composable
fun EtaSharingBanner(
    isNavigationActive: Boolean,
    isCurrentlySharing: Boolean,
    currentEtaMinutes: Int,
    destination: String?,
    recipientName: String?,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Show banner if navigation is active (either to offer sharing or show active status)
    AnimatedVisibility(
        visible = isNavigationActive,
        enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        AnimatedContent(
            targetState = isCurrentlySharing,
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
            },
            label = "banner_mode_transition"
        ) { sharing ->
            if (sharing) {
                ActiveSharingBanner(
                    etaMinutes = currentEtaMinutes,
                    destination = destination,
                    recipientName = recipientName,
                    onStopSharing = onStopSharing
                )
            } else {
                OfferSharingBanner(
                    etaMinutes = currentEtaMinutes,
                    destination = destination,
                    onStartSharing = onStartSharing
                )
            }
        }
    }
}

/**
 * Banner offering to start ETA sharing - uses a floating card design with gradient
 */
@Composable
private fun OfferSharingBanner(
    etaMinutes: Int,
    destination: String?,
    onStartSharing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val navigationGradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF4285F4), // Google blue
            Color(0xFF34A853)  // Google green
        )
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(navigationGradient)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Navigation icon with subtle bounce
                    Icon(
                        imageVector = Icons.Filled.Navigation,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Navigation Active",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (etaMinutes > 0) {
                                formatEta(etaMinutes) + (destination?.let { " to $it" }?.take(25) ?: "")
                            } else {
                                destination?.take(25) ?: "Ready to share"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // Large touch target button (min 48dp)
                Button(
                    onClick = onStartSharing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF4285F4)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = "Share ETA",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Banner showing active ETA sharing status with pulsing animation
 */
@Composable
private fun ActiveSharingBanner(
    etaMinutes: Int,
    destination: String?,
    recipientName: String?,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pulsing animation for the navigation icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val activeGradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF00C853), // Vibrant green
            Color(0xFF00BFA5)  // Teal accent
        )
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(activeGradient)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Pulsing navigation icon indicating live updates
                    Icon(
                        imageVector = Icons.Filled.Navigation,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(28.dp)
                            .scale(pulseScale)
                            .alpha(pulseAlpha)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = recipientName?.let { "Sharing with $it" } ?: "Sharing ETA",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = buildString {
                                if (etaMinutes > 0) {
                                    append(formatEta(etaMinutes))
                                    destination?.let {
                                        if (it.length <= 20) append(" to $it")
                                    }
                                } else {
                                    append("Calculating...")
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // Large stop button (48dp touch target)
                IconButton(
                    onClick = onStopSharing,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop sharing",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

private fun formatEta(minutes: Int): String {
    return when {
        minutes < 1 -> "Arriving"
        minutes < 60 -> "$minutes min"
        else -> {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
        }
    }
}
