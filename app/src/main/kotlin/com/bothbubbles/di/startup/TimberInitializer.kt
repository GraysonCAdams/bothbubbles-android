package com.bothbubbles.di.startup

import android.content.Context
import androidx.startup.Initializer
import com.bothbubbles.BuildConfig
import timber.log.Timber

/**
 * Initializes Timber logging for debug builds.
 *
 * This initializer is automatically run by AndroidX Startup before Application.onCreate().
 * It plants a DebugTree for debug builds, enabling logging throughout the app.
 */
class TimberInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber initialized via AndroidX Startup")
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // No dependencies - Timber can initialize anytime
        return emptyList()
    }
}
