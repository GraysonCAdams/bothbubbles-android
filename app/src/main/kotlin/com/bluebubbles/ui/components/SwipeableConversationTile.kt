package com.bluebubbles.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluebubbles.ui.conversations.MessageStatus

/**
 * Available swipe actions for conversation tiles
 */
enum class SwipeActionType(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val color: Color
) {
    NONE("none", "None", Icons.Default.Block, Color.Gray),
    PIN("pin", "Pin", Icons.Default.PushPin, Color(0xFF1976D2)),
    UNPIN("unpin", "Unpin", Icons.Outlined.PushPin, Color(0xFF1976D2)),
    ARCHIVE("archive", "Archive", Icons.Default.Archive, Color(0xFF388E3C)),
    DELETE("delete", "Delete", Icons.Default.Delete, Color(0xFFD32F2F)),
    MUTE("mute", "Mute", Icons.Default.NotificationsOff, Color(0xFF7B1FA2)),
    UNMUTE("unmute", "Unmute", Icons.Default.Notifications, Color(0xFF7B1FA2)),
    MARK_READ("mark_read", "Mark as Read", Icons.Default.MarkEmailRead, Color(0xFF0097A7)),
    MARK_UNREAD("mark_unread", "Mark as Unread", Icons.Default.MarkEmailUnread, Color(0xFF0097A7));

    companion object {
        fun fromKey(key: String): SwipeActionType =
            entries.find { it.key == key } ?: NONE

        /**
         * Get the appropriate action based on current state
         */
        fun getContextualAction(
            baseAction: SwipeActionType,
            isPinned: Boolean,
            isMuted: Boolean,
            isRead: Boolean
        ): SwipeActionType {
            return when (baseAction) {
                PIN, UNPIN -> if (isPinned) UNPIN else PIN
                MUTE, UNMUTE -> if (isMuted) UNMUTE else MUTE
                MARK_READ, MARK_UNREAD -> if (isRead) MARK_UNREAD else MARK_READ
                else -> baseAction
            }
        }
    }
}

/**
 * Data class to hold swipe configuration
 */
data class SwipeConfig(
    val enabled: Boolean = true,
    val leftAction: SwipeActionType = SwipeActionType.ARCHIVE,
    val rightAction: SwipeActionType = SwipeActionType.PIN,
    val sensitivity: Float = 0.25f
)

/**
 * A conversation list tile with configurable swipe actions
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableConversationTile(
    title: String,
    subtitle: String,
    timestamp: String,
    unreadCount: Int = 0,
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    isTyping: Boolean = false,
    messageStatus: MessageStatus = MessageStatus.NONE,
    avatarContent: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onSwipeAction: (SwipeActionType) -> Unit,
    onAvatarClick: (() -> Unit)? = null,
    swipeConfig: SwipeConfig = SwipeConfig(),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val isRead = unreadCount == 0

    // Get contextual actions based on current state
    val leftAction = SwipeActionType.getContextualAction(
        swipeConfig.leftAction,
        isPinned,
        isMuted,
        isRead
    )
    val rightAction = SwipeActionType.getContextualAction(
        swipeConfig.rightAction,
        isPinned,
        isMuted,
        isRead
    )

    if (!swipeConfig.enabled || (leftAction == SwipeActionType.NONE && rightAction == SwipeActionType.NONE)) {
        // No swipe actions, render simple tile
        ConversationTileContent(
            title = title,
            subtitle = subtitle,
            timestamp = timestamp,
            unreadCount = unreadCount,
            isPinned = isPinned,
            isMuted = isMuted,
            isTyping = isTyping,
            messageStatus = messageStatus,
            avatarContent = avatarContent,
            onClick = onClick,
            onLongClick = onLongClick,
            onAvatarClick = onAvatarClick
        )
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (rightAction != SwipeActionType.NONE) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSwipeAction(rightAction)
                    }
                    false // Don't dismiss, reset
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (leftAction != SwipeActionType.NONE) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSwipeAction(leftAction)
                    }
                    // Dismiss for delete/archive, reset for others
                    leftAction == SwipeActionType.DELETE || leftAction == SwipeActionType.ARCHIVE
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { it * swipeConfig.sensitivity }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = rightAction != SwipeActionType.NONE,
        enableDismissFromEndToStart = leftAction != SwipeActionType.NONE,
        backgroundContent = {
            SwipeBackground(
                dismissDirection = dismissState.dismissDirection,
                targetValue = dismissState.targetValue,
                leftAction = leftAction,
                rightAction = rightAction
            )
        },
        modifier = modifier
    ) {
        ConversationTileContent(
            title = title,
            subtitle = subtitle,
            timestamp = timestamp,
            unreadCount = unreadCount,
            isPinned = isPinned,
            isMuted = isMuted,
            isTyping = isTyping,
            messageStatus = messageStatus,
            avatarContent = avatarContent,
            onClick = onClick,
            onLongClick = onLongClick,
            onAvatarClick = onAvatarClick,
            hasRoundedCorners = true
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(
    dismissDirection: SwipeToDismissBoxValue,
    targetValue: SwipeToDismissBoxValue,
    leftAction: SwipeActionType,
    rightAction: SwipeActionType
) {
    val action = when (targetValue) {
        SwipeToDismissBoxValue.StartToEnd -> rightAction
        SwipeToDismissBoxValue.EndToStart -> leftAction
        else -> SwipeActionType.NONE
    }

    // Use a single desaturated color for all swipe actions (MD3 style)
    val swipeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest

    val color by animateColorAsState(
        targetValue = if (targetValue != SwipeToDismissBoxValue.Settled) {
            swipeBackgroundColor
        } else {
            Color.Transparent
        },
        animationSpec = tween(200),
        label = "swipeColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (targetValue != SwipeToDismissBoxValue.Settled) 1f else 0.8f,
        animationSpec = tween(200),
        label = "iconScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
            SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    ) {
        if (action != SwipeActionType.NONE) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .scale(scale)
                        .size(28.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.scale(scale)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTileContent(
    title: String,
    subtitle: String,
    timestamp: String,
    unreadCount: Int,
    isPinned: Boolean,
    isMuted: Boolean,
    isTyping: Boolean,
    messageStatus: MessageStatus = MessageStatus.NONE,
    avatarContent: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onAvatarClick: (() -> Unit)? = null,
    hasRoundedCorners: Boolean = false
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = if (hasRoundedCorners) RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasRoundedCorners) Modifier.padding(vertical = 4.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar - clickable/long-clickable if handler provided
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .then(
                        if (onAvatarClick != null) {
                            Modifier.clip(CircleShape).combinedClickable(
                                onClick = onAvatarClick,
                                onLongClick = onAvatarClick
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                avatarContent()
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unreadCount > 0) FontWeight.ExtraBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    if (isMuted) {
                        Icon(
                            Icons.Outlined.NotificationsOff,
                            contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                if (isTyping) {
                    Text(
                        text = "typing...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Message status indicator
                        if (messageStatus != MessageStatus.NONE) {
                            MessageStatusIndicator(status = messageStatus)
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                lineHeight = 18.sp
                            ),
                            color = if (unreadCount > 0)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Timestamp and badge
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unreadCount > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (unreadCount > 0) {
                    UnreadBadge(count = unreadCount)
                }
            }
        }
    }
}

@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = CircleShape,
        modifier = modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Pinned conversation tile with fixed position indicator
 */
@Composable
fun PinnedConversationTile(
    title: String,
    avatarContent: @Composable () -> Unit,
    unreadCount: Int = 0,
    isTyping: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.size(80.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box {
                    avatarContent()

                    if (unreadCount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(18.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Typing indicator overlay
            if (isTyping) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Indicator showing message delivery/read status
 */
@Composable
fun MessageStatusIndicator(
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    when (status) {
        MessageStatus.SENDING -> {
            CircularProgressIndicator(
                modifier = modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.NONE -> { /* No indicator */ }
    }
}
