package com.bluebubbles.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations for the app
 */
sealed interface Screen {

    @Serializable
    data object Setup : Screen

    @Serializable
    data object Conversations : Screen

    @Serializable
    data class Chat(val chatGuid: String) : Screen

    @Serializable
    data class ChatCreator(
        val initialAddress: String? = null,
        val initialAttachments: List<String> = emptyList()
    ) : Screen

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
    data class ChatDetails(val chatGuid: String) : Screen

    @Serializable
    data class ChatNotificationSettings(val chatGuid: String) : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data class SmsSettings(val returnToSettings: Boolean = false) : Screen

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
    data class About(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class SwipeSettings(val returnToSettings: Boolean = false) : Screen

    @Serializable
    data class EffectsSettings(val returnToSettings: Boolean = false) : Screen

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
    data object Search : Screen

    @Serializable
    data class Camera(val chatGuid: String) : Screen
}
