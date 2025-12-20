package com.bothbubbles.ui.conversations

import android.app.Application
import android.text.format.DateFormat
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.util.parsing.UrlParsingUtils
import java.text.SimpleDateFormat
import java.util.*

/**
 * Formats a message preview for display in conversation list.
 */
internal fun formatMessagePreview(conversation: ConversationUiModel): String {
    // Show typing indicator if someone is typing
    if (conversation.isTyping) {
        return "typing..."
    }

    // Check invisible ink first - hide actual content
    if (conversation.isInvisibleInk) {
        val content = if (conversation.lastMessageType in listOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.LIVE_PHOTO))
            "Image sent with Invisible Ink"
        else
            "Message sent with Invisible Ink"
        return formatWithSenderPrefix(conversation, content)
    }

    val content = when (conversation.lastMessageType) {
        MessageType.DELETED -> "Message deleted"
        MessageType.REACTION -> formatReactionPreview(conversation.reactionPreviewData)
        MessageType.GROUP_EVENT -> conversation.groupEventText ?: "Group updated"
        MessageType.STICKER -> "Sticker"
        MessageType.CONTACT -> "Contact"
        MessageType.LIVE_PHOTO -> "Live Photo"
        MessageType.VOICE_MESSAGE -> "Voice message"
        MessageType.DOCUMENT -> conversation.documentType ?: "Document"
        MessageType.LOCATION -> "Location"
        MessageType.APP_MESSAGE -> "App message"
        MessageType.IMAGE -> formatAttachmentCount(conversation.attachmentCount, "Photo")
        MessageType.GIF -> "GIF"
        MessageType.VIDEO -> formatAttachmentCount(conversation.attachmentCount, "Video")
        MessageType.AUDIO -> "Audio"
        MessageType.LINK -> formatLinkPreview(conversation)
        MessageType.ATTACHMENT -> conversation.attachmentPreviewText ?: "File"
        MessageType.TEXT -> conversation.lastMessageText
    }

    return formatWithSenderPrefix(conversation, content)
}

/**
 * Add sender prefix ("You:" or "Name:") to preview content.
 */
internal fun formatWithSenderPrefix(conversation: ConversationUiModel, content: String): String {
    return when {
        conversation.isFromMe -> "You: $content"
        // For group chats, show sender's first name
        conversation.isGroup && conversation.lastMessageSenderName != null ->
            "${conversation.lastMessageSenderName}: $content"
        else -> content
    }
}

/**
 * Format reaction preview text: "Liked 'original message...'".
 */
internal fun formatReactionPreview(data: ReactionPreviewData?): String {
    if (data == null) return "Reacted to a message"
    val target = when {
        data.originalText != null -> {
            val truncated = data.originalText
            val ellipsis = if (truncated.length >= 30) "..." else ""
            "\"$truncated$ellipsis\""
        }
        data.hasAttachment -> "an attachment"
        else -> "a message"
    }
    return "${data.tapbackVerb} $target"
}

/**
 * Format attachment count: "Photo" for 1, "2 Photos" for multiple.
 */
internal fun formatAttachmentCount(count: Int, singular: String): String {
    return when {
        count <= 1 -> singular
        else -> "$count ${singular}s"
    }
}

/**
 * Formats a link preview for display in the conversation list.
 * Includes any text surrounding the link, with the link portion replaced by
 * the preview title/domain when available.
 */
internal fun formatLinkPreview(conversation: ConversationUiModel): String {
    val title = conversation.lastMessageLinkTitle
    val domain = conversation.lastMessageLinkDomain
    val messageText = conversation.lastMessageText

    // Get the link representation (title, domain, or raw URL)
    val linkDisplay = when {
        title != null && domain != null -> "$title ($domain)"
        title != null -> title
        domain != null -> domain
        else -> null
    }

    // If we have no link display info, just return the message text
    if (linkDisplay == null) {
        return messageText.take(100)
    }

    // Find the URL position in the original text
    val detectedUrl = UrlParsingUtils.getFirstUrl(messageText)
    if (detectedUrl == null) {
        return linkDisplay
    }

    // Extract text before and after the URL
    val textBefore = messageText.substring(0, detectedUrl.startIndex).trim()
    val textAfter = messageText.substring(detectedUrl.endIndex).trim()

    // Build the preview with surrounding text
    return buildString {
        if (textBefore.isNotEmpty()) {
            append(textBefore)
            append(" ")
        }
        append(linkDisplay)
        if (textAfter.isNotEmpty()) {
            append(" ")
            append(textAfter)
        }
    }
}

/**
 * Formats a phone number for pretty display.
 * Handles various formats and converts to (XXX) XXX-XXXX for US numbers.
 */
internal fun formatPhoneNumber(input: String): String {
    // Remove all non-digit characters
    val digits = input.replace(Regex("[^0-9]"), "")

    return when {
        // US number with country code: +1XXXXXXXXXX or 1XXXXXXXXXX
        digits.length == 11 && digits.startsWith("1") -> {
            val areaCode = digits.substring(1, 4)
            val prefix = digits.substring(4, 7)
            val lineNumber = digits.substring(7, 11)
            "($areaCode) $prefix-$lineNumber"
        }
        // US number without country code: XXXXXXXXXX
        digits.length == 10 -> {
            val areaCode = digits.substring(0, 3)
            val prefix = digits.substring(3, 6)
            val lineNumber = digits.substring(6, 10)
            "($areaCode) $prefix-$lineNumber"
        }
        // 7-digit local number: XXX-XXXX
        digits.length == 7 -> {
            val prefix = digits.substring(0, 3)
            val lineNumber = digits.substring(3, 7)
            "$prefix-$lineNumber"
        }
        // Short codes (5-6 digits): leave as-is
        digits.length in 5..6 -> digits
        // Other formats: return original if it looks like a number, otherwise return as-is
        else -> input
    }
}

/**
 * Formats a display name, applying phone number formatting if it looks like a phone number.
 */
internal fun formatDisplayName(name: String): String {
    // Check if the name looks like a phone number (mostly digits with optional +, -, (), spaces)
    val stripped = name.replace(Regex("[+\\-()\\s]"), "")
    return if (stripped.all { it.isDigit() } && stripped.length >= 5) {
        formatPhoneNumber(name)
    } else {
        name
    }
}

/**
 * Format a timestamp as a relative time string (e.g., "2:30 PM", "Mon", "Jan 15").
 */
fun formatRelativeTime(timestamp: Long, application: Application): String {
    if (timestamp == 0L) return ""

    val now = System.currentTimeMillis()
    val messageDate = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()

    val isSameYear = messageDate.get(Calendar.YEAR) == today.get(Calendar.YEAR)

    // Check if within the last 24 hours (show time instead of day name)
    val hoursDiff = (now - timestamp) / (1000 * 60 * 60)
    val isWithin24Hours = hoursDiff < 24

    // Check if within the last 7 days
    val daysDiff = (now - timestamp) / (1000 * 60 * 60 * 24)

    // Get system time format (12h or 24h)
    val is24Hour = DateFormat.is24HourFormat(application)
    val timePattern = if (is24Hour) "HH:mm" else "h:mm a"

    return when {
        isWithin24Hours -> SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(timestamp))
        daysDiff < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        isSameYear -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("M/d/yy", Locale.getDefault()).format(Date(timestamp))
    }
}

/**
 * Extract the first name from a full name, excluding emojis and non-letter characters.
 * If the name starts with emojis, finds the first word that contains letters.
 */
fun extractFirstName(fullName: String): String {
    // Split by whitespace and find the first word that has letters
    val words = fullName.trim().split(Regex("\\s+"))
    for (word in words) {
        // Filter to only letters/digits
        val cleaned = word.filter { it.isLetterOrDigit() }
        // Check if it has at least one letter (not just digits/emojis)
        if (cleaned.isNotEmpty() && cleaned.any { it.isLetter() }) {
            return cleaned
        }
    }
    // Fallback to the first word cleaned of non-alphanumeric characters
    return words.firstOrNull()?.filter { it.isLetterOrDigit() } ?: fullName
}

/**
 * Check if audio attachment is a voice message (by UTI or filename pattern)
 */
fun isVoiceMessage(attachment: AttachmentEntity): Boolean {
    val uti = attachment.uti?.lowercase() ?: ""
    val name = attachment.transferName?.lowercase() ?: ""
    return uti.contains("voice") ||
           name.startsWith("audio message") ||
           name.endsWith(".caf") // Voice memos are often .caf format
}

/**
 * Check if attachment is a GIF
 */
fun isGif(attachment: AttachmentEntity): Boolean {
    return attachment.mimeType?.lowercase() == "image/gif"
}

/**
 * Check if attachment is a vLocation (shared location)
 */
fun isVLocation(attachment: AttachmentEntity): Boolean {
    val uti = attachment.uti?.lowercase() ?: ""
    val mimeType = attachment.mimeType?.lowercase() ?: ""
    val name = attachment.transferName?.lowercase() ?: ""
    return uti == "public.vlocation" ||
           mimeType == "text/x-vlocation" ||
           name.endsWith(".loc.vcf")
}

/**
 * Get descriptive preview text for an attachment based on its MIME type and extension.
 * Used as fallback when the attachment doesn't match known categories.
 */
fun getAttachmentPreviewText(mimeType: String?, extension: String?): String {
    val type = mimeType?.lowercase() ?: ""
    val ext = extension?.lowercase() ?: ""

    return when {
        // Archive types
        type.contains("zip") || ext == "zip" -> "Zip file"
        type.contains("rar") || ext == "rar" -> "RAR archive"
        type.contains("7z") || ext == "7z" -> "7z archive"
        type.contains("tar") || ext == "tar" -> "Archive"
        type.contains("gzip") || ext == "gz" -> "Archive"

        // Android app
        type == "application/vnd.android.package-archive" || ext == "apk" -> "Android app"

        // Text types
        type == "text/plain" || ext == "txt" -> "Text file"
        type == "text/html" || ext == "html" || ext == "htm" -> "HTML file"
        type == "text/css" || ext == "css" -> "CSS file"
        type == "text/csv" || ext == "csv" -> "CSV file"
        type.contains("json") || ext == "json" -> "JSON file"
        type.contains("xml") || ext == "xml" -> "XML file"
        type.contains("javascript") || ext == "js" -> "JavaScript file"

        // Font types
        type.contains("font") || ext in listOf("ttf", "otf", "woff", "woff2") -> "Font file"

        // Other application types with extension
        ext.isNotEmpty() -> "${ext.uppercase()} file"

        // Final fallback
        else -> "File"
    }
}

/**
 * Check if mime type represents a document file
 */
fun isDocumentType(mimeType: String?): Boolean {
    val type = mimeType?.lowercase() ?: return false
    return type.startsWith("application/pdf") ||
           type.contains("document") ||
           type.contains("spreadsheet") ||
           type.contains("presentation") ||
           type.startsWith("application/msword") ||
           type.startsWith("application/vnd.ms-") ||
           type.startsWith("application/vnd.openxmlformats")
}

/**
 * Get friendly document type name from mime type and extension
 */
fun getDocumentTypeName(mimeType: String?, extension: String?): String {
    return when {
        mimeType?.contains("pdf") == true -> "PDF"
        mimeType?.contains("word") == true || extension == "doc" || extension == "docx" -> "Document"
        mimeType?.contains("excel") == true || mimeType?.contains("spreadsheet") == true -> "Spreadsheet"
        mimeType?.contains("powerpoint") == true || mimeType?.contains("presentation") == true -> "Presentation"
        else -> "File"
    }
}

/**
 * Check if text contains location coordinates or map links
 */
fun String.containsLocation(): Boolean {
    return contains("maps.apple.com") ||
           contains("maps.google.com") ||
           contains("goo.gl/maps") ||
           matches(Regex(".*-?\\d+\\.\\d+,\\s*-?\\d+\\.\\d+.*"))
}

/**
 * Parse tapback/reaction type to human-readable verb
 */
fun parseTapbackType(associatedMessageType: String?): TapbackInfo {
    val type = associatedMessageType?.lowercase() ?: ""
    return when {
        type.contains("love") -> TapbackInfo("Loved", "❤️")
        type.contains("like") -> TapbackInfo("Liked", "👍")
        type.contains("dislike") -> TapbackInfo("Disliked", "👎")
        type.contains("laugh") -> TapbackInfo("Laughed at", "😂")
        type.contains("emphasize") || type.contains("!!") -> TapbackInfo("Emphasized", "‼️")
        type.contains("question") || type.contains("?") -> TapbackInfo("Questioned", "❓")
        else -> TapbackInfo("Reacted to", "")
    }
}

/**
 * Data class for tapback information
 */
data class TapbackInfo(val verb: String, val emoji: String)

/**
 * Format group event message based on itemType and groupActionType
 */
suspend fun formatGroupEvent(
    message: MessageEntity,
    chatGuid: String,
    handleRepository: HandleRepository
): String {
    return when (message.itemType) {
        1 -> { // Participant change
            val participantName = message.handleId?.let { handleId ->
                handleRepository.getHandleById(handleId)?.displayName
            } ?: "Someone"
            val firstName = extractFirstName(participantName)
            when (message.groupActionType) {
                0 -> "$firstName joined the group"
                1 -> "$firstName left the group"
                else -> "Group membership changed"
            }
        }
        2 -> message.groupTitle?.let { "Name changed to \"$it\"" } ?: "Group name changed"
        3 -> "Group photo changed"
        else -> "Group updated"
    }
}

/**
 * Normalize a chat GUID for comparison by stripping formatting from phone numbers.
 * Handles cases where server sends "+1234567890" but local has "+1-234-567-890".
 */
fun normalizeGuid(guid: String): String {
    val parts = guid.split(";-;")
    if (parts.size != 2) return guid.lowercase()
    val prefix = parts[0].lowercase()
    val address = if (parts[1].contains("@")) {
        // Email address - just lowercase
        parts[1].lowercase()
    } else {
        // Phone number - strip non-digits except leading +
        parts[1].replace(Regex("[^0-9+]"), "")
    }
    return "$prefix;-;$address"
}
