package com.bluebubbles.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bluebubbles.ui.conversations.ConversationsScreen
import com.bluebubbles.ui.chat.ChatScreen
import com.bluebubbles.ui.settings.SettingsScreen
import com.bluebubbles.ui.settings.sms.SmsSettingsScreen

private const val TRANSITION_DURATION = 300

@Composable
fun BlueBubblesNavHost(
    navController: NavHostController = rememberNavController()
) {
    // TODO: Check if setup is complete to determine start destination
    val startDestination: Screen = Screen.Conversations

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            fadeIn(tween(TRANSITION_DURATION)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(TRANSITION_DURATION)
                )
        },
        exitTransition = {
            fadeOut(tween(TRANSITION_DURATION)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(TRANSITION_DURATION)
                )
        },
        popEnterTransition = {
            fadeIn(tween(TRANSITION_DURATION)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(TRANSITION_DURATION)
                )
        },
        popExitTransition = {
            fadeOut(tween(TRANSITION_DURATION)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(TRANSITION_DURATION)
                )
        }
    ) {
        // Conversations list (home)
        composable<Screen.Conversations> {
            ConversationsScreen(
                onConversationClick = { chatGuid ->
                    navController.navigate(Screen.Chat(chatGuid))
                },
                onNewMessageClick = {
                    navController.navigate(Screen.ChatCreator())
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings)
                }
            )
        }

        // Individual chat
        composable<Screen.Chat> { backStackEntry ->
            val route: Screen.Chat = backStackEntry.toRoute()
            ChatScreen(
                chatGuid = route.chatGuid,
                onBackClick = { navController.popBackStack() },
                onDetailsClick = {
                    navController.navigate(Screen.ChatDetails(route.chatGuid))
                },
                onMediaClick = { attachmentGuid ->
                    navController.navigate(Screen.MediaViewer(attachmentGuid, route.chatGuid))
                }
            )
        }

        // New chat creator
        composable<Screen.ChatCreator> { backStackEntry ->
            val route: Screen.ChatCreator = backStackEntry.toRoute()
            // TODO: ChatCreatorScreen - temporary placeholder
            SettingsScreen(onBackClick = { navController.popBackStack() })
        }

        // Chat details
        composable<Screen.ChatDetails> { backStackEntry ->
            val route: Screen.ChatDetails = backStackEntry.toRoute()
            // TODO: ChatDetailsScreen
            SettingsScreen(onBackClick = { navController.popBackStack() })
        }

        // Settings
        composable<Screen.Settings> {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onSmsSettingsClick = { navController.navigate(Screen.SmsSettings) }
            )
        }

        // SMS Settings
        composable<Screen.SmsSettings> {
            SmsSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Media viewer
        composable<Screen.MediaViewer> { backStackEntry ->
            val route: Screen.MediaViewer = backStackEntry.toRoute()
            // TODO: MediaViewerScreen
            SettingsScreen(onBackClick = { navController.popBackStack() })
        }

        // Search
        composable<Screen.Search> {
            // TODO: SearchScreen - temporary placeholder
            SettingsScreen(onBackClick = { navController.popBackStack() })
        }

        // Setup (onboarding)
        composable<Screen.Setup> {
            // TODO: SetupScreen - temporary placeholder
            SettingsScreen(onBackClick = { navController.popBackStack() })
        }
    }
}
