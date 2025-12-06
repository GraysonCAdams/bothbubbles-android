package com.bluebubbles.services.socket

import android.util.Log
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.remote.api.dto.MessageDto
import com.bluebubbles.data.repository.ChatRepository
import com.bluebubbles.data.repository.LinkPreviewRepository
import com.bluebubbles.data.repository.MessageRepository
import com.bluebubbles.services.notifications.NotificationService
import com.bluebubbles.services.spam.SpamRepository
import com.bluebubbles.ui.components.UrlParsingUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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
    private val spamRepository: SpamRepository
) {
    companion object {
        private const val TAG = "SocketEventHandler"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isListening = false

    /**
     * Start listening for Socket.IO events
     */
    fun startListening() {
        if (isListening) return
        isListening = true

        scope.launch {
            socketService.events.collect { event ->
                handleEvent(event)
            }
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
                is SocketEvent.TypingIndicator -> handleTypingIndicator(event)
                is SocketEvent.ChatRead -> handleChatRead(event)
                is SocketEvent.ParticipantAdded -> handleParticipantAdded(event)
                is SocketEvent.ParticipantRemoved -> handleParticipantRemoved(event)
                is SocketEvent.GroupNameChanged -> handleGroupNameChanged(event)
                is SocketEvent.GroupIconChanged -> handleGroupIconChanged(event)
                is SocketEvent.ServerUpdate -> handleServerUpdate(event)
                is SocketEvent.FaceTimeCall -> handleFaceTimeCall(event)
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

        // Show notification if not from me
        if (!savedMessage.isFromMe) {
            val chat = chatDao.getChatByGuid(event.chatGuid)
            val senderAddress = event.message.handle?.address ?: ""
            val messageText = savedMessage.text ?: ""

            // Check for spam - if spam, skip notification
            val spamResult = spamRepository.evaluateAndMarkSpam(event.chatGuid, senderAddress, messageText)
            if (spamResult.isSpam) {
                Log.i(TAG, "iMessage from $senderAddress detected as spam (score: ${spamResult.score}), skipping notification")
                return
            }

            val senderName = resolveSenderName(event.message)

            // Fetch link preview data if message contains a URL
            val (linkTitle, linkDomain) = if (messageText.contains("http://") || messageText.contains("https://")) {
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

            notificationService.showMessageNotification(
                chatGuid = event.chatGuid,
                chatTitle = chat?.displayName ?: chat?.chatIdentifier ?: "Unknown",
                messageText = messageText,
                messageGuid = savedMessage.guid,
                senderName = senderName,
                linkPreviewTitle = linkTitle,
                linkPreviewDomain = linkDomain
            )
        }
    }

    /**
     * Resolve the sender's display name from the message.
     * Priority: local cached contact name > inferred name > formatted address > raw address
     */
    private suspend fun resolveSenderName(message: MessageDto): String? {
        // Try to get from embedded handle
        message.handle?.let { handleDto ->
            // Look up local handle entity for cached contact name
            val localHandle = handleDao.getHandlesByAddress(handleDto.address).firstOrNull()
            if (localHandle != null) {
                return localHandle.displayName
            }
            // Fall back to server-provided formatted address or raw address
            return handleDto.formattedAddress ?: handleDto.address
        }

        // No embedded handle - try by handleId if available
        message.handleId?.let { handleId ->
            val handle = handleDao.getHandleById(handleId)
            return handle?.displayName
        }

        return null
    }

    private suspend fun handleMessageUpdated(event: SocketEvent.MessageUpdated) {
        Log.d(TAG, "Handling message update: ${event.message.guid}")
        messageRepository.handleMessageUpdate(event.message, event.chatGuid)
    }

    private suspend fun handleMessageDeleted(event: SocketEvent.MessageDeleted) {
        Log.d(TAG, "Handling message deletion: ${event.messageGuid}")
        messageRepository.deleteMessageLocally(event.messageGuid)
    }

    private suspend fun handleTypingIndicator(event: SocketEvent.TypingIndicator) {
        // This is typically handled at the UI layer via a shared flow
        // The UI can observe socketService.events directly for typing indicators
        Log.d(TAG, "Typing indicator: ${event.chatGuid} = ${event.isTyping}")
    }

    private suspend fun handleChatRead(event: SocketEvent.ChatRead) {
        Log.d(TAG, "Chat read: ${event.chatGuid}")
        chatDao.updateUnreadCount(event.chatGuid, 0)
    }

    private suspend fun handleParticipantAdded(event: SocketEvent.ParticipantAdded) {
        Log.d(TAG, "Participant added: ${event.handleAddress} to ${event.chatGuid}")
        // Re-fetch chat to get updated participants
        chatRepository.fetchChat(event.chatGuid)
    }

    private suspend fun handleParticipantRemoved(event: SocketEvent.ParticipantRemoved) {
        Log.d(TAG, "Participant removed: ${event.handleAddress} from ${event.chatGuid}")
        // Re-fetch chat to get updated participants
        chatRepository.fetchChat(event.chatGuid)
    }

    private suspend fun handleGroupNameChanged(event: SocketEvent.GroupNameChanged) {
        Log.d(TAG, "Group name changed: ${event.chatGuid} = ${event.newName}")
        chatDao.updateDisplayName(event.chatGuid, event.newName)
    }

    private suspend fun handleGroupIconChanged(event: SocketEvent.GroupIconChanged) {
        Log.d(TAG, "Group icon changed: ${event.chatGuid}")
        // Re-fetch chat to get updated icon
        chatRepository.fetchChat(event.chatGuid)
    }

    private fun handleServerUpdate(event: SocketEvent.ServerUpdate) {
        Log.i(TAG, "Server update available: ${event.version}")
        // Could show a notification or update UI to prompt server update
    }

    private fun handleError(event: SocketEvent.Error) {
        Log.e(TAG, "Socket error: ${event.message}")
    }

    private fun handleFaceTimeCall(event: SocketEvent.FaceTimeCall) {
        Log.d(TAG, "FaceTime call: ${event.callUuid}, status: ${event.status}")

        when (event.status) {
            FaceTimeCallStatus.INCOMING -> {
                // Show incoming FaceTime call notification
                val callerDisplay = event.callerName ?: event.callerAddress ?: "Unknown"
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
}
