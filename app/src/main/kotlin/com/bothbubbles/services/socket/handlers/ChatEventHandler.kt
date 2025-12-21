package com.bothbubbles.services.socket.handlers

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.contacts.sync.GroupContactSyncManager
import com.bothbubbles.services.notifications.Notifier
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.UiRefreshEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles chat-related socket events:
 * - Chat read status changes
 * - Typing indicators
 * - Participant changes (added, removed, left)
 * - Group chat updates (name, icon)
 */
@Singleton
class ChatEventHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val chatDao: ChatDao,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val notifier: Notifier,
    private val activeConversationManager: ActiveConversationManager
) {
    fun handleTypingIndicator(event: SocketEvent.TypingIndicator) {
        // Typing indicators are typically handled at the UI layer via a shared flow
        // The UI can observe socketService.events directly for typing indicators
        Timber.d("Typing indicator: ${event.chatGuid} = ${event.isTyping}")
    }

    suspend fun handleChatReadStatusChanged(
        event: SocketEvent.ChatReadStatusChanged,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Chat read status changed: ${event.chatGuid}, isRead: ${event.isRead}")

        if (event.isRead) {
            // Chat was marked as read (e.g., from another device)
            chatDao.updateUnreadCount(event.chatGuid, 0)
            chatDao.updateUnreadStatus(event.chatGuid, false)

            // Also update the unified group's unread count for badge sync
            unifiedChatGroupDao.getGroupForChat(event.chatGuid)?.let { group ->
                unifiedChatGroupDao.updateUnreadCount(group.id, 0)
            }

            // Cancel notification for this chat since it was read (possibly on another device)
            // BUT skip if the conversation is currently active (e.g. user is in the bubble)
            // Cancelling the notification would force-close the bubble while the user is using it
            if (!activeConversationManager.isConversationActive(event.chatGuid)) {
                notifier.cancelNotification(event.chatGuid)
            }
        } else {
            // Chat was marked as unread (e.g., user marked as unread from another device)
            chatDao.updateUnreadCount(event.chatGuid, 1)
            chatDao.updateUnreadStatus(event.chatGuid, true)

            // Also update the unified group's unread count for badge sync
            unifiedChatGroupDao.getGroupForChat(event.chatGuid)?.let { group ->
                unifiedChatGroupDao.updateUnreadCount(group.id, 1)
            }
        }

        // Emit UI refresh event for immediate unread badge update
        uiRefreshEvents.tryEmit(UiRefreshEvent.ChatReadStatusChanged(event.chatGuid, event.isRead))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("chat_read_status_changed"))
    }

    suspend fun handleParticipantAdded(
        event: SocketEvent.ParticipantAdded,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Participant added: ${event.handleAddress} to ${event.chatGuid}")
        // Re-fetch chat to get updated participants
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("participant_added"))

        // Sync group contacts (participants changed = avatar may change)
        GroupContactSyncManager.triggerSync(context)
    }

    suspend fun handleParticipantRemoved(
        event: SocketEvent.ParticipantRemoved,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Participant removed: ${event.handleAddress} from ${event.chatGuid}")
        // Re-fetch chat to get updated participants
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("participant_removed"))

        // Sync group contacts (participants changed = avatar may change)
        GroupContactSyncManager.triggerSync(context)
    }

    suspend fun handleParticipantLeft(
        event: SocketEvent.ParticipantLeft,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Participant left: ${event.handleAddress} from ${event.chatGuid}")
        // Re-fetch chat to get updated participants
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("participant_left"))

        // Sync group contacts (participants changed = avatar may change)
        GroupContactSyncManager.triggerSync(context)
    }

    suspend fun handleGroupNameChanged(
        event: SocketEvent.GroupNameChanged,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Group name changed: ${event.chatGuid} = ${event.newName}")
        chatDao.updateDisplayName(event.chatGuid, event.newName)

        // Emit UI refresh event
        uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("group_name_changed"))

        // Sync group contacts (name changed)
        GroupContactSyncManager.triggerSync(context)
    }

    suspend fun handleGroupIconChanged(
        event: SocketEvent.GroupIconChanged,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Group icon changed: ${event.chatGuid}")
        // Re-fetch chat to get updated icon
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("group_icon_changed"))
    }

    suspend fun handleGroupIconRemoved(
        event: SocketEvent.GroupIconRemoved,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Group icon removed: ${event.chatGuid}")
        // Clear the group icon by re-fetching chat
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("group_icon_removed"))
    }
}
