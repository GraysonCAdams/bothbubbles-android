package com.bothbubbles.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Delivery status indicator for outbound messages.
 * Shows animated icons for sending/sent/delivered/read/error states.
 */
@Composable
internal fun DeliveryIndicator(
    isSent: Boolean,
    isDelivered: Boolean,
    isRead: Boolean,
    hasError: Boolean,
    onClick: (() -> Unit)? = null
) {
    // Determine status for animation key
    val status = when {
        hasError -> "error"
        isRead -> "read"
        isDelivered -> "delivered"
        isSent -> "sent"
        else -> "sending"
    }

    val icon = when {
        hasError -> Icons.Default.Error
        isRead -> Icons.Default.DoneAll
        isDelivered -> Icons.Default.DoneAll
        isSent -> Icons.Default.Check
        else -> Icons.Default.Schedule
    }

    // Animated color transition (150ms for snappy Android 16 feel)
    val color by animateColorAsState(
        targetValue = when {
            hasError -> MaterialTheme.colorScheme.error
            isRead -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "statusColor"
    )

    // Animated scale for status changes
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "statusScale"
    )

    // Wrap in 48dp touch target for accessibility if clickable
    Box(
        modifier = Modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .size(48.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onClick() })
                        }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
            },
            label = "statusIcon",
            modifier = Modifier
                .size(14.dp)
                .scale(scale)
        ) { _ ->
            Icon(
                imageVector = icon,
                contentDescription = when {
                    hasError -> "Failed"
                    isRead -> "Read"
                    isDelivered -> "Delivered"
                    isSent -> "Sent"
                    else -> "Sending"
                },
                tint = color,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Legend dialog explaining message status icons.
 * Shown when user taps on a delivery status indicator.
 */
@Composable
internal fun DeliveryStatusLegend(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message Status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendRow(
                    icon = Icons.Default.Schedule,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Sending"
                )
                LegendRow(
                    icon = Icons.Default.Check,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Sent"
                )
                LegendRow(
                    icon = Icons.Default.DoneAll,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Delivered"
                )
                LegendRow(
                    icon = Icons.Default.DoneAll,
                    color = MaterialTheme.colorScheme.primary,
                    label = "Read"
                )
                LegendRow(
                    icon = Icons.Default.Error,
                    color = MaterialTheme.colorScheme.error,
                    label = "Failed"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

@Composable
private fun LegendRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
