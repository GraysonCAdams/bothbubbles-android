package com.bothbubbles.ui.conversations.delegates

import android.app.Application
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.ui.conversations.ConversationUiModel
import com.bothbubbles.ui.conversations.MessageStatus
import com.bothbubbles.ui.conversations.MessageType
import com.bothbubbles.ui.conversations.ReactionPreviewData
import com.bothbubbles.ui.conversations.containsLocation
import com.bothbubbles.ui.conversations.formatGroupEvent
import com.bothbubbles.ui.conversations.formatRelativeTime
import com.bothbubbles.ui.conversations.getDocumentTypeName
import com.bothbubbles.ui.conversations.isDocumentType
import com.bothbubbles.ui.conversations.isVoiceMessage
import com.bothbubbles.ui.conversations.parseTapbackType
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.parsing.UrlParsingUtils
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Delegate responsible for unified group mapping logic.
 * Handles merging iMessage/SMS conversations for the same contact.
 *
 * This delegate extracts the complex unified group to UI model conversion
 * logic from ConversationsViewModel, including optimized batch queries.
 */
class UnifiedGroupMappingDelegate @Inject constructor(
    private val application: Application,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val attachmentRepository: AttachmentRepository,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val handleRepository: HandleRepository
) {
    private lateinit var scope: CoroutineScope

    /**
     * Initialize the delegate with coroutine scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Convert a unified chat group to a UI model using pre-fetched data.
     * Eliminates N+1 queries by receiving all data from batch queries.
     *
     * @param group The unified chat group entity
     * @param chatGuids List of chat GUIDs in this group
     * @param typingChats Set of chat GUIDs that have active typing indicators
     * @param latestMessagesMap Pre-fetched latest messages indexed by chat GUID
     * @param chatsMap Pre-fetched chats indexed by GUID
     * @param participantsMap Pre-fetched participants grouped by chat GUID
     * @return ConversationUiModel or null if group is invalid
     */
    suspend fun unifiedGroupToUiModel(
        group: UnifiedChatGroupEntity,
        chatGuids: List<String>,
        typingChats: Set<String>,
        latestMessagesMap: Map<String, MessageEntity>,
        chatsMap: Map<String, ChatEntity>,
        participantsMap: Map<String, List<com.bothbubbles.data.local.db.entity.HandleEntity>>
    ): ConversationUiModel? {
        if (chatGuids.isEmpty()) return null

        // Use pre-fetched chats from batch query
        val chats = chatGuids.mapNotNull { chatsMap[it] }
        if (chats.isEmpty()) return null

        // Use pre-fetched messages instead of N queries
        var latestMessage: MessageEntity? = null
        var latestTimestamp = 0L
        for (chatGuid in chatGuids) {
            val msg = latestMessagesMap[chatGuid]
            if (msg != null && msg.dateCreated > latestTimestamp) {
                latestMessage = msg
                latestTimestamp = msg.dateCreated
            }
        }

        // Use the primary chat for display info
        val primaryChat = chats.find { it.guid == group.primaryChatGuid } ?: chats.first()

        // Get participants for this specific group's chats (not batched to avoid cross-contamination)
        val groupParticipants = chatGuids.flatMap { chatGuid ->
            participantsMap[chatGuid] ?: emptyList()
        }.distinctBy { it.id }
        val primaryParticipant = chatRepository.getBestParticipant(groupParticipants)
        val address = primaryParticipant?.address ?: primaryChat.chatIdentifier ?: group.identifier

        // Determine display name
        val displayName = primaryParticipant?.cachedDisplayName?.takeIf { it.isNotBlank() }
            ?: group.displayName?.let { PhoneNumberFormatter.format(it) }?.takeIf { it.isNotBlank() }
            ?: primaryChat.displayName?.let { PhoneNumberFormatter.format(it) }?.takeIf { it.isNotBlank() }
            ?: primaryParticipant?.inferredName?.let { "Maybe: $it" }
            ?: primaryChat.chatIdentifier?.let { PhoneNumberFormatter.format(it) }
            ?: address.let { PhoneNumberFormatter.format(it) }

        // Sum unread counts
        val totalUnread = chats.sumOf { it.unreadCount }

        // Check for any typing indicators
        val anyTyping = chatGuids.any { it in typingChats }

        // Check for any pinned
        val anyPinned = chats.any { it.isPinned } || group.isPinned

        // Check if all muted
        val allMuted = chats.all { it.muteType != null } || group.muteType != null

        // Check for drafts
        val chatWithDraft = chats.find { !it.textFieldText.isNullOrBlank() }

        val rawMessageText = latestMessage?.text ?: primaryChat.lastMessageText ?: ""
        val isFromMe = latestMessage?.isFromMe ?: false

        // Get attachments for the latest message
        val attachments = if (latestMessage?.hasAttachments == true) {
            attachmentRepository.getAttachmentsForMessage(latestMessage.guid)
        } else emptyList()
        val firstAttachment = attachments.firstOrNull()
        val attachmentCount = attachments.size

        // Check for invisible ink effect
        val isInvisibleInk = latestMessage?.expressiveSendStyleId?.contains("invisibleink", ignoreCase = true) == true

        // Determine message type with enhanced detection
        val messageType = determineMessageType(latestMessage, firstAttachment, rawMessageText)

        // Generate preview text
        val messageText = generatePreviewText(rawMessageText, messageType)

        // Get document type name if applicable
        val documentType = if (messageType == MessageType.DOCUMENT && firstAttachment != null) {
            getDocumentTypeName(firstAttachment.mimeType, firstAttachment.fileExtension)
        } else null

        // Get reaction preview data
        val reactionPreviewData = if (messageType == MessageType.REACTION && latestMessage != null) {
            getReactionPreviewData(latestMessage)
        } else null

        // Get group event text
        val groupEventText = if (messageType == MessageType.GROUP_EVENT && latestMessage != null) {
            formatGroupEvent(latestMessage, group.primaryChatGuid, handleRepository)
        } else null

        // Determine message status
        val messageStatus = determineMessageStatus(isFromMe, latestMessage)

        // Get link preview data for LINK type
        val (linkTitle, linkDomain) = if (messageType == MessageType.LINK) {
            getLinkPreviewData(rawMessageText)
        } else {
            null to null
        }

        return ConversationUiModel(
            guid = group.primaryChatGuid,
            displayName = displayName,
            avatarPath = primaryParticipant?.cachedAvatarPath,
            lastMessageText = messageText,
            lastMessageTime = formatRelativeTime(latestTimestamp.takeIf { it > 0 } ?: group.latestMessageDate ?: 0L, application),
            lastMessageTimestamp = latestTimestamp.takeIf { it > 0 } ?: group.latestMessageDate ?: 0L,
            unreadCount = totalUnread.takeIf { it > 0 } ?: group.unreadCount,
            isPinned = anyPinned,
            pinIndex = group.pinIndex ?: chats.mapNotNull { it.pinIndex }.minOrNull() ?: Int.MAX_VALUE,
            isMuted = allMuted,
            isGroup = false,
            isTyping = anyTyping,
            isFromMe = isFromMe,
            hasDraft = chatWithDraft != null,
            draftText = chatWithDraft?.textFieldText,
            lastMessageType = messageType,
            lastMessageStatus = messageStatus,
            participantNames = groupParticipants.map { it.displayName },
            address = address,
            hasInferredName = primaryParticipant?.hasInferredName == true,
            inferredName = primaryParticipant?.inferredName,
            lastMessageLinkTitle = linkTitle,
            lastMessageLinkDomain = linkDomain,
            isSpam = chats.any { it.isSpam },
            category = primaryChat.category,
            isSnoozed = group.snoozeUntil != null,
            snoozeUntil = group.snoozeUntil,
            lastMessageSource = latestMessage?.messageSource,
            mergedChatGuids = chatGuids,
            isMerged = chatGuids.size > 1,
            contactKey = PhoneNumberFormatter.getContactKey(address),
            // Enhanced preview fields
            isInvisibleInk = isInvisibleInk,
            reactionPreviewData = reactionPreviewData,
            groupEventText = groupEventText,
            documentType = documentType,
            attachmentCount = if (attachmentCount > 0) attachmentCount else 1
        )
    }

    /**
     * Determine message type based on message entity and attachments.
     */
    private fun determineMessageType(
        message: MessageEntity?,
        firstAttachment: AttachmentEntity?,
        rawMessageText: String
    ): MessageType {
        return when {
            message?.dateDeleted != null -> MessageType.DELETED
            message?.isReaction == true -> MessageType.REACTION
            message?.isGroupEvent == true -> MessageType.GROUP_EVENT
            firstAttachment != null -> when {
                firstAttachment.isSticker -> MessageType.STICKER
                firstAttachment.mimeType == "text/vcard" || firstAttachment.mimeType == "text/x-vcard" -> MessageType.CONTACT
                firstAttachment.isImage && firstAttachment.hasLivePhoto -> MessageType.LIVE_PHOTO
                firstAttachment.isImage -> MessageType.IMAGE
                firstAttachment.isVideo -> MessageType.VIDEO
                firstAttachment.isAudio && isVoiceMessage(firstAttachment) -> MessageType.VOICE_MESSAGE
                firstAttachment.isAudio -> MessageType.AUDIO
                isDocumentType(firstAttachment.mimeType) -> MessageType.DOCUMENT
                else -> MessageType.ATTACHMENT
            }
            !message?.balloonBundleId.isNullOrBlank() -> MessageType.APP_MESSAGE
            rawMessageText.containsLocation() -> MessageType.LOCATION
            rawMessageText.contains("http://") || rawMessageText.contains("https://") -> MessageType.LINK
            else -> MessageType.TEXT
        }
    }

    /**
     * Generate preview text based on message type.
     */
    private fun generatePreviewText(rawMessageText: String, messageType: MessageType): String {
        return when {
            rawMessageText.isNotBlank() -> rawMessageText
            messageType == MessageType.IMAGE -> "Photo"
            messageType == MessageType.LIVE_PHOTO -> "Live Photo"
            messageType == MessageType.VIDEO -> "Video"
            messageType == MessageType.AUDIO -> "Audio"
            messageType == MessageType.VOICE_MESSAGE -> "Voice message"
            messageType == MessageType.STICKER -> "Sticker"
            messageType == MessageType.CONTACT -> "Contact"
            messageType == MessageType.DOCUMENT -> "Document"
            messageType == MessageType.LOCATION -> "Location"
            messageType == MessageType.APP_MESSAGE -> "App message"
            messageType == MessageType.ATTACHMENT -> "Attachment"
            else -> rawMessageText
        }
    }

    /**
     * Get reaction preview data for a reaction message.
     */
    private suspend fun getReactionPreviewData(message: MessageEntity): ReactionPreviewData? {
        val originalGuid = message.associatedMessageGuid
        val originalMessage = originalGuid?.let { messageRepository.getMessageByGuid(it) }
        val tapbackInfo = parseTapbackType(message.associatedMessageType)
        return ReactionPreviewData(
            tapbackVerb = tapbackInfo.verb,
            originalText = originalMessage?.text?.take(30),
            hasAttachment = originalMessage?.hasAttachments == true
        )
    }

    /**
     * Determine message status for display.
     */
    private fun determineMessageStatus(isFromMe: Boolean, message: MessageEntity?): MessageStatus {
        return when {
            !isFromMe -> MessageStatus.NONE
            message == null -> MessageStatus.NONE
            message.guid.startsWith("temp-") -> MessageStatus.SENDING
            message.dateRead != null -> MessageStatus.READ
            message.dateDelivered != null -> MessageStatus.DELIVERED
            message.error == 0 -> MessageStatus.SENT
            else -> MessageStatus.NONE
        }
    }

    /**
     * Get link preview data for a link message.
     */
    private suspend fun getLinkPreviewData(rawMessageText: String): Pair<String?, String?> {
        val detectedUrl = UrlParsingUtils.getFirstUrl(rawMessageText)
        return if (detectedUrl != null) {
            val preview = linkPreviewRepository.getLinkPreview(detectedUrl.url)
            val title = preview?.title?.takeIf { it.isNotBlank() }
            val domain = preview?.domain ?: detectedUrl.domain
            title to domain
        } else {
            null to null
        }
    }
}
