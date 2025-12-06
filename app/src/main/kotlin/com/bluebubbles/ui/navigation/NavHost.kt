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
import androidx.compose.runtime.remember
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
import com.bluebubbles.ui.chatcreator.GroupCreatorScreen
import com.bluebubbles.ui.camera.InAppCameraScreen

private const val TRANSITION_DURATION = 300

@Composable
fun BlueBubblesNavHost(
    navController: NavHostController = rememberNavController()
) {
    // TODO: Check if setup is complete to determine start destination
    val startDestination: Screen = Screen.Conversations

    fun popBackStackReturningToSettings(returnToSettings: Boolean) {
        if (returnToSettings) {
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.set("open_settings_panel", true)
        }
        navController.popBackStack()
    }

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
        composable<Screen.Conversations> { backStackEntry ->
            val reopenSettingsPanelFlow = remember(backStackEntry) {
                backStackEntry.savedStateHandle.getStateFlow("open_settings_panel", false)
            }
            val reopenSettingsPanel by reopenSettingsPanelFlow.collectAsState(initial = false)

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
                onSettingsNavigate = { destination, returnToSettings ->
                    val screen = when (destination) {
                        "server" -> Screen.ServerSettings(returnToSettings)
                        "archived" -> Screen.ArchivedChats(returnToSettings)
                        "blocked" -> Screen.BlockedContacts(returnToSettings)
                        "sync" -> Screen.SyncSettings(returnToSettings)
                        "sms" -> Screen.SmsSettings(returnToSettings)
                        "notifications" -> Screen.NotificationSettings(returnToSettings)
                        "swipe" -> Screen.SwipeSettings(returnToSettings)
                        "effects" -> Screen.EffectsSettings(returnToSettings)
                        "about" -> Screen.About(returnToSettings)
                        else -> null
                    }

                    screen?.let { navController.navigate(it) }
                },
                reopenSettingsPanel = reopenSettingsPanel,
                onSettingsPanelHandled = {
                    backStackEntry.savedStateHandle["open_settings_panel"] = false
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
                onNavigateToGroupSetup = { participantsJson, groupService ->
                    navController.navigate(Screen.GroupSetup(participantsJson, groupService))
                }
            )
        }

        // Group creator (Step 1: Contact picker)
        composable<Screen.GroupCreator> {
            GroupCreatorScreen(
                onBackClick = { navController.popBackStack() },
                onNextClick = { participantsJson, groupService ->
                    navController.navigate(Screen.GroupSetup(participantsJson, groupService))
                }
            )
        }

        // Group setup (Step 2: Name/photo configuration)
        composable<Screen.GroupSetup> { backStackEntry ->
            val route: Screen.GroupSetup = backStackEntry.toRoute()
            com.bluebubbles.ui.chatcreator.GroupSetupScreen(
                onBackClick = { navController.popBackStack() },
                onGroupCreated = { chatGuid ->
                    navController.navigate(Screen.Chat(chatGuid)) {
                        popUpTo(Screen.Conversations) { inclusive = false }
                    }
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
                },
                onCreateGroupClick = { address, displayName, service, avatarPath ->
                    navController.navigate(Screen.GroupCreator(
                        preSelectedAddress = address,
                        preSelectedDisplayName = displayName,
                        preSelectedService = service,
                        preSelectedAvatarPath = avatarPath
                    ))
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
                onServerSettingsClick = { navController.navigate(Screen.ServerSettings(returnToSettings = true)) },
                onArchivedClick = { navController.navigate(Screen.ArchivedChats(returnToSettings = true)) },
                onBlockedClick = { navController.navigate(Screen.BlockedContacts(returnToSettings = true)) },
                onSyncSettingsClick = { navController.navigate(Screen.SyncSettings(returnToSettings = true)) },
                onSmsSettingsClick = { navController.navigate(Screen.SmsSettings(returnToSettings = true)) },
                onNotificationsClick = { navController.navigate(Screen.NotificationSettings(returnToSettings = true)) },
                onSwipeSettingsClick = { navController.navigate(Screen.SwipeSettings(returnToSettings = true)) },
                onEffectsSettingsClick = { navController.navigate(Screen.EffectsSettings(returnToSettings = true)) },
                onAboutClick = { navController.navigate(Screen.About(returnToSettings = true)) }
            )
        }

        // SMS Settings
        composable<Screen.SmsSettings> { backStackEntry ->
            val route: Screen.SmsSettings = backStackEntry.toRoute()
            SmsSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Server Settings
        composable<Screen.ServerSettings> { backStackEntry ->
            val route: Screen.ServerSettings = backStackEntry.toRoute()
            com.bluebubbles.ui.settings.server.ServerSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Archived Chats
        composable<Screen.ArchivedChats> { backStackEntry ->
            val route: Screen.ArchivedChats = backStackEntry.toRoute()
            com.bluebubbles.ui.settings.archived.ArchivedChatsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                },
                onChatClick = { chatGuid ->
                    navController.navigate(Screen.Chat(chatGuid))
                }
            )
        }

        // Blocked Contacts
        composable<Screen.BlockedContacts> { backStackEntry ->
            val route: Screen.BlockedContacts = backStackEntry.toRoute()
            com.bluebubbles.ui.settings.blocked.BlockedContactsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Sync Settings
        composable<Screen.SyncSettings> { backStackEntry ->
            val route: Screen.SyncSettings = backStackEntry.toRoute()
            com.bluebubbles.ui.settings.sync.SyncSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Notification Settings
        composable<Screen.NotificationSettings> { backStackEntry ->
            val route: Screen.NotificationSettings = backStackEntry.toRoute()
            com.bluebubbles.ui.settings.notifications.NotificationSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // About
        composable<Screen.About> { backStackEntry ->
            val route: Screen.About = backStackEntry.toRoute()
            com.bluebubbles.ui.settings.about.AboutScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Swipe Settings
        composable<Screen.SwipeSettings> { backStackEntry ->
            val route: Screen.SwipeSettings = backStackEntry.toRoute()
            com.bluebubbles.ui.settings.swipe.SwipeSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Effects Settings
        composable<Screen.EffectsSettings> { backStackEntry ->
            val route: Screen.EffectsSettings = backStackEntry.toRoute()
            com.bluebubbles.ui.settings.EffectsSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
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
