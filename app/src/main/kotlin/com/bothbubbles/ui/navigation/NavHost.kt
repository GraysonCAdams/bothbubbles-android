package com.bothbubbles.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.bothbubbles.ui.theme.MotionTokens
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bothbubbles.ui.conversations.ConversationsScreen
import com.bothbubbles.ui.chat.ChatScreen
import com.bothbubbles.ui.chat.details.ChatNotificationSettingsScreen
import com.bothbubbles.ui.chat.details.ConversationDetailsScreen
import com.bothbubbles.ui.chat.details.LinksScreen
import com.bothbubbles.ui.chat.details.MediaGalleryScreen
import com.bothbubbles.ui.chat.details.MediaLinksScreen
import com.bothbubbles.ui.chat.details.PlacesScreen
import com.bothbubbles.ui.settings.SettingsScreen
import com.bothbubbles.ui.settings.sms.SmsSettingsScreen
import com.bothbubbles.ui.chatcreator.ChatCreatorScreen
import com.bothbubbles.ui.chatcreator.GroupCreatorScreen
import com.bothbubbles.ui.camera.InAppCameraScreen
import com.bothbubbles.ui.media.MediaViewerScreen
import com.bothbubbles.ui.setup.SetupScreen
import com.bothbubbles.ui.share.SharePickerScreen
import com.bothbubbles.data.local.prefs.SettingsDataStore

// MD3 motion tokens for navigation transitions
private val ENTER_DURATION = MotionTokens.Duration.MEDIUM_4
private val EXIT_DURATION = MotionTokens.Duration.MEDIUM_2

/**
 * Data class to hold share intent data parsed from MainActivity
 */
data class ShareIntentData(
    val sharedText: String? = null,
    val sharedUris: List<Uri> = emptyList()
)

/**
 * Data class to hold state restoration data for resuming previous session
 */
data class StateRestorationData(
    val chatGuid: String,
    val mergedGuids: String? = null,
    val scrollPosition: Int = 0,
    val scrollOffset: Int = 0
)

@Composable
fun BothBubblesNavHost(
    navController: NavHostController = rememberNavController(),
    isSetupComplete: Boolean = true,
    shareIntentData: ShareIntentData? = null,
    stateRestorationData: StateRestorationData? = null
) {
    // Determine start destination based on setup status and share intent
    val startDestination: Screen = when {
        !isSetupComplete -> Screen.Setup()
        shareIntentData != null -> Screen.SharePicker(
            sharedText = shareIntentData.sharedText,
            sharedUris = shareIntentData.sharedUris.map { it.toString() }
        )
        else -> Screen.Conversations
    }

    // Handle state restoration: navigate to saved chat after initial composition
    LaunchedEffect(stateRestorationData) {
        if (stateRestorationData != null && isSetupComplete && shareIntentData == null) {
            // Navigate to the restored chat
            navController.navigate(Screen.Chat(stateRestorationData.chatGuid, stateRestorationData.mergedGuids)) {
                // Keep Conversations in the back stack so back button works
                launchSingleTop = true
            }
            // Set scroll position on the chat entry's saved state handle
            try {
                val chatEntry = navController.getBackStackEntry(
                    Screen.Chat(stateRestorationData.chatGuid, stateRestorationData.mergedGuids)
                )
                chatEntry.savedStateHandle["restore_scroll_position"] = stateRestorationData.scrollPosition
                chatEntry.savedStateHandle["restore_scroll_offset"] = stateRestorationData.scrollOffset
            } catch (_: Exception) {
                // Entry might not be found immediately
            }
        }
    }

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
            fadeIn(tween(ENTER_DURATION, easing = MotionTokens.Easing.EmphasizedDecelerate)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(ENTER_DURATION, easing = MotionTokens.Easing.EmphasizedDecelerate),
                    initialOffset = { it / 4 } // Subtle 25% slide
                )
        },
        exitTransition = {
            fadeOut(tween(EXIT_DURATION, easing = MotionTokens.Easing.EmphasizedAccelerate)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(EXIT_DURATION, easing = MotionTokens.Easing.EmphasizedAccelerate),
                    targetOffset = { it / 10 } // Minimal exit movement
                )
        },
        popEnterTransition = {
            fadeIn(tween(ENTER_DURATION, easing = MotionTokens.Easing.EmphasizedDecelerate)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(ENTER_DURATION, easing = MotionTokens.Easing.EmphasizedDecelerate),
                    initialOffset = { it / 10 } // Minimal re-entry movement
                )
        },
        popExitTransition = {
            fadeOut(tween(EXIT_DURATION, easing = MotionTokens.Easing.EmphasizedAccelerate)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(EXIT_DURATION, easing = MotionTokens.Easing.EmphasizedAccelerate),
                    targetOffset = { it / 4 } // 25% slide out
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
                onConversationClick = { chatGuid, mergedGuids ->
                    // Pass merged GUIDs as comma-separated string for merged conversations
                    val mergedGuidsStr = if (mergedGuids.size > 1) mergedGuids.joinToString(",") else null
                    navController.navigate(Screen.Chat(chatGuid, mergedGuidsStr))
                },
                onNewMessageClick = {
                    navController.navigate(Screen.ChatCreator())
                },
                onSettingsClick = {
                    // No longer used - settings panel slides in from right
                },
                onSettingsNavigate = { destination, returnToSettings ->
                    val screen: Screen? = when (destination) {
                        "server" -> Screen.ServerSettings(returnToSettings)
                        "setup" -> Screen.Setup(skipWelcome = true, skipSmsSetup = true)
                        "archived" -> Screen.ArchivedChats(returnToSettings)
                        "blocked" -> Screen.BlockedContacts(returnToSettings)
                        "sync" -> Screen.SyncSettings(returnToSettings)
                        "sms" -> Screen.SmsSettings(returnToSettings)
                        "notifications" -> Screen.NotificationSettings(returnToSettings)
                        "swipe" -> Screen.SwipeSettings(returnToSettings)
                        "effects" -> Screen.EffectsSettings(returnToSettings)
                        "templates" -> Screen.QuickReplyTemplates(returnToSettings)
                        "spam" -> Screen.SpamSettings(returnToSettings)
                        "categorization" -> Screen.CategorizationSettings(returnToSettings)
                        "about" -> Screen.About(returnToSettings)
                        "export" -> Screen.ExportMessages(returnToSettings)
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

            // Handle shared content from share picker
            val sharedText = backStackEntry.savedStateHandle
                .getStateFlow<String?>("shared_text", null)
                .collectAsState()
            val sharedUris = backStackEntry.savedStateHandle
                .getStateFlow<ArrayList<String>?>("shared_uris", null)
                .collectAsState()

            // Handle search activation from ChatDetails screen
            val activateSearch = backStackEntry.savedStateHandle
                .getStateFlow("activate_search", false)
                .collectAsState()

            // Handle scroll position restoration
            val restoreScrollPosition = backStackEntry.savedStateHandle
                .getStateFlow("restore_scroll_position", 0)
                .collectAsState()
            val restoreScrollOffset = backStackEntry.savedStateHandle
                .getStateFlow("restore_scroll_offset", 0)
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
                },
                sharedText = sharedText.value,
                sharedUris = sharedUris.value?.map { it.toUri() } ?: emptyList(),
                onSharedContentHandled = {
                    backStackEntry.savedStateHandle.remove<String>("shared_text")
                    backStackEntry.savedStateHandle.remove<ArrayList<String>>("shared_uris")
                },
                activateSearch = activateSearch.value,
                onSearchActivated = {
                    backStackEntry.savedStateHandle["activate_search"] = false
                },
                initialScrollPosition = restoreScrollPosition.value,
                initialScrollOffset = restoreScrollOffset.value,
                onScrollPositionRestored = {
                    backStackEntry.savedStateHandle.remove<Int>("restore_scroll_position")
                    backStackEntry.savedStateHandle.remove<Int>("restore_scroll_offset")
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
            com.bothbubbles.ui.chatcreator.GroupSetupScreen(
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
                    // Set activate_search flag on Chat screen and pop back
                    navController.previousBackStackEntry?.savedStateHandle?.set("activate_search", true)
                    navController.popBackStack()
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
                onExportClick = { navController.navigate(Screen.ExportMessages(returnToSettings = true)) },
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
                },
                onBackupRestoreClick = {
                    navController.navigate(Screen.SmsBackup(returnToSettings = route.returnToSettings))
                }
            )
        }

        // SMS Backup & Restore
        composable<Screen.SmsBackup> { backStackEntry ->
            val route: Screen.SmsBackup = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.sms.SmsBackupScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Server Settings
        composable<Screen.ServerSettings> { backStackEntry ->
            val route: Screen.ServerSettings = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.server.ServerSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Archived Chats
        composable<Screen.ArchivedChats> { backStackEntry ->
            val route: Screen.ArchivedChats = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.archived.ArchivedChatsScreen(
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
            com.bothbubbles.ui.settings.blocked.BlockedContactsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Sync Settings
        composable<Screen.SyncSettings> { backStackEntry ->
            val route: Screen.SyncSettings = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.sync.SyncSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Notification Settings
        composable<Screen.NotificationSettings> { backStackEntry ->
            val route: Screen.NotificationSettings = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.notifications.NotificationSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // About
        composable<Screen.About> { backStackEntry ->
            val route: Screen.About = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.about.AboutScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                },
                onOpenSourceLicensesClick = {
                    navController.navigate(Screen.OpenSourceLicenses)
                }
            )
        }

        // Open Source Licenses
        composable<Screen.OpenSourceLicenses> {
            com.bothbubbles.ui.settings.about.OpenSourceLicensesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Swipe Settings
        composable<Screen.SwipeSettings> { backStackEntry ->
            val route: Screen.SwipeSettings = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.swipe.SwipeSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Effects Settings
        composable<Screen.EffectsSettings> { backStackEntry ->
            val route: Screen.EffectsSettings = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.EffectsSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Quick Reply Templates
        composable<Screen.QuickReplyTemplates> { backStackEntry ->
            val route: Screen.QuickReplyTemplates = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.templates.QuickReplyTemplatesScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Spam Settings
        composable<Screen.SpamSettings> { backStackEntry ->
            val route: Screen.SpamSettings = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.spam.SpamSettingsScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Categorization Settings
        composable<Screen.CategorizationSettings> { backStackEntry ->
            val route: Screen.CategorizationSettings = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.categorization.CategorizationSettingsScreen(
                onBackClick = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Media viewer
        composable<Screen.MediaViewer> { backStackEntry ->
            MediaViewerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
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
            val context = LocalContext.current
            LinksScreen(
                chatGuid = route.chatGuid,
                onNavigateBack = { navController.popBackStack() },
                onLinkClick = { url ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                }
            )
        }

        // Places gallery
        composable<Screen.PlacesGallery> { backStackEntry ->
            val route: Screen.PlacesGallery = backStackEntry.toRoute()
            val context = LocalContext.current
            PlacesScreen(
                chatGuid = route.chatGuid,
                onNavigateBack = { navController.popBackStack() },
                onPlaceClick = { placeUrl ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(placeUrl))
                    context.startActivity(intent)
                }
            )
        }

        // Search - Note: Main search is integrated into ConversationsScreen
        // This route is currently unused but kept for potential future global search
        composable<Screen.Search> {
            SettingsScreen(onBackClick = { navController.popBackStack() })
        }

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

        // Export Messages
        composable<Screen.ExportMessages> { backStackEntry ->
            val route: Screen.ExportMessages = backStackEntry.toRoute()
            com.bothbubbles.ui.settings.export.ExportScreen(
                onNavigateBack = {
                    popBackStackReturningToSettings(route.returnToSettings)
                }
            )
        }

        // Developer Event Log
        composable<Screen.DeveloperEventLog> {
            com.bothbubbles.ui.settings.developer.DeveloperEventLogScreen(
                onNavigateBack = { navController.popBackStack() }
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
}
