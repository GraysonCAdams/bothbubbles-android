package com.bothbubbles.di.startup

import android.content.Context
import androidx.startup.Initializer
import androidx.work.Configuration
import androidx.work.WorkManager
import com.bothbubbles.di.startup.helpers.HiltWorkerFactoryProvider

/**
 * Initializes WorkManager with Hilt worker factory.
 *
 * This initializer is automatically run by AndroidX Startup before Application.onCreate().
 * It configures WorkManager to use Hilt for dependency injection in workers.
 *
 * IMPORTANT: Default WorkManager initialization must be disabled in AndroidManifest.xml:
 * ```xml
 * <provider
 *     android:name="androidx.startup.InitializationProvider"
 *     android:authorities="${applicationId}.androidx-startup"
 *     tools:node="merge">
 *     <meta-data
 *         android:name="androidx.work.WorkManagerInitializer"
 *         android:value="androidx.startup"
 *         tools:node="remove" />
 * </provider>
 * ```
 */
class WorkManagerInitializer : Initializer<WorkManager> {

    override fun create(context: Context): WorkManager {
        // Get HiltWorkerFactory from the application
        val workerFactory = HiltWorkerFactoryProvider.getWorkerFactory(context)

        // Configure WorkManager with Hilt support
        val configuration = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

        // Initialize WorkManager
        WorkManager.initialize(context, configuration)

        return WorkManager.getInstance(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // No dependencies - WorkManager initializes first
        return emptyList()
    }
}
