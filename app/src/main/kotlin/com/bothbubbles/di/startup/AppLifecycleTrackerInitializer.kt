package com.bothbubbles.di.startup

import android.content.Context
import androidx.startup.Initializer
import com.bothbubbles.di.startup.helpers.AppLifecycleTrackerProvider

/**
 * Initializes AppLifecycleTracker to monitor app foreground/background state.
 *
 * This initializer is automatically run by AndroidX Startup before Application.onCreate().
 * It starts observing the app's lifecycle immediately, which is critical for:
 * - Clearing active conversation when app backgrounds
 * - Controlling adaptive polling in ChatViewModel
 * - Managing connection modes
 *
 * Note: This must run early because many features depend on lifecycle state.
 */
class AppLifecycleTrackerInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        val tracker = AppLifecycleTrackerProvider.getAppLifecycleTracker(context)
        tracker.initialize()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // No dependencies - lifecycle tracking can start anytime
        return emptyList()
    }
}
