package com.bothbubbles.ui.conversations.delegates

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.UnifiedChatGroupRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.ui.components.conversation.SwipeActionType
import com.bothbubbles.ui.conversations.ConversationUiModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Delegate responsible for user actions on conversations.
 * Handles pin, mute, snooze, archive, delete, mark read/unread, block, etc.
 *
 * Phase 8: Uses AssistedInject for lifecycle-safe construction and
 * SharedFlow events instead of callbacks for ViewModel coordination.
 */
class ConversationActionsDelegate @AssistedInject constructor(
    private val application: Application,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val handleRepository: HandleRepository,
    private val unifiedChatGroupRepository: UnifiedChatGroupRepository,
    private val androidContactsService: AndroidContactsService,
    private val settingsDataStore: SettingsDataStore,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ConversationActionsDelegate
    }

    companion object {
        private const val TAG = "ConversationActionsDelegate"
    }

    // ============================================================================
    // Event Flow - Phase 8: Replaces callbacks
    // ============================================================================

    private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    /**
     * Handle swipe action on a conversation.
     */
    fun handleSwipeAction(chatGuid: String, action: SwipeActionType, conversations: List<ConversationUiModel>) {
        scope.launch {
            when (action) {
                SwipeActionType.PIN, SwipeActionType.UNPIN -> togglePin(chatGuid, conversations)
                SwipeActionType.ARCHIVE -> archiveChat(chatGuid, conversations)
                SwipeActionType.DELETE -> deleteChat(chatGuid, conversations)
                SwipeActionType.MUTE, SwipeActionType.UNMUTE -> toggleMute(chatGuid, conversations)
                SwipeActionType.MARK_READ -> markAsRead(chatGuid, conversations)
                SwipeActionType.MARK_UNREAD -> markAsUnread(chatGuid, conversations)
                SwipeActionType.SNOOZE -> snoozeChat(chatGuid, 1 * 60 * 60 * 1000L, conversations) // Quick snooze for 1 hour
                SwipeActionType.UNSNOOZE -> unsnoozeChat(chatGuid, conversations)
                SwipeActionType.NONE -> { /* No action */ }
            }
        }
    }

    /**
     * Snooze a chat for a specific duration.
     */
    fun snoozeChat(chatGuid: String, durationMs: Long, conversations: List<ConversationUiModel>) {
        scope.launch {
            val snoozeUntil = if (durationMs == -1L) -1L else System.currentTimeMillis() + durationMs

            // Optimistically update UI immediately for instant feedback
            val updated = conversations.map { conv ->
                if (conv.guid == chatGuid) conv.copy(isSnoozed = true, snoozeUntil = snoozeUntil)
                else conv
            }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatRepository.snoozeChat(chatGuid, durationMs)
        }
    }

    /**
     * Unsnooze a chat.
     */
    fun unsnoozeChat(chatGuid: String, conversations: List<ConversationUiModel>) {
        scope.launch {
            // Optimistically update UI immediately for instant feedback
            val updated = conversations.map { conv ->
                if (conv.guid == chatGuid) conv.copy(isSnoozed = false, snoozeUntil = null)
                else conv
            }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatRepository.unsnoozeChat(chatGuid)
        }
    }

    /**
     * Mark a chat as unread.
     */
    fun markAsUnread(chatGuid: String, conversations: List<ConversationUiModel>) {
        scope.launch {
            // Optimistically update UI immediately for instant feedback
            val updated = conversations.map { conv ->
                if (conv.guid == chatGuid) conv.copy(unreadCount = 1)
                else conv
            }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatRepository.markChatAsUnread(chatGuid)
        }
    }

    /**
     * Toggle pin status for a chat.
     */
    fun togglePin(chatGuid: String, conversations: List<ConversationUiModel>) {
        scope.launch {
            val conversation = conversations.find { it.guid == chatGuid }
            if (conversation == null) return@launch

            // If already pinned, always allow unpinning
            // If not pinned, only allow pinning if the contact is saved
            if (!conversation.isPinned && !conversation.hasContact) return@launch

            val newPinState = !conversation.isPinned

            // Calculate new pin index: add to end when pinning, clear when unpinning
            val currentPinnedCount = conversations.count { it.isPinned }
            val newPinIndex = if (newPinState) {
                val maxPinIndex = conversations
                    .filter { it.isPinned }
                    .maxOfOrNull { it.pinIndex } ?: -1
                maxPinIndex + 1
            } else {
                Int.MAX_VALUE
            }

            // Optimistically update UI immediately for instant feedback
            val updated = conversations.map { conv ->
                if (conv.guid == chatGuid) conv.copy(isPinned = newPinState, pinIndex = newPinIndex)
                else conv
            }.sortedWith(
                compareByDescending<ConversationUiModel> { it.isPinned }
                    .thenBy { it.pinIndex }
                    .thenByDescending { it.lastMessageTimestamp }
            )
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Emit scroll event when pinning (not unpinning)
            if (newPinState) {
                // If more than 3 pins, scroll to the new pin position (end of pins)
                // Otherwise scroll to top
                val scrollIndex = if (currentPinnedCount >= 3) newPinIndex else 0
                _events.emit(ConversationEvent.ScrollToIndex(scrollIndex))
            }

            // Persist to database in background - update both chats and unified_chat_groups
            chatRepository.setPinned(chatGuid, newPinState, if (newPinState) newPinIndex else null)
            // Also update the unified chat group for this chat
            val group = unifiedChatGroupRepository.getGroupForChat(chatGuid)
            if (group != null) {
                unifiedChatGroupRepository.updatePinStatus(group.id, newPinState, if (newPinState) newPinIndex else null)
            }
        }
    }

    /**
     * Check if a chat can be pinned.
     * Only chats with saved contacts can be pinned.
     * Chats that are already pinned can always be unpinned.
     */
    fun canPinChat(chatGuid: String, conversations: List<ConversationUiModel>): Boolean {
        val conversation = conversations.find { it.guid == chatGuid }
        return conversation?.hasContact == true
    }

    /**
     * Reorder pinned conversations by updating their pin indices.
     * @param reorderedGuids The new order of pinned conversation GUIDs
     */
    fun reorderPins(reorderedGuids: List<String>, conversations: List<ConversationUiModel>) {
        scope.launch {
            // Optimistically update UI immediately
            val updated = conversations.map { conv ->
                val newIndex = reorderedGuids.indexOf(conv.guid)
                if (newIndex >= 0) {
                    conv.copy(pinIndex = newIndex)
                } else {
                    conv
                }
            }.sortedWith(
                compareByDescending<ConversationUiModel> { it.isPinned }
                    .thenBy { it.pinIndex }
                    .thenByDescending { it.lastMessageTimestamp }
            )
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database - update both chats table and unified_chat_groups table
            reorderedGuids.forEachIndexed { index, guid ->
                chatRepository.setPinned(guid, true, index)
                // Also update the unified chat group for this chat
                val group = unifiedChatGroupRepository.getGroupForChat(guid)
                if (group != null) {
                    unifiedChatGroupRepository.updatePinStatus(group.id, true, index)
                }
            }
        }
    }

    /**
     * Set a custom photo for a group chat.
     * Saves the image to local storage and updates the chat entity.
     */
    fun setGroupPhoto(chatGuid: String, uri: Uri) {
        scope.launch {
            try {
                val avatarPath = saveGroupPhoto(chatGuid, uri)
                if (avatarPath != null) {
                    chatRepository.updateCustomAvatarPath(chatGuid, avatarPath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set group photo", e)
            }
        }
    }

    /**
     * Save group photo to local storage.
     * @return The path to the saved file, or null if failed
     */
    private fun saveGroupPhoto(chatGuid: String, uri: Uri): String? {
        return try {
            val avatarsDir = File(application.filesDir, "group_avatars")
            if (!avatarsDir.exists()) {
                avatarsDir.mkdirs()
            }

            // Sanitize chatGuid for filename
            val sanitizedGuid = chatGuid.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val photoFile = File(avatarsDir, "${sanitizedGuid}.jpg")

            // Copy and compress the image
            application.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                photoFile.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                }
            }

            photoFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save group photo", e)
            null
        }
    }

    /**
     * Toggle mute status for a chat.
     */
    fun toggleMute(chatGuid: String, conversations: List<ConversationUiModel>) {
        scope.launch {
            val conversation = conversations.find { it.guid == chatGuid }
            if (conversation == null) return@launch

            val newMuteState = !conversation.isMuted

            // Optimistically update UI immediately for instant feedback
            val updated = conversations.map { conv ->
                if (conv.guid == chatGuid) conv.copy(isMuted = newMuteState)
                else conv
            }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatRepository.setMuted(chatGuid, newMuteState)
        }
    }

    /**
     * Archive a chat.
     */
    fun archiveChat(chatGuid: String, conversations: List<ConversationUiModel>) {
        scope.launch {
            // Optimistically remove from list immediately for instant feedback
            val updated = conversations.filter { it.guid != chatGuid }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatRepository.setArchived(chatGuid, true)
        }
    }

    /**
     * Delete a chat.
     */
    fun deleteChat(chatGuid: String, conversations: List<ConversationUiModel>) {
        scope.launch {
            // Optimistically remove from list immediately for instant feedback
            val updated = conversations.filter { it.guid != chatGuid }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatRepository.deleteChat(chatGuid)
        }
    }

    /**
     * Mark a chat as read.
     */
    fun markAsRead(chatGuid: String, conversations: List<ConversationUiModel>) {
        scope.launch {
            // Optimistically update UI immediately for instant feedback
            val updated = conversations.map { conv ->
                if (conv.guid == chatGuid) conv.copy(unreadCount = 0)
                else conv
            }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatRepository.markChatAsRead(chatGuid)
        }
    }

    /**
     * Mark multiple chats as unread.
     */
    fun markChatsAsUnread(chatGuids: Set<String>, conversations: List<ConversationUiModel>) {
        scope.launch {
            // Optimistically update UI immediately for instant feedback
            val updated = conversations.map { conv ->
                if (conv.guid in chatGuids) conv.copy(unreadCount = 1)
                else conv
            }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatGuids.forEach { chatGuid ->
                chatRepository.markChatAsUnread(chatGuid)
            }
        }
    }

    /**
     * Mark multiple chats as read.
     */
    fun markChatsAsRead(chatGuids: Set<String>, conversations: List<ConversationUiModel>) {
        scope.launch {
            // Optimistically update UI immediately for instant feedback
            val updated = conversations.map { conv ->
                if (conv.guid in chatGuids) conv.copy(unreadCount = 0)
                else conv
            }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatGuids.forEach { chatGuid ->
                chatRepository.markChatAsRead(chatGuid)
            }
        }
    }

    /**
     * Block multiple chats.
     */
    fun blockChats(chatGuids: Set<String>, conversations: List<ConversationUiModel>) {
        scope.launch {
            // Optimistically remove from list immediately for instant feedback
            val updated = conversations.filter { it.guid !in chatGuids }
            _events.emit(ConversationEvent.ConversationsUpdated(updated))

            // Persist to database in background
            chatGuids.forEach { chatGuid ->
                // Get the phone number for this chat
                val address = chatRepository.getChatParticipantAddress(chatGuid)

                // Block using Android's native BlockedNumberContract
                if (address != null) {
                    androidContactsService.blockNumber(address)
                }

                // Also archive the chat locally
                chatRepository.setArchived(chatGuid, true)
            }
        }
    }

    /**
     * Check if a contact is starred (favorite) in Android Contacts.
     * Should be called on IO thread for best performance.
     */
    fun isContactStarred(address: String): Boolean {
        return androidContactsService.isContactStarred(address)
    }

    /**
     * Dismiss an inferred name for a contact (user indicated it was wrong).
     */
    fun dismissInferredName(address: String) {
        scope.launch {
            handleRepository.clearInferredNameByAddress(address)
        }
    }

    /**
     * Refresh contact info from device contacts.
     * Called when returning from the system contacts app after adding a contact.
     */
    fun refreshContactInfo(address: String) {
        scope.launch {
            val displayName = androidContactsService.getContactDisplayName(address)
            val photoUri = androidContactsService.getContactPhotoUri(address)
            if (displayName != null || photoUri != null) {
                chatRepository.updateHandleCachedContactInfo(address, displayName, photoUri)
            }
        }
    }

    /**
     * Create intent to add a contact.
     */
    fun getAddToContactsIntent(participantPhone: String?, inferredName: String?): Intent {
        val phone = participantPhone ?: ""
        return Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, phone)
            if (inferredName != null) {
                putExtra(ContactsContract.Intents.Insert.NAME, inferredName)
            }
        }
    }
}
