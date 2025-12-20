package com.bothbubbles.core.network.di

import com.bothbubbles.core.network.AuthCredentialsProvider
import com.bothbubbles.core.network.BuildConfig
import com.bothbubbles.core.network.api.AuthInterceptor
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.Life360Api
import com.bothbubbles.core.network.api.TenorApi
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
object CoreNetworkModule {

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
            // Use HEADERS level to avoid OOM when logging large attachment bodies
            // Level.BODY would try to buffer entire request/response bodies as strings,
            // which causes OutOfMemoryError for large video/file uploads (100MB+)
            // Disable in release builds to prevent authorization header exposure
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    @Provides
    @Singleton
    fun provideAuthInterceptor(
        credentialsProvider: AuthCredentialsProvider
    ): AuthInterceptor {
        return AuthInterceptor(credentialsProvider)
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
        moshi: Moshi
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

    @Provides
    @Singleton
    @Named("tenor")
    fun provideTenorOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideTenorApi(
        @Named("tenor") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): TenorApi {
        return Retrofit.Builder()
            .baseUrl(TenorApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TenorApi::class.java)
    }

    @Provides
    @Singleton
    @Named("life360")
    fun provideLife360OkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideLife360Api(
        @Named("life360") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Life360Api {
        return Retrofit.Builder()
            .baseUrl(Life360Api.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(Life360Api::class.java)
    }
}
