package com.bothbubbles.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations for the app
 */
sealed interface Screen {

    @Serializable
    data class Setup(
        val skipWelcome: Boolean = false,
        val skipSmsSetup: Boolean = false
    ) : Screen

    @Serializable
    data object Conversations : Screen

    @Serializable
    data class Chat(
        val chatGuid: String,
        // Comma-separated list of all merged chat GUIDs (for merged iMessage+SMS conversations)
        val mergedGuids: String? = null,
        // Target message GUID to scroll to and highlight (from notification deep-link)
        val targetMessageGuid: String? = null
    ) : Screen

    @Serializable
    data class ChatCreator(
        val initialAddress: String? = null,
        val initialAttachments: List<String> = emptyList()
    ) : Screen

    /**
     * Apple-style compose screen with recipient field and inline conversation.
     */
    @Serializable
    data object Compose : Screen

    @Serializable
    data class GroupCreator(
        val preSelectedAddress: String? = null,
        val preSelectedDisplayName: String? = null,
        val preSelectedService: String? = null,
        val preSelectedAvatarPath: String? = null
    ) : Screen

    @Serializable
    data class GroupSetup(
        val participantsJson: String,  // JSON-encoded List of participants
        val groupService: String       // "IMESSAGE" or "MMS"
    ) : Screen

    @Serializable
    data class AttachmentEdit(val uri: String) : Screen

    @Serializable
    data class ChatDetails(val chatGuid: String) : Screen

    @Serializable
    data class ChatNotificationSettings(val chatGuid: String) : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data class SmsSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class SmsBackup(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class ServerSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class ArchivedChats(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class BlockedContacts(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class SyncSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class NotificationSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class NotificationProvider(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class BubbleChatSelector(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class About(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data object OpenSourceLicenses : Screen

    @Serializable
    data class SwipeSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class EffectsSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class QuickReplyTemplates(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class SpamSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class CategorizationSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class AutoResponderSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class EtaSharingSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class Life360Settings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class ImageQualitySettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class MediaViewer(
        val attachmentGuid: String,
        val chatGuid: String
    ) : Screen

    @Serializable
    data class MediaGallery(
        val chatGuid: String,
        val mediaType: String = "images" // "images", "videos", or "all"
    ) : Screen

    @Serializable
    data class LinksGallery(val chatGuid: String) : Screen

    @Serializable
    data class PlacesGallery(val chatGuid: String) : Screen

    @Serializable
    data class SharePicker(
        val sharedText: String? = null,
        val sharedUris: List<String> = emptyList()
    ) : Screen

    @Serializable
    data class ExportMessages(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data object DeveloperEventLog : Screen

    /**
     * Full-screen Life360 map view showing contact's location with navigation options.
     * @param participantAddress The phone number/address to look up the Life360 member
     */
    @Serializable
    data class Life360Map(val participantAddress: String) : Screen
}
