package com.bothbubbles.ui.chat

import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.ReactionUiModel
import com.bothbubbles.ui.components.message.ReplyPreviewData
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.util.toStable
import com.bothbubbles.util.EmojiUtils.analyzeEmojis
import com.bothbubbles.util.PhoneNumberFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utilities for transforming database entities into UI models.
 */
object MessageTransformationUtils {

    /**
     * Convert MessageEntity to MessageUiModel with all associated data.
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
                    senderName = resolveSenderName(
                        reaction.senderAddress,
                        reaction.handleId,
                        addressToName,
                        handleIdToName
                    )
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
                    // Use message.isFromMe for UI purposes - if I sent this message, treat attachment as "mine"
                    // This handles Mac-to-self messages where server reports isOutgoing=false for both copies
                    isOutgoing = isFromMe,
                    uti = attachment.uti,
                    hasLivePhoto = attachment.hasLivePhoto
                )
            }

        return MessageUiModel(
            guid = guid,
            text = text,
            subject = subject,
            dateCreated = dateCreated,
            formattedTime = formatTime(dateCreated),
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
            senderAddress = senderAddress,
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
            emojiAnalysis = analyzeEmojis(text)
        )
    }

    /**
     * Parse the reaction type from the associatedMessageType field.
     * BlueBubbles format examples: "2000" (love), "2001" (like), etc.
     * Or text format: "love", "like", "dislike", "laugh", "emphasize", "question"
     */
    private fun parseReactionType(associatedMessageType: String?): Tapback? {
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
    private fun isReactionRemoval(associatedMessageType: String?): Boolean {
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
    @Suppress("unused")
    private fun parseRemovalType(associatedMessageType: String?): Tapback? {
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
     * Format timestamp as time string (h:mm a).
     */
    private fun formatTime(timestamp: Long): String {
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
    private fun resolveSenderName(
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
    private fun resolveSenderAvatarPath(
        senderAddress: String?,
        addressToAvatarPath: Map<String, String?>
    ): String? {
        senderAddress?.let { address ->
            val normalized = normalizeAddress(address)
            return addressToAvatarPath[normalized]
        }
        return null
    }
}
