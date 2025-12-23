package com.bothbubbles.ui.conversations.delegates

import android.app.Application
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.data.local.db.entity.displayName
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
import com.bothbubbles.ui.conversations.getAttachmentPreviewText
import com.bothbubbles.ui.conversations.getDocumentTypeName
import com.bothbubbles.ui.conversations.isDocumentType
import com.bothbubbles.ui.conversations.isGif
import com.bothbubbles.ui.conversations.isVLocation
import com.bothbubbles.ui.conversations.isVoiceMessage
import com.bothbubbles.ui.conversations.parseTapbackType
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.parsing.UrlParsingUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope

/**
 * Delegate responsible for unified group mapping logic.
 * Handles merging iMessage/SMS conversations for the same contact.
 *
 * Phase 8: Uses AssistedInject for lifecycle-safe construction.
 * No more lateinit or initialize() - scope is provided at construction time.
 */
class UnifiedGroupMappingDelegate @AssistedInject constructor(
    private val application: Application,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val attachmentRepository: AttachmentRepository,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val handleRepository: HandleRepository,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): UnifiedGroupMappingDelegate
    }

    /**
     * Convert a unified chat to a UI model using pre-fetched data.
     * Eliminates N+1 queries by receiving all data from batch queries.
     *
     * @param unifiedChat The unified chat entity
     * @param chatGuids List of chat GUIDs in this unified conversation
     * @param typingChats Set of chat GUIDs that have active typing indicators
     * @param chatsMap Pre-fetched chats indexed by GUID
     * @param participantsMap Pre-fetched participants grouped by chat GUID
     * @return ConversationUiModel or null if invalid
     */
    suspend fun unifiedChatToUiModel(
        unifiedChat: UnifiedChatEntity,
        chatGuids: List<String>,
        typingChats: Set<String>,
        chatsMap: Map<String, ChatEntity>,
        participantsMap: Map<String, List<com.bothbubbles.data.local.db.entity.HandleEntity>>
    ): ConversationUiModel? {
        if (chatGuids.isEmpty()) return null

        // Use pre-fetched chats from batch query
        val chats = chatGuids.mapNotNull { chatsMap[it] }
        if (chats.isEmpty()) return null

        // Use cached latest message from unified chat
        val latestTimestamp = unifiedChat.latestMessageDate ?: 0L
        val rawMessageText = unifiedChat.latestMessageText ?: ""
        val isFromMe = unifiedChat.latestMessageIsFromMe

        // Use the source chat for display info
        val primaryChat = chats.find { it.guid == unifiedChat.sourceId } ?: chats.first()

        // Get participants for this unified chat's channels
        val groupParticipants = chatGuids.flatMap { chatGuid ->
            participantsMap[chatGuid] ?: emptyList()
        }.distinctBy { it.id }
        val primaryParticipant = chatRepository.getBestParticipant(groupParticipants)
        val address = primaryParticipant?.address ?: primaryChat.chatIdentifier ?: unifiedChat.normalizedAddress

        // Determine display name using unified chat's cached values
        // For group chats, prioritize chat display name over participant names
        val displayName: String = unifiedChat.displayName?.takeIf { it.isNotBlank() }
            ?: primaryChat.displayName?.takeIf { it.isNotBlank() }
            ?: (if (!unifiedChat.isGroup) {
                // Only use participant name for 1:1 chats
                primaryParticipant?.cachedDisplayName?.takeIf { it.isNotBlank() }
                    ?: primaryParticipant?.inferredName?.let { "Maybe: $it" }
            } else null)
            ?: primaryChat.chatIdentifier?.let { PhoneNumberFormatter.format(it) }
            ?: PhoneNumberFormatter.format(address)

        // Check for any typing indicators
        val anyTyping = chatGuids.any { it in typingChats }

        // Get attachments for the latest message if it has any
        val attachments = if (unifiedChat.latestMessageHasAttachments && unifiedChat.latestMessageGuid != null) {
            attachmentRepository.getAttachmentsForMessage(unifiedChat.latestMessageGuid!!)
        } else emptyList()
        val firstAttachment = attachments.firstOrNull()
        val attachmentCount = attachments.size

        // Check for invisible ink effect
        val latestMessage = unifiedChat.latestMessageGuid?.let { messageRepository.getMessageByGuid(it) }
        val isInvisibleInk = latestMessage?.expressiveSendStyleId?.contains("invisibleink", ignoreCase = true) == true

        // Determine message type with enhanced detection
        val messageType = determineMessageType(latestMessage, firstAttachment, rawMessageText)

        // Generate preview text
        val messageText = generatePreviewText(rawMessageText, messageType)

        // Get document type name if applicable
        val documentType = if (messageType == MessageType.DOCUMENT && firstAttachment != null) {
            getDocumentTypeName(firstAttachment.mimeType, firstAttachment.fileExtension)
        } else null

        // Get descriptive attachment preview text for generic ATTACHMENT type
        val attachmentPreviewText = if (messageType == MessageType.ATTACHMENT && firstAttachment != null) {
            getAttachmentPreviewText(firstAttachment.mimeType, firstAttachment.fileExtension)
        } else null

        // Get reaction preview data
        val reactionPreviewData = if (messageType == MessageType.REACTION && latestMessage != null) {
            getReactionPreviewData(latestMessage)
        } else null

        // Get group event text
        val groupEventText = if (messageType == MessageType.GROUP_EVENT && latestMessage != null) {
            formatGroupEvent(latestMessage, unifiedChat.sourceId, handleRepository)
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
            guid = unifiedChat.sourceId,
            displayName = displayName,
            avatarPath = unifiedChat.effectiveAvatarPath
                ?: primaryChat.serverGroupPhotoPath
                ?: primaryParticipant?.cachedAvatarPath,
            chatAvatarPath = primaryChat.serverGroupPhotoPath,  // Server group photo takes priority over collage
            lastMessageText = messageText,
            lastMessageTime = formatRelativeTime(latestTimestamp, application),
            lastMessageTimestamp = latestTimestamp,
            unreadCount = unifiedChat.unreadCount,
            isPinned = unifiedChat.isPinned,
            pinIndex = unifiedChat.pinIndex ?: Int.MAX_VALUE,
            isMuted = unifiedChat.muteType != null,
            isGroup = unifiedChat.isGroup,
            isTyping = anyTyping,
            isFromMe = isFromMe,
            hasDraft = unifiedChat.textFieldText?.isNotBlank() == true,
            draftText = unifiedChat.textFieldText,
            lastMessageType = messageType,
            lastMessageStatus = messageStatus,
            participantNames = groupParticipants.map { it.displayName },
            participantAvatarPaths = groupParticipants.map { it.cachedAvatarPath },
            address = address,
            hasInferredName = primaryParticipant?.hasInferredName == true,
            inferredName = primaryParticipant?.inferredName,
            lastMessageLinkTitle = linkTitle,
            lastMessageLinkDomain = linkDomain,
            isSpam = unifiedChat.isSpam,
            category = unifiedChat.category,
            isSnoozed = unifiedChat.isSnoozed,
            snoozeUntil = unifiedChat.snoozeUntil,
            lastMessageSource = unifiedChat.latestMessageSource,
            mergedChatGuids = chatGuids,
            isMerged = chatGuids.size > 1,
            contactKey = PhoneNumberFormatter.getContactKey(address),
            // Enhanced preview fields
            isInvisibleInk = isInvisibleInk,
            reactionPreviewData = reactionPreviewData,
            groupEventText = groupEventText,
            documentType = documentType,
            attachmentCount = if (attachmentCount > 0) attachmentCount else 1,
            attachmentPreviewText = attachmentPreviewText
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
            // Check for links early - balloonBundleId is also set for rich link previews
            rawMessageText.contains("http://") || rawMessageText.contains("https://") -> MessageType.LINK
            // Check for app messages (balloonBundleId) BEFORE attachments
            // iMessage apps send plugin payload attachments that we don't want to display as files
            !message?.balloonBundleId.isNullOrBlank() -> MessageType.APP_MESSAGE
            firstAttachment != null -> when {
                firstAttachment.isSticker -> MessageType.STICKER
                isVLocation(firstAttachment) -> MessageType.LOCATION
                firstAttachment.mimeType == "text/vcard" || firstAttachment.mimeType == "text/x-vcard" -> MessageType.CONTACT
                firstAttachment.isImage && firstAttachment.hasLivePhoto -> MessageType.LIVE_PHOTO
                isGif(firstAttachment) -> MessageType.GIF
                firstAttachment.isImage -> MessageType.IMAGE
                firstAttachment.isVideo -> MessageType.VIDEO
                firstAttachment.isAudio && isVoiceMessage(firstAttachment) -> MessageType.VOICE_MESSAGE
                firstAttachment.isAudio -> MessageType.AUDIO
                isDocumentType(firstAttachment.mimeType) -> MessageType.DOCUMENT
                else -> MessageType.ATTACHMENT
            }
            rawMessageText.containsLocation() -> MessageType.LOCATION
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
            messageType == MessageType.GIF -> "GIF"
            messageType == MessageType.VIDEO -> "Video"
            messageType == MessageType.AUDIO -> "Audio"
            messageType == MessageType.VOICE_MESSAGE -> "Voice message"
            messageType == MessageType.STICKER -> "Sticker"
            messageType == MessageType.CONTACT -> "Contact"
            messageType == MessageType.DOCUMENT -> "Document"
            messageType == MessageType.LOCATION -> "Location"
            messageType == MessageType.APP_MESSAGE -> "App message"
            messageType == MessageType.ATTACHMENT -> "File"
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
