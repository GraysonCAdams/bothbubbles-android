package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.ui.chat.PendingMessage
import com.bothbubbles.ui.chat.QueuedMessageUiModel
import com.bothbubbles.ui.util.StableList
import com.bothbubbles.ui.util.toStable

/**
 * State owned by ChatSendDelegate.
 * Contains all send-related UI state, isolated from other domains to prevent cascade recompositions.
 */
@Stable
data class SendState(
    val isSending: Boolean = false,
    val sendProgress: Float = 0f,
    val pendingMessages: StableList<PendingMessage> = emptyList<PendingMessage>().toStable(),
    val queuedMessages: StableList<QueuedMessageUiModel> = emptyList<QueuedMessageUiModel>().toStable(),
    val replyingToGuid: String? = null,
    val isForwarding: Boolean = false,
    val forwardSuccess: Boolean = false,
    val sendError: String? = null
)
