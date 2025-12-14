package com.bothbubbles.ui.conversations

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.components.common.GroupAvatar

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GoogleStyleConversationTile(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    hasRoundedCorners: Boolean = false,
    onAvatarClick: (() -> Unit)? = null
) {
    // Animate corner radius for smooth transition (MD3 uses 12.dp "Large" shape token)
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelectionMode || hasRoundedCorners) 12.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "cornerRadius"
    )
    val shape = RoundedCornerShape(cornerRadius)

    // Animate vertical padding for smooth height transition
    val verticalPadding by animateDpAsState(
        targetValue = if (isSelectionMode || hasRoundedCorners) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "verticalPadding"
    )

    // Animate background color for smooth selection transition
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = 200),
        label = "backgroundColor"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with animated crossfade between selection checkmark and regular avatar
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .then(
                        if (onAvatarClick != null && !isSelected) {
                            Modifier.combinedClickable(
                                onClick = onAvatarClick,
                                onLongClick = onAvatarClick
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                AnimatedContent(
                    targetState = isSelected,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(200)) togetherWith
                            fadeOut(animationSpec = tween(200))
                    },
                    label = "avatarSelection"
                ) { selected ->
                    if (selected) {
                        // Show checkmark when selected - use muted color instead of saturated primary
                        Surface(
                            color = MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.size(56.dp)) {
                            if (conversation.isGroup) {
                                GroupAvatar(
                                    names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                                    avatarPaths = conversation.participantAvatarPaths,
                                    size = 56.dp
                                )
                            } else {
                                Avatar(
                                    name = conversation.rawDisplayName,
                                    avatarPath = conversation.avatarPath,
                                    size = 56.dp
                                )
                            }

                            // Typing indicator badge
                            if (conversation.isTyping) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(20.dp)
                                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    ) {
                                        AnimatedTypingDots()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Name row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatDisplayName(conversation.displayName),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (conversation.unreadCount > 0) FontWeight.ExtraBold else FontWeight.Normal
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (conversation.isMuted) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Muted",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (conversation.isSnoozed) {
                        Icon(
                            Icons.Default.Snooze,
                            contentDescription = "Snoozed",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // "is typing..." indicator text under the name
                if (conversation.isTyping) {
                    Text(
                        text = "is typing...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = FontStyle.Italic
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Message preview with inline status indicator
                val textColor = when {
                    conversation.hasDraft -> MaterialTheme.colorScheme.error
                    conversation.unreadCount > 0 -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                }

                val showInlineStatus = conversation.isFromMe &&
                    conversation.lastMessageStatus != MessageStatus.NONE &&
                    conversation.unreadCount == 0 &&
                    !conversation.isTyping

                val previewText = formatMessagePreview(conversation)
                val annotatedText = buildAnnotatedString {
                    append(previewText)
                    if (showInlineStatus) {
                        append(" ")
                        appendInlineContent("status", "[status]")
                    }
                }

                val inlineContent = if (showInlineStatus) {
                    mapOf(
                        "status" to InlineTextContent(
                            placeholder = Placeholder(
                                width = 14.sp,
                                height = 14.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                            )
                        ) {
                            InlineMessageStatusIcon(
                                status = conversation.lastMessageStatus,
                                tint = textColor
                            )
                        }
                    )
                } else {
                    emptyMap()
                }

                Text(
                    text = annotatedText,
                    inlineContent = inlineContent,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                        lineHeight = 18.sp
                    ),
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Trailing content - timestamp and unread badge
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Draft or timestamp
                if (conversation.hasDraft) {
                    Text(
                        text = "Draft",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = conversation.lastMessageTime,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Unread badge only - status indicator is now inline with text
                if (conversation.unreadCount > 0) {
                    UnreadBadge(count = conversation.unreadCount)
                }
            }
        }
    }
}

@Composable
internal fun AnimatedTypingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "typingDots")

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(2.dp)
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1200
                        val delay = index * 150
                        0.3f at delay using LinearEasing
                        1f at (delay + 200) using FastOutSlowInEasing
                        0.3f at (delay + 400) using FastOutSlowInEasing
                        0.3f at 1200 using LinearEasing
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "dotFade$index"
            )

            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

/**
 * Inline message status icon for use in text with InlineTextContent.
 * Uses clock icon for sending, checkmarks for sent/delivered/read.
 */
@Composable
internal fun InlineMessageStatusIcon(
    status: MessageStatus,
    tint: Color,
    modifier: Modifier = Modifier
) {
    when (status) {
        MessageStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Sending",
                tint = tint,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = tint,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered",
                tint = tint,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.NONE -> {
            // No icon for NONE status
        }
    }
}

@Composable
internal fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = CircleShape,
        modifier = modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }
}
