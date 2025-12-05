package com.bluebubbles.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bluebubbles.ui.conversations.ConversationsScreen
import com.bluebubbles.ui.chat.ChatScreen
import com.bluebubbles.ui.chat.details.ChatNotificationSettingsScreen
import com.bluebubbles.ui.chat.details.ConversationDetailsScreen
import com.bluebubbles.ui.chat.details.LinksScreen
import com.bluebubbles.ui.chat.details.MediaGalleryScreen
import com.bluebubbles.ui.chat.details.MediaLinksScreen
import com.bluebubbles.ui.chat.details.PlacesScreen
import com.bluebubbles.ui.settings.SettingsScreen
import com.bluebubbles.ui.settings.sms.SmsSettingsScreen
import com.bluebubbles.ui.chatcreator.ChatCreatorScreen
import com.bluebubbles.ui.camera.InAppCameraScreen

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
                    // No longer used - settings panel slides in from right
                },
                onSettingsNavigate = { destination ->
                    // Handle navigation from the sliding settings panel
                    when (destination) {
                        "server" -> navController.navigate(Screen.ServerSettings)
                        "archived" -> navController.navigate(Screen.ArchivedChats)
                        "blocked" -> navController.navigate(Screen.BlockedContacts)
                        "sync" -> navController.navigate(Screen.SyncSettings)
                        "sms" -> navController.navigate(Screen.SmsSettings)
                        "notifications" -> navController.navigate(Screen.NotificationSettings)
                        "swipe" -> navController.navigate(Screen.SwipeSettings)
                        "about" -> navController.navigate(Screen.About)
                    }
                }
            )
        }

        // Individual chat
        composable<Screen.Chat> { backStackEntry ->
            val route: Screen.Chat = backStackEntry.toRoute()

            // Handle captured photo from camera screen
            val capturedPhotoUri = backStackEntry.savedStateHandle
                .getStateFlow<String?>("captured_photo_uri", null)
                .collectAsState()

            ChatScreen(
                chatGuid = route.chatGuid,
                onBackClick = { navController.popBackStack() },
                onDetailsClick = {
                    navController.navigate(Screen.ChatDetails(route.chatGuid))
                },
                onMediaClick = { attachmentGuid ->
                    navController.navigate(Screen.MediaViewer(attachmentGuid, route.chatGuid))
                },
                onCameraClick = {
                    navController.navigate(Screen.Camera(route.chatGuid))
                },
                capturedPhotoUri = capturedPhotoUri.value?.toUri(),
                onCapturedPhotoHandled = {
                    backStackEntry.savedStateHandle.remove<String>("captured_photo_uri")
                }
            )
        }

        // New chat creator
        composable<Screen.ChatCreator> { backStackEntry ->
            val route: Screen.ChatCreator = backStackEntry.toRoute()
            ChatCreatorScreen(
                onBackClick = { navController.popBackStack() },
                onChatCreated = { chatGuid ->
                    navController.navigate(Screen.Chat(chatGuid)) {
                        popUpTo(Screen.Conversations) { inclusive = false }
                    }
                },
                onCreateGroupClick = {
                    // TODO: Navigate to group creation screen
                }
            )
        }

        // Chat details
        composable<Screen.ChatDetails> { backStackEntry ->
            val route: Screen.ChatDetails = backStackEntry.toRoute()
            ConversationDetailsScreen(
                chatGuid = route.chatGuid,
                onNavigateBack = { navController.popBackStack() },
                onSearchClick = {
                    // TODO: Navigate to search in conversation
                },
                onMediaGalleryClick = { mediaType ->
                    navController.navigate(Screen.MediaGallery(route.chatGuid, mediaType))
                },
                onNotificationSettingsClick = {
                    navController.navigate(Screen.ChatNotificationSettings(route.chatGuid))
                }
            )
        }

        // Chat notification settings
        composable<Screen.ChatNotificationSettings> { backStackEntry ->
            val route: Screen.ChatNotificationSettings = backStackEntry.toRoute()
            ChatNotificationSettingsScreen(
                chatGuid = route.chatGuid,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Settings
        composable<Screen.Settings> {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onServerSettingsClick = { navController.navigate(Screen.ServerSettings) },
                onArchivedClick = { navController.navigate(Screen.ArchivedChats) },
                onBlockedClick = { navController.navigate(Screen.BlockedContacts) },
                onSyncSettingsClick = { navController.navigate(Screen.SyncSettings) },
                onSmsSettingsClick = { navController.navigate(Screen.SmsSettings) },
                onNotificationsClick = { navController.navigate(Screen.NotificationSettings) },
                onSwipeSettingsClick = { navController.navigate(Screen.SwipeSettings) },
                onAboutClick = { navController.navigate(Screen.About) }
            )
        }

        // SMS Settings
        composable<Screen.SmsSettings> {
            SmsSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Server Settings
        composable<Screen.ServerSettings> {
            com.bluebubbles.ui.settings.server.ServerSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Archived Chats
        composable<Screen.ArchivedChats> {
            com.bluebubbles.ui.settings.archived.ArchivedChatsScreen(
                onNavigateBack = { navController.popBackStack() },
                onChatClick = { chatGuid ->
                    navController.navigate(Screen.Chat(chatGuid))
                }
            )
        }

        // Blocked Contacts
        composable<Screen.BlockedContacts> {
            com.bluebubbles.ui.settings.blocked.BlockedContactsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Sync Settings
        composable<Screen.SyncSettings> {
            com.bluebubbles.ui.settings.sync.SyncSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Notification Settings
        composable<Screen.NotificationSettings> {
            com.bluebubbles.ui.settings.notifications.NotificationSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // About
        composable<Screen.About> {
            com.bluebubbles.ui.settings.about.AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Swipe Settings
        composable<Screen.SwipeSettings> {
            com.bluebubbles.ui.settings.swipe.SwipeSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Media viewer
        composable<Screen.MediaViewer> { backStackEntry ->
            val route: Screen.MediaViewer = backStackEntry.toRoute()
            // TODO: MediaViewerScreen
            SettingsScreen(onBackClick = { navController.popBackStack() })
        }

        // Media gallery (images/videos grid or links overview)
        composable<Screen.MediaGallery> { backStackEntry ->
            val route: Screen.MediaGallery = backStackEntry.toRoute()
            if (route.mediaType == "all") {
                // Show the overview with sections for images, videos, places, links
                MediaLinksScreen(
                    chatGuid = route.chatGuid,
                    onNavigateBack = { navController.popBackStack() },
                    onMediaClick = { attachmentGuid ->
                        navController.navigate(Screen.MediaViewer(attachmentGuid, route.chatGuid))
                    },
                    onSeeAllImages = {
                        navController.navigate(Screen.MediaGallery(route.chatGuid, "images"))
                    },
                    onSeeAllVideos = {
                        navController.navigate(Screen.MediaGallery(route.chatGuid, "videos"))
                    },
                    onSeeAllPlaces = {
                        navController.navigate(Screen.PlacesGallery(route.chatGuid))
                    },
                    onSeeAllLinks = {
                        navController.navigate(Screen.LinksGallery(route.chatGuid))
                    }
                )
            } else {
                // Show the grid view for specific media type
                MediaGalleryScreen(
                    chatGuid = route.chatGuid,
                    onNavigateBack = { navController.popBackStack() },
                    onMediaClick = { attachmentGuid ->
                        navController.navigate(Screen.MediaViewer(attachmentGuid, route.chatGuid))
                    }
                )
            }
        }

        // Links gallery
        composable<Screen.LinksGallery> { backStackEntry ->
            val route: Screen.LinksGallery = backStackEntry.toRoute()
            LinksScreen(
                chatGuid = route.chatGuid,
                onNavigateBack = { navController.popBackStack() },
                onLinkClick = { url ->
                    // TODO: Open URL in browser
                }
            )
        }

        // Places gallery
        composable<Screen.PlacesGallery> { backStackEntry ->
            val route: Screen.PlacesGallery = backStackEntry.toRoute()
            PlacesScreen(
                chatGuid = route.chatGuid,
                onNavigateBack = { navController.popBackStack() },
                onPlaceClick = { placeId ->
                    // TODO: Open place details
                }
            )
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

        // In-app camera
        composable<Screen.Camera> { backStackEntry ->
            val route: Screen.Camera = backStackEntry.toRoute()
            InAppCameraScreen(
                chatGuid = route.chatGuid,
                onClose = { navController.popBackStack() },
                onPhotoTaken = { uri ->
                    // Navigate back to chat with the photo URI
                    // The URI is passed via saved state handle
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("captured_photo_uri", uri.toString())
                    navController.popBackStack()
                }
            )
        }
    }
}
