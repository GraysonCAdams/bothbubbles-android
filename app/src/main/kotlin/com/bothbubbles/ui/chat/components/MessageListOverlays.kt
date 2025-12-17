package com.bothbubbles.ui.chat.components

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.message.JumpToBottomIndicator
import com.bothbubbles.ui.components.message.MessageBubble
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.components.message.MessageSpotlightOverlay
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.Tapback
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

        // Message Spotlight Overlay
        MessageSpotlightOverlay(
            visible = selectedMessageForTapback != null && selectedMessageBounds != null,
            anchorBounds = selectedMessageBounds,
            isFromMe = selectedMessageForTapback?.isFromMe == true,
            composerHeight = composerHeight,
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
