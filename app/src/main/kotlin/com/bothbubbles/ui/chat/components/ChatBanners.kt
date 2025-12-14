package com.bothbubbles.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.ui.chat.PendingMessage
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.delay

/**
 * Thin progress bar that appears at the top when sending a message.
 * Shows immediately at 10% when send starts, then shows real upload progress for remaining 90%.
 * Color-coded based on message protocol (green for SMS, blue for iMessage).
 */
@Composable
fun SendingIndicatorBar(
    isVisible: Boolean,
    isLocalSmsChat: Boolean,
    hasAttachments: Boolean,
    progress: Float = 0f,
    pendingMessages: List<PendingMessage> = emptyList(),
    modifier: Modifier = Modifier
) {
    // Determine color from first pending message, fallback to chat type
    val isSmsSend = pendingMessages.firstOrNull()?.isLocalSms ?: isLocalSmsChat
    val progressColor = if (isSmsSend) {
        Color(0xFF34C759) // Green for SMS
    } else {
        MaterialTheme.colorScheme.primary // Blue for iMessage
    }
    val trackColor = progressColor.copy(alpha = 0.3f)

    // Track completion state for smooth fade-out
    var completingProgress by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf(0L) }

    // Animated progress for smooth transitions
    val animatedProgress by animateFloatAsState(
        targetValue = when {
            completingProgress -> 1f
            isVisible -> progress.coerceAtLeast(0.1f) // Always show at least 10%
            else -> 0f
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "sendProgress"
    )

    // Handle visibility changes
    LaunchedEffect(isVisible) {
        if (isVisible) {
            startTime = System.currentTimeMillis()
            completingProgress = false
        } else if (startTime > 0) {
            // Send completed - ensure minimum visible duration then animate to 100%
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 400) {
                delay(400 - elapsed)
            }
            completingProgress = true
            delay(400) // Hold at 100% briefly before hiding
            completingProgress = false
            startTime = 0L
        }
    }

    // Determine if bar should be visible - show immediately when sending
    val shouldShow = isVisible || completingProgress

    AnimatedVisibility(
        visible = shouldShow,
        enter = expandVertically(animationSpec = tween(150)),
        exit = shrinkVertically(animationSpec = tween(200))
    ) {
        LinearProgressIndicator(
            progress = { animatedProgress.coerceIn(0f, 1f) },
            modifier = modifier
                .fillMaxWidth()
                .height(3.dp),
            color = progressColor,
            trackColor = trackColor
        )
    }
}

/**
 * Banner prompting user to save an unknown sender as a contact.
 * Shows for 1-on-1 chats with unsaved contacts, dismissible once per address.
 */
@Composable
fun SaveContactBanner(
    visible: Boolean,
    senderAddress: String,
    inferredName: String? = null,
    onAddContact: () -> Unit,
    onReportSpam: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Add contact icon
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PersonAddAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Text content
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (inferredName != null) {
                                "Save $inferredName?"
                            } else {
                                "Save ${PhoneNumberFormatter.format(senderAddress)}?"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (inferredName != null) {
                                "Add ${PhoneNumberFormatter.format(senderAddress)} as a contact"
                            } else {
                                "Saving this number will add a new contact"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Dismiss button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onReportSpam) {
                        Text("Report spam")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onAddContact) {
                        Text("Add contact")
                    }
                }
            }
        }
    }
}

/**
 * MD3 Helper text shown below the input field when in fallback or offline mode.
 * Replaces the top banner with less intrusive supporting text per MD3 guidelines.
 *
 * @param visible Whether the helper text should be shown
 * @param fallbackReason The reason for the fallback (if any)
 * @param isServerConnected Whether the server is currently connected
 * @param showExitAction Whether to show an "Undo" action to exit fallback mode
 * @param onExitFallback Callback when user wants to exit fallback mode
 * @param modifier Modifier for this composable
 */
@Composable
fun SendModeHelperText(
    visible: Boolean,
    fallbackReason: FallbackReason?,
    isServerConnected: Boolean,
    showExitAction: Boolean,
    onExitFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    // SMS Green color for helper text
    val smsColor = Color(0xFF34C759)

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(200)) + fadeIn(),
        exit = shrinkVertically(animationSpec = tween(150)) + fadeOut()
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Helper text with icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Small dot indicator matching SMS color
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (isServerConnected) smsColor else MaterialTheme.colorScheme.error,
                            shape = CircleShape
                        )
                )
                Text(
                    text = when {
                        !isServerConnected -> "Offline • Sending as SMS"
                        fallbackReason != null -> "iMessage unavailable • Sending as SMS"
                        else -> "Sending as SMS"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isServerConnected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            // Undo action button (optional)
            if (showExitAction && isServerConnected) {
                TextButton(
                    onClick = onExitFallback,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Undo",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Legacy thin banner - kept for backwards compatibility.
 * Consider using SendModeHelperText instead for MD3 compliance.
 */
@Composable
@Deprecated("Use SendModeHelperText below the input field instead", ReplaceWith("SendModeHelperText"))
fun SmsFallbackBanner(
    visible: Boolean,
    fallbackReason: FallbackReason?,
    isServerConnected: Boolean,
    showExitAction: Boolean,
    onExitFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Delegate to new helper text component
    SendModeHelperText(
        visible = visible,
        fallbackReason = fallbackReason,
        isServerConnected = isServerConnected,
        showExitAction = showExitAction,
        onExitFallback = onExitFallback,
        modifier = modifier
    )
}
