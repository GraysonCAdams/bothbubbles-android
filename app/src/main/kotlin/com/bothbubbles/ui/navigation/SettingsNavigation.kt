package com.bothbubbles.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.bothbubbles.ui.settings.SettingsScreen
import com.bothbubbles.ui.settings.sms.SmsSettingsScreen

/**
 * Settings-related navigation routes including main settings screen and all sub-settings pages.
 */
fun NavGraphBuilder.settingsNavigation(
    navController: NavHostController,
    popBackStackReturningToSettings: (Boolean) -> Unit
) {
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
            onImageQualityClick = { navController.navigate(Screen.ImageQualitySettings(returnToSettings = true)) },
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

    // Auto-Responder Settings
    composable<Screen.AutoResponderSettings> { backStackEntry ->
        val route: Screen.AutoResponderSettings = backStackEntry.toRoute()
        com.bothbubbles.ui.settings.autoresponder.AutoResponderSettingsScreen(
            onNavigateBack = {
                popBackStackReturningToSettings(route.returnToSettings)
            }
        )
    }

    // Image Quality Settings
    composable<Screen.ImageQualitySettings> { backStackEntry ->
        val route: Screen.ImageQualitySettings = backStackEntry.toRoute()
        com.bothbubbles.ui.settings.attachments.ImageQualitySettingsScreen(
            onNavigateBack = {
                popBackStackReturningToSettings(route.returnToSettings)
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
}
