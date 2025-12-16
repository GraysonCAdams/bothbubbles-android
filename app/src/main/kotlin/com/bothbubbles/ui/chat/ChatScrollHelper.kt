package com.bothbubbles.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Scroll helpers for ChatMessageList.
 *
 * Scroll behavior ownership:
 * - Load more on scroll: LoadMoreOnScroll (this file)
 * - Scroll position tracking: ScrollPositionTracker (this file)
 * - Keyboard hide on scroll: KeyboardHideOnScroll (this file)
 * - Search result scrolling: ChatMessageList.kt (LaunchedEffect on currentMatchIndex)
 * - Highlighted message scrolling: ChatMessageList.kt (LaunchedEffect on highlightedMessageGuid)
 * - New message auto-scroll: ChatMessageList.kt (LaunchedEffect on messages.firstOrNull()?.guid)
 * - Typing indicator auto-scroll: ChatMessageList.kt (LaunchedEffect on isTyping)
 * - Scroll restoration: ChatScreenEffects.kt (LaunchedEffect on messages.isNotEmpty)
 * - Deep-link/thread scrolling: ChatScreenEffects.kt (LaunchedEffect on targetMessageGuid)
 * - Scroll-to-safety for tapback: ChatScreenEffects.kt (LaunchedEffect on selectedMessageForTapback)
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
    val currentCanLoadMore by rememberUpdatedState(canLoadMore)
    val currentIsLoadingMore by rememberUpdatedState(isLoadingMore)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)

    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItem >= totalItems - 25 && totalItems > 0
        }
            .distinctUntilChanged()
            .collect { shouldLoadMore ->
                if (shouldLoadMore && currentCanLoadMore && !currentIsLoadingMore) {
                    currentOnLoadMore()
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
 * Derives whether user is scrolled away from bottom.
 * With reverseLayout=true, index 0 = bottom.
 */
@Composable
fun rememberIsScrolledAwayFromBottom(listState: LazyListState): State<Boolean> {
    return remember {
        derivedStateOf { listState.firstVisibleItemIndex > 3 }
    }
}
