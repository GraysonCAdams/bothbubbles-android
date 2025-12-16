package com.bothbubbles.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Encapsulates scroll-related side effects for ChatScreen.
 *
 * This helper extracts scroll logic from ChatScreen to improve readability and maintainability.
 * It handles:
 * - Keyboard hiding on scroll
 * - Load more triggers when scrolling to older messages
 * - Scroll position tracking for state restoration
 *
 * Usage:
 * ```
 * ChatScrollEffects(
 *     listState = listState,
 *     keyboardController = keyboardController,
 *     canLoadMore = uiState.canLoadMore,
 *     isLoadingMore = uiState.isLoadingMore,
 *     onLoadMore = { viewModel.messageList.loadMoreMessages() },
 *     onScrollPositionChanged = { index, offset, visibleCount ->
 *         viewModel.updateScrollPosition(index, offset, visibleCount)
 *         viewModel.onScrollPositionChanged(index, index + visibleCount - 1)
 *     }
 * )
 * ```
 */
@Composable
fun ChatScrollEffects(
    listState: LazyListState,
    keyboardController: SoftwareKeyboardController?,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onScrollPositionChanged: (index: Int, offset: Int, visibleCount: Int) -> Unit
) {
    // Hide keyboard when user scrolls more than a threshold
    KeyboardHideOnScroll(
        listState = listState,
        keyboardController = keyboardController
    )

    // Load more messages when scrolling near the top (older messages)
    LoadMoreOnScroll(
        listState = listState,
        canLoadMore = canLoadMore,
        isLoadingMore = isLoadingMore,
        onLoadMore = onLoadMore
    )

    // Track scroll position changes for state restoration and paging
    ScrollPositionTracker(
        listState = listState,
        onScrollPositionChanged = onScrollPositionChanged
    )
}

/**
 * Hides the keyboard when user scrolls more than a threshold (~250dp).
 * Resets accumulated scroll when scrolling stops.
 */
@Composable
private fun KeyboardHideOnScroll(
    listState: LazyListState,
    keyboardController: SoftwareKeyboardController?
) {
    LaunchedEffect(listState) {
        var previousScrollOffset = listState.firstVisibleItemScrollOffset
        var previousFirstVisibleItem = listState.firstVisibleItemIndex
        var accumulatedScroll = 0

        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (currentIndex, currentOffset, isScrolling) ->
            if (isScrolling) {
                // Calculate scroll delta
                val scrollDelta = if (currentIndex == previousFirstVisibleItem) {
                    currentOffset - previousScrollOffset
                } else {
                    // Changed items, estimate large scroll
                    (currentIndex - previousFirstVisibleItem) * 200
                }
                accumulatedScroll += kotlin.math.abs(scrollDelta)

                // Hide keyboard after scrolling ~250dp worth
                if (accumulatedScroll > 750) {
                    keyboardController?.hide()
                    accumulatedScroll = 0
                }
            } else {
                // Reset when scroll stops
                accumulatedScroll = 0
            }

            previousFirstVisibleItem = currentIndex
            previousScrollOffset = currentOffset
        }
    }
}

/**
 * Triggers load more when scrolling near the top (older messages).
 * Since reverseLayout=true, higher indices = older messages at the visual top.
 */
@Composable
private fun LoadMoreOnScroll(
    listState: LazyListState,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItem >= totalItems - 25 && totalItems > 0
        }
            .distinctUntilChanged()
            .collect { shouldLoadMore ->
                if (shouldLoadMore && canLoadMore && !isLoadingMore) {
                    onLoadMore()
                }
            }
    }
}

/**
 * Tracks scroll position changes for state restoration and paging.
 * Reports first visible item index, scroll offset, and visible item count.
 */
@Composable
private fun ScrollPositionTracker(
    listState: LazyListState,
    onScrollPositionChanged: (index: Int, offset: Int, visibleCount: Int) -> Unit
) {
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
            Triple(
                firstVisible,
                listState.firstVisibleItemScrollOffset,
                lastVisible
            )
        }.collect { (index, offset, lastVisibleIndex) ->
            onScrollPositionChanged(index, offset, lastVisibleIndex - index + 1)
        }
    }
}

/**
 * State holder for scroll-to-safety behavior.
 * Used when showing tapback menu to ensure selected message is visible.
 */
class ScrollToSafetyState {
    var isInProgress by mutableStateOf(false)
        private set

    fun startScrollToSafety() {
        isInProgress = true
    }

    fun finishScrollToSafety() {
        isInProgress = false
    }
}

/**
 * Remember a ScrollToSafetyState instance.
 */
@Composable
fun rememberScrollToSafetyState(): ScrollToSafetyState {
    return remember { ScrollToSafetyState() }
}

/**
 * Scrolls to ensure the selected message is visible and centered for tapback menu.
 * Dismisses selection when user scrolls (but not during programmatic scroll-to-safety).
 *
 * @param selectedMessageGuid The GUID of the selected message, or null if none selected
 * @param messages The list of messages to search for the selected message
 * @param listState The LazyListState to scroll
 * @param scrollToSafetyState State holder to track programmatic scroll
 * @param onDismiss Callback to dismiss the selection when user scrolls
 */
@Composable
fun ScrollToSafetyEffect(
    selectedMessageGuid: String?,
    messages: List<com.bothbubbles.ui.components.message.MessageUiModel>,
    listState: LazyListState,
    scrollToSafetyState: ScrollToSafetyState,
    onDismiss: () -> Unit
) {
    // Scroll-to-safety: When showing tapback menu, ensure message is visible and centered
    LaunchedEffect(selectedMessageGuid) {
        val messageGuid = selectedMessageGuid ?: return@LaunchedEffect
        val messageIndex = messages.indexOfFirst { it.guid == messageGuid }
        if (messageIndex < 0) return@LaunchedEffect

        // Get current viewport info
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val viewportHeight = layoutInfo.viewportSize.height

        // Check if message is currently visible
        val isVisible = visibleItems.any { it.index == messageIndex }

        // Calculate safe zone: center third of viewport
        // With reverseLayout=true, we want to position message in center
        if (!isVisible) {
            // Message not visible - scroll to center it
            scrollToSafetyState.startScrollToSafety()
            val centerOffset = -(viewportHeight / 3)
            listState.animateScrollToItem(messageIndex, scrollOffset = centerOffset)
            scrollToSafetyState.finishScrollToSafety()
        } else {
            // Message is visible - check if it's in safe zone
            val visibleItem = visibleItems.find { it.index == messageIndex }
            if (visibleItem != null) {
                val itemTop = visibleItem.offset
                val itemBottom = visibleItem.offset + visibleItem.size
                val safeTop = viewportHeight / 4
                val safeBottom = viewportHeight * 3 / 4

                // If message extends outside safe zone, scroll to center
                if (itemTop < safeTop || itemBottom > safeBottom) {
                    scrollToSafetyState.startScrollToSafety()
                    val centerOffset = -(viewportHeight / 3)
                    listState.animateScrollToItem(messageIndex, scrollOffset = centerOffset)
                    scrollToSafetyState.finishScrollToSafety()
                }
            }
        }
    }

    // Dismiss tapback menu when user scrolls (but not during programmatic scroll-to-safety)
    LaunchedEffect(selectedMessageGuid) {
        if (selectedMessageGuid != null) {
            // Wait for scroll-to-safety to complete before enabling dismiss-on-scroll
            snapshotFlow { listState.isScrollInProgress to scrollToSafetyState.isInProgress }
                .collect { (isScrolling, isScrollToSafety) ->
                    if (isScrolling && !isScrollToSafety) {
                        onDismiss()
                    }
                }
        }
    }
}

/**
 * Auto-scrolls to the newest message when it arrives (if user is near bottom).
 * Tracks new messages from socket for the "new messages" indicator.
 *
 * @param messages The list of messages
 * @param listState The LazyListState to scroll
 * @param socketNewMessageFlow Flow of new message GUIDs from socket
 * @param onNewMessageCountIncrement Callback when a new message arrives while scrolled away
 * @param hapticFeedback Optional haptic feedback for incoming messages
 */
@Composable
fun AutoScrollOnNewMessage(
    newestMessageGuid: String?,
    listState: LazyListState,
    onAutoScroll: suspend () -> Unit
) {
    // Track the previous newest message GUID to detect truly NEW messages (not initial load)
    var previousNewestGuid by remember { mutableStateOf<String?>(null) }
    var hasInitiallyLoaded by remember { mutableStateOf(false) }

    // Auto-scroll to show newest message when it arrives (if user is viewing recent messages)
    LaunchedEffect(newestMessageGuid) {
        val isNearBottom = listState.firstVisibleItemIndex <= 2

        // Skip if no messages yet
        if (newestMessageGuid == null) return@LaunchedEffect

        // Track initial load - don't auto-scroll on first message load
        if (!hasInitiallyLoaded) {
            hasInitiallyLoaded = true
            previousNewestGuid = newestMessageGuid
            return@LaunchedEffect
        }

        // Only auto-scroll if a NEW message arrived (guid changed from previous)
        val isNewMessage = previousNewestGuid != null && previousNewestGuid != newestMessageGuid
        previousNewestGuid = newestMessageGuid

        if (isNewMessage && isNearBottom) {
            // Small delay to let the message render and calculate its height
            kotlinx.coroutines.delay(100)
            onAutoScroll()
        }
    }
}

/**
 * Derives whether user is scrolled away from bottom.
 * With reverseLayout=true, index 0 = bottom.
 */
@Composable
fun rememberIsScrolledAwayFromBottom(listState: LazyListState): State<Boolean> {
    return remember {
        derivedStateOf { listState.firstVisibleItemIndex > 3 }
    }
}
