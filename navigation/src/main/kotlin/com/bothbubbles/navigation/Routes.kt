package com.bothbubbles.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for the app.
 *
 * This sealed interface defines all navigation destinations that can be used
 * across feature modules. Feature modules depend on this module to define
 * their navigation contracts without depending on each other.
 */
sealed interface Route {

    // ===== Setup =====

    @Serializable
    data class Setup(
        val skipWelcome: Boolean = false,
        val skipSmsSetup: Boolean = false
    ) : Route

    // ===== Conversations =====

    @Serializable
    data object Conversations : Route

    // ===== Chat =====

    @Serializable
    data class Chat(
        val chatGuid: String,
        /** Comma-separated list of all merged chat GUIDs (for merged iMessage+SMS conversations) */
        val mergedGuids: String? = null,
        /** Target message GUID to scroll to and highlight (from notification deep-link) */
        val targetMessageGuid: String? = null
    ) : Route

    @Serializable
    data class ChatCreator(
        val initialAddress: String? = null,
        val initialAttachments: List<String> = emptyList()
    ) : Route

    @Serializable
    data class GroupCreator(
        val preSelectedAddress: String? = null,
        val preSelectedDisplayName: String? = null,
        val preSelectedService: String? = null,
        val preSelectedAvatarPath: String? = null
    ) : Route

    @Serializable
    data class GroupSetup(
        val participantsJson: String,
        val groupService: String
    ) : Route

    @Serializable
    data class AttachmentEdit(val uri: String) : Route

    @Serializable
    data class ChatDetails(val chatGuid: String) : Route

    @Serializable
    data class ChatNotificationSettings(val chatGuid: String) : Route

    // ===== Settings =====

    @Serializable
    data object Settings : Route

    @Serializable
    data class SmsSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class SmsBackup(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class ServerSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class ArchivedChats(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class BlockedContacts(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class SyncSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class NotificationSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class NotificationProvider(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class BubbleChatSelector(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class About(val returnToSettings: Boolean = false) : Route

    @Serializable
    data object OpenSourceLicenses : Route

    @Serializable
    data class SwipeSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class EffectsSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class QuickReplyTemplates(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class SpamSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class CategorizationSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class AutoResponderSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class EtaSharingSettings(val returnToSettings: Boolean = false) : Route

    @Serializable
    data class ImageQualitySettings(val returnToSettings: Boolean = false) : Route

    // ===== Media =====

    @Serializable
    data class MediaViewer(
        val attachmentGuid: String,
        val chatGuid: String
    ) : Route

    @Serializable
    data class MediaGallery(
        val chatGuid: String,
        val mediaType: String = "images"
    ) : Route

    @Serializable
    data class LinksGallery(val chatGuid: String) : Route

    @Serializable
    data class PlacesGallery(val chatGuid: String) : Route

    @Serializable
    data class Camera(val chatGuid: String) : Route

    // ===== Sharing =====

    @Serializable
    data class SharePicker(
        val sharedText: String? = null,
        val sharedUris: List<String> = emptyList()
    ) : Route

    @Serializable
    data class ExportMessages(val returnToSettings: Boolean = false) : Route

    // ===== Developer =====

    @Serializable
    data object DeveloperEventLog : Route
}
