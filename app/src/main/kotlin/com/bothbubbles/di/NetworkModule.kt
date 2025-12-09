package com.bothbubbles.di

import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.AuthInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
    }

    @Provides
    @Singleton
    fun provideAuthInterceptor(
        settingsDataStore: SettingsDataStore
    ): AuthInterceptor {
        return AuthInterceptor(settingsDataStore)
    }

    /**
     * Trust manager that accepts all certificates (for self-signed BlueBubbles servers)
     */
    @Provides
    @Singleton
    fun provideTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }

    /**
     * SSL context configured to trust all certificates
     */
    @Provides
    @Singleton
    fun provideSslContext(trustManager: X509TrustManager): SSLContext {
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor,
        sslContext: SSLContext,
        trustManager: X509TrustManager
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Trust all certificates (needed for self-signed BlueBubbles servers)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        moshi: Moshi,
        @Suppress("UNUSED_PARAMETER") settingsDataStore: SettingsDataStore
    ): Retrofit {
        // Placeholder base URL - AuthInterceptor dynamically replaces this with
        // the actual server address from SettingsDataStore at request time
        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideBothBubblesApi(retrofit: Retrofit): BothBubblesApi {
        return retrofit.create(BothBubblesApi::class.java)
    }
}
