package com.bothbubbles.di

import com.bothbubbles.core.network.AuthCredentialsProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App-level network module that provides the binding for AuthCredentialsProvider.
 * All other network dependencies (OkHttp, Retrofit, APIs) are provided by CoreNetworkModule.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {

    /**
     * Binds the app's AuthCredentialsProviderImpl to the AuthCredentialsProvider interface.
     * This allows :core:network to access server credentials without depending on the data layer.
     */
    @Binds
    @Singleton
    abstract fun bindAuthCredentialsProvider(
        impl: AuthCredentialsProviderImpl
    ): AuthCredentialsProvider
}
