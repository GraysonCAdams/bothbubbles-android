package com.bothbubbles.ui.components.message

import timber.log.Timber
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bothbubbles.BuildConfig
import java.io.File
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
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
    attachmentDelegate: ChatAttachmentDelegate? = null,
    // Whether to show delivery indicator (iMessage-style: only on last message in sequence)
    showDeliveryIndicator: Boolean = true,
    // Callback for swipe-to-reply (iMessage only). Pass message GUID when triggered.
    onReply: ((String) -> Unit)? = null,
    // Callback when reply indicator is tapped - scrolls to original message
    onScrollToOriginal: ((String) -> Unit)? = null,
    // Callback when reply indicator is long-pressed - opens thread overlay
    onReplyIndicatorClick: ((String) -> Unit)? = null,
    // Callback when swipe gesture starts/ends. Used to hide stickers during swipe.
    onSwipeStateChanged: ((Boolean) -> Unit)? = null,
    // Callback for date reveal swipe progress (0.0 to 1.0). Used to fade out subtext elements.
    onDateRevealProgress: ((Float) -> Unit)? = null,
    // Callback for retrying a failed message. Pass message GUID when triggered.
    onRetry: ((String) -> Unit)? = null,
    // Callback to retry as SMS (for error 22 - not registered with iMessage)
    onRetryAsSms: ((String) -> Unit)? = null,
    // Callback to delete a failed message
    onDeleteMessage: ((String) -> Unit)? = null,
    // Whether SMS retry is available for this message (requires phone number recipient)
    canRetryAsSms: Boolean = false,
    // Group chat avatar support
    isGroupChat: Boolean = false,
    // Show avatar only on last message in a consecutive group from same sender
    showAvatar: Boolean = false,
    // Callback for reporting message bounds (for tapback overlay positioning)
    onBoundsChanged: ((Rect) -> Unit)? = null,
    // Callback when sender avatar is clicked in group chat (for contact details)
    onAvatarClick: (() -> Unit)? = null,
    // Callback when a mention is clicked (opens contact details)
    onMentionClick: ((String) -> Unit)? = null,
    // Multi-message selection support
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectionToggle: (() -> Unit)? = null,
    // Social media video fullscreen - opens Reels feed
    onOpenReelsFeed: (() -> Unit)? = null,
    // Avatar size for group chats (passed to bubble content for proper alignment)
    avatarSize: androidx.compose.ui.unit.Dp = 28.dp
) {
    // Detect first URL in message text for link preview
    val firstUrl = remember(message.text) {
        UrlParsingUtils.getFirstUrl(message.text)
    }

    // PERFORMANCE TRACKING: Log when a temporary message is actually composed (debug builds only)
    if (BuildConfig.DEBUG && message.guid.startsWith("temp-")) {
        androidx.compose.runtime.SideEffect {
            Timber.tag("MessageBubble").d("⏱️ [RENDER] MessageBubble composed for ${message.guid}")
        }
    }

    // Check if this message needs segmented rendering
    // (has media attachments OR has link preview with text)
    val needsSegmentation = remember(message, firstUrl) {
        MessageSegmentParser.needsSegmentation(message, firstUrl != null)
    }

    // Show avatar for received messages in group chats
    val shouldShowAvatarSpace = isGroupChat && !message.isFromMe

    // Wrap content in a column for the message content
    // Align based on whether this message (the reply) is from me
    Column(
        modifier = modifier,
        horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start
    ) {
        // Haptic feedback for selection toggle
        val hapticFeedback = LocalHapticFeedback.current
        val handleSelectionToggle: () -> Unit = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onSelectionToggle?.invoke()
        }

        // Main content row with selection indicator
        // Avatar is now rendered inside bubble content components for proper alignment with bubble
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isSelectionMode) {
                        Modifier
                            .clickable(onClick = handleSelectionToggle)
                            .semantics {
                                onClick(label = "Toggle message selection") { true }
                            }
                    } else {
                        Modifier
                    }
                )
        ) {
            // Message content - disable media/link interactions in selection mode
            val effectiveOnMediaClick: (String) -> Unit = if (isSelectionMode) { _ -> } else onMediaClick

            // Prepare reply quote tap handler
            val onReplyQuoteTap: (() -> Unit)? = message.threadOriginatorGuid?.let { originGuid ->
                {
                    if (onScrollToOriginal != null) {
                        onScrollToOriginal(originGuid)
                    } else {
                        onReplyIndicatorClick?.invoke(originGuid)
                    }
                }
            }
            val onReplyQuoteLongPress: (() -> Unit)? = message.threadOriginatorGuid?.let { originGuid ->
                { onReplyIndicatorClick?.invoke(originGuid) }
            }

            if (needsSegmentation) {
                // Use segmented rendering for messages with media/links
                SegmentedMessageBubble(
                    message = message,
                    firstUrl = firstUrl,
                    onLongPress = onLongPress,
                    onMediaClick = effectiveOnMediaClick,
                    groupPosition = groupPosition,
                    searchQuery = searchQuery,
                    isCurrentSearchMatch = isCurrentSearchMatch,
                    onDownloadClick = onDownloadClick,
                    attachmentDelegate = attachmentDelegate,
                    showDeliveryIndicator = showDeliveryIndicator,
                    onReply = onReply,
                    onSwipeStateChanged = onSwipeStateChanged,
                    onDateRevealProgress = onDateRevealProgress,
                    onRetry = onRetry,
                    onRetryAsSms = onRetryAsSms,
                    onDeleteMessage = onDeleteMessage,
                    canRetryAsSms = canRetryAsSms,
                    onBoundsChanged = onBoundsChanged,
                    onMentionClick = onMentionClick,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    onSelectionToggle = onSelectionToggle,
                    replyPreview = message.replyPreview,
                    onReplyQuoteTap = onReplyQuoteTap,
                    onReplyQuoteLongPress = onReplyQuoteLongPress,
                    onOpenReelsFeed = onOpenReelsFeed,
                    // Avatar props for proper alignment with bubble (not subtext)
                    shouldShowAvatarSpace = shouldShowAvatarSpace,
                    showAvatar = showAvatar,
                    avatarSize = avatarSize,
                    onAvatarClick = onAvatarClick,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Use optimized single-bubble rendering for simple text messages
                SimpleBubbleContent(
                    message = message,
                    firstUrl = firstUrl,
                    onLongPress = onLongPress,
                    onMediaClick = effectiveOnMediaClick,
                    groupPosition = groupPosition,
                    searchQuery = searchQuery,
                    isCurrentSearchMatch = isCurrentSearchMatch,
                    onDownloadClick = onDownloadClick,
                    attachmentDelegate = attachmentDelegate,
                    showDeliveryIndicator = showDeliveryIndicator,
                    onReply = onReply,
                    onSwipeStateChanged = onSwipeStateChanged,
                    onDateRevealProgress = onDateRevealProgress,
                    onRetry = onRetry,
                    onRetryAsSms = onRetryAsSms,
                    onDeleteMessage = onDeleteMessage,
                    canRetryAsSms = canRetryAsSms,
                    onBoundsChanged = onBoundsChanged,
                    onMentionClick = onMentionClick,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    onSelectionToggle = onSelectionToggle,
                    replyPreview = message.replyPreview,
                    onReplyQuoteTap = onReplyQuoteTap,
                    onReplyQuoteLongPress = onReplyQuoteLongPress,
                    // Avatar props for proper alignment with bubble (not subtext)
                    shouldShowAvatarSpace = shouldShowAvatarSpace,
                    showAvatar = showAvatar,
                    avatarSize = avatarSize,
                    onAvatarClick = onAvatarClick,
                    modifier = Modifier.weight(1f)
                )
            }

            // Selection indicator (aligned right of everything)
            // Note: No click handler here - the entire row is clickable in selection mode
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp, end = 12.dp)
                        .size(24.dp)
                        .then(
                            if (isSelected) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primary,
                                    CircleShape
                                )
                            } else {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    CircleShape
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Quote-style indicator shown above a message that is a reply.
 * Displays a preview of the original message being replied to.
 *
 * Interaction model:
 * - Tap: Scroll to original message in the list (if loaded)
 * - Long-press: Open thread overlay for full context
 *
 * @param replyPreview The preview data for the quoted message
 * @param isFromMe Whether the current message (not the quoted one) is from the user
 * @param onTap Called when tapped - should scroll to original message
 * @param onLongPress Called when long-pressed - should open thread overlay
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReplyQuoteIndicator(
    replyPreview: ReplyPreviewData,
    isFromMe: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    var isExpanded by remember { mutableStateOf(false) }

    // Build accessibility description
    val senderDescription = if (replyPreview.isFromMe) "You" else (replyPreview.senderName ?: "Unknown")
    val textPreview = when {
        replyPreview.isNotLoaded -> "Message not loaded"
        replyPreview.previewText.isNullOrBlank() && replyPreview.hasAttachment -> "Attachment"
        replyPreview.previewText.isNullOrBlank() -> "Message"
        else -> replyPreview.previewText
    }
    val expandedLabel = if (isExpanded) "collapse" else "expand"
    val semanticDescription = "Quote from $senderDescription: $textPreview. Tap to scroll to original, double-tap to $expandedLabel, hold for thread"

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .widthIn(max = 240.dp)
            .padding(bottom = 4.dp)
            .animateContentSize()
            .semantics {
                contentDescription = semanticDescription
                onClick(label = "Scroll to original message") { onTap(); true }
                onLongClick(label = "Open thread view") { onLongPress(); true }
            }
            .combinedClickable(
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTap()
                },
                onDoubleClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    isExpanded = !isExpanded
                },
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = if (isExpanded) Alignment.Top else Alignment.CenterVertically
        ) {
            // Vertical accent bars - show multiple when quoteDepth > 1
            val accentColor = if (replyPreview.isFromMe)
                BothBubblesTheme.bubbleColors.iMessageSent
            else
                MaterialTheme.colorScheme.primary

            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                repeat(replyPreview.quoteDepth.coerceIn(1, 3)) { index ->
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .then(
                                if (isExpanded) Modifier.height(48.dp)
                                else Modifier.height(32.dp)
                            )
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(
                                // Slightly lighter for nested bars
                                if (index == 0) accentColor
                                else accentColor.copy(alpha = 0.5f)
                            )
                    )
                }
            }

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
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                    overflow = if (isExpanded)
                        androidx.compose.ui.text.style.TextOverflow.Visible
                    else
                        androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            // Thumbnail preview for images/videos (on the right)
            if (replyPreview.thumbnailUri != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = File(replyPreview.thumbnailUri),
                        contentDescription = "Quoted attachment",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
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

/**
 * Inline reply quote displayed inside the message bubble at the top.
 * Shows a compact preview of the original message being replied to.
 *
 * @param replyPreview The preview data for the quoted message
 * @param isFromMe Whether the current message (not the quoted one) is from the user
 * @param bubbleColor The background color of the parent bubble (used for contrast)
 * @param onTap Called when tapped - should scroll to original message
 * @param onLongPress Called when long-pressed - should open thread overlay
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InlineReplyQuote(
    replyPreview: ReplyPreviewData,
    isFromMe: Boolean,
    bubbleColor: Color,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCardHeader: Boolean = false // When true, renders without background for use as card header
) {
    val hapticFeedback = LocalHapticFeedback.current

    // Determine colors based on bubble context
    // For card header mode on sent messages, use white for readability on blue background
    val accentColor = when {
        isCardHeader && isFromMe -> Color.White
        replyPreview.isFromMe -> BothBubblesTheme.bubbleColors.iMessageSent
        else -> MaterialTheme.colorScheme.primary
    }

    // Use a semi-transparent overlay that works on both light and dark bubbles
    // Skip background for card header mode
    val quoteBackgroundColor = when {
        isCardHeader -> Color.Transparent
        isFromMe -> Color.Black.copy(alpha = 0.1f)
        else -> Color.Black.copy(alpha = 0.06f)
    }

    // Text colors that work on the quote background
    // For card header on sent messages, use white text
    val senderTextColor = accentColor
    val previewTextColor = when {
        isCardHeader && isFromMe -> Color.White.copy(alpha = 0.8f)
        isFromMe -> BothBubblesTheme.bubbleColors.iMessageSentText.copy(alpha = 0.8f)
        else -> BothBubblesTheme.bubbleColors.receivedText.copy(alpha = 0.8f)
    }

    Surface(
        color = quoteBackgroundColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onTap != null || onLongPress != null) {
                    Modifier.combinedClickable(
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTap?.invoke()
                        },
                        onLongClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress?.invoke()
                        }
                    )
                } else {
                    Modifier
                }
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
                    .height(28.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(accentColor)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Sender name
                Text(
                    text = if (replyPreview.isFromMe) "You" else (replyPreview.senderName ?: "Unknown"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = senderTextColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                // Preview text
                val displayText = when {
                    replyPreview.isNotLoaded -> "Tap to view"
                    replyPreview.previewText.isNullOrBlank() && replyPreview.hasAttachment -> "📎 Attachment"
                    replyPreview.previewText.isNullOrBlank() -> "Message"
                    else -> replyPreview.previewText
                }

                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = previewTextColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            // Thumbnail preview for images/videos
            if (replyPreview.thumbnailUri != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = File(replyPreview.thumbnailUri),
                        contentDescription = "Quoted attachment",
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}
