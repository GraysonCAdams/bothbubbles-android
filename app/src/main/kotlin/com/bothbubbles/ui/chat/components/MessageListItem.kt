package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import com.bothbubbles.ui.theme.MotionTokens
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bothbubbles.ui.chat.ChatScreenState
import com.bothbubbles.ui.chat.calculateGroupPosition
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
import com.bothbubbles.ui.chat.delegates.ChatEffectsDelegate
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.formatTimeSeparator
import com.bothbubbles.ui.chat.shouldShowTimeSeparator
import com.bothbubbles.ui.chat.state.ChatInfoState
import com.bothbubbles.ui.components.common.newMessageEntrance
import com.bothbubbles.ui.components.message.DateSeparator
import com.bothbubbles.ui.components.message.GroupEventIndicator
import com.bothbubbles.ui.components.message.MessageBubble
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.ui.effects.bubble.BubbleEffectWrapper
import com.bothbubbles.ui.modifiers.materialAttentionHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Callbacks for individual message item interactions.
 */
data class MessageItemCallbacks(
    val onMediaClick: (String) -> Unit,
    val onSetReplyTo: (guid: String) -> Unit,
    val onScrollToOriginal: (originGuid: String) -> Unit,
    val onLoadThread: (originGuid: String) -> Unit,
    val onCanRetryAsSms: suspend (guid: String) -> Boolean,
    val onRetryMessage: (guid: String) -> Unit,
    val onRetryAsSms: (guid: String) -> Unit,
    val onDeleteMessage: (guid: String) -> Unit,
    val onBubbleEffectCompleted: (messageGuid: String) -> Unit,
    val onClearHighlight: () -> Unit,
    val onDownloadAttachment: ((guid: String) -> Unit)?,
    val onStopSharingEta: () -> Unit,
    val onAvatarClick: ((MessageUiModel) -> Unit)?
)

/**
 * Renders a single message item in the chat list.
 *
 * Handles:
 * - Message bubble with grouping and styling
 * - Bubble effects (invisible ink, etc.)
 * - Date separators
 * - Sender names for group chats
 * - Avatars for group chats
 * - Search highlighting
 * - Message animations
 * - ETA stop sharing link
 * - Sticker overlays
 */
@Composable
fun MessageListItem(
    message: MessageUiModel,
    index: Int,
    messages: List<MessageUiModel>,
    chatScreenState: ChatScreenState,
    chatInfoState: ChatInfoState,
    searchDelegate: ChatSearchDelegate,
    attachmentDelegate: ChatAttachmentDelegate,
    etaSharingDelegate: ChatEtaSharingDelegate,
    effectsDelegate: ChatEffectsDelegate,
    highlightedMessageGuid: String?,
    isServerConnected: Boolean,
    initialLoadComplete: Boolean,
    nextVisibleMessage: MessageUiModel?,
    lastOutgoingIndex: Int,
    latestEtaMessageIndex: Int,
    showSenderName: Boolean,
    showAvatar: Boolean,
    selectedMessageForTapback: MessageUiModel?,
    selectedMessageForRetry: MessageUiModel?,
    swipingMessageGuid: String?,
    onSelectMessageForTapback: (MessageUiModel?) -> Unit,
    onSelectMessageForRetry: (MessageUiModel?) -> Unit,
    onCanRetrySmsUpdate: (Boolean) -> Unit,
    onSwipingMessageChange: (String?) -> Unit,
    onSelectedBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit,
    // Multi-message selection support
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onEnterSelectionMode: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    callbacks: MessageItemCallbacks
) {
    val retryMenuScope = rememberCoroutineScope()
    val searchState by searchDelegate.state.collectAsStateWithLifecycle()
    val effectsState by effectsDelegate.state.collectAsStateWithLifecycle()
    val etaSharingState by etaSharingDelegate.etaSharingState.collectAsStateWithLifecycle()
    val autoDownloadEnabled by attachmentDelegate.autoDownloadEnabled.collectAsStateWithLifecycle()

    val canTapback = !message.text.isNullOrBlank() &&
        message.isServerOrigin &&
        isServerConnected &&
        !message.guid.startsWith("temp") &&
        !message.guid.startsWith("error") &&
        !message.hasError

    val isCurrentSearchMatch = searchState.isActive &&
        searchState.currentMatchIndex >= 0 &&
        searchState.matchIndices.getOrNull(searchState.currentMatchIndex) == index

    val showTimeSeparator = !message.isReaction && (nextVisibleMessage?.let {
        shouldShowTimeSeparator(message.dateCreated, it.dateCreated)
    } ?: true)

    val groupPosition = calculateGroupPosition(
        messages = messages,
        index = index,
        message = message
    )

    // Show indicator on: 1) last outgoing message, OR 2) any message still transmitting/waiting
    val showDeliveryIndicator = message.isFromMe && (
        index == lastOutgoingIndex || !message.isSent
    )

    // Base padding for message grouping
    val basePadding = when {
        message.isPlacedSticker -> 0.dp
        groupPosition == MessageGroupPosition.SINGLE || groupPosition == MessageGroupPosition.FIRST -> 6.dp
        else -> 2.dp
    }
    // Extra padding between consecutive inbound messages from different senders in group chats.
    // Only adds padding when the previous message was also inbound (from someone else),
    // not when transitioning from an outbound message to inbound.
    val previousMessage = messages.getOrNull(index + 1)
    val isPreviousInbound = previousMessage != null && !previousMessage.isFromMe
    val senderChangePadding = if (showSenderName && isPreviousInbound) 10.dp else 0.dp
    // The bubble components now use layout modifier to expand bounds by 20dp for reactions,
    // so we no longer need extra padding here for reaction overflow.
    val topPadding = basePadding + senderChangePadding
    val stickerOverlapOffset = if (message.isPlacedSticker) (-20).dp else 0.dp

    val targetGuid = message.associatedMessageGuid?.let { guid ->
        if (guid.contains("/")) guid.substringAfter("/") else guid
    }
    val isStickerTargetInteracting = message.isPlacedSticker && (
        selectedMessageForTapback?.guid == targetGuid ||
        swipingMessageGuid == targetGuid
    )
    val stickerFadeAlpha = if (isStickerTargetInteracting) 0f else 1f

    val isHighlighted = highlightedMessageGuid == message.guid

    val isAlreadyAnimated = remember(message.guid) {
        chatScreenState.isMessageAnimated(message.guid)
    }
    val shouldAnimateEntrance = initialLoadComplete && !isAlreadyAnimated

    if (shouldAnimateEntrance) {
        LaunchedEffect(message.guid) {
            delay(16)
            chatScreenState.markMessageAnimated(message.guid)
        }
    }

    // Focus state for tapback overlay: highlight selected, dim others
    val isSelectedForTapback = selectedMessageForTapback?.guid == message.guid
    val isFocusModeActive = selectedMessageForTapback != null

    // Dim non-selected messages when focus mode is active
    val focusDimAlpha by animateFloatAsState(
        targetValue = when {
            !isFocusModeActive -> 1f  // No focus mode: full opacity
            isSelectedForTapback -> 1f  // Selected: full opacity
            else -> 0.3f  // Other messages: dimmed
        },
        animationSpec = tween(durationMillis = MotionTokens.Duration.SHORT_4),
        label = "focusDimAlpha"
    )

    // Scale up selected message slightly
    val focusScale by animateFloatAsState(
        targetValue = if (isSelectedForTapback) 1.03f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "focusScale"
    )

    // Elevate selected message
    val focusElevation by animateFloatAsState(
        targetValue = if (isSelectedForTapback) 16f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "focusElevation"
    )

    // Elevate zIndex for stickers, messages with reactions, and focused message.
    // In reversed LazyColumn, older items (visually above) are drawn after newer items,
    // so reactions extending upward would be covered without elevated zIndex.
    val needsElevatedZIndex = message.isPlacedSticker ||
        message.reactions.isNotEmpty() ||
        isSelectedForTapback

    Column(
        modifier = Modifier
            .graphicsLayer {
                clip = false
                scaleX = focusScale
                scaleY = focusScale
                shadowElevation = focusElevation
            }
            .zIndex(if (isSelectedForTapback) 100f else if (needsElevatedZIndex) 1f else 0f)
            .alpha(stickerFadeAlpha * focusDimAlpha)
            .offset(y = stickerOverlapOffset)
            .padding(top = topPadding)
            .newMessageEntrance(
                shouldAnimate = shouldAnimateEntrance,
                isFromMe = message.isFromMe
            )
            .materialAttentionHighlight(
                shouldHighlight = isHighlighted,
                onHighlightFinished = { callbacks.onClearHighlight() }
            )
    ) {
        if (showTimeSeparator) {
            DateSeparator(
                date = formatTimeSeparator(message.dateCreated)
            )
        }

        // Group events (participant changes, name/icon changes) render as centered system messages
        if (message.isGroupEvent) {
            GroupEventIndicator(
                text = message.groupEventText ?: "Group updated"
            )
            return@Column
        }

        if (showSenderName) {
            // When message has reactions, the bubble adds 17dp of top space via layout modifier
            // to make room for reaction chips. Use offset to move the sender name down into that
            // space so it stays visually close to the bubble content.
            val reactionOffset = if (message.reactions.isNotEmpty()) 17.dp else 0.dp
            Text(
                text = message.senderName!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 52.dp, bottom = 2.dp)
                    .offset(y = reactionOffset)
            )
        }

        Box {
            val bubbleEffect = remember(message.expressiveSendStyleId) {
                MessageEffect.fromStyleId(message.expressiveSendStyleId) as? MessageEffect.Bubble
            }

            val shouldAnimateBubble = bubbleEffect != null &&
                effectsState.autoPlayEffects && !effectsState.reduceMotion &&
                (!message.effectPlayed || effectsState.replayOnScroll)

            val hasMedia = remember(message.attachments) {
                message.attachments.any { it.isImage || it.isVideo }
            }

            val isInvisibleInkRevealed = chatScreenState.isInvisibleInkRevealed(message.guid)
            val isInvisibleInk = bubbleEffect == MessageEffect.Bubble.InvisibleInk

            BubbleEffectWrapper(
                effect = bubbleEffect,
                isNewMessage = shouldAnimateBubble,
                isFromMe = message.isFromMe,
                onEffectComplete = { callbacks.onBubbleEffectCompleted(message.guid) },
                isInvisibleInkRevealed = isInvisibleInkRevealed,
                onInvisibleInkRevealChanged = { revealed ->
                    chatScreenState.toggleInvisibleInk(message.guid, revealed)
                },
                hasMedia = hasMedia,
                onMediaClickBlocked = {}
            ) {
                // Check if this message can be retried as SMS (async, so we compute upfront)
                var canRetryAsSmsState by remember { mutableStateOf(false) }
                LaunchedEffect(message.guid, message.hasError) {
                    if (message.hasError && message.isFromMe) {
                        canRetryAsSmsState = callbacks.onCanRetryAsSms(message.guid)
                    }
                }

                MessageBubble(
                    message = message,
                    onLongPress = {
                        if (message.isPlacedSticker) return@MessageBubble

                        // In selection mode, long-press toggles selection
                        if (isSelectionMode) {
                            onToggleSelection(message.guid)
                            return@MessageBubble
                        }

                        // Not in selection mode: open tapback/reactions menu (includes "Select" option)
                        onSelectMessageForTapback(message)
                    },
                    // Selection mode state
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    onSelectionToggle = { onToggleSelection(message.guid) },
                    onMediaClick = if (isInvisibleInk && hasMedia && !isInvisibleInkRevealed) {
                        { _ -> }
                    } else {
                        callbacks.onMediaClick
                    },
                    groupPosition = groupPosition,
                    searchQuery = if (searchState.isActive) searchState.query else null,
                    isCurrentSearchMatch = isCurrentSearchMatch,
                    onDownloadClick = if (!autoDownloadEnabled) callbacks.onDownloadAttachment else null,
                    attachmentDelegate = attachmentDelegate,
                    showDeliveryIndicator = showDeliveryIndicator,
                    onReply = if (message.isPlacedSticker) null else { guid ->
                        callbacks.onSetReplyTo(guid)
                    },
                    onScrollToOriginal = { originGuid ->
                        callbacks.onScrollToOriginal(originGuid)
                    },
                    onReplyIndicatorClick = { originGuid ->
                        callbacks.onLoadThread(originGuid)
                    },
                    onSwipeStateChanged = { isSwiping ->
                        onSwipingMessageChange(if (isSwiping) message.guid else null)
                    },
                    onRetry = callbacks.onRetryMessage,
                    onRetryAsSms = callbacks.onRetryAsSms,
                    onDeleteMessage = callbacks.onDeleteMessage,
                    canRetryAsSms = canRetryAsSmsState,
                    isGroupChat = chatInfoState.isGroup,
                    showAvatar = showAvatar,
                    onBoundsChanged = if (selectedMessageForTapback?.guid == message.guid) { bounds ->
                        onSelectedBoundsChange(bounds)
                    } else null,
                    onAvatarClick = if (showAvatar && callbacks.onAvatarClick != null) {
                        { callbacks.onAvatarClick.invoke(message) }
                    } else null
                )
            }
        }

        // ETA stop sharing link
        if (etaSharingState.isCurrentlySharing && index == latestEtaMessageIndex) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                EtaStopSharingLink(
                    isVisible = true,
                    onStopSharing = callbacks.onStopSharingEta,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        }
    }
}
