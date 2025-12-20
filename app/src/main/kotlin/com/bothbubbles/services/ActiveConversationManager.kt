package com.bothbubbles.services

import timber.log.Timber
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which conversation the user is currently viewing.
 *
 * This allows message handlers to:
 * 1. Skip showing notifications for messages in the active chat
 * 2. Auto-mark messages as read when received in active chat
 *
 * ChatScreen should call setActiveConversation() when entering a chat
 * and clearActiveConversation() when leaving.
 *
 * The active conversation is also automatically cleared when the app
 * goes to background, ensuring notifications are shown when the app
 * is not visible.
 */
@Singleton
class ActiveConversationManager @Inject constructor() : DefaultLifecycleObserver {

    private var isInitialized = false

    /**
     * Initialize lifecycle observation. Should be called once from Application.onCreate()
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Timber.d("Initialized with ProcessLifecycleOwner")
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background - clear active conversation so notifications show
        Timber.d("App backgrounded - clearing active conversation")
        clearActiveConversation()
    }

    /**
     * Immutable state holder for active conversation.
     * Single atomic update prevents race condition where readers see
     * mismatched chatGuid and mergedGuids.
     */
    data class ActiveConversationState(
        val chatGuid: String,
        val mergedGuids: Set<String>
    )

    // Single volatile field for atomic state updates
    @Volatile
    private var activeConversation: ActiveConversationState? = null

    /**
     * Set the active conversation when user enters a chat.
     *
     * @param chatGuid The primary chat GUID
     * @param mergedGuids Additional GUIDs that represent the same conversation
     *                    (e.g., iMessage and SMS GUIDs for same contact)
     */
    fun setActiveConversation(chatGuid: String, mergedGuids: Set<String> = emptySet()) {
        Timber.d("Setting active conversation: $chatGuid (merged: ${mergedGuids.size})")
        // Single atomic write - prevents readers from seeing inconsistent state
        activeConversation = ActiveConversationState(
            chatGuid = chatGuid,
            mergedGuids = mergedGuids + chatGuid // Include primary in merged set
        )
    }

    /**
     * Clear the active conversation when user leaves a chat.
     */
    fun clearActiveConversation() {
        val previous = activeConversation?.chatGuid
        Timber.d("Clearing active conversation (was: $previous)")
        activeConversation = null
    }

    /**
     * Check if a conversation is currently active (user is viewing it).
     *
     * @param chatGuid The chat GUID to check
     * @return true if this chat is currently being viewed
     */
    fun isConversationActive(chatGuid: String): Boolean {
        val current = activeConversation ?: return false
        return current.chatGuid == chatGuid || current.mergedGuids.contains(chatGuid)
    }

    /**
     * Get the currently active conversation GUID, if any.
     */
    fun getActiveConversation(): String? {
        return activeConversation?.chatGuid
    }

    /**
     * Get all GUIDs considered part of the active conversation
     * (includes primary and merged GUIDs).
     */
    fun getActiveConversationGuids(): Set<String> {
        return activeConversation?.mergedGuids ?: emptySet()
    }
}
