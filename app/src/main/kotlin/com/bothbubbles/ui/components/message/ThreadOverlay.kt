package com.bothbubbles.ui.components.message

import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.AttachmentItem
import com.bothbubbles.ui.chat.composer.ChatComposer
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.chat.composer.ComposerPanel
import com.bothbubbles.ui.chat.composer.ComposerState
import com.bothbubbles.ui.chat.delegates.ChatThreadDelegate
import com.bothbubbles.core.design.theme.AppTextStyles
import com.bothbubbles.ui.theme.BothBubblesTheme
import java.util.UUID

/**
 * Data class for thread reply with text and attachments.
 */
data class ThreadReplyData(
    val text: String,
    val attachments: List<Uri>
)

/**
 * Full-screen overlay showing a thread of messages (original + replies).
 * Tapping any message dismisses the overlay and scrolls to that message in the main chat.
 * Tapping the scrim (background) dismisses the overlay without scrolling.
 *
 * @param threadChain The thread chain containing origin and replies
 * @param onMessageClick Callback when a message is tapped (scrolls to it in main chat)
 * @param onDismiss Callback when the overlay is dismissed
 * @param onSendReply Optional callback to send a reply with text and attachments (shown only when origin is excluded, e.g., from Reels)
 * @param onCameraClick Optional callback when camera button is clicked
 * @param modifier Modifier for the overlay
 * @param bottomPadding Bottom padding above composer bar (unused when composer is shown inside overlay)
 */
@Composable
fun ThreadOverlay(
    threadChain: ThreadChain,
    onMessageClick: (String) -> Unit,
    onDismiss: () -> Unit,
    onSendReply: ((ThreadReplyData) -> Unit)? = null,
    onCameraClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    // Combine origin message and replies into a single list
    val allMessages = buildList {
        threadChain.originMessage?.let { add(it) }
        addAll(threadChain.replies)
    }

    // Determine if we're in "replies only" mode (origin was excluded, e.g., from Reels)
    val isRepliesOnly = threadChain.originMessage == null

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding() // Handle keyboard insets
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

        // Content card - positioned at bottom, resizes with keyboard
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (isRepliesOnly && onSendReply != null) 0.9f else 0.85f)
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
                // State for stacked reaction details modal
                var selectedReaction by remember { mutableStateOf<Tapback?>(null) }
                val groupedReactions = remember(threadChain.reactions) {
                    threadChain.reactions.groupBy { it.tapback }
                }

                // Header with reactions
                ThreadOverlayHeader(
                    messageCount = allMessages.size,
                    isRepliesOnly = isRepliesOnly,
                    reactions = threadChain.reactions,
                    onReactionClick = { tapback -> selectedReaction = tapback },
                    onClose = onDismiss
                )

                // Stacked bottom sheet for reaction details (shown on top of thread modal)
                if (selectedReaction != null) {
                    val reactionsList = groupedReactions[selectedReaction] ?: emptyList()
                    StackedReactionDetailsSheet(
                        tapback = selectedReaction!!,
                        reactions = reactionsList,
                        onDismiss = { selectedReaction = null }
                    )
                }

                HorizontalDivider()

                // Message list
                if (allMessages.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isRepliesOnly) "No replies yet" else "Thread not found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
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

                // Full composer - shown when accessed from Reels (isRepliesOnly) and callback provided
                if (isRepliesOnly && onSendReply != null) {
                    HorizontalDivider()
                    ThreadComposerHost(
                        onSendReply = onSendReply,
                        onCameraClick = onCameraClick
                    )
                }
            }
        }
    }
}

/**
 * Full-featured composer host for the thread overlay.
 * Uses the same ChatComposer component as the main chat for consistent UX.
 * Manages its own local state for text and attachments.
 */
@Composable
private fun ThreadComposerHost(
    onSendReply: (ThreadReplyData) -> Unit,
    onCameraClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Local composer state - independent from main chat
    var composerText by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<AttachmentItem>>(emptyList()) }
    var activePanel by remember { mutableStateOf(ComposerPanel.None) }
    var isTextFieldFocused by remember { mutableStateOf(false) }

    // Build ComposerState for ChatComposer
    val composerState = remember(composerText, attachments, activePanel, isTextFieldFocused) {
        ComposerState(
            text = composerText,
            attachments = attachments,
            activePanel = activePanel,
            isTextFieldFocused = isTextFieldFocused,
            sendMode = ChatSendMode.IMESSAGE, // Default to iMessage for thread replies
            canToggleSendMode = false, // No mode toggle in thread context
            isGroupChat = false // Mentions not supported in thread overlay
        )
    }

    // Handle send action
    val handleSend = {
        if (composerText.isNotBlank() || attachments.isNotEmpty()) {
            onSendReply(ThreadReplyData(
                text = composerText.trim(),
                attachments = attachments.map { it.uri }
            ))
            // Clear after sending
            composerText = ""
            attachments = emptyList()
            activePanel = ComposerPanel.None
        }
    }

    // Handle composer events
    val onEvent: (ComposerEvent) -> Unit = { event ->
        when (event) {
            is ComposerEvent.TextChanged -> {
                composerText = event.text
            }
            is ComposerEvent.TextFieldFocusChanged -> {
                isTextFieldFocused = event.isFocused
                // Close panel when text field gains focus
                if (event.isFocused && activePanel != ComposerPanel.None) {
                    activePanel = ComposerPanel.None
                }
            }
            is ComposerEvent.Send -> {
                handleSend()
            }
            is ComposerEvent.AddAttachments -> {
                val newAttachments = event.uris.map { uri ->
                    AttachmentItem(
                        id = UUID.randomUUID().toString(),
                        uri = uri,
                        mimeType = null,
                        displayName = uri.lastPathSegment,
                        sizeBytes = null
                    )
                }
                attachments = attachments + newAttachments
            }
            is ComposerEvent.RemoveAttachment -> {
                attachments = attachments.filter { it.id != event.attachment.id }
            }
            is ComposerEvent.ClearAllAttachments -> {
                attachments = emptyList()
            }
            is ComposerEvent.ToggleMediaPicker -> {
                activePanel = when (activePanel) {
                    ComposerPanel.MediaPicker, ComposerPanel.GifPicker -> ComposerPanel.None
                    else -> ComposerPanel.MediaPicker
                }
            }
            is ComposerEvent.ToggleEmojiPicker -> {
                activePanel = if (activePanel == ComposerPanel.EmojiKeyboard) {
                    ComposerPanel.None
                } else {
                    ComposerPanel.EmojiKeyboard
                }
            }
            is ComposerEvent.ToggleGifPicker -> {
                activePanel = if (activePanel == ComposerPanel.GifPicker) {
                    ComposerPanel.None
                } else {
                    ComposerPanel.GifPicker
                }
            }
            is ComposerEvent.DismissPanel -> {
                activePanel = ComposerPanel.None
            }
            is ComposerEvent.OpenCamera -> {
                onCameraClick?.invoke()
            }
            // Ignore events not relevant to thread context
            else -> {}
        }
    }

    ChatComposer(
        state = composerState,
        onEvent = onEvent,
        onMediaSelected = { uris ->
            val newAttachments = uris.map { uri ->
                AttachmentItem(
                    id = UUID.randomUUID().toString(),
                    uri = uri,
                    mimeType = null,
                    displayName = uri.lastPathSegment,
                    sizeBytes = null
                )
            }
            attachments = attachments + newAttachments
        },
        onCameraClick = { onCameraClick?.invoke() },
        modifier = modifier
    )
}

@Composable
private fun ThreadOverlayHeader(
    messageCount: Int,
    isRepliesOnly: Boolean = false,
    reactions: List<ReactionUiModel> = emptyList(),
    onReactionClick: (Tapback) -> Unit = {},
    onClose: () -> Unit
) {
    // Group reactions by type
    val groupedReactions = remember(reactions) {
        reactions.groupBy { it.tapback }
            .filter { it.value.isNotEmpty() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isRepliesOnly) "Replies" else "Thread",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (isRepliesOnly) {
                    "$messageCount ${if (messageCount == 1) "reply" else "replies"}"
                } else {
                    "$messageCount ${if (messageCount == 1) "message" else "messages"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Reaction badges in top right (only in Reels context - when we have reactions)
        if (groupedReactions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                groupedReactions.forEach { (tapback, reactionsList) ->
                    HeaderReactionBadge(
                        tapback = tapback,
                        count = reactionsList.size,
                        hasMyReaction = reactionsList.any { it.isFromMe },
                        onClick = { onReactionClick(tapback) }
                    )
                }
            }
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
 * Reaction badge for the thread overlay header.
 * Shows emoji and count, clickable to show who reacted.
 */
@Composable
private fun HeaderReactionBadge(
    tapback: Tapback,
    count: Int,
    hasMyReaction: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (hasMyReaction) {
        BothBubblesTheme.bubbleColors.iMessageSent
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val textColor = if (hasMyReaction) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = tapback.emoji,
                style = AppTextStyles.emojiSmall
            )
            if (count > 1) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
            }
        }
    }
}

/**
 * Stacked bottom sheet for showing reaction details.
 * Shown on top of the thread overlay when a reaction badge is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StackedReactionDetailsSheet(
    tapback: Tapback,
    reactions: List<ReactionUiModel>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Build comma-separated list of names
    val names = reactions.map { reaction ->
        when {
            reaction.isFromMe -> "You"
            reaction.senderName != null -> reaction.senderName
            else -> "Unknown"
        }
    }.joinToString(", ")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tapback.emoji,
                style = AppTextStyles.emojiMedium
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = names,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
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
            onScrollToOriginal = null,
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
 * Collects thread state internally from the delegate to avoid ChatScreen recomposition.
 *
 * @param threadDelegate Delegate for internal state collection
 * @param onMessageClick Callback when a message is clicked (scrolls to it in main chat)
 * @param onDismiss Callback when the overlay is dismissed
 * @param onSendReply Optional callback to send a reply with text and attachments (for Reels context)
 * @param onCameraClick Optional callback when camera button is clicked
 */
@Composable
fun AnimatedThreadOverlay(
    threadDelegate: ChatThreadDelegate,
    onMessageClick: (String) -> Unit,
    onDismiss: () -> Unit,
    onSendReply: ((ThreadReplyData) -> Unit)? = null,
    onCameraClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    // Collect thread state internally from delegate to avoid ChatScreen recomposition
    val threadState by threadDelegate.state.collectAsStateWithLifecycle()
    val threadChain = threadState.threadOverlay

    AnimatedVisibility(
        visible = threadChain != null,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
        modifier = modifier
    ) {
        // Use non-null check since AnimatedVisibility only shows content when visible=true
        // and we only set visible=true when threadChain != null
        if (threadChain != null) {
            ThreadOverlay(
                threadChain = threadChain,
                onMessageClick = onMessageClick,
                onDismiss = onDismiss,
                onSendReply = onSendReply,
                onCameraClick = onCameraClick,
                bottomPadding = bottomPadding
            )
        }
    }
}
