package com.bothbubbles.ui.conversations

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * State holder for pull-to-search behavior.
 *
 * @property pullOffset Current pull offset in pixels
 * @property nestedScrollConnection Connection to attach to the scrollable container
 */
class PullToSearchState(
    pullOffset: Float,
    val nestedScrollConnection: NestedScrollConnection
) {
    var pullOffset by mutableFloatStateOf(pullOffset)
        internal set
}

/**
 * Creates and remembers pull-to-search state with a nested scroll connection.
 *
 * Handles detecting overscroll at the top of a list and converting it into a
 * pull-to-search gesture. When the pull threshold is reached, [onSearchActivated]
 * is called.
 *
 * @param listState The list state to monitor for scroll position
 * @param isSearchActive Whether search is currently active (prevents re-triggering)
 * @param onSearchActivated Callback when pull threshold is reached
 * @param pullThresholdDp Distance in dp to pull before triggering search (default 80dp)
 * @return [PullToSearchState] containing the pull offset and nested scroll connection
 */
@Composable
fun rememberPullToSearchState(
    listState: LazyListState,
    isSearchActive: Boolean,
    onSearchActivated: () -> Unit,
    pullThresholdDp: Float = 80f
): PullToSearchState {
    val density = LocalDensity.current
    val pullThreshold = with(density) { pullThresholdDp.dp.toPx() }

    var pullOffset by remember { mutableFloatStateOf(0f) }
    // Track if the current gesture started at the top (prevents flings from triggering pull-to-search)
    var gestureStartedAtTop by remember { mutableStateOf(false) }

    // Nested scroll connection to detect overscroll at top
    val nestedScrollConnection = remember(listState, pullThreshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val isAtTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0

                // Only allow pull-to-search if it's a drag that started at the top
                // This prevents flings from below carrying through and triggering pull-to-search
                if (source == NestedScrollSource.UserInput && isAtTop && !gestureStartedAtTop) {
                    gestureStartedAtTop = true
                }

                // When scrolling up (pulling down) at the top of the list
                // Only allow if the gesture started at the top (not from a fling)
                if (available.y > 0 && isAtTop && gestureStartedAtTop &&
                    source == NestedScrollSource.UserInput
                ) {
                    pullOffset = (pullOffset + available.y * 0.5f).coerceIn(0f, pullThreshold * 1.5f)
                    return Offset(0f, available.y)
                }
                // Reset pull offset when scrolling down
                if (available.y < 0 && pullOffset > 0) {
                    val consumed = minOf(-available.y, pullOffset)
                    pullOffset -= consumed
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val isAtTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0

                // Handle overscroll at top - only for user input when gesture started at top
                if (available.y > 0 && isAtTop && gestureStartedAtTop &&
                    source == NestedScrollSource.UserInput
                ) {
                    pullOffset = (pullOffset + available.y * 0.5f).coerceIn(0f, pullThreshold * 1.5f)
                    return available
                }
                return Offset.Zero
            }
        }
    }

    // Trigger search when pull threshold is reached
    LaunchedEffect(pullOffset, isSearchActive) {
        if (pullOffset >= pullThreshold && !isSearchActive) {
            onSearchActivated()
            pullOffset = 0f
        }
    }

    // Reset pull offset and gesture state when finger is released
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            if (pullOffset > 0 && pullOffset < pullThreshold) {
                pullOffset = 0f
            }
            gestureStartedAtTop = false
        }
    }

    return remember(nestedScrollConnection) {
        PullToSearchState(
            pullOffset = pullOffset,
            nestedScrollConnection = nestedScrollConnection
        )
    }.also {
        it.pullOffset = pullOffset
    }
}
