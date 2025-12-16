package com.bothbubbles.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bothbubbles.navigation.Route

/**
 * Navigation extension for settings feature.
 *
 * This defines how the settings screens are added to the navigation graph.
 *
 * ## Migration Pattern
 *
 * When migrating screens to this feature module:
 *
 * 1. **Move screen and ViewModel** to this module
 * 2. **Update dependencies** to use :core:data interfaces (SettingsProvider, etc.)
 * 3. **Add composable route** here with the actual screen
 * 4. **Update app module** to use this extension instead of its own
 *
 * Example after migration:
 * ```kotlin
 * composable<Route.Settings> {
 *     SettingsScreen(
 *         onNavigateBack = { navController.navigateUp() },
 *         onNavigateToServerSettings = { navController.navigate(Route.ServerSettings()) },
 *         ...
 *     )
 * }
 * ```
 *
 * ## Current State
 *
 * Settings screens remain in the app module while infrastructure is complete.
 * The app module's NavHost.kt calls its own settingsNavigation() extension.
 * When ready to migrate, replace app's extension with this one.
 */
fun NavGraphBuilder.settingsNavigation(
    navController: NavController,
    onNavigateToServerSettings: () -> Unit = {},
    onNavigateToNotificationSettings: () -> Unit = {},
    onNavigateToSmsSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    composable<Route.Settings> {
        // TODO: Move SettingsScreen here from app module
        // SettingsScreen(
        //     onNavigateBack = { navController.navigateUp() },
        //     onNavigateToServerSettings = onNavigateToServerSettings,
        //     ...
        // )
    }

    composable<Route.About> {
        // TODO: Move AboutScreen here from app module
        // AboutScreen(
        //     onNavigateBack = { navController.navigateUp() },
        // )
    }
}
