package com.bothbubbles.ui.preview

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.components.message.EmojiAnalysis
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.ReplyPreviewData
import com.bothbubbles.ui.util.toStable

/**
 * Sample data for Compose previews.
 * These are fake objects that don't require database access.
 */
object PreviewData {

    // ====================
    // Message Samples
    // ====================

    val sampleMessage = MessageUiModel(
        guid = "preview-message-1",
        text = "Hey! How are you doing today?",
        dateCreated = System.currentTimeMillis(),
        formattedTime = "12:34 PM",
        isFromMe = false,
        isSent = true,
        isDelivered = true,
        isRead = false,
        hasError = false,
        isReaction = false,
        attachments = emptyList<AttachmentUiModel>().toStable(),
        senderName = "John Appleseed",
        messageSource = MessageSource.IMESSAGE.name,
        emojiAnalysis = EmojiAnalysis(isEmojiOnly = false, emojiCount = 0)
    )

    val sampleMessageFromMe = sampleMessage.copy(
        guid = "preview-message-2",
        text = "I'm doing great, thanks for asking!",
        isFromMe = true,
        isDelivered = true,
        isRead = true,
        senderName = null
    )

    val sampleLongMessage = sampleMessage.copy(
        guid = "preview-message-3",
        text = "This is a much longer message that should wrap to multiple lines. " +
               "It tests how the message bubble handles longer content and ensures " +
               "the layout doesn't break with extended text. Here's even more text " +
               "to really push the limits of the bubble width."
    )

    val sampleSmsMessage = sampleMessage.copy(
        guid = "preview-sms-1",
        text = "This is an SMS message",
        messageSource = MessageSource.LOCAL_SMS.name
    )

    val sampleEmojiOnlyMessage = sampleMessage.copy(
        guid = "preview-emoji-1",
        text = "\uD83D\uDE00\uD83D\uDE0D\uD83D\uDE02",
        emojiAnalysis = EmojiAnalysis(isEmojiOnly = true, emojiCount = 3)
    )

    val sampleReplyMessage = sampleMessage.copy(
        guid = "preview-reply-1",
        text = "This is a reply to your message",
        threadOriginatorGuid = "preview-message-1",
        replyPreview = ReplyPreviewData(
            originalGuid = "preview-message-1",
            previewText = "Hey! How are you doing today?",
            senderName = "John",
            isFromMe = false,
            hasAttachment = false
        )
    )

    val sampleFailedMessage = sampleMessageFromMe.copy(
        guid = "preview-failed-1",
        text = "This message failed to send",
        hasError = true,
        isSent = false,
        isDelivered = false
    )

    val samplePendingMessage = sampleMessageFromMe.copy(
        guid = "temp-preview-pending-1",
        text = "Sending...",
        isSent = false,
        isDelivered = false
    )

    // ====================
    // Attachment Samples
    // ====================

    val sampleImageAttachment = AttachmentUiModel(
        guid = "preview-attachment-1",
        mimeType = "image/jpeg",
        localPath = null,
        webUrl = null,
        width = 1920,
        height = 1080,
        transferName = "photo.jpg",
        totalBytes = 2_500_000,
        blurhash = "LGF5]+Yk^6#M@-5c,1J5@[or[Q6."
    )

    val sampleVideoAttachment = AttachmentUiModel(
        guid = "preview-attachment-2",
        mimeType = "video/mp4",
        localPath = null,
        webUrl = null,
        width = 1920,
        height = 1080,
        transferName = "video.mp4",
        totalBytes = 15_000_000
    )

    val sampleMessageWithImage = sampleMessage.copy(
        guid = "preview-message-with-image",
        text = "Check out this photo!",
        attachments = listOf(sampleImageAttachment).toStable()
    )

    // ====================
    // Conversation Samples
    // ====================

    data class ConversationPreviewData(
        val chatGuid: String,
        val displayName: String,
        val isGroup: Boolean,
        val lastMessageText: String?,
        val lastMessageDate: Long?,
        val unreadCount: Int,
        val isPinned: Boolean,
        val isMuted: Boolean,
        val isArchived: Boolean,
        val isSnoozed: Boolean,
        val isSms: Boolean
    )

    val sampleConversation = ConversationPreviewData(
        chatGuid = "preview-chat-1",
        displayName = "John Appleseed",
        isGroup = false,
        lastMessageText = "Hey! How are you?",
        lastMessageDate = System.currentTimeMillis(),
        unreadCount = 0,
        isPinned = false,
        isMuted = false,
        isArchived = false,
        isSnoozed = false,
        isSms = false
    )

    val sampleGroupConversation = sampleConversation.copy(
        chatGuid = "preview-chat-2",
        displayName = "Family Group",
        isGroup = true,
        lastMessageText = "Who's coming to dinner?"
    )

    val sampleUnreadConversation = sampleConversation.copy(
        chatGuid = "preview-chat-3",
        unreadCount = 5,
        lastMessageText = "You have new messages!"
    )

    val samplePinnedConversation = sampleConversation.copy(
        chatGuid = "preview-chat-4",
        isPinned = true,
        displayName = "Mom"
    )

    val sampleMutedConversation = sampleConversation.copy(
        chatGuid = "preview-chat-5",
        isMuted = true,
        displayName = "Work Group"
    )

    val sampleSmsConversation = sampleConversation.copy(
        chatGuid = "sms;preview-chat-6",
        displayName = "Pizza Place",
        isSms = true,
        lastMessageText = "Your order is ready for pickup"
    )

    // ====================
    // Collections for Parameters
    // ====================

    val sampleMessages = listOf(
        sampleMessage,
        sampleMessageFromMe,
        sampleLongMessage,
        sampleEmojiOnlyMessage
    )

    val sampleConversations = listOf(
        sampleConversation,
        sampleGroupConversation,
        sampleUnreadConversation,
        samplePinnedConversation
    )
}

/**
 * Preview parameter provider for message variations.
 */
class MessagePreviewProvider : PreviewParameterProvider<MessageUiModel> {
    override val values: Sequence<MessageUiModel> = sequenceOf(
        PreviewData.sampleMessage,
        PreviewData.sampleMessageFromMe,
        PreviewData.sampleLongMessage
    )
}

/**
 * Preview parameter provider for message group positions.
 */
class MessageGroupPositionProvider : PreviewParameterProvider<MessageGroupPosition> {
    override val values: Sequence<MessageGroupPosition> = sequenceOf(
        MessageGroupPosition.SINGLE,
        MessageGroupPosition.FIRST,
        MessageGroupPosition.MIDDLE,
        MessageGroupPosition.LAST
    )
}
