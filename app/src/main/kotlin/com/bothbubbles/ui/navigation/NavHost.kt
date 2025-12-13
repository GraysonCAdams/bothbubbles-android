package com.bothbubbles.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bothbubbles.ui.conversations.ConversationsScreen
import com.bothbubbles.ui.theme.MotionTokens

// MD3 motion tokens for navigation transitions
private val ENTER_DURATION = MotionTokens.Duration.MEDIUM_4
private val EXIT_DURATION = MotionTokens.Duration.MEDIUM_2

/**
 * Data class to hold share intent data parsed from MainActivity
 */
data class ShareIntentData(
    val sharedText: String? = null,
    val sharedUris: List<Uri> = emptyList(),
    val directShareChatGuid: String? = null  // Pre-selected chat from sharing shortcut
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

/**
 * Data class to hold notification deep link data for navigating to a specific message
 */
data class NotificationDeepLinkData(
    val chatGuid: String,
    val messageGuid: String?
)

@Composable
fun BothBubblesNavHost(
    navController: NavHostController = rememberNavController(),
    isSetupComplete: Boolean = true,
    shareIntentData: ShareIntentData? = null,
    stateRestorationData: StateRestorationData? = null,
    notificationDeepLinkData: NotificationDeepLinkData? = null
) {
    // Determine start destination based on setup status and share intent
    val startDestination: Screen = when {
        !isSetupComplete -> Screen.Setup()
        // Direct share from shortcut - go straight to chat
        shareIntentData?.directShareChatGuid != null -> Screen.Chat(shareIntentData.directShareChatGuid)
        // Regular share - show picker to select conversation
        shareIntentData != null -> Screen.SharePicker(
            sharedText = shareIntentData.sharedText,
            sharedUris = shareIntentData.sharedUris.map { it.toString() }
        )
        else -> Screen.Conversations
    }

    // Handle notification deep link: navigate to chat with target message (takes priority over state restoration)
    LaunchedEffect(notificationDeepLinkData) {
        if (notificationDeepLinkData != null && isSetupComplete && shareIntentData == null) {
            // Navigate to the chat with target message for deep-link scrolling
            navController.navigate(
                Screen.Chat(
                    chatGuid = notificationDeepLinkData.chatGuid,
                    targetMessageGuid = notificationDeepLinkData.messageGuid
                )
            ) {
                launchSingleTop = true
            }
        }
    }

    // Handle state restoration: navigate to saved chat after initial composition
    // Skip if notification deep link is present (it takes priority)
    LaunchedEffect(stateRestorationData) {
        if (stateRestorationData != null && isSetupComplete && shareIntentData == null && notificationDeepLinkData == null) {
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

    // Handle direct share from sharing shortcut: pass shared content to chat screen
    LaunchedEffect(shareIntentData) {
        if (shareIntentData?.directShareChatGuid != null && isSetupComplete) {
            // Pass shared content to the chat screen via savedStateHandle
            try {
                val chatEntry = navController.getBackStackEntry(
                    Screen.Chat(shareIntentData.directShareChatGuid)
                )
                chatEntry.savedStateHandle.apply {
                    shareIntentData.sharedText?.let { set("shared_text", it) }
                    if (shareIntentData.sharedUris.isNotEmpty()) {
                        set("shared_uris", ArrayList(shareIntentData.sharedUris.map { it.toString() }))
                    }
                }
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
                        "autoresponder" -> Screen.AutoResponderSettings(returnToSettings)
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

        // Chat-related routes
        chatNavigation(navController)

        // Settings-related routes
        settingsNavigation(navController, ::popBackStackReturningToSettings)

        // Setup and share-related routes
        setupShareNavigation(navController)
    }
}
