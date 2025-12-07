package com.bothbubbles.services.export

/**
 * Export format options
 */
enum class ExportFormat {
    HTML,
    PDF
}

/**
 * Export style options
 */
enum class ExportStyle {
    CHAT_BUBBLES,   // iMessage-style colored bubbles
    PLAIN_TEXT      // Simple text with timestamps
}

/**
 * Configuration for a message export operation
 */
data class ExportConfig(
    val format: ExportFormat,
    val style: ExportStyle,
    val chatGuids: List<String>,           // Empty = all chats
    val startDate: Long? = null,           // Null = no start filter
    val endDate: Long? = null              // Null = no end filter
)

/**
 * Progress tracking for export operations
 */
sealed class ExportProgress {
    data object Idle : ExportProgress()

    data class Loading(
        val chatName: String,
        val chatIndex: Int,
        val totalChats: Int,
        val messagesLoaded: Int = 0
    ) : ExportProgress() {
        val progressFraction: Float
            get() = if (totalChats > 0) chatIndex.toFloat() / totalChats else 0f
    }

    data class Generating(val stage: String) : ExportProgress()

    data class Saving(val fileName: String) : ExportProgress()

    data class Complete(
        val filePath: String,
        val fileName: String,
        val messageCount: Int,
        val chatCount: Int
    ) : ExportProgress()

    data class Error(val message: String) : ExportProgress()

    data object Cancelled : ExportProgress()
}

/**
 * Flattened message model for export (includes resolved sender name)
 */
data class ExportableMessage(
    val guid: String,
    val chatGuid: String,
    val text: String?,
    val dateCreated: Long,
    val isFromMe: Boolean,
    val senderName: String,
    val attachmentNames: List<String>,
    val messageSource: String  // "iMessage", "SMS", "MMS"
)

/**
 * Chat info with messages for export
 */
data class ExportableChat(
    val guid: String,
    val displayName: String,
    val isGroup: Boolean,
    val messages: List<ExportableMessage>
)
