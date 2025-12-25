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
import timber.log.Timber

// MD3 motion tokens for navigation transitions
private val ENTER_DURATION = MotionTokens.Duration.MEDIUM_4
private val EXIT_DURATION = MotionTokens.Duration.MEDIUM_2

/**
 * Data class to hold share intent data parsed from MainActivity.
 * Also used for voice command intents (Google Assistant, Android Auto).
 */
data class ShareIntentData(
    val sharedText: String? = null,
    val sharedUris: List<Uri> = emptyList(),
    val directShareChatGuid: String? = null,  // Pre-selected chat from sharing shortcut
    val recipientAddress: String? = null      // Pre-filled recipient from voice command (sms: URI)
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
    val messageGuid: String?,
    val mergedGuids: String? = null
)

/**
 * Enum for shortcut navigation targets
 */
enum class ShortcutNavigation {
    COMPOSE
}

@Composable
fun BothBubblesNavHost(
    navController: NavHostController = rememberNavController(),
    isSetupComplete: Boolean = true,
    shareIntentData: ShareIntentData? = null,
    stateRestorationData: StateRestorationData? = null,
    notificationDeepLinkData: NotificationDeepLinkData? = null,
    shortcutNavigation: ShortcutNavigation? = null
) {
    // Determine start destination based on setup status and share intent
    // Note: For direct share (with directShareChatGuid), we start at Conversations
    // and navigate to Chat via LaunchedEffect so back button works
    val startDestination: Screen = when {
        !isSetupComplete -> Screen.Setup()
        // Launcher shortcut navigation (e.g., "New Message" from long-press menu)
        shortcutNavigation == ShortcutNavigation.COMPOSE -> Screen.Compose()
        // Voice command intent with recipient (Google Assistant, Android Auto)
        shareIntentData?.recipientAddress != null -> Screen.Compose(
            sharedText = shareIntentData.sharedText,
            initialAddress = shareIntentData.recipientAddress
        )
        // Regular share (no pre-selected chat) - go to compose screen with shared content
        shareIntentData != null && shareIntentData.directShareChatGuid == null -> Screen.Compose(
            sharedText = shareIntentData.sharedText,
            sharedUris = shareIntentData.sharedUris.map { it.toString() }
        )
        // For direct share or normal launch, start at Conversations
        else -> Screen.Conversations
    }

    // Handle notification deep link: navigate to chat with target message (takes priority over state restoration)
    LaunchedEffect(notificationDeepLinkData) {
        if (notificationDeepLinkData != null && isSetupComplete && shareIntentData == null) {
            // Navigate to the chat with target message for deep-link scrolling
            // Include mergedGuids for unified chat support (iMessage + SMS combined view)
            navController.navigate(
                Screen.Chat(
                    chatGuid = notificationDeepLinkData.chatGuid,
                    mergedGuids = notificationDeepLinkData.mergedGuids,
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
            // Set scroll position on current back stack entry
            navController.currentBackStackEntry?.savedStateHandle?.apply {
                set(NavigationKeys.RESTORE_SCROLL_POSITION, stateRestorationData.scrollPosition)
                set(NavigationKeys.RESTORE_SCROLL_OFFSET, stateRestorationData.scrollOffset)
            } ?: Timber.w("Failed to set scroll position: currentBackStackEntry is null")
        }
    }

    // Handle direct share from sharing shortcut: navigate to chat with shared content
    LaunchedEffect(shareIntentData) {
        if (shareIntentData?.directShareChatGuid != null && isSetupComplete) {
            // Navigate to the chat with shared content as route params
            // (Conversations is already in back stack as start destination)
            navController.navigate(
                Screen.Chat(
                    chatGuid = shareIntentData.directShareChatGuid,
                    sharedText = shareIntentData.sharedText,
                    sharedUris = shareIntentData.sharedUris.map { it.toString() }
                )
            ) {
                launchSingleTop = true
            }
        }
    }

    fun popBackStackReturningToSettings(returnToSettings: Boolean) {
        if (returnToSettings) {
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.set(NavigationKeys.OPEN_SETTINGS_PANEL, true)
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
                backStackEntry.savedStateHandle.getStateFlow(NavigationKeys.OPEN_SETTINGS_PANEL, false)
            }
            val reopenSettingsPanel by reopenSettingsPanelFlow.collectAsState(initial = false)

            ConversationsScreen(
                onConversationClick = { chatGuid, mergedGuids ->
                    // Pass merged GUIDs as comma-separated string for merged conversations
                    val mergedGuidsStr = if (mergedGuids.size > 1) mergedGuids.joinToString(",") else null
                    navController.navigate(Screen.Chat(chatGuid, mergedGuidsStr))
                },
                onNewMessageClick = {
                    navController.navigate(Screen.Compose())
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
                    backStackEntry.savedStateHandle[NavigationKeys.OPEN_SETTINGS_PANEL] = false
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
