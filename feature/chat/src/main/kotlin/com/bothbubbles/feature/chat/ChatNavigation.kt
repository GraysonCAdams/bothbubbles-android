package com.bothbubbles.feature.chat

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.bothbubbles.navigation.Route

/**
 * Navigation extension for chat feature.
 *
 * This defines how the chat screen is added to the navigation graph.
 * The actual ChatScreen composable will be moved here from the app module.
 */
fun NavGraphBuilder.chatNavigation(
    navController: NavController,
    onNavigateBack: () -> Unit = { navController.navigateUp() },
    onNavigateToChatDetails: (chatGuid: String) -> Unit = {}
) {
    composable<Route.Chat> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.Chat>()
        // TODO: Move ChatScreen here from app module
        // ChatScreen(
        //     chatGuid = route.chatGuid,
        //     mergedGuids = route.mergedGuids,
        //     targetMessageGuid = route.targetMessageGuid,
        //     onNavigateBack = onNavigateBack,
        //     onNavigateToChatDetails = { onNavigateToChatDetails(route.chatGuid) }
        // )
    }
}
