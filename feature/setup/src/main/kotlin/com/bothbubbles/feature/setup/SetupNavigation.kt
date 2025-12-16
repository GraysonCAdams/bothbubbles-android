package com.bothbubbles.feature.setup

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bothbubbles.navigation.Route

/**
 * Navigation extension for setup feature.
 *
 * This defines how the setup wizard screens are added to the navigation graph.
 * The actual SetupScreen composable will be moved here from the app module.
 */
fun NavGraphBuilder.setupNavigation(
    navController: NavController,
    onSetupComplete: () -> Unit = {}
) {
    composable<Route.Setup> {
        // TODO: Move SetupScreen here from app module
        // SetupScreen(
        //     onSetupComplete = onSetupComplete,
        //     ...
        // )
    }
}
