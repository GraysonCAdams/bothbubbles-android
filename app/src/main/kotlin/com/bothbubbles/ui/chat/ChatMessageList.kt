package com.bothbubbles.ui.chat

import android.widget.Toast
import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bothbubbles.ui.chat.components.EmptyStateMessages
import com.bothbubbles.ui.chat.components.EtaSharingBanner
import com.bothbubbles.ui.chat.components.EtaStopSharingLink
import com.bothbubbles.ui.chat.components.InlineSearchBar
import com.bothbubbles.ui.chat.components.LoadingMoreIndicator
import com.bothbubbles.ui.chat.components.SaveContactBanner
import com.bothbubbles.ui.chat.components.SendingIndicatorBar
import com.bothbubbles.ui.chat.components.SmsFallbackBanner
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate.EtaSharingUiState
import com.bothbubbles.ui.chat.state.ChatInfoState
import com.bothbubbles.ui.chat.state.SearchState
import com.bothbubbles.ui.chat.state.EffectsState
import com.bothbubbles.ui.chat.state.OperationsState
import com.bothbubbles.ui.chat.state.SendState
import com.bothbubbles.ui.chat.state.SyncState
import com.bothbubbles.ui.components.common.MessageBubbleSkeleton
import com.bothbubbles.ui.components.common.MessageListSkeleton
import com.bothbubbles.ui.components.common.SpamSafetyBanner
import com.bothbubbles.ui.components.common.newMessageEntrance
import com.bothbubbles.ui.components.message.DateSeparator
import com.bothbubbles.ui.components.message.JumpToBottomIndicator
import com.bothbubbles.ui.components.message.MessageBubble
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.components.message.MessageSpotlightOverlay
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.components.message.TypingIndicator
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.ui.effects.bubble.BubbleEffectWrapper
import com.bothbubbles.ui.modifiers.materialAttentionHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val SCROLL_DEBUG_TAG = "ChatScroll"

/**
 * Content types for LazyColumn item recycling optimization.
 *
 * Compose uses contentType to group items with similar layouts. Items with
 * the same contentType can efficiently reuse each other's compositions,
 * reducing allocation and layout overhead during scrolling.
 *
 * These types are stable integers (not strings) for efficient comparison.
 *
 * Message content types (0-5) are the most impactful since messages are repeated.
 * Static items (6+) are singletons but included for completeness.
 */
internal object ContentType {
    // Message types (high frequency, benefit most from recycling)
    const val INCOMING_TEXT = 0
    const val OUTGOING_TEXT = 1
    const val INCOMING_WITH_ATTACHMENT = 2
    const val OUTGOING_WITH_ATTACHMENT = 3
    const val STICKER = 4
    const val REACTION = 5
    // Static items (low frequency, minimal recycling benefit)
    const val TYPING_INDICATOR = 6
    const val LOADING_SKELETON = 7
    const val BANNER = 8
}

/**
 * Callbacks for message list interactions.
 */
data class MessageListCallbacks(
    val onMediaClick: (String) -> Unit,
    val onToggleReaction: (messageGuid: String, tapback: Tapback) -> Unit,
    val onSetReplyTo: (guid: String) -> Unit,
    val onClearReply: () -> Unit,
    val onLoadThread: (originGuid: String) -> Unit,
    val onRetryMessage: (guid: String) -> Unit,
    val onCanRetryAsSms: suspend (guid: String) -> Boolean,
    val onForwardRequest: (message: MessageUiModel) -> Unit,
    val onBubbleEffectCompleted: (messageGuid: String) -> Unit,
    val onHighlightMessage: (guid: String) -> Unit,
    val onClearHighlight: () -> Unit,
    val onDownloadAttachment: ((guid: String) -> Unit)?,
    val onAddContact: () -> Unit,
    val onReportSpam: () -> Unit,
    val onDismissSaveContactBanner: () -> Unit,
    val onMarkAsSafe: () -> Unit,
    val onStartSharingEta: () -> Unit,
    val onStopSharingEta: () -> Unit,
    val onDismissEtaBanner: () -> Unit,
    val onExitSmsFallback: () -> Unit,
    val onSearchQueryChange: (query: String) -> Unit,
    val onCloseSearch: () -> Unit,
    val onNavigateSearchUp: () -> Unit,
    val onNavigateSearchDown: () -> Unit,
    val onViewAllSearchResults: () -> Unit
)

/**
 * Extracted message list component from ChatScreen.
 *
 * Contains the LazyColumn with all message rendering logic, banners, indicators,
 * and scroll-related effects.
 */
@Composable
fun ChatMessageList(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    messages: List<MessageUiModel>,

    // State objects
    chatInfoState: ChatInfoState,
    sendState: SendState,
    syncState: SyncState,
    searchState: SearchState,
    operationsState: OperationsState,
    effectsState: EffectsState,
    etaSharingState: EtaSharingUiState,

    // UI state
    highlightedMessageGuid: String?,
    isLoadingMore: Boolean,
    isLoadingFromServer: Boolean,
    isSyncingMessages: Boolean,
    initialLoadComplete: Boolean,
    autoDownloadEnabled: Boolean,
    downloadingAttachments: Map<String, Float>,

    // Socket new message flow for "x new messages" indicator
    socketNewMessageFlow: Flow<String>,

    // Callbacks
    callbacks: MessageListCallbacks,

    // Tapback overlay state management (owned by parent for coordination)
    selectedMessageForTapback: MessageUiModel?,
    selectedMessageBounds: androidx.compose.ui.geometry.Rect?,
    onSelectMessageForTapback: (MessageUiModel?) -> Unit,
    onSelectedBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit,

    // Retry menu state (owned by parent for coordination)
    selectedMessageForRetry: MessageUiModel?,
    canRetrySmsForMessage: Boolean,
    onSelectMessageForRetry: (MessageUiModel?) -> Unit,
    onCanRetrySmsUpdate: (Boolean) -> Unit,

    // Swipe state for sticker hiding
    swipingMessageGuid: String?,
    onSwipingMessageChange: (String?) -> Unit,

    // Composer height for tapback LiveZone calculation
    composerHeightPx: Float,

    // Server connection for tapback availability
    isServerConnected: Boolean
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val scrollScope = rememberCoroutineScope()
    val retryMenuScope = rememberCoroutineScope()

    // Auto-scroll to search result when navigating
    LaunchedEffect(searchState.currentMatchIndex) {
        if (searchState.currentMatchIndex >= 0 && searchState.matchIndices.isNotEmpty()) {
            val messageIndex = searchState.matchIndices[searchState.currentMatchIndex]
            val viewportHeight = listState.layoutInfo.viewportSize.height
            val centerOffset = -(viewportHeight / 3)
            listState.animateScrollToItem(messageIndex, scrollOffset = centerOffset)
        }
    }

    // Auto-scroll when jumping to a message (from search results or deep link)
    LaunchedEffect(highlightedMessageGuid) {
        highlightedMessageGuid?.let { guid ->
            val index = messages.indexOfFirst { it.guid == guid }
            if (index >= 0) {
                val viewportHeight = listState.layoutInfo.viewportSize.height
                val centerOffset = -(viewportHeight / 3)
                listState.animateScrollToItem(index, scrollOffset = centerOffset)
            }
        }
    }

    // Track the previous newest message GUID to detect truly NEW messages
    var previousNewestGuid by remember { mutableStateOf<String?>(null) }
    var hasInitiallyLoaded by remember { mutableStateOf(false) }

    // Track new messages while scrolled away from bottom (for jump-to-bottom indicator)
    var newMessageCountWhileAway by remember { mutableIntStateOf(0) }

    // Derive whether user is scrolled away from bottom
    val isScrolledAwayFromBottom by rememberIsScrolledAwayFromBottom(listState)

    // Reset new message count when user scrolls back to bottom
    LaunchedEffect(isScrolledAwayFromBottom) {
        if (!isScrolledAwayFromBottom) {
            newMessageCountWhileAway = 0
        }
    }

    // Auto-scroll to show newest message when it arrives
    LaunchedEffect(messages.firstOrNull()?.guid) {
        val newestMessage = messages.firstOrNull()
        val newestGuid = newestMessage?.guid
        val firstVisibleIndex = listState.firstVisibleItemIndex
        val isNearBottom = firstVisibleIndex <= 2

        Log.d(SCROLL_DEBUG_TAG, "📜 AutoScroll LaunchedEffect triggered: " +
            "newestGuid=${newestGuid?.takeLast(8)}, " +
            "previousGuid=${previousNewestGuid?.takeLast(8)}, " +
            "firstVisibleIndex=$firstVisibleIndex, " +
            "isNearBottom=$isNearBottom, " +
            "hasInitiallyLoaded=$hasInitiallyLoaded, " +
            "isFromMe=${newestMessage?.isFromMe}")

        if (newestGuid == null) {
            Log.d(SCROLL_DEBUG_TAG, "📜 SKIP: newestGuid is null")
            return@LaunchedEffect
        }

        if (!hasInitiallyLoaded) {
            hasInitiallyLoaded = true
            previousNewestGuid = newestGuid
            Log.d(SCROLL_DEBUG_TAG, "📜 SKIP: Initial load, setting previousNewestGuid=${newestGuid.takeLast(8)}")
            return@LaunchedEffect
        }

        val isNewMessage = previousNewestGuid != null && previousNewestGuid != newestGuid
        Log.d(SCROLL_DEBUG_TAG, "📜 isNewMessage=$isNewMessage (prev=${previousNewestGuid?.takeLast(8)} vs new=${newestGuid.takeLast(8)})")
        previousNewestGuid = newestGuid

        // For sent messages: scroll if NOT already at bottom (user scrolled up before sending)
        if (newestMessage.isFromMe) {
            if (isNewMessage && firstVisibleIndex > 0) {
                Log.d(SCROLL_DEBUG_TAG, "📜 SCROLLING for SENT message: firstVisibleIndex=$firstVisibleIndex > 0")
                delay(50)
                listState.scrollToItem(0)
                Log.d(SCROLL_DEBUG_TAG, "📜 Scroll complete for sent, now at index=${listState.firstVisibleItemIndex}")
            } else {
                Log.d(SCROLL_DEBUG_TAG, "📜 SKIP sent message scroll: isNewMessage=$isNewMessage, firstVisibleIndex=$firstVisibleIndex")
            }
            return@LaunchedEffect
        }

        if (isNewMessage && isNearBottom) {
            Log.d(SCROLL_DEBUG_TAG, "📜 SCROLLING to item 0 for incoming message")
            delay(50)
            listState.scrollToItem(0)
            Log.d(SCROLL_DEBUG_TAG, "📜 Scroll complete, now at index=${listState.firstVisibleItemIndex}")
        } else {
            Log.d(SCROLL_DEBUG_TAG, "📜 NOT scrolling: isNewMessage=$isNewMessage, isNearBottom=$isNearBottom")
        }
    }

    // Track new messages from socket for indicator
    LaunchedEffect(Unit) {
        socketNewMessageFlow.collect { messageGuid ->
            val isNearBottom = listState.firstVisibleItemIndex <= 2
            val newestMessage = messages.firstOrNull { it.guid == messageGuid }

            if (newestMessage?.isFromMe == false) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }

            if (!isNearBottom) {
                newMessageCountWhileAway++
            }
        }
    }

    // Auto-scroll when typing indicator appears
    LaunchedEffect(syncState.isTyping) {
        if (syncState.isTyping) {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val threshold = (totalItems * 0.1).toInt().coerceAtLeast(1)
            val isNearBottom = listState.firstVisibleItemIndex <= threshold

            if (isNearBottom) {
                delay(100)
                listState.scrollToItem(0)
            }
        }
    }

    // Track revealed invisible ink messages (resets when leaving chat)
    var revealedInvisibleInkMessages by remember { mutableStateOf(setOf<String>()) }

    // Track messages that have been animated
    val animatedMessageGuids = remember { mutableSetOf<String>() }

    // Effect settings
    val autoPlayEffects = effectsState.autoPlayEffects
    val replayEffectsOnScroll = effectsState.replayOnScroll
    val reduceMotion = effectsState.reduceMotion

    // Track processed screen effects this session
    val processedEffectMessages = remember { mutableSetOf<String>() }

    // Detect new messages with screen effects
    LaunchedEffect(messages.firstOrNull()?.guid) {
        val newest = messages.firstOrNull() ?: return@LaunchedEffect
        if (newest.guid in processedEffectMessages) return@LaunchedEffect
        if (!autoPlayEffects || reduceMotion) return@LaunchedEffect
        if (newest.effectPlayed && !replayEffectsOnScroll) return@LaunchedEffect

        val effect = MessageEffect.fromStyleId(newest.expressiveSendStyleId)
        if (effect is MessageEffect.Screen) {
            processedEffectMessages.add(newest.guid)
            // Screen effects are handled at ChatScreen level via ScreenEffectOverlay
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Inline search bar
        InlineSearchBar(
            visible = searchState.isActive,
            query = searchState.query,
            onQueryChange = { callbacks.onSearchQueryChange(it) },
            onClose = callbacks.onCloseSearch,
            onNavigateUp = callbacks.onNavigateSearchUp,
            onNavigateDown = callbacks.onNavigateSearchDown,
            currentMatch = if (searchState.matchIndices.isNotEmpty()) searchState.currentMatchIndex + 1 else 0,
            totalMatches = searchState.matchIndices.size,
            isSearchingDatabase = searchState.isSearchingDatabase,
            databaseResultCount = searchState.databaseResults.size,
            onViewAllClick = callbacks.onViewAllSearchResults
        )

        // Sending indicator bar
        SendingIndicatorBar(
            isVisible = sendState.isSending,
            isLocalSmsChat = chatInfoState.isLocalSmsChat || syncState.isInSmsFallbackMode,
            hasAttachments = sendState.pendingMessages.any { it.hasAttachments },
            progress = sendState.sendProgress,
            pendingMessages = sendState.pendingMessages
        )

        // SMS fallback mode banner
        SmsFallbackBanner(
            visible = syncState.isInSmsFallbackMode && !chatInfoState.isLocalSmsChat,
            fallbackReason = syncState.fallbackReason,
            isServerConnected = syncState.isServerConnected,
            showExitAction = chatInfoState.isIMessageChat,
            onExitFallback = callbacks.onExitSmsFallback
        )

        // Save contact banner
        SaveContactBanner(
            visible = chatInfoState.showSaveContactBanner,
            senderAddress = chatInfoState.unsavedSenderAddress ?: "",
            inferredName = chatInfoState.inferredSenderName,
            onAddContact = callbacks.onAddContact,
            onReportSpam = callbacks.onReportSpam,
            onDismiss = callbacks.onDismissSaveContactBanner
        )

        // ETA sharing banner
        EtaSharingBanner(
            etaState = etaSharingState,
            onStartSharing = callbacks.onStartSharingEta,
            onDismiss = callbacks.onDismissEtaBanner
        )

        // Delayed loading indicator
        var showDelayedLoader by remember { mutableStateOf(false) }
        LaunchedEffect(initialLoadComplete) {
            if (!initialLoadComplete) {
                showDelayedLoader = false
                delay(500)
                if (!initialLoadComplete) {
                    showDelayedLoader = true
                }
            }
        }

        // Mark initial messages as animated
        LaunchedEffect(initialLoadComplete) {
            if (initialLoadComplete) {
                messages.forEach { animatedMessageGuids.add(it.guid) }
            }
        }

        when {
            !initialLoadComplete && showDelayedLoader -> {
                MessageListSkeleton(
                    count = 10,
                    modifier = Modifier.fillMaxSize()
                )
            }
            initialLoadComplete && messages.isEmpty() -> {
                EmptyStateMessages(
                    modifier = Modifier.fillMaxSize()
                )
            }
            !initialLoadComplete && messages.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize())
            }
            else -> {
                val bannerTopPadding by animateDpAsState(
                    targetValue = if (chatInfoState.showSaveContactBanner) 24.dp else 8.dp,
                    animationSpec = tween(durationMillis = 300),
                    label = "banner_padding"
                )

                // Pre-compute expensive lookups
                val nextVisibleMessageMap = remember(messages) {
                    val map = mutableMapOf<Int, MessageUiModel?>()
                    var lastVisibleMessage: MessageUiModel? = null
                    for (i in messages.indices.reversed()) {
                        map[i] = lastVisibleMessage
                        if (!messages[i].isReaction) {
                            lastVisibleMessage = messages[i]
                        }
                    }
                    map
                }

                val lastOutgoingIndex = remember(messages) {
                    messages.indexOfFirst { it.isFromMe && !it.isReaction }
                }

                val latestEtaMessageIndex = remember(messages) {
                    messages.indexOfFirst { it.isFromMe && it.text?.startsWith("\uD83D\uDCCD") == true }
                }

                val showSenderNameMap = remember(messages, chatInfoState.isGroup) {
                    if (!chatInfoState.isGroup) emptyMap()
                    else {
                        val map = mutableMapOf<Int, Boolean>()
                        for (i in messages.indices) {
                            val message = messages[i]
                            val previousMessage = messages.getOrNull(i + 1)
                            map[i] = !message.isFromMe && message.senderName != null &&
                                (previousMessage == null || previousMessage.isFromMe ||
                                    previousMessage.senderName != message.senderName)
                        }
                        map
                    }
                }

                val showAvatarMap = remember(messages, chatInfoState.isGroup) {
                    if (!chatInfoState.isGroup) emptyMap()
                    else {
                        val map = mutableMapOf<Int, Boolean>()
                        for (i in messages.indices) {
                            val message = messages[i]
                            val newerMessage = messages.getOrNull(i - 1)
                            map[i] = !message.isFromMe &&
                                (newerMessage == null || newerMessage.isFromMe ||
                                    newerMessage.senderName != message.senderName)
                        }
                        map
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = bannerTopPadding,
                            bottom = 8.dp
                        )
                    ) {
                        // Spam safety banner
                        if (operationsState.isSpam) {
                            item(key = "spam_safety_banner", contentType = ContentType.BANNER) {
                                SpamSafetyBanner(
                                    onMarkAsSafe = callbacks.onMarkAsSafe
                                )
                            }
                        }

                        // Typing indicator
                        if (syncState.isTyping) {
                            item(key = "typing_indicator", contentType = ContentType.TYPING_INDICATOR) {
                                TypingIndicator(
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }

                        itemsIndexed(
                            items = messages,
                            key = { _, message -> message.guid },
                            contentType = { _, message ->
                                when {
                                    message.isReaction -> ContentType.REACTION
                                    message.isPlacedSticker -> ContentType.STICKER
                                    message.attachments.isNotEmpty() ->
                                        if (message.isFromMe) ContentType.OUTGOING_WITH_ATTACHMENT
                                        else ContentType.INCOMING_WITH_ATTACHMENT
                                    message.isFromMe -> ContentType.OUTGOING_TEXT
                                    else -> ContentType.INCOMING_TEXT
                                }
                            }
                        ) { index, message ->
                            val canTapback = !message.text.isNullOrBlank() &&
                                message.isServerOrigin &&
                                isServerConnected &&
                                !message.guid.startsWith("temp") &&
                                !message.guid.startsWith("error") &&
                                !message.hasError

                            val isSearchMatch = searchState.isActive && index in searchState.matchIndices
                            val isCurrentSearchMatch = searchState.isActive &&
                                searchState.currentMatchIndex >= 0 &&
                                searchState.matchIndices.getOrNull(searchState.currentMatchIndex) == index

                            val nextVisibleMessage = nextVisibleMessageMap[index]
                            val showTimeSeparator = !message.isReaction && (nextVisibleMessage?.let {
                                shouldShowTimeSeparator(message.dateCreated, it.dateCreated)
                            } ?: true)

                            val groupPosition = calculateGroupPosition(
                                messages = messages,
                                index = index,
                                message = message
                            )

                            val showDeliveryIndicator = message.isFromMe && index == lastOutgoingIndex

                            val topPadding = when {
                                message.isPlacedSticker -> 0.dp
                                groupPosition == MessageGroupPosition.SINGLE || groupPosition == MessageGroupPosition.FIRST -> 6.dp
                                else -> 2.dp
                            }
                            val stickerOverlapOffset = if (message.isPlacedSticker) (-20).dp else 0.dp

                            val showSenderName = showSenderNameMap[index] ?: false
                            val showAvatar = showAvatarMap[index] ?: false

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
                                message.guid in animatedMessageGuids
                            }
                            val shouldAnimateEntrance = initialLoadComplete && !isAlreadyAnimated

                            if (shouldAnimateEntrance) {
                                LaunchedEffect(message.guid) {
                                    delay(16)
                                    animatedMessageGuids.add(message.guid)
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .zIndex(if (message.isPlacedSticker) 1f else 0f)
                                    .alpha(stickerFadeAlpha)
                                    .offset(y = stickerOverlapOffset)
                                    .padding(top = topPadding)
                                    .newMessageEntrance(
                                        shouldAnimate = shouldAnimateEntrance,
                                        isFromMe = message.isFromMe
                                    )
                                    .animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = snap()
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
                                        autoPlayEffects && !reduceMotion &&
                                        (!message.effectPlayed || replayEffectsOnScroll)

                                    val hasMedia = remember(message.attachments) {
                                        message.attachments.any { it.isImage || it.isVideo }
                                    }

                                    val isInvisibleInkRevealed = message.guid in revealedInvisibleInkMessages
                                    val isInvisibleInk = bubbleEffect == MessageEffect.Bubble.InvisibleInk

                                    BubbleEffectWrapper(
                                        effect = bubbleEffect,
                                        isNewMessage = shouldAnimateBubble,
                                        isFromMe = message.isFromMe,
                                        onEffectComplete = { callbacks.onBubbleEffectCompleted(message.guid) },
                                        isInvisibleInkRevealed = isInvisibleInkRevealed,
                                        onInvisibleInkRevealChanged = { revealed ->
                                            revealedInvisibleInkMessages = if (revealed) {
                                                revealedInvisibleInkMessages + message.guid
                                            } else {
                                                revealedInvisibleInkMessages - message.guid
                                            }
                                        },
                                        hasMedia = hasMedia,
                                        onMediaClickBlocked = {}
                                    ) {
                                        MessageBubble(
                                            message = message,
                                            onLongPress = {
                                                if (message.isPlacedSticker) return@MessageBubble

                                                if (message.hasError && message.isFromMe) {
                                                    onSelectMessageForRetry(message)
                                                    retryMenuScope.launch {
                                                        val canRetry = callbacks.onCanRetryAsSms(message.guid)
                                                        onCanRetrySmsUpdate(canRetry)
                                                    }
                                                } else if (canTapback) {
                                                    onSelectMessageForTapback(message)
                                                }
                                            },
                                            onMediaClick = if (isInvisibleInk && hasMedia && !isInvisibleInkRevealed) {
                                                { _ -> }
                                            } else {
                                                callbacks.onMediaClick
                                            },
                                            groupPosition = groupPosition,
                                            searchQuery = if (searchState.isActive) searchState.query else null,
                                            isCurrentSearchMatch = isCurrentSearchMatch,
                                            onDownloadClick = callbacks.onDownloadAttachment,
                                            downloadingAttachments = downloadingAttachments,
                                            showDeliveryIndicator = showDeliveryIndicator,
                                            onReply = if (message.isPlacedSticker) null else { guid ->
                                                callbacks.onSetReplyTo(guid)
                                            },
                                            onReplyIndicatorClick = { originGuid ->
                                                callbacks.onLoadThread(originGuid)
                                            },
                                            onSwipeStateChanged = { isSwiping ->
                                                onSwipingMessageChange(if (isSwiping) message.guid else null)
                                            },
                                            onRetry = { guid ->
                                                onSelectMessageForRetry(message)
                                                retryMenuScope.launch {
                                                    val canRetry = callbacks.onCanRetryAsSms(guid)
                                                    onCanRetrySmsUpdate(canRetry)
                                                }
                                            },
                                            isGroupChat = chatInfoState.isGroup,
                                            showAvatar = showAvatar,
                                            onBoundsChanged = if (selectedMessageForTapback?.guid == message.guid) { bounds ->
                                                onSelectedBoundsChange(bounds)
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

                        // Loading more indicator
                        if (isLoadingMore) {
                            item(key = "loading_more", contentType = ContentType.LOADING_SKELETON) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    MessageBubbleSkeleton(isFromMe = false)
                                    MessageBubbleSkeleton(isFromMe = true)
                                }
                            }
                        }

                        // Loading from server indicator
                        if (isLoadingFromServer && messages.isNotEmpty()) {
                            item(key = "loading_more_indicator", contentType = ContentType.LOADING_SKELETON) {
                                LoadingMoreIndicator()
                            }
                        }

                        // Syncing indicator
                        if (isSyncingMessages && messages.isNotEmpty()) {
                            item(key = "sync_skeleton", contentType = ContentType.LOADING_SKELETON) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    MessageBubbleSkeleton(isFromMe = false)
                                    MessageBubbleSkeleton(isFromMe = true)
                                    MessageBubbleSkeleton(isFromMe = false)
                                }
                            }
                        }
                    }

                    // Jump to bottom indicator
                    JumpToBottomIndicator(
                        visible = isScrolledAwayFromBottom,
                        newMessageCount = newMessageCountWhileAway,
                        onClick = {
                            scrollScope.launch {
                                listState.animateScrollToItem(0)
                                newMessageCountWhileAway = 0
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                    )

                    // Message Spotlight Overlay
                    MessageSpotlightOverlay(
                        visible = selectedMessageForTapback != null && selectedMessageBounds != null,
                        anchorBounds = selectedMessageBounds,
                        isFromMe = selectedMessageForTapback?.isFromMe == true,
                        composerHeight = composerHeightPx,
                        myReactions = selectedMessageForTapback?.myReactions ?: emptySet(),
                        canReply = selectedMessageForTapback?.isServerOrigin == true,
                        canCopy = !selectedMessageForTapback?.text.isNullOrBlank(),
                        canForward = true,
                        showReactions = selectedMessageForTapback?.isServerOrigin == true && isServerConnected,
                        onDismiss = {
                            onSelectMessageForTapback(null)
                            onSelectedBoundsChange(null)
                        },
                        onReactionSelected = { tapback ->
                            selectedMessageForTapback?.let { message ->
                                callbacks.onToggleReaction(message.guid, tapback)
                            }
                        },
                        onReply = {
                            selectedMessageForTapback?.let { message ->
                                callbacks.onSetReplyTo(message.guid)
                            }
                        },
                        onCopy = {
                            selectedMessageForTapback?.text?.let { text ->
                                val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("Message", text))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onForward = {
                            selectedMessageForTapback?.let { message ->
                                callbacks.onForwardRequest(message)
                            }
                        }
                    ) {
                        selectedMessageForTapback?.let { message ->
                            MessageBubble(
                                message = message,
                                onLongPress = {},
                                onMediaClick = {},
                                groupPosition = MessageGroupPosition.SINGLE,
                                showDeliveryIndicator = false,
                                onBoundsChanged = null
                            )
                        }
                    }
                }
            }
        }
    }
}
