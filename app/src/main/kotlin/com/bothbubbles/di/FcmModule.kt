package com.bothbubbles.di

import android.content.Context
import com.google.android.gms.common.GoogleApiAvailability
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing FCM-related dependencies.
 *
 * Note: FirebaseMessaging is not provided here because Firebase must be
 * initialized dynamically with server-provided config first. Use
 * FirebaseConfigManager to initialize Firebase, then access
 * FirebaseMessaging.getInstance() directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object FcmModule {

    @Provides
    @Singleton
    fun provideGoogleApiAvailability(): GoogleApiAvailability {
        return GoogleApiAvailability.getInstance()
    }
}
