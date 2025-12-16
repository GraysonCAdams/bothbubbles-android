package com.bothbubbles.di.startup.helpers

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Provides access to HiltWorkerFactory before the Application onCreate is called.
 *
 * This is needed for WorkManagerInitializer which runs during AndroidX Startup,
 * before Application.onCreate(). We use Hilt EntryPoints to get the worker factory
 * directly from the Dagger component.
 */
object HiltWorkerFactoryProvider {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerFactoryEntryPoint {
        fun workerFactory(): HiltWorkerFactory
    }

    fun getWorkerFactory(context: Context): HiltWorkerFactory {
        val appContext = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            WorkerFactoryEntryPoint::class.java
        )
        return entryPoint.workerFactory()
    }
}
