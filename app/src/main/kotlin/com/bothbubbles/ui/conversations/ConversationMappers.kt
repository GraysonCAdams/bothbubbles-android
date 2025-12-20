package com.bothbubbles.ui.conversations

import android.app.Application
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.parsing.UrlParsingUtils

/**
 * Extension function to convert ChatEntity to ConversationUiModel.
 * This handles both group chats and 1:1 chats.
 */
suspend fun ChatEntity.toUiModel(
    typingChats: Set<String>,
    messageRepository: MessageRepository,
    chatRepository: ChatRepository,
    attachmentRepository: AttachmentRepository,
    linkPreviewRepository: LinkPreviewRepository,
    application: Application
): ConversationUiModel {
    val lastMessage = messageRepository.getLatestMessageForChat(guid)
    val rawMessageText = lastMessage?.text ?: lastMessageText ?: ""
    val isFromMe = lastMessage?.isFromMe ?: false

    // Get participants for this chat
    val participants = chatRepository.getParticipantsForChat(guid)
    val participantNames = participants.map { it.displayName }
    val participantAvatarPaths = participants.map { it.cachedAvatarPath }
    val primaryParticipant = participants.firstOrNull()
    val address = primaryParticipant?.address ?: chatIdentifier ?: ""
    val avatarPath = primaryParticipant?.cachedAvatarPath

    // Get attachments for the last message
    val attachments = if (lastMessage?.hasAttachments == true) {
        attachmentRepository.getAttachmentsForMessage(lastMessage.guid)
    } else emptyList()
    val firstAttachment = attachments.firstOrNull()
    val attachmentCount = attachments.size

    // Check for invisible ink effect
    val isInvisibleInk = lastMessage?.expressiveSendStyleId?.contains("invisibleink", ignoreCase = true) == true

    // Determine message type with enhanced detection
    val messageType = when {
        // Check deleted first
        lastMessage?.dateDeleted != null -> MessageType.DELETED

        // Check for reactions/tapbacks
        lastMessage?.isReaction == true -> MessageType.REACTION

        // Check for group events
        lastMessage?.isGroupEvent == true -> MessageType.GROUP_EVENT

        // Attachment-based types
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

        // Check for app messages (balloonBundleId)
        !lastMessage?.balloonBundleId.isNullOrBlank() -> MessageType.APP_MESSAGE

        // Check for location in text
        rawMessageText.containsLocation() -> MessageType.LOCATION

        // Check for links
        rawMessageText.contains("http://") || rawMessageText.contains("https://") -> MessageType.LINK

        else -> MessageType.TEXT
    }

    // Generate preview text - show type placeholder when message has no text
    val messageText = when {
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

    // Get document type name if applicable
    val documentType = if (messageType == MessageType.DOCUMENT && firstAttachment != null) {
        getDocumentTypeName(firstAttachment.mimeType, firstAttachment.fileExtension)
    } else null

    // Get descriptive attachment preview text for generic ATTACHMENT type
    val attachmentPreviewText = if (messageType == MessageType.ATTACHMENT && firstAttachment != null) {
        getAttachmentPreviewText(firstAttachment.mimeType, firstAttachment.fileExtension)
    } else null

    // Get reaction preview data if this is a reaction
    val reactionPreviewData = if (messageType == MessageType.REACTION && lastMessage != null) {
        val originalGuid = lastMessage.associatedMessageGuid
        val originalMessage = originalGuid?.let { messageRepository.getMessageByGuid(it) }
        val tapbackInfo = parseTapbackType(lastMessage.associatedMessageType)
        ReactionPreviewData(
            tapbackVerb = tapbackInfo.verb,
            originalText = originalMessage?.text?.take(30),
            hasAttachment = originalMessage?.hasAttachments == true
        )
    } else null

    // Get group event text if this is a group event
    val groupEventText = if (messageType == MessageType.GROUP_EVENT && lastMessage != null) {
        // Note: formatGroupEvent requires HandleRepository, which we don't have here
        // This will need to be handled in the ViewModel
        null
    } else null

    // Determine message status for outgoing messages
    val messageStatus = when {
        !isFromMe -> MessageStatus.NONE
        lastMessage == null -> MessageStatus.NONE
        lastMessage.guid.startsWith("temp-") -> MessageStatus.SENDING
        lastMessage.dateRead != null -> MessageStatus.READ
        lastMessage.dateDelivered != null -> MessageStatus.DELIVERED
        lastMessage.error == 0 -> MessageStatus.SENT
        else -> MessageStatus.NONE
    }

    // For 1:1 chats, use the participant's display name (includes "Maybe:" prefix for inferred names)
    // For group chats, use the chat's display name, then fall back to joined participant names
    // Use takeIf to convert empty strings to null for proper fallback
    // Strip service suffixes from display names as a safety net
    val resolvedDisplayName = if (!isGroup && primaryParticipant != null) {
        primaryParticipant.displayName.takeIf { it.isNotBlank() }
            ?: chatIdentifier?.let { PhoneNumberFormatter.format(it) }
            ?: address.let { PhoneNumberFormatter.format(it) }
    } else if (isGroup) {
        // For group chats: explicit name > joined participant names > formatted identifier
        displayName?.let { PhoneNumberFormatter.stripServiceSuffix(it) }?.takeIf { it.isNotBlank() }
            ?: participantNames.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: "Group Chat"
    } else {
        displayName?.let { PhoneNumberFormatter.stripServiceSuffix(it) }?.takeIf { it.isNotBlank() }
            ?: chatIdentifier?.let { PhoneNumberFormatter.format(it) }
            ?: address.let { PhoneNumberFormatter.format(it) }
    }

    // Fetch link preview data for LINK type messages
    val (linkTitle, linkDomain) = if (messageType == MessageType.LINK) {
        val detectedUrl = UrlParsingUtils.getFirstUrl(rawMessageText)
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

    // Generate contact key for potential merging (only for 1:1 chats)
    val contactKey = if (!isGroup && address.isNotBlank()) {
        PhoneNumberFormatter.getContactKey(address)
    } else {
        ""
    }

    // For group chats, get sender's first name if not from me
    val senderAddress = lastMessage?.senderAddress
    val senderName = if (isGroup && !isFromMe && senderAddress != null) {
        // Find participant by sender address
        val senderParticipant = participants.find { it.address == senderAddress }
        val fullName = senderParticipant?.cachedDisplayName
            ?: senderParticipant?.formattedAddress
            ?: senderAddress
        extractFirstName(fullName)
    } else {
        null
    }

    return ConversationUiModel(
        guid = guid,
        displayName = resolvedDisplayName,
        avatarPath = avatarPath,
        chatAvatarPath = customAvatarPath, // Custom group photo takes precedence
        lastMessageText = messageText,
        lastMessageTime = formatRelativeTime(lastMessage?.dateCreated ?: lastMessageDate ?: 0L, application),
        lastMessageTimestamp = lastMessage?.dateCreated ?: lastMessageDate ?: 0L,
        unreadCount = unreadCount,
        isPinned = isPinned,
        pinIndex = pinIndex ?: Int.MAX_VALUE,
        isMuted = muteType != null,
        isGroup = isGroup,
        isTyping = guid in typingChats,
        isFromMe = isFromMe,
        hasDraft = !textFieldText.isNullOrBlank(),
        draftText = textFieldText,
        lastMessageType = messageType,
        lastMessageStatus = messageStatus,
        participantNames = participantNames,
        participantAvatarPaths = participantAvatarPaths,
        address = address,
        hasInferredName = primaryParticipant?.hasInferredName == true,
        inferredName = primaryParticipant?.inferredName,
        lastMessageLinkTitle = linkTitle,
        lastMessageLinkDomain = linkDomain,
        isSpam = isSpam,
        category = category,
        isSnoozed = isSnoozed,
        snoozeUntil = snoozeUntil,
        lastMessageSource = lastMessage?.messageSource,
        lastMessageSenderName = senderName,
        mergedChatGuids = listOf(guid), // Initially just this chat
        isMerged = false,
        contactKey = contactKey,
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
 * Merge 1:1 conversations with the same contact into a single entry.
 * Groups chats by their contactKey (normalized phone number) and combines:
 * - Uses the most recent message
 * - Sums up unread counts
 * - Combines all chat GUIDs for navigation
 * - Prefers saved contact names over inferred names
 *
 * Group chats are never merged.
 */
fun mergeConversations(conversations: List<ConversationUiModel>): List<ConversationUiModel> {
    // Separate group chats (never merge) from 1:1 chats
    val (groupChats, oneOnOneChats) = conversations.partition { it.isGroup }

    // Group 1:1 chats by contact key
    val grouped = oneOnOneChats.groupBy { it.contactKey }

    val mergedChats = grouped.map { (contactKey, chats) ->
        if (chats.size == 1 || contactKey.isEmpty()) {
            // Single chat or no valid contact key - no merging needed
            chats.first()
        } else {
            // Multiple chats to merge
            mergeChatGroup(chats)
        }
    }

    // Combine merged 1:1 chats with group chats
    return (mergedChats + groupChats)
        .sortedWith(
            compareByDescending<ConversationUiModel> { it.isPinned }
                .thenBy { it.pinIndex }
                .thenByDescending { it.lastMessageTimestamp }
        )
}

/**
 * Merge multiple 1:1 chats (same contact) into a single conversation entry.
 */
fun mergeChatGroup(chats: List<ConversationUiModel>): ConversationUiModel {
    // Sort by most recent message
    val sortedByRecent = chats.sortedByDescending { it.lastMessageTimestamp }
    val primary = sortedByRecent.first()

    // Combine all chat GUIDs
    val allGuids = chats.map { it.guid }

    // Sum unread counts
    val totalUnread = chats.sumOf { it.unreadCount }

    // Any typing indicator from any chat
    val anyTyping = chats.any { it.isTyping }

    // Any pinned - use lowest pinIndex from pinned chats
    val anyPinned = chats.any { it.isPinned }
    val minPinIndex = chats.filter { it.isPinned }.minOfOrNull { it.pinIndex } ?: Int.MAX_VALUE

    // All muted
    val allMuted = chats.all { it.isMuted }

    // Prefer saved contact name over inferred name
    val preferredChat = chats.find { it.hasContact } ?: primary
    val displayName = preferredChat.displayName
    val hasInferredName = preferredChat.hasInferredName
    val inferredName = preferredChat.inferredName
    val avatarPath = preferredChat.avatarPath ?: chats.firstNotNullOfOrNull { it.avatarPath }
    val chatAvatarPath = preferredChat.chatAvatarPath ?: chats.firstNotNullOfOrNull { it.chatAvatarPath }

    // Check for any draft
    val chatWithDraft = chats.find { it.hasDraft }
    val hasDraft = chatWithDraft != null
    val draftText = chatWithDraft?.draftText

    return primary.copy(
        guid = primary.guid, // Primary guid for navigation (will use mergedChatGuids in chat)
        displayName = displayName,
        avatarPath = avatarPath,
        chatAvatarPath = chatAvatarPath,
        unreadCount = totalUnread,
        isPinned = anyPinned,
        pinIndex = minPinIndex,
        isMuted = allMuted,
        isTyping = anyTyping,
        hasDraft = hasDraft,
        draftText = draftText,
        hasInferredName = hasInferredName,
        inferredName = inferredName,
        mergedChatGuids = allGuids,
        isMerged = true
    )
}
