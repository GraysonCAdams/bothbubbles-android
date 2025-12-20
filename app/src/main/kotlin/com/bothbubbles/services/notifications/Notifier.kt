package com.bothbubbles.services.notifications

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
     * @param subject Optional message subject (for iMessage). When present, shows ONLY the subject.
     * @param attachmentUri Optional content:// URI to an attachment image/video for inline preview
     * @param attachmentMimeType MIME type of the attachment (required if attachmentUri is provided)
     */
    fun showMessageNotification(
        chatGuid: String,
        chatTitle: String,
        messageText: String,
        messageGuid: String,
        senderName: String?,
        senderAddress: String? = null,
        isGroup: Boolean = false,
        avatarUri: String? = null,
        linkPreviewTitle: String? = null,
        linkPreviewDomain: String? = null,
        participantNames: List<String> = emptyList(),
        participantAvatarPaths: List<String?> = emptyList(),
        subject: String? = null,
        attachmentUri: android.net.Uri? = null,
        attachmentMimeType: String? = null
    )

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
}
