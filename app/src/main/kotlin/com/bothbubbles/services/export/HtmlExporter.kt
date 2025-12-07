package com.bothbubbles.services.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HtmlExporter @Inject constructor() : MessageExporter {

    override val format: ExportFormat = ExportFormat.HTML
    override val fileExtension: String = "html"
    override val mimeType: String = "text/html"

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val fullDateFormat = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault())

    override suspend fun export(
        chats: List<ExportableChat>,
        style: ExportStyle,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val html = generateHtml(chats, style)
            outputFile.writeText(html)
            outputFile
        }
    }

    private fun generateHtml(chats: List<ExportableChat>, style: ExportStyle): String {
        val totalMessages = chats.sumOf { it.messages.size }
        val exportDate = fullDateFormat.format(Date())

        return buildString {
            append("<!DOCTYPE html>\n")
            append("<html lang=\"en\">\n")
            append("<head>\n")
            append("    <meta charset=\"UTF-8\">\n")
            append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            append("    <title>BlueBubbles Message Export</title>\n")
            append("    <style>\n")
            append(if (style == ExportStyle.CHAT_BUBBLES) getChatBubblesCSS() else getPlainTextCSS())
            append("    </style>\n")
            append("</head>\n")
            append("<body>\n")

            // Header
            append("    <header class=\"export-header\">\n")
            append("        <h1>Message Export</h1>\n")
            append("        <div class=\"metadata\">\n")
            append("            <p>Exported: $exportDate</p>\n")
            append("            <p>Conversations: ${chats.size} | Messages: $totalMessages</p>\n")
            append("        </div>\n")
            append("    </header>\n\n")

            // Chat sections
            chats.forEach { chat ->
                append(generateChatSection(chat, style))
            }

            // Footer
            append("    <footer class=\"export-footer\">\n")
            append("        <p>Exported from BlueBubbles</p>\n")
            append("    </footer>\n")
            append("</body>\n")
            append("</html>")
        }
    }

    private fun generateChatSection(chat: ExportableChat, style: ExportStyle): String {
        return buildString {
            append("    <div class=\"chat-section\">\n")
            append("        <div class=\"chat-header\">\n")
            append("            <h2>${escapeHtml(chat.displayName)}</h2>\n")
            if (chat.isGroup) {
                append("            <span class=\"chat-type\">Group Chat</span>\n")
            }
            append("            <span class=\"message-count\">${chat.messages.size} messages</span>\n")
            append("        </div>\n")

            // Group messages by date
            val messagesByDate = chat.messages.groupBy { dateFormat.format(Date(it.dateCreated)) }

            messagesByDate.forEach { (date, messages) ->
                append("        <div class=\"date-divider\">$date</div>\n")

                messages.forEach { message ->
                    append(
                        if (style == ExportStyle.CHAT_BUBBLES)
                            generateBubbleMessage(message, chat.isGroup)
                        else
                            generatePlainTextMessage(message)
                    )
                }
            }

            append("    </div>\n\n")
        }
    }

    private fun generateBubbleMessage(message: ExportableMessage, isGroup: Boolean): String {
        val bubbleClass = when {
            message.isFromMe && message.messageSource == "iMessage" -> "sent imessage"
            message.isFromMe -> "sent sms"
            else -> "received"
        }

        return buildString {
            append("        <div class=\"message-row ${if (message.isFromMe) "from-me" else "from-them"}\">\n")

            // Show sender name for group chats (received messages)
            if (isGroup && !message.isFromMe) {
                append("            <div class=\"sender-name\">${escapeHtml(message.senderName)}</div>\n")
            }

            append("            <div class=\"message-bubble $bubbleClass\">\n")

            // Message text
            if (!message.text.isNullOrBlank()) {
                append("                <div class=\"message-text\">${escapeHtml(message.text)}</div>\n")
            }

            // Attachments
            if (message.attachmentNames.isNotEmpty()) {
                append("                <div class=\"attachments\">\n")
                message.attachmentNames.forEach { name ->
                    append("                    <div class=\"attachment\">📎 ${escapeHtml(name)}</div>\n")
                }
                append("                </div>\n")
            }

            // Timestamp
            append("                <div class=\"timestamp\">${timeFormat.format(Date(message.dateCreated))}</div>\n")
            append("            </div>\n")
            append("        </div>\n")
            append("        <div class=\"clearfix\"></div>\n")
        }
    }

    private fun generatePlainTextMessage(message: ExportableMessage): String {
        val time = timeFormat.format(Date(message.dateCreated))
        val sender = if (message.isFromMe) "Me" else message.senderName
        val sourceTag = if (message.messageSource != "iMessage") " [${message.messageSource}]" else ""

        return buildString {
            append("        <div class=\"message\">\n")
            append("            <span class=\"time\">[$time]</span> ")
            append("<span class=\"sender\">${escapeHtml(sender)}$sourceTag:</span> ")

            if (!message.text.isNullOrBlank()) {
                append("<span class=\"text\">${escapeHtml(message.text)}</span>")
            }

            if (message.attachmentNames.isNotEmpty()) {
                message.attachmentNames.forEach { name ->
                    append("\n            <div class=\"attachment\">[Attachment: ${escapeHtml(name)}]</div>")
                }
            }

            append("\n        </div>\n")
        }
    }

    private fun getChatBubblesCSS(): String = """
        :root {
            --imessage-blue: #007AFF;
            --sms-green: #34C759;
            --received-bg: #E5E5EA;
            --text-dark: #1C1C1E;
        }

        * {
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            max-width: 800px;
            margin: 0 auto;
            padding: 20px;
            background: #FFFFFF;
            color: var(--text-dark);
            line-height: 1.4;
        }

        .export-header {
            text-align: center;
            border-bottom: 2px solid #E5E5EA;
            padding-bottom: 20px;
            margin-bottom: 30px;
        }

        .export-header h1 {
            margin: 0 0 10px 0;
            color: #007AFF;
        }

        .metadata {
            color: #8E8E93;
            font-size: 14px;
        }

        .metadata p {
            margin: 4px 0;
        }

        .chat-section {
            margin-bottom: 40px;
            page-break-inside: avoid;
        }

        .chat-header {
            border-bottom: 2px solid #007AFF;
            padding-bottom: 12px;
            margin-bottom: 20px;
        }

        .chat-header h2 {
            margin: 0 0 4px 0;
            font-size: 20px;
        }

        .chat-type, .message-count {
            font-size: 12px;
            color: #8E8E93;
            margin-right: 12px;
        }

        .date-divider {
            text-align: center;
            color: #8E8E93;
            font-size: 12px;
            margin: 20px 0 12px 0;
            font-weight: 500;
        }

        .message-row {
            margin-bottom: 4px;
        }

        .message-row.from-me {
            text-align: right;
        }

        .message-row.from-them {
            text-align: left;
        }

        .sender-name {
            font-size: 11px;
            color: #8E8E93;
            margin-bottom: 2px;
            margin-left: 12px;
        }

        .message-bubble {
            display: inline-block;
            max-width: 70%;
            padding: 10px 14px;
            border-radius: 18px;
            text-align: left;
            word-wrap: break-word;
        }

        .message-bubble.imessage {
            background: var(--imessage-blue);
            color: white;
        }

        .message-bubble.sms {
            background: var(--sms-green);
            color: white;
        }

        .message-bubble.received {
            background: var(--received-bg);
            color: var(--text-dark);
        }

        .message-text {
            white-space: pre-wrap;
        }

        .timestamp {
            font-size: 10px;
            opacity: 0.7;
            margin-top: 4px;
            text-align: right;
        }

        .attachments {
            margin-top: 6px;
        }

        .attachment {
            font-size: 12px;
            font-style: italic;
            opacity: 0.9;
        }

        .clearfix::after {
            content: "";
            display: table;
            clear: both;
        }

        .export-footer {
            text-align: center;
            color: #8E8E93;
            font-size: 12px;
            margin-top: 40px;
            padding-top: 20px;
            border-top: 1px solid #E5E5EA;
        }

        @media print {
            body {
                max-width: 100%;
            }
            .chat-section {
                page-break-inside: avoid;
            }
        }
    """.trimIndent()

    private fun getPlainTextCSS(): String = """
        body {
            font-family: 'Courier New', Courier, monospace;
            max-width: 800px;
            margin: 0 auto;
            padding: 20px;
            background: #FFFFFF;
            color: #1C1C1E;
            line-height: 1.5;
            font-size: 13px;
        }

        .export-header {
            border-bottom: 2px solid #333;
            padding-bottom: 15px;
            margin-bottom: 25px;
        }

        .export-header h1 {
            margin: 0 0 10px 0;
        }

        .metadata {
            color: #666;
        }

        .metadata p {
            margin: 2px 0;
        }

        .chat-section {
            margin-bottom: 30px;
            border-bottom: 1px solid #CCC;
            padding-bottom: 20px;
        }

        .chat-header {
            margin-bottom: 15px;
        }

        .chat-header h2 {
            margin: 0 0 4px 0;
            font-size: 16px;
        }

        .chat-type, .message-count {
            font-size: 11px;
            color: #666;
        }

        .date-divider {
            color: #666;
            margin: 15px 0 8px 0;
            font-weight: bold;
        }

        .message {
            margin: 4px 0;
        }

        .time {
            color: #666;
        }

        .sender {
            font-weight: bold;
        }

        .text {
            white-space: pre-wrap;
        }

        .attachment {
            color: #666;
            font-style: italic;
            margin-left: 20px;
        }

        .export-footer {
            text-align: center;
            color: #666;
            font-size: 11px;
            margin-top: 30px;
            padding-top: 15px;
            border-top: 1px solid #CCC;
        }

        @media print {
            body {
                max-width: 100%;
            }
        }
    """.trimIndent()

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("\n", "<br>")
    }
}
