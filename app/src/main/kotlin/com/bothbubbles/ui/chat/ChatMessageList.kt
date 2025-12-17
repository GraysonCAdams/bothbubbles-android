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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.bothbubbles.ui.chat.delegates.ChatEffectsDelegate
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatMessageListDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.delegates.ChatSyncDelegate
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
    chatScreenState: ChatScreenState,
    messages: List<MessageUiModel>,

    // Wave 2: All delegates for internal state collection
    messageListDelegate: ChatMessageListDelegate,
    sendDelegate: ChatSendDelegate,
    searchDelegate: ChatSearchDelegate,
    syncDelegate: ChatSyncDelegate,
    operationsDelegate: ChatOperationsDelegate,
    attachmentDelegate: ChatAttachmentDelegate,
    etaSharingDelegate: ChatEtaSharingDelegate,
    effectsDelegate: ChatEffectsDelegate,

    // State objects (passed by reference - still needed at this level)
    chatInfoState: ChatInfoState,

    // UI state
    highlightedMessageGuid: String?,
    isLoadingMore: Boolean,
    isSyncingMessages: Boolean,

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
    isServerConnected: Boolean
) {
    // Collect state internally from delegates to avoid ChatScreen recomposition
    val sendState by sendDelegate.state.collectAsStateWithLifecycle()
    val searchState by searchDelegate.state.collectAsStateWithLifecycle()
    val syncState by syncDelegate.state.collectAsStateWithLifecycle()
    val operationsState by operationsDelegate.state.collectAsStateWithLifecycle()
    val effectsState by effectsDelegate.state.collectAsStateWithLifecycle()
    val etaSharingState by etaSharingDelegate.etaSharingState.collectAsStateWithLifecycle()
    val isLoadingFromServer by messageListDelegate.isLoadingFromServer.collectAsStateWithLifecycle()
    val initialLoadComplete by messageListDelegate.initialLoadComplete.collectAsStateWithLifecycle()
    val autoDownloadEnabled by attachmentDelegate.autoDownloadEnabled.collectAsStateWithLifecycle()

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

        // For sent messages: scroll if NOT already at bottom (user scrolled up before sending)
        if (newestMessage.isFromMe) {
            if (isNewMessage && firstVisibleIndex > 0) {
                scrollDebugLog { "📜 SCROLLING for SENT message: firstVisibleIndex=$firstVisibleIndex > 0" }
                delay(50)
                listState.scrollToItem(0)
                scrollDebugLog { "📜 Scroll complete for sent, now at index=${listState.firstVisibleItemIndex}" }
            } else {
                scrollDebugLog { "📜 SKIP sent message scroll: isNewMessage=$isNewMessage, firstVisibleIndex=$firstVisibleIndex" }
            }
            return@LaunchedEffect
        }

        if (isNewMessage && isNearBottom) {
            scrollDebugLog { "📜 SCROLLING to item 0 for incoming message" }
            delay(50)
            listState.scrollToItem(0)
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
                            MessageListItem(
                                message = message,
                                index = index,
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
                                nextVisibleMessage = nextVisibleMessageMap[index],
                                lastOutgoingIndex = lastOutgoingIndex,
                                latestEtaMessageIndex = latestEtaMessageIndex,
                                showSenderName = showSenderNameMap[index] ?: false,
                                showAvatar = showAvatarMap[index] ?: false,
                                selectedMessageForTapback = selectedMessageForTapback,
                                selectedMessageForRetry = selectedMessageForRetry,
                                swipingMessageGuid = swipingMessageGuid,
                                onSelectMessageForTapback = onSelectMessageForTapback,
                                onSelectMessageForRetry = onSelectMessageForRetry,
                                onCanRetrySmsUpdate = onCanRetrySmsUpdate,
                                onSwipingMessageChange = onSwipingMessageChange,
                                onSelectedBoundsChange = onSelectedBoundsChange,
                                callbacks = MessageItemCallbacks(
                                    onMediaClick = callbacks.onMediaClick,
                                    onSetReplyTo = callbacks.onSetReplyTo,
                                    onLoadThread = callbacks.onLoadThread,
                                    onCanRetryAsSms = callbacks.onCanRetryAsSms,
                                    onBubbleEffectCompleted = callbacks.onBubbleEffectCompleted,
                                    onClearHighlight = callbacks.onClearHighlight,
                                    onDownloadAttachment = callbacks.onDownloadAttachment,
                                    onStopSharingEta = callbacks.onStopSharingEta
                                )
                            )
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

                    // Overlays (jump to bottom + message spotlight)
                    MessageListOverlays(
                        listState = listState,
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
                            onForwardRequest = callbacks.onForwardRequest
                        )
                    )
                }
            }
        }
    }
}
