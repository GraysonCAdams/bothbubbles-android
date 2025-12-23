package com.bothbubbles.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Preview state for conversation message preview.
 * Used by both ChatCreator and ShareSheet.
 */
sealed class MessagePreviewListState {
    /** Loading the conversation preview */
    data object Loading : MessagePreviewListState()

    /** No existing conversation - will be a new chat */
    data object NewConversation : MessagePreviewListState()

    /** Existing conversation with full message models */
    data class Existing(
        val chatGuid: String,
        val messages: List<MessageUiModel>,
        val isGroup: Boolean = false
    ) : MessagePreviewListState()
}

/**
 * A reusable conversation preview component that displays messages using the full MessageBubble.
 *
 * Used by both ChatCreator and ShareSheet to provide consistent message rendering
 * with full features like reactions, sender names in groups, attachments, etc.
 *
 * @param previewState The current state of the preview (loading, new, existing)
 * @param modifier Modifier for the container
 * @param minHeight Minimum height of the preview container
 * @param maxHeight Maximum height of the preview container
 */
@Composable
fun MessagePreviewList(
    previewState: MessagePreviewListState,
    modifier: Modifier = Modifier,
    minHeight: Dp = 120.dp,
    maxHeight: Dp = 200.dp
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight, max = maxHeight),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp)
    ) {
        when (previewState) {
            is MessagePreviewListState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            is MessagePreviewListState.NewConversation -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "New Conversation",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            is MessagePreviewListState.Existing -> {
                MessagePreviewContent(
                    messages = previewState.messages,
                    isGroup = previewState.isGroup,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Internal composable that renders the message list content.
 * Uses MessageBubble in preview (non-interactive) mode.
 */
@Composable
private fun MessagePreviewContent(
    messages: List<MessageUiModel>,
    isGroup: Boolean,
    modifier: Modifier = Modifier
) {
    // Reverse messages so newest appears at bottom (for chronological display)
    val orderedMessages = remember(messages) { messages.reversed() }
    val scrollState = rememberScrollState()

    // Pre-compute message groupings for visual consistency
    val groupPositions = remember(orderedMessages) {
        computeGroupPositions(orderedMessages)
    }

    val showAvatarMap = remember(orderedMessages, isGroup) {
        if (!isGroup) emptyMap()
        else computeShowAvatarMap(orderedMessages)
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        orderedMessages.forEachIndexed { index, message ->
            // Skip reaction messages in preview
            if (message.isReaction) return@forEachIndexed

            MessageBubble(
                message = message,
                onLongPress = { /* No-op in preview mode */ },
                onMediaClick = { /* No-op in preview mode */ },
                modifier = Modifier.fillMaxWidth(),
                groupPosition = groupPositions[index] ?: MessageGroupPosition.SINGLE,
                showDeliveryIndicator = false, // No delivery indicators in preview
                isGroupChat = isGroup,
                showAvatar = showAvatarMap[index] ?: false
                // All interaction callbacks are null/no-op in preview mode
            )
        }
    }
}

/**
 * Compute visual group positions for messages (SINGLE, FIRST, MIDDLE, LAST).
 * Groups consecutive messages from the same sender.
 */
private fun computeGroupPositions(messages: List<MessageUiModel>): Map<Int, MessageGroupPosition> {
    if (messages.isEmpty()) return emptyMap()

    val result = mutableMapOf<Int, MessageGroupPosition>()

    for (i in messages.indices) {
        val current = messages[i]
        if (current.isReaction) continue

        val prev = messages.getOrNull(i - 1)?.takeIf { !it.isReaction }
        val next = messages.getOrNull(i + 1)?.takeIf { !it.isReaction }

        val sameAsPrev = prev?.isFromMe == current.isFromMe &&
            prev?.senderAddress == current.senderAddress
        val sameAsNext = next?.isFromMe == current.isFromMe &&
            next?.senderAddress == current.senderAddress

        result[i] = when {
            !sameAsPrev && !sameAsNext -> MessageGroupPosition.SINGLE
            !sameAsPrev && sameAsNext -> MessageGroupPosition.FIRST
            sameAsPrev && sameAsNext -> MessageGroupPosition.MIDDLE
            sameAsPrev && !sameAsNext -> MessageGroupPosition.LAST
            else -> MessageGroupPosition.SINGLE
        }
    }

    return result
}

/**
 * Compute which messages should show sender avatars in group chats.
 * Avatar is shown on the last message in a consecutive group from the same sender.
 */
private fun computeShowAvatarMap(messages: List<MessageUiModel>): Map<Int, Boolean> {
    val result = mutableMapOf<Int, Boolean>()

    for (i in messages.indices) {
        val message = messages[i]
        if (message.isFromMe || message.isReaction) {
            result[i] = false
            continue
        }

        val nextMessage = messages.getOrNull(i + 1)?.takeIf { !it.isReaction }

        // Show avatar if next message is from a different sender or from me
        result[i] = nextMessage == null ||
            nextMessage.isFromMe ||
            nextMessage.senderAddress != message.senderAddress
    }

    return result
}
