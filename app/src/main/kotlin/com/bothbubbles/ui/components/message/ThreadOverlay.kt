package com.bothbubbles.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.ThreadChain

/**
 * Full-screen overlay showing a thread of messages (original + replies).
 * Tapping any message dismisses the overlay and scrolls to that message in the main chat.
 * Tapping the scrim (background) dismisses the overlay without scrolling.
 */
@Composable
fun ThreadOverlay(
    threadChain: ThreadChain,
    onMessageClick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Combine origin message and replies into a single list
    val allMessages = buildList {
        threadChain.originMessage?.let { add(it) }
        addAll(threadChain.replies)
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Semi-transparent scrim - tapping dismisses
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        // Content card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .align(Alignment.BottomCenter)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* Consume click to prevent dismissing when tapping content */ },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column {
                // Header
                ThreadOverlayHeader(
                    messageCount = allMessages.size,
                    onClose = onDismiss
                )

                HorizontalDivider()

                // Message list
                if (allMessages.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Thread not found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = allMessages,
                            key = { it.guid }
                        ) { message ->
                            ThreadMessageItem(
                                message = message,
                                isOrigin = message.guid == threadChain.originMessage?.guid,
                                onClick = { onMessageClick(message.guid) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadOverlayHeader(
    messageCount: Int,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Thread",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "$messageCount ${if (messageCount == 1) "message" else "messages"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close thread"
            )
        }
    }
}

/**
 * Individual message item in the thread overlay.
 * Simplified version of MessageBubble for the overlay context.
 */
@Composable
private fun ThreadMessageItem(
    message: MessageUiModel,
    isOrigin: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start
    ) {
        // Origin message label
        if (isOrigin) {
            Text(
                text = "Original message",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = if (message.isFromMe) 0.dp else 12.dp,
                    end = if (message.isFromMe) 12.dp else 0.dp,
                    bottom = 4.dp
                )
            )
        }

        // Reuse MessageBubble for consistent styling
        MessageBubble(
            message = message,
            onLongPress = { /* No-op in thread overlay */ },
            onMediaClick = { /* No-op in thread overlay */ },
            groupPosition = MessageGroupPosition.SINGLE,
            showDeliveryIndicator = message.isFromMe,
            onReply = null,
            onReplyIndicatorClick = null
        )

        // Timestamp
        Text(
            text = message.formattedTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = if (message.isFromMe) 0.dp else 12.dp,
                end = if (message.isFromMe) 12.dp else 0.dp,
                top = 2.dp
            )
        )
    }
}

/**
 * Animated wrapper for ThreadOverlay that handles enter/exit animations.
 */
@Composable
fun AnimatedThreadOverlay(
    threadChain: ThreadChain?,
    onMessageClick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = threadChain != null,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
        modifier = modifier
    ) {
        // Use non-null assertion since AnimatedVisibility only shows content when visible=true
        // and we only set visible=true when threadChain != null
        if (threadChain != null) {
            ThreadOverlay(
                threadChain = threadChain,
                onMessageClick = onMessageClick,
                onDismiss = onDismiss
            )
        }
    }
}
