package com.bothbubbles.services.messaging

import android.content.Context
import android.net.Uri
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.dto.EditMessageRequest
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.core.network.api.dto.SendReactionRequest
import com.bothbubbles.core.network.api.dto.UnsendMessageRequest
import com.bothbubbles.services.messaging.sender.MessageSenderStrategy
import com.bothbubbles.services.messaging.sender.SendOptions
import com.bothbubbles.services.sms.SmsSendService
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.util.error.MessageError
import com.bothbubbles.util.error.NetworkError
import com.bothbubbles.util.error.SmsError
import com.bothbubbles.util.error.safeCall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Message delivery mode - determines how messages are routed
 */
enum class MessageDeliveryMode {
    IMESSAGE,      // Via BlueBubbles server (iMessage/SMS via Mac)
    LOCAL_SMS,     // Direct SMS from Android device
    LOCAL_MMS,     // Direct MMS from Android device
    AUTO           // Auto-select based on chat type
}

/**
 * Event emitted when a message's temp GUID is replaced with the server GUID.
 * Used to notify the UI to update its cached message state.
 */
data class GuidReplacementEvent(
    val chatGuid: String,
    val tempGuid: String,
    val serverGuid: String
)

/**
 * Represents the progress of an attachment upload
 */
data class UploadProgress(
    val fileName: String,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val attachmentIndex: Int,
    val totalAttachments: Int
) {
    val progress: Float
        get() = if (totalBytes > 0) bytesUploaded.toFloat() / totalBytes else 0f

    val isComplete: Boolean
        get() = bytesUploaded >= totalBytes
}

/**
 * Service responsible for sending messages via various channels.
 *
 * This service encapsulates all message sending logic including:
 * - iMessage via BlueBubbles server
 * - Local SMS/MMS via Android's telephony APIs
 * - Attachment uploading with compression
 * - Delivery mode determination and fallback handling
 *
 * This is part of the services layer and coordinates between:
 * - Data layer (DAOs for persistence)
 * - Remote API (BlueBubbles server)
 * - SMS/MMS services (carrier messaging)
 */
@Singleton
class MessageSendingService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val attachmentDao: AttachmentDao,
    private val api: BothBubblesApi,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val smsSendService: SmsSendService,
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore,
    private val chatFallbackTracker: ChatFallbackTracker,
    private val strategies: Set<@JvmSuppressWildcards MessageSenderStrategy>
) : MessageSender {
    // ===== Upload Progress Tracking =====

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    override val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    // ===== GUID Replacement Events =====

    private val _guidReplacementEvents = MutableSharedFlow<GuidReplacementEvent>(extraBufferCapacity = 10)
    override val guidReplacementEvents: SharedFlow<GuidReplacementEvent> = _guidReplacementEvents.asSharedFlow()

    /**
     * Emit a GUID replacement event for UI updates.
     * Called by sender strategies after successful GUID replacement.
     */
    fun emitGuidReplacement(chatGuid: String, tempGuid: String, serverGuid: String) {
        Timber.i("[SEND_TRACE] Emitting GuidReplacementEvent: $tempGuid -> $serverGuid")
        _guidReplacementEvents.tryEmit(GuidReplacementEvent(chatGuid, tempGuid, serverGuid))
    }

    /**
     * Reset upload progress state
     */
    override fun resetUploadProgress() {
        _uploadProgress.value = null
    }

    // ===== Unified Send Operations =====

    /**
     * Send a message via the appropriate channel.
     * Uses the Strategy Pattern to delegate to the appropriate sender strategy.
     */
    override suspend fun sendUnified(
        chatGuid: String,
        text: String,
        replyToGuid: String?,
        effectId: String?,
        subject: String?,
        attachments: List<PendingAttachmentInput>,
        deliveryMode: MessageDeliveryMode,
        subscriptionId: Int,
        tempGuid: String?, // Stable ID for retry idempotency
        attributedBodyJson: String?
    ): Result<MessageEntity> {
        val sendStart = System.currentTimeMillis()
        Timber.i("[SEND_TRACE] ── MessageSendingService.sendUnified START ──")
        Timber.i("[SEND_TRACE] chatGuid=$chatGuid, text=\"${text.take(30)}...\", tempGuid=$tempGuid")
        Timber.i("[SEND_TRACE] deliveryMode=$deliveryMode, attachments=${attachments.size}")

        // Determine actual delivery mode
        val actualMode = when (deliveryMode) {
            MessageDeliveryMode.AUTO -> determineDeliveryMode(chatGuid, attachments.isNotEmpty())
            else -> deliveryMode
        }
        Timber.i("[SEND_TRACE] actualMode=$actualMode +${System.currentTimeMillis() - sendStart}ms")

        // Find strategy that can handle this delivery mode
        val strategy = strategies.firstOrNull { it.canHandle(actualMode) }
            ?: return Result.failure(
                IllegalStateException("No strategy found for delivery mode: $actualMode")
            )

        Timber.i("[SEND_TRACE] Using ${strategy::class.simpleName} for $actualMode +${System.currentTimeMillis() - sendStart}ms")

        val options = SendOptions(
            chatGuid = chatGuid,
            text = text,
            replyToGuid = replyToGuid,
            effectId = effectId,
            subject = subject,
            attachments = attachments,
            subscriptionId = subscriptionId,
            tempGuid = tempGuid,
            attributedBodyJson = attributedBodyJson
        )

        Timber.i("[SEND_TRACE] Calling strategy.send() +${System.currentTimeMillis() - sendStart}ms")
        val result = strategy.send(options).toResult()
        Timber.i("[SEND_TRACE] strategy.send() returned: success=${result.isSuccess} +${System.currentTimeMillis() - sendStart}ms")

        // Emit GUID replacement event on success so UI can update cached message state
        if (result.isSuccess && tempGuid != null) {
            val serverGuid = result.getOrNull()?.guid
            if (serverGuid != null && serverGuid != tempGuid) {
                emitGuidReplacement(chatGuid, tempGuid, serverGuid)
            }
        }

        Timber.i("[SEND_TRACE] ── MessageSendingService.sendUnified END: ${System.currentTimeMillis() - sendStart}ms ──")
        return result
    }

    /**
     * Send a new message via iMessage.
     * If the send fails, the message is marked as failed. Users can manually retry as SMS
     * using the "Retry as SMS" button.
     */
    override suspend fun sendMessage(
        chatGuid: String,
        text: String,
        replyToGuid: String?,
        effectId: String?,
        subject: String?,
        providedTempGuid: String?
    ): Result<MessageEntity> {
        return sendUnified(
            chatGuid = chatGuid,
            text = text,
            replyToGuid = replyToGuid,
            effectId = effectId,
            subject = subject,
            deliveryMode = MessageDeliveryMode.IMESSAGE,
            tempGuid = providedTempGuid
        )
    }

    /**
     * Send a reaction/tapback
     */
    override suspend fun sendReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String, // e.g., "love", "like", "dislike", "laugh", "emphasize", "question"
        selectedMessageText: String?,
        partIndex: Int
    ): Result<MessageEntity> = safeCall {
        Timber.d("sendReaction: chatGuid=$chatGuid, messageGuid=$messageGuid, reaction=$reaction, textLen=${selectedMessageText?.length ?: 0}")

        val response = api.sendReaction(
            SendReactionRequest(
                chatGuid = chatGuid,
                selectedMessageGuid = messageGuid,
                selectedMessageText = selectedMessageText ?: "",
                reaction = reaction,
                partIndex = partIndex
            )
        )

        Timber.d("sendReaction: response code=${response.code()}, isSuccessful=${response.isSuccessful}")

        val body = response.body()
        if (!response.isSuccessful || body == null || body.status != 200) {
            Timber.e("sendReaction: failed - code=${response.code()}, message=${body?.message}")
            throw NetworkError.ServerError(response.code(), body?.message ?: "Failed to send reaction")
        }

        val reactionMessage = body.data ?: throw NetworkError.ServerError(response.code(), "No reaction returned")
        Timber.d("sendReaction: success - reactionGuid=${reactionMessage.guid}")

        val entity = reactionMessage.toEntity(chatGuid)
        messageDao.insertMessage(entity)

        // Update parent message to show it has reactions
        messageDao.updateReactionStatus(messageGuid, true)

        entity
    }

    /**
     * Remove a reaction - sends the same reaction again to toggle it off
     */
    override suspend fun removeReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String,
        selectedMessageText: String?,
        partIndex: Int
    ): Result<Unit> = safeCall {
        Timber.d("removeReaction: chatGuid=$chatGuid, messageGuid=$messageGuid, reaction=$reaction, textLen=${selectedMessageText?.length ?: 0}")

        // In iMessage, sending the same reaction again removes it
        val removeReaction = "-$reaction" // Prefix with - to remove
        api.sendReaction(
            SendReactionRequest(
                chatGuid = chatGuid,
                selectedMessageGuid = messageGuid,
                selectedMessageText = selectedMessageText ?: "",
                reaction = removeReaction,
                partIndex = partIndex
            )
        )
        Unit
    }

    /**
     * Edit a message (iOS 16+)
     */
    override suspend fun editMessage(
        chatGuid: String,
        messageGuid: String,
        newText: String,
        partIndex: Int
    ): Result<MessageEntity> = safeCall {
        val response = api.editMessage(
            guid = messageGuid,
            request = EditMessageRequest(
                editedMessage = newText,
                partIndex = partIndex
            )
        )

        val body = response.body()
        if (!response.isSuccessful || body == null || body.status != 200) {
            throw NetworkError.ServerError(response.code(), body?.message ?: "Failed to edit message")
        }

        // Update local message
        messageDao.updateMessageText(messageGuid, newText, System.currentTimeMillis())

        messageDao.getMessageByGuid(messageGuid) ?: throw MessageError.SendFailed(messageGuid, "Message not found")
    }

    /**
     * Unsend a message (iOS 16+)
     */
    override suspend fun unsendMessage(
        chatGuid: String,
        messageGuid: String,
        partIndex: Int
    ): Result<Unit> = safeCall {
        api.unsendMessage(
            guid = messageGuid,
            request = UnsendMessageRequest(partIndex = partIndex)
        )

        // Soft delete locally
        messageDao.softDeleteMessage(messageGuid)
    }

    /**
     * Retry sending a failed message
     */
    override suspend fun retryMessage(messageGuid: String): Result<MessageEntity> = safeCall {
        val message = messageDao.getMessageByGuid(messageGuid)
            ?: throw MessageError.SendFailed(messageGuid, "Message not found")

        // Soft delete the original failed message so it doesn't appear alongside the retry
        messageDao.softDeleteMessage(messageGuid)

        // Determine delivery mode from original message source
        val deliveryMode = when (message.messageSource) {
            MessageSource.LOCAL_SMS.name -> MessageDeliveryMode.LOCAL_SMS
            MessageSource.LOCAL_MMS.name -> MessageDeliveryMode.LOCAL_MMS
            else -> MessageDeliveryMode.IMESSAGE
        }

        // Re-send
        sendUnified(
            chatGuid = message.chatGuid,
            text = message.text ?: "",
            replyToGuid = message.threadOriginatorGuid,
            effectId = message.expressiveSendStyleId,
            subject = message.subject,
            deliveryMode = deliveryMode
        ).getOrThrow()
    }

    /**
     * Retry sending a failed iMessage as SMS/MMS.
     * This marks the original message as superseded and sends a new message via SMS.
     */
    override suspend fun retryAsSms(messageGuid: String): Result<MessageEntity> = safeCall {
        val message = messageDao.getMessageByGuid(messageGuid)
            ?: throw MessageError.SendFailed(messageGuid, "Message not found")

        val chat = chatDao.getChatByGuid(message.chatGuid)

        // Check if we can send SMS for this chat
        val address = extractAddressFromChatGuid(message.chatGuid)
        if (address == null || !address.isPhoneNumber()) {
            throw SmsError.PermissionDenied(Exception("Cannot send SMS to this contact (no phone number)"))
        }

        // Soft delete the original failed message
        messageDao.softDeleteMessage(messageGuid)

        // Enter fallback mode for this chat
        chatFallbackTracker.enterFallbackMode(message.chatGuid, FallbackReason.IMESSAGE_FAILED)

        // Determine SMS/MMS based on whether it had attachments or is group
        val deliveryMode = if (message.hasAttachments || chat?.isGroup == true) {
            MessageDeliveryMode.LOCAL_MMS
        } else {
            MessageDeliveryMode.LOCAL_SMS
        }

        ensureCarrierReadyOrThrow(requireMms = deliveryMode == MessageDeliveryMode.LOCAL_MMS)

        Timber.i("Retrying message $messageGuid as $deliveryMode")

        // Re-send via SMS/MMS
        sendUnified(
            chatGuid = message.chatGuid,
            text = message.text ?: "",
            subject = message.subject,
            deliveryMode = deliveryMode
        ).getOrThrow()
    }

    /**
     * Check if a failed iMessage can be retried as SMS
     */
    override suspend fun canRetryAsSms(messageGuid: String): Boolean {
        val message = messageDao.getMessageByGuid(messageGuid) ?: return false

        // Only failed iMessages can be retried as SMS
        if (message.error == 0 || message.messageSource != MessageSource.IMESSAGE.name) {
            return false
        }

        // Check if SMS is available (we're the default SMS app)
        if (!smsPermissionHelper.isDefaultSmsApp()) {
            return false
        }

        // Check if we have a phone number
        val address = extractAddressFromChatGuid(message.chatGuid)
        return address?.isPhoneNumber() == true
    }

    /**
     * Delete a failed message from the local database.
     * Only deletes messages with error status > 0.
     */
    override suspend fun deleteFailedMessage(messageGuid: String) {
        val message = messageDao.getMessageByGuid(messageGuid)
        if (message != null && message.error > 0) {
            messageDao.softDeleteMessage(messageGuid)
            Timber.d("Deleted failed message: $messageGuid")
        } else {
            Timber.w("Cannot delete message $messageGuid - not a failed message")
        }
    }

    /**
     * Forward a message to another conversation.
     * Copies the message text and attachments to the target chat.
     */
    override suspend fun forwardMessage(
        messageGuid: String,
        targetChatGuid: String
    ): Result<MessageEntity> = safeCall {
        val originalMessage = messageDao.getMessageByGuid(messageGuid)
            ?: throw MessageError.SendFailed(messageGuid, "Original message not found")

        // Get attachments for the original message
        val attachments = attachmentDao.getAttachmentsForMessage(messageGuid)

        // Convert attachment entities to PendingAttachmentInput if they have local paths
        val attachmentInputs = attachments.mapNotNull { attachment ->
            attachment.localPath?.let { path ->
                try {
                    PendingAttachmentInput(
                        uri = Uri.parse(path),
                        mimeType = attachment.mimeType,
                        name = attachment.transferName,
                        size = attachment.totalBytes
                    )
                } catch (e: Exception) {
                    null
                }
            }
            // Skip web URLs - would need to download first
        }

        Timber.d("Forwarding message $messageGuid to $targetChatGuid with ${attachmentInputs.size} attachments")

        // Send the message to the target chat
        sendUnified(
            chatGuid = targetChatGuid,
            text = originalMessage.text ?: "",
            subject = originalMessage.subject,
            attachments = attachmentInputs,
            deliveryMode = MessageDeliveryMode.AUTO
        ).getOrThrow()
    }

    // ===== Delivery Mode Determination =====

    /**
     * Determine the best delivery mode for a chat based on:
     * 1. SMS-only mode setting
     * 2. Chat-level SMS fallback mode
     * 3. Chat type (SMS/MMS prefix)
     * 4. Server connection status
     * 5. Whether fallback to SMS is possible (phone number available)
     */
    private suspend fun determineDeliveryMode(
        chatGuid: String,
        hasAttachments: Boolean
    ): MessageDeliveryMode {
        val chat = chatDao.getChatByGuid(chatGuid)

        // Check if SMS-only mode is enabled in settings
        if (settingsDataStore.smsOnlyMode.first()) {
            return if (chat?.isGroup == true || hasAttachments) {
                MessageDeliveryMode.LOCAL_MMS
            } else {
                MessageDeliveryMode.LOCAL_SMS
            }
        }

        // Check if chat is in SMS fallback mode (due to previous iMessage failure)
        if (chatFallbackTracker.isInFallbackMode(chatGuid)) {
            val address = extractAddressFromChatGuid(chatGuid)
            if (address?.isPhoneNumber() == true) {
                return if (chat?.isGroup == true || hasAttachments) {
                    MessageDeliveryMode.LOCAL_MMS
                } else {
                    MessageDeliveryMode.LOCAL_SMS
                }
            }
        }

        // Check if it's already a local SMS/MMS/RCS chat
        if (chatGuid.startsWith("sms;-;", ignoreCase = true) ||
            chatGuid.startsWith("mms;-;", ignoreCase = true) ||
            chatGuid.startsWith("RCS;-;", ignoreCase = true)) {
            // Use MMS if group or has attachments
            return if (chat?.isGroup == true || hasAttachments) {
                MessageDeliveryMode.LOCAL_MMS
            } else {
                MessageDeliveryMode.LOCAL_SMS
            }
        }

        // Check server connection for iMessage chats
        val isConnected = socketService.connectionState.value == ConnectionState.CONNECTED
        if (!isConnected) {
            // Server disconnected - try SMS fallback if we have phone numbers
            val address = extractAddressFromChatGuid(chatGuid)
            val canFallbackToSms = address?.isPhoneNumber() == true

            if (canFallbackToSms) {
                val capability = smsPermissionHelper.getSmsCapabilityStatus()
                if (capability.isDefaultSmsApp && capability.canSendSms && capability.hasCellularConnectivity) {
                    chatFallbackTracker.enterFallbackMode(chatGuid, FallbackReason.SERVER_DISCONNECTED)
                    return if (chat?.isGroup == true || hasAttachments) {
                        MessageDeliveryMode.LOCAL_MMS
                    } else {
                        MessageDeliveryMode.LOCAL_SMS
                    }
                } else if (!capability.hasCellularConnectivity) {
                    Timber.w("Cannot enter SMS fallback for $chatGuid: no cellular connectivity")
                } else {
                    Timber.w("Cannot enter SMS fallback for $chatGuid: default SMS role missing")
                }
            }
        }

        // Default to iMessage for connected server or non-phone-number chats
        return MessageDeliveryMode.IMESSAGE
    }

    // ===== Helper Methods =====

    /**
     * Check if carrier messaging is ready. Returns an AppError if not ready, null if ready.
     */
    private fun ensureCarrierReady(requireMms: Boolean = false): SmsError? {
        val status = smsPermissionHelper.getSmsCapabilityStatus()
        if (!status.deviceSupportsSms) {
            return SmsError.PermissionDenied(Exception("This device cannot send SMS/MMS messages"))
        }
        if (!status.isDefaultSmsApp) {
            return SmsError.NoDefaultApp()
        }
        if (!status.canSendSms) {
            return SmsError.PermissionDenied()
        }
        if (requireMms && !status.deviceSupportsMms) {
            return SmsError.PermissionDenied(Exception("This device cannot send MMS messages"))
        }
        return null
    }

    /**
     * Throws if carrier messaging is not ready. Use inside runCatching blocks.
     */
    private fun ensureCarrierReadyOrThrow(requireMms: Boolean = false) {
        ensureCarrierReady(requireMms)?.let { throw it }
    }

    /**
     * Extract a single address from a chat GUID (for SMS)
     */
    private fun extractAddressFromChatGuid(chatGuid: String): String? {
        // Format: "sms;-;+1234567890" or "iMessage;-;+1234567890"
        val parts = chatGuid.split(";-;")
        return if (parts.size == 2) parts[1] else null
    }

    /**
     * Check if a string looks like a phone number
     */
    private fun String.isPhoneNumber(): Boolean {
        val cleaned = this.replace(Regex("[^0-9+]"), "")
        return cleaned.startsWith("+") || (cleaned.length >= 10 && cleaned.all { it.isDigit() })
    }

    /**
     * Convert a MessageDto to a MessageEntity
     */
    private fun MessageDto.toEntity(chatGuid: String): MessageEntity {
        // Determine message source based on handle's service or chat GUID prefix
        val source = when {
            handle?.service?.equals("SMS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            handle?.service?.equals("RCS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("SMS;-;") -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("RCS;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("mms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            else -> MessageSource.IMESSAGE.name
        }

        return MessageEntity(
            guid = guid,
            chatGuid = chatGuid,
            handleId = handleId,
            senderAddress = handle?.address,
            text = text,
            subject = subject,
            dateCreated = dateCreated ?: System.currentTimeMillis(),
            dateRead = dateRead,
            dateDelivered = dateDelivered,
            dateEdited = dateEdited,
            datePlayed = datePlayed,
            isFromMe = isFromMe,
            error = error,
            itemType = itemType,
            groupTitle = groupTitle,
            groupActionType = groupActionType,
            balloonBundleId = balloonBundleId,
            associatedMessageGuid = associatedMessageGuid,
            associatedMessagePart = associatedMessagePart,
            associatedMessageType = associatedMessageType,
            expressiveSendStyleId = expressiveSendStyleId,
            threadOriginatorGuid = threadOriginatorGuid,
            threadOriginatorPart = threadOriginatorPart,
            hasAttachments = attachments?.isNotEmpty() == true,
            hasReactions = hasReactions,
            bigEmoji = bigEmoji,
            wasDeliveredQuietly = wasDeliveredQuietly,
            didNotifyRecipient = didNotifyRecipient,
            messageSource = source,
            isReactionDb = com.bothbubbles.data.local.db.entity.ReactionClassifier.isReaction(associatedMessageGuid, associatedMessageType)
        )
    }
}
