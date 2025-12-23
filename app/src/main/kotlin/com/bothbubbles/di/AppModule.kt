package com.bothbubbles.di

import android.content.Context
import androidx.work.WorkManager
import coil.ImageLoader
import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.core.data.prefs.SettingsDataStore
import com.bothbubbles.core.data.prefs.SyncPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing application-scoped utilities.
 *
 * For database dependencies, see [DatabaseModule].
 * For network dependencies, see [NetworkModule].
 * For coroutine dependencies, see [CoroutinesModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        // Coil 3.x: ImageLoaderFactory creates the ImageLoader via newImageLoader()
        return (context.applicationContext as coil.ImageLoaderFactory).newImageLoader()
    }

    /**
     * Provides FeaturePreferences from SettingsDataStore to ensure all code uses the same
     * DataStore instance. This avoids the bug where settings saved via SettingsDataStore
     * (using "settings" file) would not be visible to code that injected FeaturePreferences
     * directly (which was using a different "feature_preferences" file).
     */
    @Provides
    @Singleton
    fun provideFeaturePreferences(settingsDataStore: SettingsDataStore): FeaturePreferences {
        return settingsDataStore.getFeaturePreferences()
    }

    /**
     * Provides SyncPreferences from SettingsDataStore to ensure all code uses the same
     * DataStore instance.
     */
    @Provides
    @Singleton
    fun provideSyncPreferences(settingsDataStore: SettingsDataStore): SyncPreferences {
        return settingsDataStore.getSyncPreferences()
    }
}
