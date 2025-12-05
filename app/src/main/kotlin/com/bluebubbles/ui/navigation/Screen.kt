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
    data object Settings : Screen

    @Serializable
    data object SmsSettings : Screen

    @Serializable
    data class MediaViewer(
        val attachmentGuid: String,
        val chatGuid: String
    ) : Screen

    @Serializable
    data object Search : Screen
}
