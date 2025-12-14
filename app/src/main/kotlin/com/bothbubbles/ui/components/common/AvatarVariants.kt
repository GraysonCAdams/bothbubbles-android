package com.bothbubbles.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Avatar with online indicator
 */
@Composable
fun AvatarWithStatus(
    name: String,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    avatarPath: String? = null,
    size: Dp = 56.dp
) {
    Box(modifier = modifier.size(size)) {
        Avatar(
            name = name,
            avatarPath = avatarPath,
            size = size
        )

        if (isOnline) {
            Surface(
                color = Color(0xFF4CAF50), // Green
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.25f)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            ) {}
        }
    }
}

/**
 * Conversation avatar that handles both single and group chats
 */
@Composable
fun ConversationAvatar(
    displayName: String,
    isGroup: Boolean,
    participantNames: List<String> = emptyList(),
    avatarPath: String? = null,
    participantAvatars: List<String?> = emptyList(),
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    if (isGroup && participantNames.size > 1) {
        GroupAvatar(
            names = participantNames,
            avatarPaths = participantAvatars,
            size = size,
            modifier = modifier
        )
    } else {
        Avatar(
            name = displayName,
            avatarPath = avatarPath,
            size = size,
            modifier = modifier
        )
    }
}

/**
 * Avatar wrapper that displays a message type indicator (iMessage cloud or SMS cellular)
 * in the bottom right corner with an outline that "cuts into" the avatar.
 *
 * @param messageSourceType The type of the last message (IMESSAGE, SMS, or NONE)
 * @param backgroundColor Background color for the indicator outline (should match the list background)
 * @param size The size of the avatar
 * @param indicatorSizeOverride Optional explicit size for the indicator badge. If null, defaults to size * 0.36f
 * @param avatarContent The avatar composable to wrap (Avatar or GroupAvatar)
 */
@Composable
fun AvatarWithMessageType(
    messageSourceType: MessageSourceType,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    size: Dp = 56.dp,
    indicatorSizeOverride: Dp? = null,
    avatarContent: @Composable () -> Unit
) {
    val indicatorSize = indicatorSizeOverride ?: (size * 0.36f)
    val badgeOverflow = 4.dp // How much the badge extends beyond the avatar

    // Use theme-aware colors for iMessage/SMS indicators
    val bubbleColors = BothBubblesTheme.bubbleColors
    val iMessageColor = bubbleColors.iMessageSent
    val smsColor = bubbleColors.smsSent

    // Outer box sized to accommodate badge overflow
    Box(
        modifier = modifier.size(size + badgeOverflow),
        contentAlignment = Alignment.TopStart
    ) {
        // Avatar positioned to leave room for badge
        Box(modifier = Modifier.size(size)) {
            avatarContent()
        }

        if (messageSourceType != MessageSourceType.NONE) {
            val iconSize = indicatorSize * 0.6f
            val indicatorColor = when (messageSourceType) {
                MessageSourceType.IMESSAGE -> iMessageColor
                MessageSourceType.SMS -> smsColor
                else -> Color.Transparent
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(indicatorSize)
                    .border(2.dp, backgroundColor, CircleShape)
                    .clip(CircleShape)
                    .background(indicatorColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (messageSourceType) {
                        MessageSourceType.IMESSAGE -> Icons.Default.Cloud
                        MessageSourceType.SMS -> Icons.Default.CellTower
                        else -> Icons.Default.Cloud
                    },
                    contentDescription = when (messageSourceType) {
                        MessageSourceType.IMESSAGE -> "iMessage"
                        MessageSourceType.SMS -> "SMS"
                        else -> null
                    },
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

/**
 * Helper function to convert message source string to MessageSourceType
 */
fun getMessageSourceType(messageSource: String?): MessageSourceType {
    return when (messageSource) {
        "IMESSAGE" -> MessageSourceType.IMESSAGE
        "SERVER_SMS", "LOCAL_SMS", "LOCAL_MMS" -> MessageSourceType.SMS
        else -> MessageSourceType.NONE
    }
}
