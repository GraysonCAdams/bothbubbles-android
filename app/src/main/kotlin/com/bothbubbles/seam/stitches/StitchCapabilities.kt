package com.bothbubbles.seam.stitches

data class StitchCapabilities(
    val supportsReactions: Boolean = false,
    val reactionTypes: Set<String> = emptySet(),
    val supportsTypingIndicators: Boolean = false,
    val supportsReadReceipts: Boolean = false,
    val supportsDeliveryReceipts: Boolean = false,
    val supportsMessageEditing: Boolean = false,
    val supportsMessageUnsend: Boolean = false,
    val supportsMessageEffects: Boolean = false,
    val supportsReplies: Boolean = false,
    val supportsGroupChats: Boolean = true,
    val canModifyGroups: Boolean = false,
    val maxAttachmentSize: Long? = null,
    val supportedMimeTypes: Set<String>? = null,
    val supportsMultipleAttachments: Boolean = true,
    val supportsRealTimePush: Boolean = false,
    val requiresDefaultSmsApp: Boolean = false
) {
    companion object {
        val SMS = StitchCapabilities(
            supportsReactions = false,
            supportsTypingIndicators = false,
            supportsReadReceipts = false,
            supportsDeliveryReceipts = true,
            supportsMessageEditing = false,
            supportsMessageUnsend = false,
            supportsMessageEffects = false,
            supportsReplies = false,
            supportsGroupChats = true,
            canModifyGroups = false,
            maxAttachmentSize = 1_000_000L,
            supportedMimeTypes = setOf(
                "image/jpeg", "image/png", "image/gif",
                "video/3gpp", "video/mp4",
                "audio/amr", "audio/mpeg"
            ),
            supportsRealTimePush = true,
            requiresDefaultSmsApp = true
        )

        val BLUEBUBBLES = StitchCapabilities(
            supportsReactions = true,
            reactionTypes = setOf("love", "like", "dislike", "laugh", "emphasize", "question"),
            supportsTypingIndicators = true,
            supportsReadReceipts = true,
            supportsDeliveryReceipts = true,
            supportsMessageEditing = true,
            supportsMessageUnsend = true,
            supportsMessageEffects = true,
            supportsReplies = true,
            supportsGroupChats = true,
            canModifyGroups = true,
            maxAttachmentSize = null,
            supportedMimeTypes = null,
            supportsRealTimePush = true,
            requiresDefaultSmsApp = false
        )
    }
}
