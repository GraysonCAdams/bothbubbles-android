package com.bothbubbles.ui.navigation

import androidx.core.net.toUri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.bothbubbles.ui.setup.SetupScreen
import com.bothbubbles.ui.share.SharePickerScreen

/**
 * Setup and sharing-related navigation routes.
 */
fun NavGraphBuilder.setupShareNavigation(navController: NavHostController) {
    // Setup (onboarding)
    composable<Screen.Setup> { backStackEntry ->
        val route: Screen.Setup = backStackEntry.toRoute()
        SetupScreen(
            skipWelcome = route.skipWelcome,
            skipSmsSetup = route.skipSmsSetup,
            onSetupComplete = {
                navController.navigate(Screen.Conversations) {
                    popUpTo(Screen.Setup(route.skipWelcome, route.skipSmsSetup)) { inclusive = true }
                }
            }
        )
    }

    // Share picker - conversation selection for sharing content
    composable<Screen.SharePicker> { backStackEntry ->
        val route: Screen.SharePicker = backStackEntry.toRoute()
        SharePickerScreen(
            sharedText = route.sharedText,
            sharedUris = route.sharedUris.map { it.toUri() },
            onConversationSelected = { chatGuid ->
                // Navigate to chat with shared content
                // Pass shared URIs as strings in a special format that ChatScreen can parse
                val chatRoute = Screen.Chat(chatGuid)
                navController.navigate(chatRoute) {
                    popUpTo(Screen.SharePicker(route.sharedText, route.sharedUris)) { inclusive = true }
                }
                // Set shared content on the destination entry after navigation
                try {
                    val chatEntry = navController.getBackStackEntry(chatRoute)
                    chatEntry.savedStateHandle.apply {
                        route.sharedText?.let { set("shared_text", it) }
                        if (route.sharedUris.isNotEmpty()) {
                            set("shared_uris", ArrayList(route.sharedUris))
                        }
                    }
                } catch (_: Exception) {
                    // Entry might not be found immediately - the Chat screen handles this
                }
            },
            onNewConversation = {
                // Navigate to chat creator with shared content
                val creatorRoute = Screen.ChatCreator(
                    initialAttachments = route.sharedUris
                )
                navController.navigate(creatorRoute) {
                    popUpTo(Screen.SharePicker(route.sharedText, route.sharedUris)) { inclusive = true }
                }
                // Pass shared text via saved state handle
                try {
                    val creatorEntry = navController.getBackStackEntry(creatorRoute)
                    route.sharedText?.let {
                        creatorEntry.savedStateHandle.set("shared_text", it)
                    }
                } catch (_: Exception) {
                    // Entry might not be found immediately
                }
            },
            onCancel = {
                // Just finish the activity - go back to the source app
                navController.popBackStack()
            }
        )
    }
}
