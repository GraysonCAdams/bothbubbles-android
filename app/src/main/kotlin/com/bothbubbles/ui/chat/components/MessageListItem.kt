package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateFloatAsState
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

    val showDeliveryIndicator = message.isFromMe && index == lastOutgoingIndex

    // Base padding for message grouping
    val basePadding = when {
        message.isPlacedSticker -> 0.dp
        groupPosition == MessageGroupPosition.SINGLE || groupPosition == MessageGroupPosition.FIRST -> 6.dp
        else -> 2.dp
    }
    // Add extra padding if this message has reactions to prevent overlap with previous message
    // Reactions are positioned at -14dp Y offset. We add 18dp (instead of 14dp) to provide
    // a 4dp buffer for shadows and emoji font height variations.
    val topPadding = if (message.reactions.isNotEmpty()) basePadding + 18.dp else basePadding
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

    // Hide the message when it's selected for tapback overlay (prevents double-vision)
    val isSelectedForTapback = selectedMessageForTapback?.guid == message.guid
    val tapbackHideAlpha by animateFloatAsState(
        targetValue = if (isSelectedForTapback) 0f else 1f,
        animationSpec = tween(
            durationMillis = MotionTokens.Duration.SHORT_3,
            delayMillis = MotionTokens.Duration.SHORT_1
        ),
        label = "tapbackHideAlpha"
    )

    Column(
        modifier = Modifier
            .graphicsLayer { clip = false }
            .zIndex(if (message.isPlacedSticker) 1f else 0f)
            .alpha(stickerFadeAlpha * tapbackHideAlpha)
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
            Text(
                text = message.senderName!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, bottom = 2.dp)
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
