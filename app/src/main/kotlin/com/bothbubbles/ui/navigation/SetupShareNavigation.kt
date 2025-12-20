package com.bothbubbles.ui.navigation

import androidx.core.net.toUri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.bothbubbles.ui.setup.SetupScreen
import com.bothbubbles.ui.share.SharePickerScreen
import timber.log.Timber

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
                navController.navigate(Screen.Chat(chatGuid)) {
                    popUpTo(Screen.SharePicker(route.sharedText, route.sharedUris)) { inclusive = true }
                }
                // Set shared content on current back stack entry
                navController.currentBackStackEntry?.savedStateHandle?.apply {
                    route.sharedText?.let { set(NavigationKeys.SHARED_TEXT, it) }
                    if (route.sharedUris.isNotEmpty()) {
                        set(NavigationKeys.SHARED_URIS, ArrayList(route.sharedUris))
                    }
                } ?: Timber.w("Failed to set shared content: currentBackStackEntry is null")
            },
            onNewConversation = {
                // Navigate to chat creator with shared content
                navController.navigate(
                    Screen.ChatCreator(initialAttachments = route.sharedUris)
                ) {
                    popUpTo(Screen.SharePicker(route.sharedText, route.sharedUris)) { inclusive = true }
                }
                // Pass shared text via current back stack entry
                navController.currentBackStackEntry?.savedStateHandle?.apply {
                    route.sharedText?.let { set(NavigationKeys.SHARED_TEXT, it) }
                } ?: Timber.w("Failed to set shared text: currentBackStackEntry is null")
            },
            onCancel = {
                // Just finish the activity - go back to the source app
                navController.popBackStack()
            }
        )
    }
}
