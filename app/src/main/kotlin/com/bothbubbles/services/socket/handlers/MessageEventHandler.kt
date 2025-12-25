package com.bothbubbles.services.socket.handlers

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.messaging.IncomingMessageHandler
import com.bothbubbles.services.autoresponder.AutoResponderService
import com.bothbubbles.services.categorization.CategorizationRepository
import com.bothbubbles.services.media.AttachmentDownloadQueue
import com.bothbubbles.services.notifications.NotificationParamsBuilder
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.UiRefreshEvent
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.services.messaging.MessageDeduplicator
import com.bothbubbles.core.network.api.dto.AttachmentDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles message-related socket events:
 * - New message received
 * - Message updated (read receipt, delivery, edit, reaction)
 * - Message deleted/unsent
 * - Message send error
 */
@Singleton
class MessageEventHandler @Inject constructor(
    private val messageRepository: MessageRepository,
    private val incomingMessageHandler: IncomingMessageHandler,
    private val chatDao: ChatDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val notificationService: NotificationService,
    private val notificationParamsBuilder: NotificationParamsBuilder,
    private val spamRepository: SpamRepository,
    private val categorizationRepository: CategorizationRepository,
    private val messageDeduplicator: MessageDeduplicator,
    private val activeConversationManager: ActiveConversationManager,
    private val autoResponderService: AutoResponderService,
    private val attachmentDownloadQueue: AttachmentDownloadQueue
) {
    companion object {
        private const val TAG = "MessageEventHandler"
    }

    suspend fun handleNewMessage(
        event: SocketEvent.NewMessage,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>,
        scope: CoroutineScope
    ) {
        Timber.d("Handling new message: ${event.message.guid}")

        // Save message to database via IncomingMessageHandler (services layer)
        val savedMessage = incomingMessageHandler.handleIncomingMessage(event.message, event.chatGuid)

        // Emit UI refresh events for immediate updates
        uiRefreshEvents.tryEmit(UiRefreshEvent.NewMessage(event.chatGuid, savedMessage.guid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("new_message"))

        // Show notification if not from me
        if (!savedMessage.isFromMe) {
            // Check for duplicate notification (message may arrive via both socket and FCM)
            if (!messageDeduplicator.shouldNotifyForMessage(savedMessage.guid)) {
                Timber.i("Message ${savedMessage.guid} already notified, skipping duplicate notification")
                return
            }

            // Check if user is currently viewing this conversation
            if (activeConversationManager.isConversationActive(event.chatGuid)) {
                Timber.i("Chat ${event.chatGuid} is currently active, skipping notification")
                return
            }

            val chat = chatDao.getChatByGuid(event.chatGuid)
            val senderAddress = event.message.handle?.address ?: ""
            val messageText = savedMessage.text?.takeIf { it.isNotBlank() }
                ?: getAttachmentPreviewText(event.message.attachments)
                ?: ""

            // Get unified chat for notification settings
            val unifiedChat = chat?.unifiedChatId?.let { unifiedChatDao.getById(it) }

            // Check if notifications are disabled for this chat
            if (unifiedChat?.notificationsEnabled == false) {
                Timber.i("Notifications disabled for chat ${event.chatGuid}, skipping notification")
                return
            }

            // Check if chat is snoozed - if snoozed, skip notification
            if (unifiedChat?.isSnoozed == true) {
                Timber.i("Chat ${event.chatGuid} is snoozed, skipping notification")
                return
            }

            // Check for spam - if spam, skip notification
            val spamResult = spamRepository.evaluateAndMarkSpam(event.chatGuid, senderAddress, messageText)
            if (spamResult.isSpam) {
                Timber.i("iMessage from $senderAddress detected as spam (score: ${spamResult.score}), skipping notification")
                return
            }

            // Auto-responder check - send greeting to first-time iMessage contacts
            // Runs asynchronously to not delay notification
            if (senderAddress.isNotBlank()) {
                scope.launch {
                    autoResponderService.maybeAutoRespond(
                        chatGuid = event.chatGuid,
                        senderAddress = senderAddress,
                        isFromMe = false
                    )
                }
            }

            // Categorize the message for filtering purposes
            categorizationRepository.evaluateAndCategorize(event.chatGuid, senderAddress, messageText)

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

            // Build notification params using shared builder (ensures consistent contact caching)
            val params = notificationParamsBuilder.buildParams(
                messageGuid = savedMessage.guid,
                chatGuid = event.chatGuid,
                messageText = notificationText,
                subject = savedMessage.subject,
                senderAddress = senderAddress,
                fetchLinkPreview = true,
                isInvisibleInk = isInvisibleInk
            )

            if (params != null) {
                notificationService.showMessageNotification(params)
            }

            // Enqueue first image/video attachment for download so notification can update with inline preview
            val firstMediaAttachment = event.message.attachments?.firstOrNull { attachment ->
                val mimeType = attachment.mimeType?.lowercase() ?: ""
                (mimeType.startsWith("image/") || mimeType.startsWith("video/")) && !attachment.isSticker
            }
            if (firstMediaAttachment != null) {
                Timber.d("Enqueuing notification attachment download: ${firstMediaAttachment.guid}")
                attachmentDownloadQueue.enqueue(
                    attachmentGuid = firstMediaAttachment.guid,
                    chatGuid = event.chatGuid,
                    priority = AttachmentDownloadQueue.Priority.IMMEDIATE
                )
            }
        }
    }

    suspend fun handleMessageUpdated(
        event: SocketEvent.MessageUpdated,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Handling message update: ${event.message.guid}")
        incomingMessageHandler.handleMessageUpdate(event.message, event.chatGuid)

        // Emit UI refresh events for immediate updates (read receipts, delivery status, edits)
        uiRefreshEvents.tryEmit(UiRefreshEvent.MessageUpdated(event.chatGuid, event.message.guid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("message_updated"))

        // Update notification if this is an edit to a message from another user
        updateNotificationIfEdited(event)
    }

    /**
     * Updates the notification for an edited message.
     * Only updates if:
     * - The message has a dateEdited (indicating it was edited)
     * - The message is not from me (I don't need notifications for my own edits)
     * - The chat is not currently active (user isn't viewing it)
     */
    private suspend fun updateNotificationIfEdited(event: SocketEvent.MessageUpdated) {
        val messageDto = event.message

        // Skip if not an edit
        if (messageDto.dateEdited == null) return

        // Skip if from me
        if (messageDto.isFromMe) return

        // Skip if conversation is active
        if (activeConversationManager.isConversationActive(event.chatGuid)) return

        Timber.d("Updating notification for edited message: ${messageDto.guid}")

        val chat = chatDao.getChatByGuid(event.chatGuid)
        val senderAddress = messageDto.handle?.address

        // Get unified chat for notification settings
        val unifiedChat = chat?.unifiedChatId?.let { unifiedChatDao.getById(it) }

        // Check notification settings
        if (unifiedChat?.notificationsEnabled == false) return
        if (unifiedChat?.isSnoozed == true) return

        val messageText = messageDto.text ?: return // No text to show

        // Build notification params using shared builder (ensures consistent contact caching)
        val params = notificationParamsBuilder.buildParams(
            messageGuid = messageDto.guid,
            chatGuid = event.chatGuid,
            messageText = messageText,
            subject = messageDto.subject,
            senderAddress = senderAddress,
            fetchLinkPreview = false // Edit notifications don't need link preview
        )

        if (params != null) {
            notificationService.showMessageNotification(params)
        }
    }

    suspend fun handleMessageDeleted(
        event: SocketEvent.MessageDeleted,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.d("Handling message deletion: ${event.messageGuid}")
        messageRepository.deleteMessageLocally(event.messageGuid)

        // Emit UI refresh events
        uiRefreshEvents.tryEmit(UiRefreshEvent.MessageDeleted(event.chatGuid, event.messageGuid))
        uiRefreshEvents.tryEmit(UiRefreshEvent.ConversationListChanged("message_deleted"))
    }

    suspend fun handleMessageSendError(
        event: SocketEvent.MessageSendError,
        uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>
    ) {
        Timber.e("Message send error: ${event.tempGuid} - ${event.errorMessage} (code: ${event.errorCode})")

        // Update the message in database to mark as failed with the specific error code
        messageRepository.markMessageAsFailed(event.tempGuid, event.errorMessage, event.errorCode)

        // Emit UI refresh event so ChatViewModel can update the message state
        uiRefreshEvents.tryEmit(UiRefreshEvent.MessageSendFailed(event.tempGuid, event.errorMessage, event.errorCode))
    }

    // ===== Private Helper Methods =====

    /**
     * Generate descriptive preview text for attachments in notifications.
     */
    private fun getAttachmentPreviewText(attachments: List<AttachmentDto>?): String? {
        val first = attachments?.firstOrNull() ?: return null
        val mimeType = first.mimeType?.lowercase() ?: ""
        val uti = first.uti?.lowercase() ?: ""
        val name = first.transferName?.lowercase() ?: ""
        val count = attachments.size

        return when {
            first.isSticker -> "Sticker"
            uti == "public.vlocation" || mimeType == "text/x-vlocation" || name.endsWith(".loc.vcf") -> "Location"
            mimeType == "text/vcard" || mimeType == "text/x-vcard" || (name.endsWith(".vcf") && !name.endsWith(".loc.vcf")) -> "Contact"
            first.hasLivePhoto && mimeType.startsWith("image/") -> "Live Photo"
            mimeType == "image/gif" -> "GIF"
            mimeType.startsWith("image/") -> if (count > 1) "$count Photos" else "Photo"
            mimeType.startsWith("video/") -> if (count > 1) "$count Videos" else "Video"
            mimeType.startsWith("audio/") -> {
                val isVoice = uti.contains("voice") || name.startsWith("audio message") || name.endsWith(".caf")
                if (isVoice) "Voice message" else "Audio"
            }
            mimeType.contains("pdf") -> "PDF"
            mimeType.contains("document") || mimeType.contains("word") -> "Document"
            mimeType.contains("spreadsheet") || mimeType.contains("excel") -> "Spreadsheet"
            mimeType.contains("presentation") || mimeType.contains("powerpoint") -> "Presentation"
            else -> "File"
        }
    }
}
