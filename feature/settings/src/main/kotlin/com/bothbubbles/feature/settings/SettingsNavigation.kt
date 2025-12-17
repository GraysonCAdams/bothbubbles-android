package com.bothbubbles.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bothbubbles.feature.settings.about.AboutScreen
import com.bothbubbles.navigation.Route

/**
 * Navigation extension for settings feature.
 *
 * This defines how the settings screens are added to the navigation graph.
 * Call this from your app module's NavHost to include settings routes.
 */
fun NavGraphBuilder.settingsFeatureNavigation(
    navController: NavController,
    onNavigateToOpenSourceLicenses: () -> Unit = {}
) {
    composable<Route.About> {
        AboutScreen(
            onNavigateBack = { navController.navigateUp() },
            onOpenSourceLicensesClick = onNavigateToOpenSourceLicenses
        )
    }
}
