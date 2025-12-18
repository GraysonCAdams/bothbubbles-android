package com.bothbubbles.services.socket
import com.bothbubbles.core.data.ConnectionState

import timber.log.Timber
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.socket.handlers.ChatEventHandler
import com.bothbubbles.services.socket.handlers.MessageEventHandler
import com.bothbubbles.services.socket.handlers.SystemEventHandler
import com.bothbubbles.services.sync.SyncService
import dagger.Lazy
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Events emitted when UI should refresh due to real-time updates.
 * ViewModels can observe these for immediate UI updates, supplementing Room Flow invalidation.
 */
sealed class UiRefreshEvent {
    /** A new message was received - refresh chat and conversation list */
    data class NewMessage(val chatGuid: String, val messageGuid: String) : UiRefreshEvent()

    /** A message was updated (read receipt, delivery, edit, reaction) */
    data class MessageUpdated(val chatGuid: String, val messageGuid: String) : UiRefreshEvent()

    /** A message was deleted/unsent */
    data class MessageDeleted(val chatGuid: String, val messageGuid: String) : UiRefreshEvent()

    /** Chat read status changed (e.g., read from another device) */
    data class ChatRead(val chatGuid: String) : UiRefreshEvent()

    /** Conversation list should refresh (new chat, chat updated, etc.) */
    data class ConversationListChanged(val reason: String) : UiRefreshEvent()

    /** Group chat was updated (participants, name, icon) */
    data class GroupChatUpdated(val chatGuid: String) : UiRefreshEvent()

    /** A message send failed - update UI to show error state */
    data class MessageSendFailed(
        val tempGuid: String,
        val errorMessage: String,
        val errorCode: Int = 1
    ) : UiRefreshEvent()

    /** Incoming FaceTime call */
    data class IncomingFaceTime(val caller: String) : UiRefreshEvent()

    /** iCloud account status changed (logged in/out) */
    data class ICloudAccountStatusChanged(val alias: String?, val active: Boolean) : UiRefreshEvent()
}

/**
 * Handles Socket.IO events by delegating to specialized handlers.
 *
 * This class coordinates event routing to:
 * - [MessageEventHandler] for message-related events
 * - [ChatEventHandler] for chat and group-related events
 * - [SystemEventHandler] for system-level events (FaceTime, server updates, etc.)
 */
@Singleton
class SocketEventHandler @Inject constructor(
    private val socketService: SocketService,
    private val messageEventHandler: MessageEventHandler,
    private val chatEventHandler: ChatEventHandler,
    private val systemEventHandler: SystemEventHandler,
    private val syncService: Lazy<SyncService>,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private var isListening = false

    /**
     * SharedFlow that emits UI refresh events when real-time updates occur.
     * ViewModels can observe this for immediate UI updates, supplementing Room Flow observation.
     * Buffer of 50 events to handle bursts without dropping.
     */
    private val _uiRefreshEvents = MutableSharedFlow<UiRefreshEvent>(extraBufferCapacity = 50)
    val uiRefreshEvents: SharedFlow<UiRefreshEvent> = _uiRefreshEvents.asSharedFlow()

    /**
     * Start listening for Socket.IO events
     */
    fun startListening() {
        if (isListening) return
        isListening = true

        // Listen for socket events (messages, typing, etc.)
        applicationScope.launch(ioDispatcher) {
            socketService.events.collect { event ->
                handleEvent(event)
            }
        }

        // Listen for connection state changes and trigger incremental sync on connect
        applicationScope.launch(ioDispatcher) {
            socketService.connectionState
                .collect { state ->
                    if (state == ConnectionState.CONNECTED) {
                        handleSocketConnected()
                    }
                }
        }
    }

    /**
     * Handle socket connected event - trigger incremental sync to catch missed messages.
     * Only syncs if initial sync is complete (not during setup).
     */
    private suspend fun handleSocketConnected() {
        try {
            // Only run incremental sync if initial sync is complete
            val initialSyncComplete = settingsDataStore.initialSyncComplete.first()
            if (!initialSyncComplete) {
                Timber.d("Socket connected but initial sync not complete - skipping incremental sync")
                return
            }

            Timber.i("Socket connected - triggering incremental sync to catch missed messages")
            syncService.get().performIncrementalSync()
                .onSuccess {
                    Timber.i("Incremental sync on reconnect completed successfully")
                }
                .onFailure { e ->
                    Timber.e(e, "Incremental sync on reconnect failed")
                }
        } catch (e: Exception) {
            Timber.e(e, "Error handling socket connected")
        }
    }

    /**
     * Stop listening for events
     */
    fun stopListening() {
        isListening = false
    }

    private suspend fun handleEvent(event: SocketEvent) {
        try {
            when (event) {
                // Message events -> MessageEventHandler
                is SocketEvent.NewMessage -> messageEventHandler.handleNewMessage(event, _uiRefreshEvents, applicationScope)
                is SocketEvent.MessageUpdated -> messageEventHandler.handleMessageUpdated(event, _uiRefreshEvents)
                is SocketEvent.MessageDeleted -> messageEventHandler.handleMessageDeleted(event, _uiRefreshEvents)
                is SocketEvent.MessageSendError -> messageEventHandler.handleMessageSendError(event, _uiRefreshEvents)

                // Chat events -> ChatEventHandler
                is SocketEvent.TypingIndicator -> chatEventHandler.handleTypingIndicator(event)
                is SocketEvent.ChatRead -> chatEventHandler.handleChatRead(event, _uiRefreshEvents)
                is SocketEvent.ParticipantAdded -> chatEventHandler.handleParticipantAdded(event, _uiRefreshEvents)
                is SocketEvent.ParticipantRemoved -> chatEventHandler.handleParticipantRemoved(event, _uiRefreshEvents)
                is SocketEvent.ParticipantLeft -> chatEventHandler.handleParticipantLeft(event, _uiRefreshEvents)
                is SocketEvent.GroupNameChanged -> chatEventHandler.handleGroupNameChanged(event, _uiRefreshEvents)
                is SocketEvent.GroupIconChanged -> chatEventHandler.handleGroupIconChanged(event, _uiRefreshEvents)
                is SocketEvent.GroupIconRemoved -> chatEventHandler.handleGroupIconRemoved(event, _uiRefreshEvents)

                // System events -> SystemEventHandler
                is SocketEvent.ServerUpdate -> systemEventHandler.handleServerUpdate(event)
                is SocketEvent.IncomingFaceTime -> systemEventHandler.handleIncomingFaceTime(event, _uiRefreshEvents)
                is SocketEvent.FaceTimeCall -> systemEventHandler.handleFaceTimeCall(event)
                is SocketEvent.ScheduledMessageCreated -> systemEventHandler.handleScheduledMessageCreated(event, _uiRefreshEvents)
                is SocketEvent.ScheduledMessageSent -> systemEventHandler.handleScheduledMessageSent(event, _uiRefreshEvents)
                is SocketEvent.ScheduledMessageError -> systemEventHandler.handleScheduledMessageError(event, _uiRefreshEvents)
                is SocketEvent.ScheduledMessageDeleted -> systemEventHandler.handleScheduledMessageDeleted(event, _uiRefreshEvents)
                is SocketEvent.ICloudAccountStatus -> systemEventHandler.handleICloudAccountStatus(event, _uiRefreshEvents)
                is SocketEvent.Error -> systemEventHandler.handleError(event)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling event: $event")
        }
    }
}
