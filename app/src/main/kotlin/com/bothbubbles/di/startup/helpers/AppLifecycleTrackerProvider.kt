package com.bothbubbles.di.startup.helpers

import android.content.Context
import com.bothbubbles.services.AppLifecycleTracker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Provides access to AppLifecycleTracker before the Application onCreate is called.
 *
 * This is needed for AppLifecycleTrackerInitializer which runs during AndroidX Startup,
 * before Application.onCreate(). We use Hilt EntryPoints to get the tracker
 * directly from the Dagger component.
 */
object AppLifecycleTrackerProvider {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppLifecycleTrackerEntryPoint {
        fun appLifecycleTracker(): AppLifecycleTracker
    }

    fun getAppLifecycleTracker(context: Context): AppLifecycleTracker {
        val appContext = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            AppLifecycleTrackerEntryPoint::class.java
        )
        return entryPoint.appLifecycleTracker()
    }
}
