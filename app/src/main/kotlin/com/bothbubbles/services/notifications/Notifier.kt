package com.bothbubbles.services.notifications

/**
 * Parameters for showing a message notification.
 * Consolidates the 15 parameters into a single data class for better API design.
 *
 * @param chatGuid Unique identifier for the chat
 * @param chatTitle Display name of the conversation
 * @param messageText The message content
 * @param messageGuid Unique identifier for the message
 * @param senderName Display name of the sender
 * @param senderAddress The sender's address (phone/email) used for bubble filtering
 * @param isGroup Whether this is a group conversation
 * @param avatarUri Optional URI to the sender's contact photo
 * @param linkPreviewTitle Optional link preview title
 * @param linkPreviewDomain Optional link preview domain
 * @param participantNames List of participant names for group chats (used for group avatar collage)
 * @param participantAvatarPaths List of avatar paths for group participants (corresponding to participantNames)
 * @param groupAvatarPath Optional path to group avatar (customAvatarPath or serverGroupPhotoPath). Takes priority over participant collage.
 * @param subject Optional message subject (for iMessage). When present, shows ONLY the subject.
 * @param attachmentUri Optional content:// URI to an attachment image/video for inline preview
 * @param attachmentMimeType MIME type of the attachment (required if attachmentUri is provided)
 */
data class MessageNotificationParams(
    val chatGuid: String,
    val chatTitle: String,
    val messageText: String,
    val messageGuid: String,
    val senderName: String?,
    val senderAddress: String? = null,
    val isGroup: Boolean = false,
    val avatarUri: String? = null,
    val linkPreviewTitle: String? = null,
    val linkPreviewDomain: String? = null,
    val participantNames: List<String> = emptyList(),
    val participantAvatarPaths: List<String?> = emptyList(),
    val groupAvatarPath: String? = null,
    val subject: String? = null,
    val attachmentUri: android.net.Uri? = null,
    val attachmentMimeType: String? = null
)

/**
 * Interface for notification operations.
 * Allows mocking in tests without modifying the concrete implementation.
 *
 * This interface defines the contract for showing and managing notifications:
 * - Message notifications with bubble support
 * - System notifications (sync complete, server updates)
 * - FaceTime call notifications
 *
 * Implementation: [NotificationService]
 */
interface Notifier {

    /**
     * Show a notification for a new message.
     */
    fun showMessageNotification(params: MessageNotificationParams)

    /**
     * Cancel notification for a specific chat.
     */
    fun cancelNotification(chatGuid: String)

    /**
     * Cancel all message notifications.
     */
    fun cancelAllNotifications()

    /**
     * Create the foreground service notification.
     */
    fun createServiceNotification(): android.app.Notification

    /**
     * Show notification when BlueBubbles initial sync completes.
     */
    fun showBlueBubblesSyncCompleteNotification(messageCount: Int)

    /**
     * Show notification when SMS import completes.
     */
    fun showSmsImportCompleteNotification()

    /**
     * Show notification when a BlueBubbles server update is available.
     */
    fun showServerUpdateNotification(version: String)

    /**
     * Show notification when iCloud account status changes.
     */
    fun showICloudAccountNotification(active: Boolean, alias: String?)

    /**
     * Show incoming FaceTime call notification.
     */
    fun showFaceTimeCallNotification(
        callUuid: String,
        callerName: String,
        callerAddress: String?
    )

    /**
     * Dismiss FaceTime call notification.
     */
    fun dismissFaceTimeCallNotification(callUuid: String)

    /**
     * Update the app badge count.
     *
     * On Android 8.0+, badge counts are derived from notifications.
     * This method updates a group summary notification with setNumber(count)
     * which is the most reliable cross-device way to set badge counts.
     *
     * Also sends manufacturer-specific broadcasts for Samsung/Sony devices.
     *
     * @param count The total unread message count to display on the app badge
     */
    fun updateAppBadge(count: Int)

    /**
     * Show notification when a message fails to deliver.
     *
     * @param chatGuid The chat where the message failed
     * @param chatTitle Display name of the conversation
     * @param messagePreview Preview of the failed message text
     * @param errorMessage The error reason (shown in expanded notification)
     */
    fun showMessageFailedNotification(
        chatGuid: String,
        chatTitle: String,
        messagePreview: String?,
        errorMessage: String
    )

    /**
     * Show grouped notification when multiple messages fail to deliver in the same chat.
     * This prevents notification spam when a message fails and cascades to dependent messages.
     *
     * @param chatGuid The chat where messages failed
     * @param chatTitle Display name of the conversation
     * @param failedCount Total number of failed messages
     * @param errorMessage The original error reason
     */
    fun showMessagesFailedNotification(
        chatGuid: String,
        chatTitle: String,
        failedCount: Int,
        errorMessage: String
    )
}
