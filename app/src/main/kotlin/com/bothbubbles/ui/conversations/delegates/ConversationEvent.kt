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
     * Chat was marked as read (from another device via socket).
     */
    data class ChatRead(val chatGuid: String) : ConversationEvent()

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
