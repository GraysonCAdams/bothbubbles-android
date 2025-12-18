package com.bothbubbles.ui.components.message

import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.ReplyPreviewData
import com.bothbubbles.util.EmojiUtils.analyzeEmojis
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.ui.util.toStable
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shared transformation functions for converting MessageEntity to MessageUiModel.
 * Used by both ChatViewModel and RoomMessageDataSource.
 */

/**
 * Convert a MessageEntity to MessageUiModel for display.
 */
fun MessageEntity.toUiModel(
    reactions: List<MessageEntity> = emptyList(),
    attachments: List<AttachmentEntity> = emptyList(),
    handleIdToName: Map<Long, String> = emptyMap(),
    addressToName: Map<String, String> = emptyMap(),
    addressToAvatarPath: Map<String, String?> = emptyMap(),
    replyPreview: ReplyPreviewData? = null
): MessageUiModel {
    // Filter reactions using old BlueBubbles Flutter logic:
    // 1. Remove duplicate GUIDs
    // 2. Sort by date (newest first)
    // 3. Keep only the latest reaction per sender
    // 4. Filter out removals (types starting with "-" or 3xxx codes)
    val uniqueReactions = reactions
        .distinctBy { it.guid }
        .sortedByDescending { it.dateCreated }
        .let { sorted ->
            val seenSenders = mutableSetOf<Long>()
            sorted.filter { reaction ->
                val senderId = if (reaction.isFromMe) 0L else (reaction.handleId ?: 0L)
                seenSenders.add(senderId) // Returns true if not already present
            }
        }
        .filter { !isReactionRemoval(it.associatedMessageType) }

    // Parse filtered reactions into UI models
    val allReactions = uniqueReactions.mapNotNull { reaction ->
        val tapbackType = parseReactionType(reaction.associatedMessageType)
        tapbackType?.let {
            ReactionUiModel(
                tapback = it,
                isFromMe = reaction.isFromMe,
                senderName = reaction.handleId?.let { id -> handleIdToName[id] }
            )
        }
    }

    // Get my reactions (for highlighting in the menu)
    val myReactions = allReactions
        .filter { it.isFromMe }
        .map { it.tapback }
        .toSet()

    // Map attachments to UI models
    val attachmentUiModels = attachments
        .filter { !it.hideAttachment }
        .map { attachment ->
            AttachmentUiModel(
                guid = attachment.guid,
                mimeType = attachment.mimeType,
                localPath = attachment.localPath,
                webUrl = attachment.webUrl,
                width = attachment.width,
                height = attachment.height,
                transferName = attachment.transferName,
                totalBytes = attachment.totalBytes,
                isSticker = attachment.isSticker,
                blurhash = attachment.blurhash,
                thumbnailPath = attachment.thumbnailPath,
                transferState = attachment.transferState,
                transferProgress = attachment.transferProgress,
                isOutgoing = attachment.isOutgoing,
                errorType = attachment.errorType,
                errorMessage = attachment.errorMessage,
                retryCount = attachment.retryCount
            )
        }

    // Generate group event text if this is a group event
    val groupEventText = if (isGroupEvent) {
        formatGroupEventText(
            itemType = itemType,
            groupActionType = groupActionType,
            groupTitle = groupTitle,
            senderName = resolveSenderName(senderAddress, handleId, addressToName, handleIdToName)
        )
    } else null

    return MessageUiModel(
        guid = guid,
        text = text,
        subject = subject,
        dateCreated = dateCreated,
        formattedTime = formatMessageTime(dateCreated),
        isFromMe = isFromMe,
        isSent = !guid.startsWith("temp-") && error == 0,
        isDelivered = dateDelivered != null,
        isRead = dateRead != null,
        hasError = error != 0,
        errorCode = error,
        errorMessage = smsErrorMessage,
        isReaction = associatedMessageType?.contains("reaction") == true,
        attachments = attachmentUiModels.toStable(),
        // Resolve sender name: try senderAddress first (most accurate), then fall back to handleId lookup
        senderName = resolveSenderName(senderAddress, handleId, addressToName, handleIdToName),
        senderAvatarPath = resolveSenderAvatarPath(senderAddress, addressToAvatarPath),
        messageSource = messageSource,
        reactions = allReactions.toStable(),
        myReactions = myReactions,
        expressiveSendStyleId = expressiveSendStyleId,
        effectPlayed = datePlayed != null,
        associatedMessageGuid = associatedMessageGuid,
        // Reply indicator fields
        threadOriginatorGuid = threadOriginatorGuid,
        replyPreview = replyPreview,
        // Pre-compute emoji analysis to avoid recalculating on every composition
        emojiAnalysis = analyzeEmojis(text),
        // Group event fields
        isGroupEvent = isGroupEvent,
        groupEventText = groupEventText
    )
}

/**
 * Parse the reaction type from the associatedMessageType field.
 * BlueBubbles format examples: "2000" (love), "2001" (like), etc.
 * Or text format: "love", "like", "dislike", "laugh", "emphasize", "question"
 */
fun parseReactionType(associatedMessageType: String?): Tapback? {
    if (associatedMessageType == null) return null

    // Try parsing as API name first (text format)
    // Note: "-love" indicates removal, so check for that first
    if (associatedMessageType.startsWith("-")) {
        return null // This is a removal, handled separately
    }
    Tapback.fromApiName(associatedMessageType)?.let { return it }

    // Parse numeric codes (iMessage internal format)
    // 2000 = love, 2001 = like, 2002 = dislike, 2003 = laugh, 2004 = emphasize, 2005 = question
    // 3000-3005 = removal of reactions (should not be counted as active reactions)
    return when {
        associatedMessageType.contains("3000") -> null // love removal
        associatedMessageType.contains("3001") -> null // like removal
        associatedMessageType.contains("3002") -> null // dislike removal
        associatedMessageType.contains("3003") -> null // laugh removal
        associatedMessageType.contains("3004") -> null // emphasize removal
        associatedMessageType.contains("3005") -> null // question removal
        associatedMessageType.contains("2000") -> Tapback.LOVE
        associatedMessageType.contains("2001") -> Tapback.LIKE
        associatedMessageType.contains("2002") -> Tapback.DISLIKE
        associatedMessageType.contains("2003") -> Tapback.LAUGH
        associatedMessageType.contains("2004") -> Tapback.EMPHASIZE
        associatedMessageType.contains("2005") -> Tapback.QUESTION
        associatedMessageType.contains("love") -> Tapback.LOVE
        associatedMessageType.contains("like") -> Tapback.LIKE
        associatedMessageType.contains("dislike") -> Tapback.DISLIKE
        associatedMessageType.contains("laugh") -> Tapback.LAUGH
        associatedMessageType.contains("emphasize") -> Tapback.EMPHASIZE
        associatedMessageType.contains("question") -> Tapback.QUESTION
        else -> null
    }
}

/**
 * Check if the reaction code indicates a removal (3000-3005 range).
 */
fun isReactionRemoval(associatedMessageType: String?): Boolean {
    if (associatedMessageType == null) return false
    if (associatedMessageType.startsWith("-")) return true
    return associatedMessageType.contains("3000") ||
            associatedMessageType.contains("3001") ||
            associatedMessageType.contains("3002") ||
            associatedMessageType.contains("3003") ||
            associatedMessageType.contains("3004") ||
            associatedMessageType.contains("3005")
}

/**
 * Parse the reaction type from a removal code (3xxx range).
 */
fun parseRemovalType(associatedMessageType: String?): Tapback? {
    if (associatedMessageType == null) return null
    if (associatedMessageType.startsWith("-")) {
        return Tapback.fromApiName(associatedMessageType.removePrefix("-"))
    }
    return when {
        associatedMessageType.contains("3000") -> Tapback.LOVE
        associatedMessageType.contains("3001") -> Tapback.LIKE
        associatedMessageType.contains("3002") -> Tapback.DISLIKE
        associatedMessageType.contains("3003") -> Tapback.LAUGH
        associatedMessageType.contains("3004") -> Tapback.EMPHASIZE
        associatedMessageType.contains("3005") -> Tapback.QUESTION
        else -> null
    }
}

/**
 * Format a timestamp for display in the message bubble.
 */
fun formatMessageTime(timestamp: Long): String {
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Normalize an address for comparison/lookup.
 * Strips non-essential characters from phone numbers, lowercases emails.
 */
fun normalizeAddress(address: String): String {
    return if (address.contains("@")) {
        address.lowercase()
    } else {
        address.replace(Regex("[^0-9+]"), "")
    }
}

/**
 * Resolve sender name from available data sources.
 * Priority: senderAddress lookup > handleId lookup > formatted address
 */
fun resolveSenderName(
    senderAddress: String?,
    handleId: Long?,
    addressToName: Map<String, String>,
    handleIdToName: Map<Long, String>
): String? {
    // 1. Try looking up by senderAddress (most accurate for group chats)
    senderAddress?.let { address ->
        val normalized = normalizeAddress(address)
        addressToName[normalized]?.let { return it }
        // No contact match - return formatted phone number
        return PhoneNumberFormatter.format(address)
    }

    // 2. Fall back to handleId lookup
    return handleId?.let { handleIdToName[it] }
}

/**
 * Resolve sender avatar path from address.
 */
fun resolveSenderAvatarPath(
    senderAddress: String?,
    addressToAvatarPath: Map<String, String?>
): String? {
    senderAddress?.let { address ->
        val normalized = normalizeAddress(address)
        return addressToAvatarPath[normalized]
    }
    return null
}

/**
 * Format group event text based on item type and action type.
 *
 * @param itemType The type of group event (1=participant, 2=name change, 3=icon change)
 * @param groupActionType The subtype for participant events (0=joined, 1=left)
 * @param groupTitle The new group name for name change events
 * @param senderName The name of the person who triggered the event
 */
fun formatGroupEventText(
    itemType: Int,
    groupActionType: Int,
    groupTitle: String?,
    senderName: String?
): String {
    // Extract first name for cleaner display
    val firstName = senderName?.let { extractFirstName(it) } ?: "Someone"

    return when (itemType) {
        1 -> { // Participant change
            when (groupActionType) {
                0 -> "$firstName joined the group"
                1 -> "$firstName left the group"
                else -> "Group membership changed"
            }
        }
        2 -> groupTitle?.let { "Name changed to \"$it\"" } ?: "Group name changed"
        3 -> "Group photo changed"
        else -> "Group updated"
    }
}

/**
 * Extract the first name from a full name for cleaner display.
 * Handles phone numbers by returning them as-is.
 */
private fun extractFirstName(fullName: String): String {
    // If it looks like a phone number, return as-is
    val stripped = fullName.replace(Regex("[+\\-()\\s]"), "")
    if (stripped.all { it.isDigit() } && stripped.length >= 5) {
        return fullName
    }

    // Get first word that contains letters
    val words = fullName.trim().split(Regex("\\s+"))
    for (word in words) {
        val cleaned = word.filter { it.isLetterOrDigit() }
        if (cleaned.isNotEmpty() && cleaned.any { it.isLetter() }) {
            return cleaned
        }
    }
    return words.firstOrNull() ?: fullName
}
