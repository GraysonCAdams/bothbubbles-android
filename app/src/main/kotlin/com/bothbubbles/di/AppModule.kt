package com.bothbubbles.di

import android.content.Context
import androidx.work.WorkManager
import coil.ImageLoader
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
}
