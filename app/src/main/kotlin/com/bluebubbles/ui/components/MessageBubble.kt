package com.bluebubbles.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bluebubbles.data.local.db.entity.MessageSource
import com.bluebubbles.ui.theme.BlueBubblesTheme
import com.bluebubbles.ui.theme.MessageShapes

/**
 * UI model for a message bubble
 */
data class MessageUiModel(
    val guid: String,
    val text: String?,
    val dateCreated: Long,
    val formattedTime: String,
    val isFromMe: Boolean,
    val isSent: Boolean,
    val isDelivered: Boolean,
    val isRead: Boolean,
    val hasError: Boolean,
    val isReaction: Boolean,
    val attachments: List<AttachmentUiModel>,
    val senderName: String?,
    val messageSource: String
)

data class AttachmentUiModel(
    val guid: String,
    val mimeType: String?,
    val localPath: String?,
    val webUrl: String?,
    val width: Int?,
    val height: Int?
)

@Composable
fun MessageBubble(
    message: MessageUiModel,
    onLongPress: () -> Unit,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleColors = BlueBubblesTheme.bubbleColors
    val isIMessage = message.messageSource == MessageSource.IMESSAGE.name

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromMe) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {

        Column(
            horizontalAlignment = if (message.isFromMe) {
                Alignment.End
            } else {
                Alignment.Start
            },
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            // Sender name for group chats
            if (!message.isFromMe && message.senderName != null) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
                )
            }

            // Message bubble
            Surface(
                shape = if (message.isFromMe) {
                    MessageShapes.sentWithTail
                } else {
                    MessageShapes.receivedWithTail
                },
                color = when {
                    message.isFromMe && isIMessage -> bubbleColors.iMessageSent
                    message.isFromMe -> bubbleColors.smsSent
                    else -> bubbleColors.received
                },
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    )
                ) {
                    // Attachments
                    message.attachments.forEach { attachment ->
                        // TODO: Render attachments
                    }

                    // Text content
                    if (!message.text.isNullOrBlank()) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = when {
                                message.isFromMe && isIMessage -> bubbleColors.iMessageSentText
                                message.isFromMe -> bubbleColors.smsSentText
                                else -> bubbleColors.receivedText
                            }
                        )
                    }
                }
            }

            // Timestamp, message type, and status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = message.formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show message type indicator (SMS/MMS)
                if (message.messageSource == MessageSource.LOCAL_SMS.name ||
                    message.messageSource == MessageSource.LOCAL_MMS.name
                ) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (message.messageSource == MessageSource.LOCAL_MMS.name) "MMS" else "SMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (message.isFromMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DeliveryIndicator(
                        isSent = message.isSent,
                        isDelivered = message.isDelivered,
                        isRead = message.isRead,
                        hasError = message.hasError
                    )
                }
            }
        }
    }
}

@Composable
private fun DeliveryIndicator(
    isSent: Boolean,
    isDelivered: Boolean,
    isRead: Boolean,
    hasError: Boolean
) {
    val (icon, color) = when {
        hasError -> Icons.Default.Error to MaterialTheme.colorScheme.error
        isRead -> Icons.Default.DoneAll to Color(0xFF34B7F1)
        isDelivered -> Icons.Default.DoneAll to MaterialTheme.colorScheme.onSurfaceVariant
        isSent -> Icons.Default.Check to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
    }

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

/**
 * Typing indicator bubble with animated dots
 */
@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.padding(end = 48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) { index ->
                TypingDot(
                    delay = index * 150,
                    modifier = Modifier.size(8.dp)
                )
            }
        }
    }
}

@Composable
private fun TypingDot(
    delay: Int,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delay.toLong())
        while (true) {
            visible = !visible
            kotlinx.coroutines.delay(500)
        }
    }

    val alpha by animateColorAsState(
        targetValue = if (visible)
            MaterialTheme.colorScheme.onSurfaceVariant
        else
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "dotAlpha"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(alpha)
    )
}

/**
 * Date separator between message groups
 */
@Composable
fun DateSeparator(
    date: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * New messages indicator floating button
 */
@Composable
fun NewMessagesIndicator(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 4.dp,
            modifier = modifier
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (count == 1) "1 new message" else "$count new messages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * Message type chip indicator (SMS/MMS/iMessage)
 */
@Composable
fun MessageTypeChip(
    messageSource: String,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (messageSource) {
        MessageSource.LOCAL_SMS.name -> "SMS" to Color(0xFF34C759)
        MessageSource.LOCAL_MMS.name -> "MMS" to Color(0xFF34C759)
        else -> "iMessage" to MaterialTheme.colorScheme.primary
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
