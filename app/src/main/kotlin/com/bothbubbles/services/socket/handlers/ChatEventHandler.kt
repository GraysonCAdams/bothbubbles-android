package com.bothbubbles.services.socket.handlers

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.repository.ChatRepository
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
    private val chatRepository: ChatRepository,
    private val chatDao: ChatDao
) {
    fun handleTypingIndicator(event: SocketEvent.TypingIndicator) {
        // Typing indicators are typically handled at the UI layer via a shared flow
        // The UI can observe socketService.events directly for typing indicators
        Timber.d("Typing indicator: ${event.chatGuid} = ${event.isTyping}")
    }

    suspend fun handleChatRead(
        event: SocketEvent.ChatRead,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Chat read: ${event.chatGuid}")
        chatDao.updateUnreadCount(event.chatGuid, 0)

        // Emit UI refresh event for immediate unread badge update
        uiRefreshEvents.tryEmit(UiRefreshEvent.ChatRead(event.chatGuid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("chat_read"))
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
