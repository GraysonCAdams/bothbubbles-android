package com.bothbubbles.ui.chat.components

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.message.JumpToBottomIndicator
import com.bothbubbles.ui.components.message.MessageBubble
import com.bothbubbles.ui.components.message.MessageGroupPosition
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
    val onForwardRequest: (message: MessageUiModel) -> Unit
)

/**
 * Overlay components for the message list.
 *
 * Includes:
 * - Jump to bottom indicator (shows new message count)
 * - Message spotlight overlay (tapback reactions and actions)
 */
@Composable
fun MessageListOverlays(
    listState: LazyListState,
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

        // Message Focus Overlay
        MessageFocusOverlay(
            state = focusState,
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
            // Render the focused message as the "ghost"
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
