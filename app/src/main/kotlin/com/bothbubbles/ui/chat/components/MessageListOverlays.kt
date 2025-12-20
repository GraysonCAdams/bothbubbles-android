package com.bothbubbles.ui.chat.components

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bothbubbles.ui.chat.ChatScreenState
import com.bothbubbles.ui.components.message.JumpToBottomIndicator
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.components.message.focus.MessageFocusOverlay
import com.bothbubbles.ui.components.message.focus.MessageFocusState
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.launch

/**
 * Callbacks for message list overlay interactions.
 */
data class MessageListOverlayCallbacks(
    val onToggleReaction: (messageGuid: String, tapback: Tapback) -> Unit,
    val onSetReplyTo: (guid: String) -> Unit,
    val onForwardRequest: (message: MessageUiModel) -> Unit,
    val onEnterSelectionMode: (messageGuid: String) -> Unit
)

/**
 * Overlay components for the message list.
 *
 * Includes:
 * - Jump to bottom indicator (shows new message count)
 * - Message focus overlay (tapback reactions and actions)
 * - Scroll-to-accommodate logic (scrolls list to make room for menu above message)
 * - Scroll restoration on dismiss
 */
@Composable
fun MessageListOverlays(
    listState: LazyListState,
    chatScreenState: ChatScreenState,
    isScrolledAwayFromBottom: Boolean,
    newMessageCountWhileAway: Int,
    onResetNewMessageCount: () -> Unit,
    selectedMessageForTapback: MessageUiModel?,
    selectedMessageBounds: androidx.compose.ui.geometry.Rect?,
    isServerConnected: Boolean,
    composerHeight: Float,
    onSelectMessageForTapback: (MessageUiModel?) -> Unit,
    onSelectedBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit,
    callbacks: MessageListOverlayCallbacks,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current

    // Get safe area top (status bar height)
    val safeAreaTop = remember(view) {
        val windowInsets = ViewCompat.getRootWindowInsets(view)
        val systemBars = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
        (systemBars?.top ?: 0).toFloat()
    }

    // Menu height estimate (reactions row + horizontal actions bar)
    val menuHeightPx = with(density) { 120.dp.toPx() }
    val spacingPx = with(density) { 16.dp.toPx() }

    // Save scroll position when a message is selected
    LaunchedEffect(selectedMessageForTapback) {
        if (selectedMessageForTapback != null && chatScreenState.preFocusScrollIndex == null) {
            // Save current scroll position before any accommodation scroll
            chatScreenState.saveFocusScrollPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    // Scroll to accommodate menu when bounds are available
    LaunchedEffect(selectedMessageBounds) {
        val bounds = selectedMessageBounds ?: return@LaunchedEffect
        if (selectedMessageForTapback == null) return@LaunchedEffect

        // Calculate space above the message
        val spaceAbove = bounds.top - safeAreaTop - spacingPx
        val spaceNeeded = menuHeightPx + spacingPx

        if (spaceAbove < spaceNeeded) {
            // Need to scroll up to make room for the menu
            // Increase the scroll offset to push content up
            val scrollAmount = (spaceNeeded - spaceAbove).toInt()
            val currentIndex = listState.firstVisibleItemIndex
            val currentOffset = listState.firstVisibleItemScrollOffset
            val newOffset = currentOffset + scrollAmount

            chatScreenState.markScrolledForFocus()
            listState.animateScrollToItem(currentIndex, newOffset)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Jump to bottom indicator
        JumpToBottomIndicator(
            visible = isScrolledAwayFromBottom,
            newMessageCount = newMessageCountWhileAway,
            onClick = {
                scrollScope.launch {
                    listState.animateScrollToItem(0)
                    onResetNewMessageCount()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )

        // Build focus state from selected message
        val focusState = remember(selectedMessageForTapback, selectedMessageBounds, isServerConnected) {
            if (selectedMessageForTapback != null && selectedMessageBounds != null) {
                MessageFocusState(
                    visible = true,
                    messageId = selectedMessageForTapback.guid,
                    messageBounds = selectedMessageBounds,
                    isFromMe = selectedMessageForTapback.isFromMe,
                    myReactions = selectedMessageForTapback.myReactions.toImmutableSet(),
                    canReact = selectedMessageForTapback.isServerOrigin && isServerConnected,
                    canCopy = !selectedMessageForTapback.text.isNullOrBlank(),
                    canForward = true,
                    canReply = selectedMessageForTapback.isServerOrigin
                )
            } else {
                MessageFocusState.Empty
            }
        }

        // Dismiss handler that restores scroll position
        val handleDismiss: () -> Unit = {
            scrollScope.launch {
                // Restore scroll position if we scrolled to accommodate
                if (chatScreenState.didScrollForFocus) {
                    val savedIndex = chatScreenState.preFocusScrollIndex
                    val savedOffset = chatScreenState.preFocusScrollOffset
                    if (savedIndex != null) {
                        listState.animateScrollToItem(savedIndex, savedOffset ?: 0)
                    }
                }

                // Clear selection state
                chatScreenState.clearTapbackSelection()
                onSelectMessageForTapback(null)
                onSelectedBoundsChange(null)
            }
        }

        // Message Focus Overlay (menu only, no ghost - highlighting is in MessageListItem)
        MessageFocusOverlay(
            state = focusState,
            onDismiss = handleDismiss,
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
            },
            onSelect = {
                selectedMessageForTapback?.let { message ->
                    callbacks.onEnterSelectionMode(message.guid)
                }
            }
        )
    }
}
