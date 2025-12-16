package com.bothbubbles.feature.conversations

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bothbubbles.navigation.Route

/**
 * Navigation extension for conversations feature.
 *
 * This defines how the conversation list screen is added to the navigation graph.
 * The actual ConversationsScreen composable will be moved here from the app module.
 */
fun NavGraphBuilder.conversationsNavigation(
    navController: NavController,
    onNavigateToChat: (chatGuid: String, mergedGuids: String?) -> Unit = { _, _ -> },
    onNavigateToSettings: () -> Unit = {},
    onNavigateToChatCreator: () -> Unit = {}
) {
    composable<Route.Conversations> {
        // TODO: Move ConversationsScreen here from app module
        // ConversationsScreen(
        //     onConversationClick = { chatGuid, mergedGuids ->
        //         onNavigateToChat(chatGuid, mergedGuids)
        //     },
        //     onNavigateToSettings = onNavigateToSettings,
        //     ...
        // )
    }
}
