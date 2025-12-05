package com.bluebubbles.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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

/**
 * Swipe actions for conversation tiles
 */
data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val onClick: () -> Unit
)

/**
 * A conversation list tile with swipe actions
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableConversationTile(
    title: String,
    subtitle: String,
    timestamp: String,
    unreadCount: Int = 0,
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    isTyping: Boolean = false,
    avatarContent: @Composable () -> Unit,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkRead: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (unreadCount > 0) onMarkRead() else onPin()
                    false // Don't dismiss, reset
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onArchive()
                    true // Dismiss
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { it * 0.25f }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            SwipeBackground(
                dismissDirection = dismissState.dismissDirection,
                targetValue = dismissState.targetValue,
                unreadCount = unreadCount,
                isPinned = isPinned
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
            avatarContent = avatarContent,
            onClick = onClick
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(
    dismissDirection: SwipeToDismissBoxValue,
    targetValue: SwipeToDismissBoxValue,
    unreadCount: Int,
    isPinned: Boolean
) {
    val color by animateColorAsState(
        targetValue = when (targetValue) {
            SwipeToDismissBoxValue.StartToEnd -> {
                if (unreadCount > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary
            }
            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.secondary
            else -> Color.Transparent
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
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
            SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    ) {
        when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> {
                Icon(
                    imageVector = if (unreadCount > 0) Icons.Default.MarkEmailRead else
                        if (isPinned) Icons.Outlined.PushPin else Icons.Default.PushPin,
                    contentDescription = if (unreadCount > 0) "Mark as read" else "Pin",
                    tint = Color.White,
                    modifier = Modifier.scale(scale)
                )
            }
            SwipeToDismissBoxValue.EndToStart -> {
                Icon(
                    imageVector = Icons.Default.Archive,
                    contentDescription = "Archive",
                    tint = Color.White,
                    modifier = Modifier.scale(scale)
                )
            }
            else -> {}
        }
    }
}

@Composable
private fun ConversationTileContent(
    title: String,
    subtitle: String,
    timestamp: String,
    unreadCount: Int,
    isPinned: Boolean,
    isMuted: Boolean,
    isTyping: Boolean,
    avatarContent: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(modifier = Modifier.size(56.dp)) {
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
                        fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
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
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (unreadCount > 0)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape,
        modifier = modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
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
