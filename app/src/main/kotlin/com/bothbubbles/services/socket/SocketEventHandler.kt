package com.bothbubbles.services.socket

import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.dto.MessageDto
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.categorization.CategorizationRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.ui.components.UrlParsingUtils
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.util.MessageDeduplicator
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    data class MessageSendFailed(val tempGuid: String, val errorMessage: String) : UiRefreshEvent()

    /** Incoming FaceTime call */
    data class IncomingFaceTime(val caller: String) : UiRefreshEvent()

    /** iCloud account status changed (logged in/out) */
    data class ICloudAccountStatusChanged(val alias: String?, val active: Boolean) : UiRefreshEvent()
}

/**
 * Handles Socket.IO events and updates the local database accordingly
 */
@Singleton
class SocketEventHandler @Inject constructor(
    private val socketService: SocketService,
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val notificationService: NotificationService,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val spamRepository: SpamRepository,
    private val categorizationRepository: CategorizationRepository,
    private val androidContactsService: AndroidContactsService,
    private val syncService: Lazy<SyncService>,
    private val settingsDataStore: SettingsDataStore,
    private val messageDeduplicator: MessageDeduplicator,
    private val activeConversationManager: ActiveConversationManager
) {
    companion object {
        private const val TAG = "SocketEventHandler"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        scope.launch {
            socketService.events.collect { event ->
                handleEvent(event)
            }
        }

        // Listen for connection state changes and trigger incremental sync on connect
        scope.launch {
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
                Log.d(TAG, "Socket connected but initial sync not complete - skipping incremental sync")
                return
            }

            Log.i(TAG, "Socket connected - triggering incremental sync to catch missed messages")
            syncService.get().performIncrementalSync()
                .onSuccess {
                    Log.i(TAG, "Incremental sync on reconnect completed successfully")
                }
                .onFailure { e ->
                    Log.e(TAG, "Incremental sync on reconnect failed", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling socket connected", e)
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
                is SocketEvent.NewMessage -> handleNewMessage(event)
                is SocketEvent.MessageUpdated -> handleMessageUpdated(event)
                is SocketEvent.MessageDeleted -> handleMessageDeleted(event)
                is SocketEvent.MessageSendError -> handleMessageSendError(event)
                is SocketEvent.TypingIndicator -> handleTypingIndicator(event)
                is SocketEvent.ChatRead -> handleChatRead(event)
                is SocketEvent.ParticipantAdded -> handleParticipantAdded(event)
                is SocketEvent.ParticipantRemoved -> handleParticipantRemoved(event)
                is SocketEvent.ParticipantLeft -> handleParticipantLeft(event)
                is SocketEvent.GroupNameChanged -> handleGroupNameChanged(event)
                is SocketEvent.GroupIconChanged -> handleGroupIconChanged(event)
                is SocketEvent.GroupIconRemoved -> handleGroupIconRemoved(event)
                is SocketEvent.ServerUpdate -> handleServerUpdate(event)
                is SocketEvent.IncomingFaceTime -> handleIncomingFaceTime(event)
                is SocketEvent.FaceTimeCall -> handleFaceTimeCall(event)
                is SocketEvent.ScheduledMessageCreated -> handleScheduledMessageCreated(event)
                is SocketEvent.ScheduledMessageSent -> handleScheduledMessageSent(event)
                is SocketEvent.ScheduledMessageError -> handleScheduledMessageError(event)
                is SocketEvent.ScheduledMessageDeleted -> handleScheduledMessageDeleted(event)
                is SocketEvent.ICloudAccountStatus -> handleICloudAccountStatus(event)
                is SocketEvent.Error -> handleError(event)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling event: $event", e)
        }
    }

    private suspend fun handleNewMessage(event: SocketEvent.NewMessage) {
        Log.d(TAG, "Handling new message: ${event.message.guid}")

        // Save message to database
        val savedMessage = messageRepository.handleIncomingMessage(event.message, event.chatGuid)

        // Emit UI refresh events for immediate updates
        _uiRefreshEvents.tryEmit(UiRefreshEvent.NewMessage(event.chatGuid, savedMessage.guid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("new_message"))

        // Show notification if not from me
        if (!savedMessage.isFromMe) {
            // Check for duplicate notification (message may arrive via both socket and FCM)
            if (!messageDeduplicator.shouldNotifyForMessage(savedMessage.guid)) {
                Log.i(TAG, "Message ${savedMessage.guid} already notified, skipping duplicate notification")
                return
            }

            // Check if user is currently viewing this conversation
            if (activeConversationManager.isConversationActive(event.chatGuid)) {
                Log.i(TAG, "Chat ${event.chatGuid} is currently active, skipping notification")
                return
            }

            val chat = chatDao.getChatByGuid(event.chatGuid)
            val senderAddress = event.message.handle?.address ?: ""
            val messageText = savedMessage.text ?: ""

            // Check if notifications are disabled for this chat
            if (chat?.notificationsEnabled == false) {
                Log.i(TAG, "Notifications disabled for chat ${event.chatGuid}, skipping notification")
                return
            }

            // Check if chat is snoozed - if snoozed, skip notification
            if (chat?.isSnoozed == true) {
                Log.i(TAG, "Chat ${event.chatGuid} is snoozed, skipping notification")
                return
            }

            // Check for spam - if spam, skip notification
            val spamResult = spamRepository.evaluateAndMarkSpam(event.chatGuid, senderAddress, messageText)
            if (spamResult.isSpam) {
                Log.i(TAG, "iMessage from $senderAddress detected as spam (score: ${spamResult.score}), skipping notification")
                return
            }

            // Categorize the message for filtering purposes
            categorizationRepository.evaluateAndCategorize(event.chatGuid, senderAddress, messageText)

            val senderName = resolveSenderName(event.message)

            // Check for invisible ink effect - hide actual content in notification
            val isInvisibleInk = MessageEffect.fromStyleId(savedMessage.expressiveSendStyleId) == MessageEffect.Bubble.InvisibleInk
            val notificationText = if (isInvisibleInk) {
                if (savedMessage.hasAttachments) {
                    "Image sent with Invisible Ink"
                } else {
                    "Message sent with Invisible Ink"
                }
            } else {
                messageText
            }

            // Fetch link preview data if message contains a URL (skip for invisible ink)
            val (linkTitle, linkDomain) = if (!isInvisibleInk && (messageText.contains("http://") || messageText.contains("https://"))) {
                val detectedUrl = UrlParsingUtils.getFirstUrl(messageText)
                if (detectedUrl != null) {
                    val preview = linkPreviewRepository.getLinkPreview(detectedUrl.url)
                    val title = preview?.title?.takeIf { it.isNotBlank() }
                    val domain = preview?.domain ?: detectedUrl.domain
                    title to domain
                } else {
                    null to null
                }
            } else {
                null to null
            }

            // For group chats, extract first name for cleaner notification display
            val displaySenderName = if (chat?.isGroup == true && senderName != null) {
                extractFirstName(senderName)
            } else {
                senderName
            }

            notificationService.showMessageNotification(
                chatGuid = event.chatGuid,
                chatTitle = chat?.displayName ?: chat?.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: "",
                messageText = notificationText,
                messageGuid = savedMessage.guid,
                senderName = displaySenderName,
                senderAddress = senderAddress,
                isGroup = chat?.isGroup ?: false,
                linkPreviewTitle = linkTitle,
                linkPreviewDomain = linkDomain
            )
        }
    }

    /**
     * Extract the first name from a full name, excluding emojis and non-letter characters.
     */
    private fun extractFirstName(fullName: String): String {
        val words = fullName.trim().split(Regex("\\s+"))
        for (word in words) {
            val cleaned = word.filter { it.isLetterOrDigit() }
            if (cleaned.isNotEmpty() && cleaned.any { it.isLetter() }) {
                return cleaned
            }
        }
        return words.firstOrNull()?.filter { it.isLetterOrDigit() } ?: fullName
    }

    /**
     * Resolve the sender's display name from the message.
     * Priority: device contact name > local cached contact name > inferred name > formatted address > raw address
     */
    private suspend fun resolveSenderName(message: MessageDto): String? {
        // Try to get from embedded handle
        message.handle?.let { handleDto ->
            val address = handleDto.address

            // Look up local handle entity for cached contact name
            val localHandle = handleDao.getHandlesByAddress(address).firstOrNull()
            if (localHandle?.cachedDisplayName != null) {
                return localHandle.cachedDisplayName
            }

            // Cached name not available - do a live contact lookup
            val contactName = androidContactsService.getContactDisplayName(address)
            if (contactName != null) {
                // Cache the contact name for future lookups
                localHandle?.let { handle ->
                    val photoUri = androidContactsService.getContactPhotoUri(address)
                    handleDao.updateCachedContactInfo(handle.id, contactName, photoUri)
                }
                return contactName
            }

            // Fall back to inferred name (with "Maybe:" prefix via displayName property)
            if (localHandle?.inferredName != null) {
                return localHandle.displayName
            }

            // Fall back to server-provided formatted address or raw address
            return handleDto.formattedAddress ?: address
        }

        // No embedded handle - try by handleId if available
        message.handleId?.let { handleId ->
            val handle = handleDao.getHandleById(handleId)
            if (handle != null) {
                // Check for cached contact name first
                if (handle.cachedDisplayName != null) {
                    return handle.cachedDisplayName
                }

                // Do a live contact lookup
                val contactName = androidContactsService.getContactDisplayName(handle.address)
                if (contactName != null) {
                    val photoUri = androidContactsService.getContactPhotoUri(handle.address)
                    handleDao.updateCachedContactInfo(handle.id, contactName, photoUri)
                    return contactName
                }

                return handle.displayName
            }
        }

        return null
    }

    private suspend fun handleMessageUpdated(event: SocketEvent.MessageUpdated) {
        Log.d(TAG, "Handling message update: ${event.message.guid}")
        messageRepository.handleMessageUpdate(event.message, event.chatGuid)

        // Emit UI refresh events for immediate updates (read receipts, delivery status, edits)
        _uiRefreshEvents.tryEmit(UiRefreshEvent.MessageUpdated(event.chatGuid, event.message.guid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("message_updated"))
    }

    private suspend fun handleMessageDeleted(event: SocketEvent.MessageDeleted) {
        Log.d(TAG, "Handling message deletion: ${event.messageGuid}")
        messageRepository.deleteMessageLocally(event.messageGuid)

        // Emit UI refresh events
        _uiRefreshEvents.tryEmit(UiRefreshEvent.MessageDeleted(event.chatGuid, event.messageGuid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("message_deleted"))
    }

    private suspend fun handleMessageSendError(event: SocketEvent.MessageSendError) {
        Log.e(TAG, "Message send error: ${event.tempGuid} - ${event.errorMessage}")

        // Update the message in database to mark as failed
        messageRepository.markMessageAsFailed(event.tempGuid, event.errorMessage)

        // Emit UI refresh event so ChatViewModel can update the message state
        _uiRefreshEvents.tryEmit(UiRefreshEvent.MessageSendFailed(event.tempGuid, event.errorMessage))
    }

    private suspend fun handleTypingIndicator(event: SocketEvent.TypingIndicator) {
        // This is typically handled at the UI layer via a shared flow
        // The UI can observe socketService.events directly for typing indicators
        Log.d(TAG, "Typing indicator: ${event.chatGuid} = ${event.isTyping}")
    }

    private suspend fun handleChatRead(event: SocketEvent.ChatRead) {
        Log.d(TAG, "Chat read: ${event.chatGuid}")
        chatDao.updateUnreadCount(event.chatGuid, 0)

        // Emit UI refresh event for immediate unread badge update
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ChatRead(event.chatGuid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("chat_read"))
    }

    private suspend fun handleParticipantAdded(event: SocketEvent.ParticipantAdded) {
        Log.d(TAG, "Participant added: ${event.handleAddress} to ${event.chatGuid}")
        // Re-fetch chat to get updated participants
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        _uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("participant_added"))
    }

    private suspend fun handleParticipantRemoved(event: SocketEvent.ParticipantRemoved) {
        Log.d(TAG, "Participant removed: ${event.handleAddress} from ${event.chatGuid}")
        // Re-fetch chat to get updated participants
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        _uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("participant_removed"))
    }

    private suspend fun handleParticipantLeft(event: SocketEvent.ParticipantLeft) {
        Log.d(TAG, "Participant left: ${event.handleAddress} from ${event.chatGuid}")
        // Re-fetch chat to get updated participants
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        _uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("participant_left"))
    }

    private suspend fun handleGroupNameChanged(event: SocketEvent.GroupNameChanged) {
        Log.d(TAG, "Group name changed: ${event.chatGuid} = ${event.newName}")
        chatDao.updateDisplayName(event.chatGuid, event.newName)

        // Emit UI refresh event
        _uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("group_name_changed"))
    }

    private suspend fun handleGroupIconChanged(event: SocketEvent.GroupIconChanged) {
        Log.d(TAG, "Group icon changed: ${event.chatGuid}")
        // Re-fetch chat to get updated icon
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        _uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("group_icon_changed"))
    }

    private suspend fun handleGroupIconRemoved(event: SocketEvent.GroupIconRemoved) {
        Log.d(TAG, "Group icon removed: ${event.chatGuid}")
        // Clear the group icon by re-fetching chat (server will return null icon)
        chatRepository.fetchChat(event.chatGuid)

        // Emit UI refresh event
        _uiRefreshEvents.tryEmit(UiRefreshEvent.GroupChatUpdated(event.chatGuid))
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("group_icon_removed"))
    }

    private fun handleServerUpdate(event: SocketEvent.ServerUpdate) {
        Log.i(TAG, "Server update available: ${event.version}")
        // Show notification to inform user about the update
        notificationService.showServerUpdateNotification(event.version)
    }

    private fun handleIncomingFaceTime(event: SocketEvent.IncomingFaceTime) {
        Log.d(TAG, "Incoming FaceTime from: ${event.caller}")

        // Show notification for incoming FaceTime call
        val callerDisplay = PhoneNumberFormatter.format(event.caller)
        notificationService.showFaceTimeCallNotification(
            callUuid = "facetime-${event.timestamp}",
            callerName = callerDisplay,
            callerAddress = event.caller
        )

        // Emit UI refresh event
        _uiRefreshEvents.tryEmit(UiRefreshEvent.IncomingFaceTime(event.caller))
    }

    private fun handleError(event: SocketEvent.Error) {
        Log.e(TAG, "Socket error: ${event.message}")
    }

    private fun handleFaceTimeCall(event: SocketEvent.FaceTimeCall) {
        Log.d(TAG, "FaceTime call: ${event.callUuid}, status: ${event.status}")

        when (event.status) {
            FaceTimeCallStatus.INCOMING -> {
                // Show incoming FaceTime call notification
                val callerDisplay = event.callerName ?: event.callerAddress?.let { PhoneNumberFormatter.format(it) } ?: ""
                notificationService.showFaceTimeCallNotification(
                    callUuid = event.callUuid,
                    callerName = callerDisplay,
                    callerAddress = event.callerAddress
                )
            }
            FaceTimeCallStatus.DISCONNECTED -> {
                // Dismiss the notification when call ends
                notificationService.dismissFaceTimeCallNotification(event.callUuid)
            }
            FaceTimeCallStatus.CONNECTED, FaceTimeCallStatus.RINGING -> {
                // Update notification state if needed
                Log.d(TAG, "FaceTime call state: ${event.status}")
            }
            FaceTimeCallStatus.UNKNOWN -> {
                Log.w(TAG, "Unknown FaceTime call status")
            }
        }
    }

    // ===== Server-side Scheduled Message Handlers =====

    private fun handleScheduledMessageCreated(event: SocketEvent.ScheduledMessageCreated) {
        Log.d(TAG, "Scheduled message created: ${event.messageId} for chat ${event.chatGuid}")
        // Server-side scheduled messages are managed by the server
        // We log them for visibility but don't need to sync them locally
        // since we use client-side scheduling via WorkManager
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("scheduled_message_created"))
    }

    private fun handleScheduledMessageSent(event: SocketEvent.ScheduledMessageSent) {
        Log.d(TAG, "Scheduled message sent: ${event.messageId} -> ${event.sentMessageGuid}")
        // The actual message will arrive via new-message event
        // This event is just for tracking that a scheduled message was sent
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("scheduled_message_sent"))
    }

    private fun handleScheduledMessageError(event: SocketEvent.ScheduledMessageError) {
        Log.e(TAG, "Scheduled message error: ${event.messageId} - ${event.errorMessage}")
        // Could show a notification or UI indicator that a scheduled message failed
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("scheduled_message_error"))
    }

    private fun handleScheduledMessageDeleted(event: SocketEvent.ScheduledMessageDeleted) {
        Log.d(TAG, "Scheduled message deleted: ${event.messageId}")
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("scheduled_message_deleted"))
    }

    private fun handleICloudAccountStatus(event: SocketEvent.ICloudAccountStatus) {
        Log.i(TAG, "iCloud account status changed: alias=${event.alias}, active=${event.active}")

        if (!event.active) {
            // iCloud account logged out - show a notification to inform the user
            notificationService.showICloudAccountNotification(active = false, alias = event.alias)
        }

        // Emit UI refresh event so settings/status screens can update
        _uiRefreshEvents.tryEmit(UiRefreshEvent.ICloudAccountStatusChanged(event.alias, event.active))
    }
}
