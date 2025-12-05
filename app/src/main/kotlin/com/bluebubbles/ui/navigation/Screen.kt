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
    data class ChatDetails(val chatGuid: String) : Screen

    @Serializable
    data class ChatNotificationSettings(val chatGuid: String) : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object SmsSettings : Screen

    @Serializable
    data object ServerSettings : Screen

    @Serializable
    data object ArchivedChats : Screen

    @Serializable
    data object BlockedContacts : Screen

    @Serializable
    data object SyncSettings : Screen

    @Serializable
    data object NotificationSettings : Screen

    @Serializable
    data object About : Screen

    @Serializable
    data object SwipeSettings : Screen

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
