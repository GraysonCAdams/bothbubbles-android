package com.bothbubbles.ui.conversations.delegates

/**
 * Events emitted by conversation delegates for ViewModel coordination.
 *
 * Phase 8: Replaces callback interfaces with event-based communication.
 * The ViewModel collects these events and decides how to respond.
 */
sealed class ConversationEvent {

    // ============================================================================
    // Observer Events - from ConversationObserverDelegate
    // ============================================================================

    /**
     * Database or socket data changed, refresh needed.
     */
    data object DataChanged : ConversationEvent()

    /**
     * New message received via socket.
     */
    data object NewMessage : ConversationEvent()

    /**
     * Message updated via socket (read receipt, delivery, edit).
     */
    data object MessageUpdated : ConversationEvent()

    /**
     * Chat read status changed (from another device via socket).
     * @param isRead true if marked as read, false if marked as unread
     */
    data class ChatReadStatusChanged(val chatGuid: String, val isRead: Boolean) : ConversationEvent()

    // ============================================================================
    // Action Events - from ConversationActionsDelegate
    // ============================================================================

    /**
     * Conversations list was updated (optimistic UI update).
     */
    data class ConversationsUpdated(
        val conversations: List<com.bothbubbles.ui.conversations.ConversationUiModel>
    ) : ConversationEvent()

    /**
     * Request to scroll to a specific index (e.g., after pinning).
     */
    data class ScrollToIndex(val index: Int) : ConversationEvent()

    /**
     * An action failed with an error.
     */
    data class ActionError(val message: String) : ConversationEvent()
}
