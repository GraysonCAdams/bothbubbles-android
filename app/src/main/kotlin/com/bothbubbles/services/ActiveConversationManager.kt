package com.bothbubbles.services

import android.util.Log
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
 */
@Singleton
class ActiveConversationManager @Inject constructor() {

    companion object {
        private const val TAG = "ActiveConversationMgr"
    }

    // The currently active chat GUID (null if no chat is open)
    @Volatile
    private var activeChatGuid: String? = null

    // Set of merged chat GUIDs that are also considered "active"
    // (for unified conversations spanning iMessage + SMS)
    @Volatile
    private var activeMergedGuids: Set<String> = emptySet()

    /**
     * Set the active conversation when user enters a chat.
     *
     * @param chatGuid The primary chat GUID
     * @param mergedGuids Additional GUIDs that represent the same conversation
     *                    (e.g., iMessage and SMS GUIDs for same contact)
     */
    fun setActiveConversation(chatGuid: String, mergedGuids: Set<String> = emptySet()) {
        Log.d(TAG, "Setting active conversation: $chatGuid (merged: ${mergedGuids.size})")
        activeChatGuid = chatGuid
        activeMergedGuids = mergedGuids + chatGuid // Include primary in merged set
    }

    /**
     * Clear the active conversation when user leaves a chat.
     */
    fun clearActiveConversation() {
        Log.d(TAG, "Clearing active conversation (was: $activeChatGuid)")
        activeChatGuid = null
        activeMergedGuids = emptySet()
    }

    /**
     * Check if a conversation is currently active (user is viewing it).
     *
     * @param chatGuid The chat GUID to check
     * @return true if this chat is currently being viewed
     */
    fun isConversationActive(chatGuid: String): Boolean {
        return activeChatGuid == chatGuid || activeMergedGuids.contains(chatGuid)
    }

    /**
     * Get the currently active conversation GUID, if any.
     */
    fun getActiveConversation(): String? {
        return activeChatGuid
    }

    /**
     * Get all GUIDs considered part of the active conversation
     * (includes primary and merged GUIDs).
     */
    fun getActiveConversationGuids(): Set<String> {
        return activeMergedGuids
    }
}
