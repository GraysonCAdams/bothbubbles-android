package com.bothbubbles.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Simplified ETA sharing banner shown at the top of the chat screen when navigation is detected.
 *
 * Key features:
 * - Flat design using primaryContainer color (no gradient)
 * - Swipe-to-dismiss in any direction (safe for driving)
 * - Shows just "ETA: X min" + "Share ETA" button
 * - Only shown when not currently sharing (when sharing, use EtaStopSharingLink instead)
 */
@Composable
fun EtaSharingBanner(
    isNavigationActive: Boolean,
    isCurrentlySharing: Boolean,
    isDismissed: Boolean,
    currentEtaMinutes: Int,
    onStartSharing: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only show banner when:
    // 1. Navigation is active
    // 2. NOT currently sharing (when sharing, stop link appears under message)
    // 3. NOT dismissed for this session
    val shouldShow = isNavigationActive && !isCurrentlySharing && !isDismissed

    AnimatedVisibility(
        visible = shouldShow,
        enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        SwipeableBannerContent(
            etaMinutes = currentEtaMinutes,
            onStartSharing = onStartSharing,
            onDismiss = onDismiss
        )
    }
}

/**
 * Banner content with swipe-to-dismiss in any direction.
 */
@Composable
private fun SwipeableBannerContent(
    etaMinutes: Int,
    onStartSharing: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 80.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Calculate alpha based on drag distance (fade out as user drags)
    val dragDistance = maxOf(offsetX.absoluteValue, offsetY.absoluteValue)
    val alpha = (1f - (dragDistance / dismissThresholdPx).coerceIn(0f, 1f)).coerceIn(0.3f, 1f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .alpha(alpha)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX.absoluteValue > dismissThresholdPx) {
                            onDismiss()
                        } else {
                            offsetX = 0f
                        }
                    },
                    onDragCancel = { offsetX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX += dragAmount
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (offsetY.absoluteValue > dismissThresholdPx) {
                            onDismiss()
                        } else {
                            offsetY = 0f
                        }
                    },
                    onDragCancel = { offsetY = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        offsetY += dragAmount
                    }
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ETA info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Navigation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (etaMinutes > 0) {
                        "ETA: ${formatEta(etaMinutes)}"
                    } else {
                        "Navigation active"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Share button (large touch target - min 48dp)
            Button(
                onClick = onStartSharing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Text(
                    text = "Share ETA",
                    fontWeight = FontWeight.SemiBold
                )
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

/**
 * Small text link shown under an ETA message to stop sharing.
 * Should be shown under the latest ETA message when actively sharing.
 */
@Composable
fun EtaStopSharingLink(
    isVisible: Boolean,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)),
        modifier = modifier
    ) {
        Text(
            text = "Stop Sharing ETA",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 4.dp) // Separation from message above
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null // No ripple - cleaner look for small text link
                ) {
                    onStopSharing()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp) // Touch target padding
        )
    }
}
