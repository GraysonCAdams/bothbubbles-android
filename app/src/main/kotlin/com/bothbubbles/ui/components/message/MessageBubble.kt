package com.bothbubbles.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.theme.BothBubblesTheme
import com.bothbubbles.util.parsing.UrlParsingUtils

/**
 * Main message bubble component that displays a message with all its content.
 *
 * This component intelligently routes to either:
 * - SegmentedMessageBubble: For messages with media attachments or link previews
 * - SimpleBubbleContent: For text-only messages (optimized fast path)
 *
 * Features:
 * - Swipe gestures for reply (iMessage) and date reveal
 * - Group chat support with avatars
 * - Reply threading with quote indicators
 * - Delivery status indicators
 * - Search highlighting
 * - Retry for failed messages
 *
 * @param message The message data to display
 * @param onLongPress Callback when bubble is long-pressed (for menu)
 * @param onMediaClick Callback when media attachment is clicked
 * @param groupPosition Position of message in visual grouping (affects bubble shape)
 * @param searchQuery Current search query for highlighting
 * @param isCurrentSearchMatch Whether this message is the active search result
 * @param onDownloadClick Callback for manual attachment download
 * @param downloadingAttachments Map of attachment GUIDs to download progress
 * @param showDeliveryIndicator Whether to show delivery status (typically only on last message)
 * @param onReply Callback for swipe-to-reply gesture
 * @param onReplyIndicatorClick Callback when reply quote is tapped (opens thread)
 * @param onSwipeStateChanged Callback when swipe gesture starts/ends
 * @param onRetry Callback to retry failed message
 * @param isGroupChat Whether this is a group chat (affects avatar display)
 * @param showAvatar Whether to show sender avatar (only on last in consecutive group)
 */
@Composable
fun MessageBubble(
    message: MessageUiModel,
    onLongPress: () -> Unit,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupPosition: MessageGroupPosition = MessageGroupPosition.SINGLE,
    searchQuery: String? = null,
    isCurrentSearchMatch: Boolean = false,
    // Attachment download support (for manual download mode)
    onDownloadClick: ((String) -> Unit)? = null,
    downloadingAttachments: Map<String, Float> = emptyMap(),
    // Whether to show delivery indicator (iMessage-style: only on last message in sequence)
    showDeliveryIndicator: Boolean = true,
    // Callback for swipe-to-reply (iMessage only). Pass message GUID when triggered.
    onReply: ((String) -> Unit)? = null,
    // Callback when reply indicator is tapped. Pass the threadOriginatorGuid to open thread overlay.
    onReplyIndicatorClick: ((String) -> Unit)? = null,
    // Callback when swipe gesture starts/ends. Used to hide stickers during swipe.
    onSwipeStateChanged: ((Boolean) -> Unit)? = null,
    // Callback for retrying a failed message. Pass message GUID when triggered.
    onRetry: ((String) -> Unit)? = null,
    // Group chat avatar support
    isGroupChat: Boolean = false,
    // Show avatar only on last message in a consecutive group from same sender
    showAvatar: Boolean = false
) {
    // Detect first URL in message text for link preview
    val firstUrl = remember(message.text) {
        UrlParsingUtils.getFirstUrl(message.text)
    }

    // Check if this message needs segmented rendering
    // (has media attachments OR has link preview with text)
    val needsSegmentation = remember(message, firstUrl) {
        MessageSegmentParser.needsSegmentation(message, firstUrl != null)
    }

    // Show avatar for received messages in group chats
    val shouldShowAvatarSpace = isGroupChat && !message.isFromMe
    val avatarSize = 28.dp

    // Wrap content in a column to show reply indicator above the bubble
    Column(modifier = modifier) {
        // Reply quote indicator (shown above reply messages)
        message.replyPreview?.let { preview ->
            message.threadOriginatorGuid?.let { originGuid ->
                // Add left padding to align with message bubble when avatar space is present
                val replyPadding = if (shouldShowAvatarSpace) (avatarSize + 8.dp) else 0.dp
                ReplyQuoteIndicator(
                    replyPreview = preview,
                    isFromMe = message.isFromMe,
                    onClick = { onReplyIndicatorClick?.invoke(originGuid) },
                    modifier = Modifier.padding(start = replyPadding)
                )
            }
        }

        // Main content row with optional avatar
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Avatar space for received messages in group chats
            if (shouldShowAvatarSpace) {
                if (showAvatar) {
                    Avatar(
                        name = message.senderName ?: "?",
                        avatarPath = message.senderAvatarPath,
                        size = avatarSize,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                } else {
                    // Empty space to maintain alignment
                    Spacer(modifier = Modifier.width(avatarSize + 8.dp))
                }
            }

            // Message content
            if (needsSegmentation) {
                // Use segmented rendering for messages with media/links
                SegmentedMessageBubble(
                    message = message,
                    firstUrl = firstUrl,
                    onLongPress = onLongPress,
                    onMediaClick = onMediaClick,
                    groupPosition = groupPosition,
                    searchQuery = searchQuery,
                    isCurrentSearchMatch = isCurrentSearchMatch,
                    onDownloadClick = onDownloadClick,
                    downloadingAttachments = downloadingAttachments,
                    showDeliveryIndicator = showDeliveryIndicator,
                    onReply = onReply,
                    onSwipeStateChanged = onSwipeStateChanged,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Use optimized single-bubble rendering for simple text messages
                SimpleBubbleContent(
                    message = message,
                    firstUrl = firstUrl,
                    onLongPress = onLongPress,
                    onMediaClick = onMediaClick,
                    groupPosition = groupPosition,
                    searchQuery = searchQuery,
                    isCurrentSearchMatch = isCurrentSearchMatch,
                    onDownloadClick = onDownloadClick,
                    downloadingAttachments = downloadingAttachments,
                    showDeliveryIndicator = showDeliveryIndicator,
                    onReply = onReply,
                    onSwipeStateChanged = onSwipeStateChanged,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Quote-style indicator shown above a message that is a reply.
 * Displays a preview of the original message being replied to.
 * Tapping opens the thread overlay.
 */
@Composable
fun ReplyQuoteIndicator(
    replyPreview: ReplyPreviewData,
    isFromMe: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current

    Surface(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .padding(
                start = if (isFromMe) 48.dp else 0.dp,
                end = if (isFromMe) 0.dp else 48.dp,
                bottom = 4.dp
            )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vertical accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(
                        if (replyPreview.isFromMe)
                            BothBubblesTheme.bubbleColors.iMessageSent
                        else
                            MaterialTheme.colorScheme.primary
                    )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Sender name
                Text(
                    text = if (replyPreview.isFromMe) "You" else (replyPreview.senderName ?: "Unknown"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (replyPreview.isFromMe)
                        BothBubblesTheme.bubbleColors.iMessageSent
                    else
                        MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                // Preview text or placeholder
                val displayText = when {
                    replyPreview.isNotLoaded -> "Tap to view thread"
                    replyPreview.previewText.isNullOrBlank() && replyPreview.hasAttachment -> "[Attachment]"
                    replyPreview.previewText.isNullOrBlank() -> "[Message]"
                    else -> replyPreview.previewText
                }

                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Message type chip indicator (SMS/MMS/iMessage).
 * Used to show the delivery method of a message.
 */
@Composable
fun MessageTypeChip(
    messageSource: String,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (messageSource) {
        "LOCAL_SMS" -> "SMS" to Color(0xFF34C759)
        "LOCAL_MMS" -> "MMS" to Color(0xFF34C759)
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
