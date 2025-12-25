package com.bothbubbles.ui.chat

import timber.log.Timber
import com.bothbubbles.BuildConfig
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.chat.components.EmptyStateMessages
import com.bothbubbles.ui.chat.components.LoadingMoreIndicator
import com.bothbubbles.ui.chat.components.MessageItemCallbacks
import com.bothbubbles.ui.chat.components.MessageListBannerCallbacks
import com.bothbubbles.ui.chat.components.MessageListBanners
import com.bothbubbles.ui.chat.components.MessageListItem
import com.bothbubbles.ui.chat.components.MessageListOverlayCallbacks
import com.bothbubbles.ui.chat.components.MessageListOverlays
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
import com.bothbubbles.ui.chat.delegates.ChatCalendarEventsDelegate
import com.bothbubbles.ui.chat.delegates.ChatEffectsDelegate
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.delegates.ChatSyncDelegate
import com.bothbubbles.ui.chat.delegates.CursorChatMessageListDelegate
import com.bothbubbles.ui.components.message.CalendarEventIndicator
import com.bothbubbles.ui.components.message.CalendarEventItem
import com.bothbubbles.ui.components.message.ChatListItem
import com.bothbubbles.util.error.AppError
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import com.bothbubbles.ui.chat.state.ChatInfoState
import com.bothbubbles.ui.components.common.MessageBubbleSkeleton
import com.bothbubbles.ui.components.common.MessageListSkeleton
import com.bothbubbles.ui.components.common.SpamSafetyBanner
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.components.message.TypingIndicator
import com.bothbubbles.ui.effects.MessageEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val SCROLL_DEBUG_TAG = "ChatScroll"

/** Debug-only scroll logging - compiles out in release builds */
private inline fun scrollDebugLog(message: () -> String) {
    if (BuildConfig.DEBUG) {
        Timber.tag(SCROLL_DEBUG_TAG).d(message())
    }
}

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
    const val CALENDAR_EVENT = 9
}

/**
 * Callbacks for message list interactions.
 */
data class MessageListCallbacks(
    val onMediaClick: (String) -> Unit,
    val onToggleReaction: (messageGuid: String, tapback: Tapback) -> Unit,
    val onSetReplyTo: (guid: String) -> Unit,
    val onClearReply: () -> Unit,
    val onScrollToOriginal: (originGuid: String) -> Unit,
    val onLoadThread: (originGuid: String) -> Unit,
    val onRetryMessage: (guid: String) -> Unit,
    val onRetryAsSms: (guid: String) -> Unit,
    val onDeleteMessage: (guid: String) -> Unit,
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
    val onViewAllSearchResults: () -> Unit,
    val onAvatarClick: ((MessageUiModel) -> Unit)?,
    val onOpenReelsFeed: (() -> Unit)?
)

/**
 * Extracted message list component from ChatScreen.
 *
 * Contains the LazyColumn with all message rendering logic, banners, indicators,
 * and scroll-related effects.
 *
 * @param isBubbleMode When true, disables features not suitable for bubble UI:
 *   - Message selection mode
 *   - Save contact banner
 *   - ETA sharing banner (SMS fallback banner is kept)
 */
@Composable
fun ChatMessageList(
    modifier: Modifier = Modifier,
    chatScreenState: ChatScreenState,
    messages: List<MessageUiModel>,

    // Message list delegate (cursor-based pagination)
    messageListDelegate: CursorChatMessageListDelegate,
    sendDelegate: ChatSendDelegate,
    searchDelegate: ChatSearchDelegate,
    syncDelegate: ChatSyncDelegate,
    operationsDelegate: ChatOperationsDelegate,
    attachmentDelegate: ChatAttachmentDelegate,
    etaSharingDelegate: ChatEtaSharingDelegate,
    effectsDelegate: ChatEffectsDelegate,
    calendarEventsDelegate: ChatCalendarEventsDelegate,

    // State objects (passed by reference - still needed at this level)
    chatInfoState: ChatInfoState,

    // UI state
    highlightedMessageGuid: String?,

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

    // Composer height for tapback LiveZone calculation (lambda to avoid parent recomposition on keyboard animation)
    composerHeightPxProvider: () -> Float,

    // Server connection for tapback availability
    isServerConnected: Boolean,

    // Bubble mode - simplified UI for Android conversation bubbles
    isBubbleMode: Boolean = false
) {
    // Collect state internally from delegates to avoid ChatScreen recomposition
    val sendState by sendDelegate.state.collectAsStateWithLifecycle()
    val searchState by searchDelegate.state.collectAsStateWithLifecycle()
    val syncState by syncDelegate.state.collectAsStateWithLifecycle()
    val operationsState by operationsDelegate.state.collectAsStateWithLifecycle()
    val effectsState by effectsDelegate.state.collectAsStateWithLifecycle()
    val etaSharingState by etaSharingDelegate.etaSharingState.collectAsStateWithLifecycle()
    val calendarEvents by calendarEventsDelegate.calendarEvents.collectAsStateWithLifecycle()
    val isLoadingFromServer by messageListDelegate.isLoadingFromServer.collectAsStateWithLifecycle()
    val initialLoadComplete by messageListDelegate.initialLoadComplete.collectAsStateWithLifecycle()
    val autoDownloadEnabled by attachmentDelegate.autoDownloadEnabled.collectAsStateWithLifecycle()

    // Merge messages and calendar events into a unified timeline
    // Calendar events are sorted by startTime and interleaved with messages by timestamp
    val timelineItems: List<ChatListItem> = remember(messages, calendarEvents) {
        if (calendarEvents.isEmpty()) {
            messages.map { ChatListItem.Message(it) }
        } else {
            // Merge messages and calendar events by timestamp (both sorted DESC)
            val result = mutableListOf<ChatListItem>()
            var msgIdx = 0
            var calIdx = 0
            val sortedCalEvents = calendarEvents.sortedByDescending { it.eventStartTime }

            while (msgIdx < messages.size || calIdx < sortedCalEvents.size) {
                when {
                    msgIdx >= messages.size -> {
                        result.add(ChatListItem.CalendarEvent(sortedCalEvents[calIdx]))
                        calIdx++
                    }
                    calIdx >= sortedCalEvents.size -> {
                        result.add(ChatListItem.Message(messages[msgIdx]))
                        msgIdx++
                    }
                    messages[msgIdx].dateCreated >= sortedCalEvents[calIdx].eventStartTime -> {
                        result.add(ChatListItem.Message(messages[msgIdx]))
                        msgIdx++
                    }
                    else -> {
                        result.add(ChatListItem.CalendarEvent(sortedCalEvents[calIdx]))
                        calIdx++
                    }
                }
            }
            result
        }
    }

    // Cursor pagination state
    val hasMoreMessages by messageListDelegate.hasMoreMessages.collectAsStateWithLifecycle()
    val loadError by messageListDelegate.loadError.collectAsStateWithLifecycle()
    val isLoadingMore by messageListDelegate.isLoadingFromServer.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val listState = chatScreenState.listState
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

        scrollDebugLog {
            "📜 AutoScroll LaunchedEffect triggered: " +
                "newestGuid=${newestGuid?.takeLast(8)}, " +
                "previousGuid=${previousNewestGuid?.takeLast(8)}, " +
                "firstVisibleIndex=$firstVisibleIndex, " +
                "isNearBottom=$isNearBottom, " +
                "hasInitiallyLoaded=$hasInitiallyLoaded, " +
                "isFromMe=${newestMessage?.isFromMe}"
        }

        if (newestGuid == null) {
            scrollDebugLog { "📜 SKIP: newestGuid is null" }
            return@LaunchedEffect
        }

        if (!hasInitiallyLoaded) {
            hasInitiallyLoaded = true
            previousNewestGuid = newestGuid
            scrollDebugLog { "📜 SKIP: Initial load, setting previousNewestGuid=${newestGuid.takeLast(8)}" }
            return@LaunchedEffect
        }

        val isNewMessage = previousNewestGuid != null && previousNewestGuid != newestGuid
        scrollDebugLog { "📜 isNewMessage=$isNewMessage (prev=${previousNewestGuid?.takeLast(8)} vs new=${newestGuid.takeLast(8)})" }
        previousNewestGuid = newestGuid

        // For sent messages: ALWAYS scroll to bottom so user sees their message
        if (newestMessage.isFromMe && isNewMessage) {
            scrollDebugLog { "📜 SCROLLING for SENT message (always scroll for own messages)" }
            delay(50)
            listState.animateScrollToItem(0)
            scrollDebugLog { "📜 Scroll complete for sent, now at index=${listState.firstVisibleItemIndex}" }
            return@LaunchedEffect
        }

        if (isNewMessage && isNearBottom) {
            scrollDebugLog { "📜 SCROLLING to item 0 for incoming message" }
            delay(50)
            listState.animateScrollToItem(0)
            scrollDebugLog { "📜 Scroll complete, now at index=${listState.firstVisibleItemIndex}" }
        } else {
            scrollDebugLog { "📜 NOT scrolling: isNewMessage=$isNewMessage, isNearBottom=$isNearBottom" }
        }
    }

    // Track new messages from socket for indicator
    val currentMessages by rememberUpdatedState(messages)
    LaunchedEffect(Unit) {
        socketNewMessageFlow.collect { messageGuid ->
            val isNearBottom = listState.firstVisibleItemIndex <= 2
            val newestMessage = currentMessages.firstOrNull { it.guid == messageGuid }

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

    // Auto-pagination: triggers loadMore() when scrolling near the oldest messages
    LaunchedEffect(listState.firstVisibleItemIndex, messages.size) {
        if (!hasMoreMessages) return@LaunchedEffect
        if (isLoadingMore) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect

        val layoutInfo = listState.layoutInfo
        val totalItems = layoutInfo.totalItemsCount
        val lastVisibleIndex = listState.firstVisibleItemIndex + layoutInfo.visibleItemsInfo.size

        // Trigger load when within 20% of the oldest messages (top of reversed list)
        val loadThreshold = (totalItems * 0.8).toInt()
        if (lastVisibleIndex >= loadThreshold) {
            messageListDelegate.loadMore()
        }
    }

    // Effect settings
    val autoPlayEffects = effectsState.autoPlayEffects
    val replayEffectsOnScroll = effectsState.replayOnScroll
    val reduceMotion = effectsState.reduceMotion

    // Detect new messages with screen effects
    LaunchedEffect(messages.firstOrNull()?.guid) {
        val newest = messages.firstOrNull() ?: return@LaunchedEffect
        if (chatScreenState.isEffectProcessed(newest.guid)) return@LaunchedEffect
        if (!autoPlayEffects || reduceMotion) return@LaunchedEffect
        if (newest.effectPlayed && !replayEffectsOnScroll) return@LaunchedEffect

        val effect = MessageEffect.fromStyleId(newest.expressiveSendStyleId)
        if (effect is MessageEffect.Screen) {
            chatScreenState.markEffectProcessed(newest.guid)
            // Screen effects are handled at ChatScreen level via ScreenEffectOverlay
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Banners section
        MessageListBanners(
            searchDelegate = searchDelegate,
            sendDelegate = sendDelegate,
            syncDelegate = syncDelegate,
            etaSharingDelegate = etaSharingDelegate,
            chatInfoState = chatInfoState,
            isBubbleMode = isBubbleMode,
            callbacks = MessageListBannerCallbacks(
                onSearchQueryChange = callbacks.onSearchQueryChange,
                onCloseSearch = callbacks.onCloseSearch,
                onNavigateSearchUp = callbacks.onNavigateSearchUp,
                onNavigateSearchDown = callbacks.onNavigateSearchDown,
                onViewAllSearchResults = callbacks.onViewAllSearchResults,
                onExitSmsFallback = callbacks.onExitSmsFallback,
                onAddContact = callbacks.onAddContact,
                onReportSpam = callbacks.onReportSpam,
                onDismissSaveContactBanner = callbacks.onDismissSaveContactBanner,
                onStartSharingEta = callbacks.onStartSharingEta,
                onDismissEtaBanner = callbacks.onDismissEtaBanner
            )
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
                messages.forEach { chatScreenState.markMessageAnimated(it.guid) }
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

                // Show avatar beside every message from other participants in group chats
                val showAvatarMap = remember(messages, chatInfoState.isGroup) {
                    if (!chatInfoState.isGroup) emptyMap()
                    else {
                        val map = mutableMapOf<Int, Boolean>()
                        for (i in messages.indices) {
                            val message = messages[i]
                            map[i] = !message.isFromMe
                        }
                        map
                    }
                }

                // Create lookup from message guid to original index in messages list
                // This is needed because precomputed maps use message indices
                val messageGuidToIndex = remember(messages) {
                    messages.withIndex().associate { it.value.guid to it.index }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Disable scrolling when tapback overlay is visible
                    val isOverlayVisible = selectedMessageForTapback != null

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        userScrollEnabled = !isOverlayVisible,
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
                            items = timelineItems,
                            key = { _, item ->
                                when (item) {
                                    is ChatListItem.Message -> item.message.guid
                                    is ChatListItem.CalendarEvent -> "cal_${item.event.id}"
                                    else -> item.key // DateSeparator, TypingIndicator won't be in timelineItems
                                }
                            },
                            contentType = { _, item ->
                                when (item) {
                                    is ChatListItem.CalendarEvent -> ContentType.CALENDAR_EVENT
                                    is ChatListItem.Message -> {
                                        val message = item.message
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
                                    else -> item.contentType // DateSeparator, TypingIndicator won't be in timelineItems
                                }
                            }
                        ) { _, item ->
                            when (item) {
                                is ChatListItem.CalendarEvent -> {
                                    CalendarEventIndicator(
                                        text = item.event.getDisplayText()
                                    )
                                }
                                is ChatListItem.Message -> {
                                    val message = item.message
                                    val messageIndex = messageGuidToIndex[message.guid] ?: 0

                                    // Use derivedStateOf for efficient per-item selection check
                                    val isSelected by remember(message.guid) {
                                        derivedStateOf { message.guid in chatScreenState.selectedMessageGuids }
                                    }

                                    MessageListItem(
                                        message = message,
                                        index = messageIndex,
                                        messages = messages,
                                        chatScreenState = chatScreenState,
                                        chatInfoState = chatInfoState,
                                        searchDelegate = searchDelegate,
                                        attachmentDelegate = attachmentDelegate,
                                        etaSharingDelegate = etaSharingDelegate,
                                        effectsDelegate = effectsDelegate,
                                        highlightedMessageGuid = highlightedMessageGuid,
                                        isServerConnected = isServerConnected,
                                        initialLoadComplete = initialLoadComplete,
                                        nextVisibleMessage = nextVisibleMessageMap[messageIndex],
                                        lastOutgoingIndex = lastOutgoingIndex,
                                        latestEtaMessageIndex = latestEtaMessageIndex,
                                        showSenderName = showSenderNameMap[messageIndex] ?: false,
                                        showAvatar = showAvatarMap[messageIndex] ?: false,
                                        selectedMessageForTapback = selectedMessageForTapback,
                                        selectedMessageForRetry = selectedMessageForRetry,
                                        swipingMessageGuid = swipingMessageGuid,
                                        onSelectMessageForTapback = onSelectMessageForTapback,
                                        onSelectMessageForRetry = onSelectMessageForRetry,
                                        onCanRetrySmsUpdate = onCanRetrySmsUpdate,
                                        onSwipingMessageChange = onSwipingMessageChange,
                                        onSelectedBoundsChange = onSelectedBoundsChange,
                                        // Multi-message selection (disabled in bubble mode)
                                        isSelectionMode = !isBubbleMode && chatScreenState.isMessageSelectionMode,
                                        isSelected = isSelected,
                                        onEnterSelectionMode = if (isBubbleMode) { _ -> } else chatScreenState::enterMessageSelectionMode,
                                        onToggleSelection = if (isBubbleMode) { _ -> } else chatScreenState::toggleMessageSelection,
                                        callbacks = MessageItemCallbacks(
                                            onMediaClick = callbacks.onMediaClick,
                                            onSetReplyTo = callbacks.onSetReplyTo,
                                            onScrollToOriginal = callbacks.onScrollToOriginal,
                                            onLoadThread = callbacks.onLoadThread,
                                            onCanRetryAsSms = callbacks.onCanRetryAsSms,
                                            onRetryMessage = callbacks.onRetryMessage,
                                            onRetryAsSms = callbacks.onRetryAsSms,
                                            onDeleteMessage = callbacks.onDeleteMessage,
                                            onBubbleEffectCompleted = callbacks.onBubbleEffectCompleted,
                                            onClearHighlight = callbacks.onClearHighlight,
                                            onDownloadAttachment = callbacks.onDownloadAttachment,
                                            onStopSharingEta = callbacks.onStopSharingEta,
                                            onAvatarClick = callbacks.onAvatarClick,
                                            onOpenReelsFeed = callbacks.onOpenReelsFeed
                                        )
                                    )
                                }
                                // DateSeparator, TypingIndicator won't be in timelineItems
                                else -> Unit
                            }
                        }

                        // Error footer for pagination failures
                        if (loadError != null) {
                            item(key = "load_error", contentType = ContentType.BANNER) {
                                CursorPaginationErrorFooter(
                                    error = loadError!!,
                                    onRetry = { messageListDelegate.retryLoad() }
                                )
                            }
                        }

                        // Loading more messages indicator
                        if (isLoadingMore && hasMoreMessages) {
                            item(key = "loading_more", contentType = ContentType.LOADING_SKELETON) {
                                LoadingMoreIndicator()
                            }
                        }
                    }

                    // Overlays (jump to bottom + message spotlight)
                    MessageListOverlays(
                        listState = listState,
                        chatScreenState = chatScreenState,
                        isScrolledAwayFromBottom = isScrolledAwayFromBottom,
                        newMessageCountWhileAway = newMessageCountWhileAway,
                        onResetNewMessageCount = { newMessageCountWhileAway = 0 },
                        selectedMessageForTapback = selectedMessageForTapback,
                        selectedMessageBounds = selectedMessageBounds,
                        isServerConnected = isServerConnected,
                        composerHeight = composerHeightPxProvider(),
                        onSelectMessageForTapback = onSelectMessageForTapback,
                        onSelectedBoundsChange = onSelectedBoundsChange,
                        callbacks = MessageListOverlayCallbacks(
                            onToggleReaction = callbacks.onToggleReaction,
                            onSetReplyTo = callbacks.onSetReplyTo,
                            onForwardRequest = if (isBubbleMode) { _ -> } else callbacks.onForwardRequest,
                            onEnterSelectionMode = if (isBubbleMode) { _ -> } else chatScreenState::enterMessageSelectionMode
                        )
                    )
                }
            }
        }
    }
}

/**
 * Error footer shown when cursor pagination fails to load more messages.
 */
@Composable
private fun CursorPaginationErrorFooter(
    error: AppError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Failed to load more messages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Retry",
                modifier = Modifier.padding(end = 4.dp)
            )
            Text("Retry")
        }
    }
}
