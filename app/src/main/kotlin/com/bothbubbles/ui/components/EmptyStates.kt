package com.bothbubbles.ui.components

import androidx.compose.animation.core.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.theme.KumbhSansFamily

/**
 * Empty state for no conversations
 */
@Composable
fun EmptyConversationsState(
    onNewMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    EmptyState(
        icon = Icons.Outlined.Forum,
        title = "No conversations yet",
        subtitle = "Start a new conversation to begin messaging",
        actionLabel = "New Message",
        onAction = onNewMessage,
        modifier = modifier
    )
}

/**
 * Empty state for no messages in a chat
 */
@Composable
fun EmptyMessagesState(
    chatName: String,
    modifier: Modifier = Modifier
) {
    EmptyState(
        icon = Icons.Outlined.ChatBubbleOutline,
        title = "Start the conversation",
        subtitle = "Send your first message to $chatName",
        modifier = modifier
    )
}

/**
 * Empty state for search with no results
 */
@Composable
fun EmptySearchState(
    query: String,
    modifier: Modifier = Modifier
) {
    EmptyState(
        icon = Icons.Outlined.SearchOff,
        title = "No results found",
        subtitle = "No conversations or messages match \"$query\"",
        modifier = modifier
    )
}

/**
 * Empty state for archived conversations
 */
@Composable
fun EmptyArchivedState(
    modifier: Modifier = Modifier
) {
    EmptyState(
        icon = Icons.Outlined.Archive,
        title = "No archived chats",
        subtitle = "Swipe left on a conversation to archive it",
        modifier = modifier
    )
}

/**
 * Error state with retry action
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    EmptyState(
        icon = Icons.Outlined.ErrorOutline,
        title = "Something went wrong",
        subtitle = message,
        actionLabel = "Try Again",
        onAction = onRetry,
        iconTint = MaterialTheme.colorScheme.error,
        modifier = modifier
    )
}

/**
 * Offline state
 */
@Composable
fun OfflineState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    EmptyState(
        icon = Icons.Outlined.CloudOff,
        title = "You're offline",
        subtitle = "Check your connection and try again",
        actionLabel = "Retry",
        onAction = onRetry,
        modifier = modifier
    )
}

/**
 * Base empty state component
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = iconTint.copy(alpha = 0.1f),
            shape = CircleShape,
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = KumbhSansFamily),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = KumbhSansFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

// ===== Loading Skeletons =====

/**
 * Shimmer effect for loading states
 */
@Composable
fun ShimmerEffect(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnimation.value, y = translateAnimation.value)
    )

    Box(modifier = modifier) {
        content()
    }
}

/**
 * Skeleton for a conversation tile
 */
@Composable
fun ConversationTileSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar skeleton
        SkeletonBox(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Title skeleton
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle skeleton
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Timestamp skeleton
        SkeletonBox(
            modifier = Modifier
                .width(40.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}

/**
 * Skeleton for a message bubble
 */
@Composable
fun MessageBubbleSkeleton(
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
    ) {
        if (!isFromMe) {
            Spacer(modifier = Modifier.width(48.dp))
        }

        SkeletonBox(
            modifier = Modifier
                .widthIn(min = 100.dp, max = 200.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(18.dp))
        )

        if (isFromMe) {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

/**
 * List of conversation skeletons for loading state
 */
@Composable
fun ConversationListSkeleton(
    count: Int = 8,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        repeat(count) {
            ConversationTileSkeleton()
        }
    }
}

/**
 * List of message skeletons for loading state
 */
@Composable
fun MessageListSkeleton(
    count: Int = 10,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(count) { index ->
            MessageBubbleSkeleton(isFromMe = index % 3 == 0)
        }
    }
}

/**
 * Animated skeleton box with shimmer effect
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier
) {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
    )

    val transition = rememberInfiniteTransition(label = "skeleton")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonTranslate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnimation.value - 500, translateAnimation.value - 500),
        end = Offset(translateAnimation.value, translateAnimation.value)
    )

    Box(
        modifier = modifier.background(brush)
    )
}

/**
 * Pulsing loading indicator
 */
@Composable
fun PulsingLoadingIndicator(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size((48 * scale).dp),
            strokeWidth = 4.dp
        )
    }
}
