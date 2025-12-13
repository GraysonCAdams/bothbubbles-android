package com.bothbubbles.data.remote.api

import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that dynamically sets the base URL from settings
 * and adds authentication and custom headers to all requests.
 *
 * IMPORTANT: Uses cached values instead of runBlocking to avoid blocking
 * OkHttp's network threads, which would cause socket timeouts.
 */
class AuthInterceptor @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : Interceptor {

    // Cached settings values - updated reactively from DataStore
    // Using @Volatile for thread-safe reads from OkHttp network threads
    @Volatile private var cachedServerAddress: String = ""
    @Volatile private var cachedAuthKey: String = ""
    @Volatile private var cachedCustomHeaders: Map<String, String> = emptyMap()

    init {
        // Collect settings updates in background - these run on IO dispatcher
        // and update the cached values that intercept() reads
        settingsDataStore.serverAddress
            .onEach { cachedServerAddress = it }
            .launchIn(applicationScope)

        settingsDataStore.guidAuthKey
            .onEach { cachedAuthKey = it }
            .launchIn(applicationScope)

        settingsDataStore.customHeaders
            .onEach { cachedCustomHeaders = it }
            .launchIn(applicationScope)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // Skip Socket.IO requests - they handle auth via their own query parameter
        // to avoid duplicate guid parameters
        if (originalUrl.encodedPath.contains("socket.io")) {
            return chain.proceed(originalRequest)
        }

        // Read cached values (non-blocking)
        val serverAddress = cachedServerAddress
        val authKey = cachedAuthKey
        val customHeaders = cachedCustomHeaders

        // Build the actual URL by replacing the placeholder base URL with the real server address
        val baseUrl = serverAddress.toHttpUrlOrNull()

        // Check if guid is already in the query (to avoid duplicate params)
        val hasGuidParam = originalUrl.queryParameter("guid") != null

        val finalUrl = if (baseUrl != null) {
            // Replace the localhost placeholder with the actual server address
            // Keep the path segments from the original request
            val builder = baseUrl.newBuilder()
                .encodedPath(originalUrl.encodedPath)
                .encodedQuery(originalUrl.encodedQuery)

            // Only add guid if not already present
            if (!hasGuidParam) {
                builder.addQueryParameter("guid", authKey)
            }
            builder.build()
        } else {
            // Fallback: just add auth to original URL (shouldn't happen if settings are configured)
            val builder = originalUrl.newBuilder()
            if (!hasGuidParam) {
                builder.addQueryParameter("guid", authKey)
            }
            builder.build()
        }

        // Build new request with updated URL and headers
        val requestBuilder = originalRequest.newBuilder()
            .url(finalUrl)

        // Add custom headers (for ngrok, zrok, etc.)
        customHeaders.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        // Add default headers for tunnel services (use the final URL's host)
        if (finalUrl.host.contains("ngrok")) {
            requestBuilder.addHeader("ngrok-skip-browser-warning", "true")
        }
        if (finalUrl.host.contains("zrok")) {
            requestBuilder.addHeader("skip_zrok_interstitial", "true")
        }

        val finalRequest = requestBuilder.build()
        Log.d("AuthInterceptor", "DEBUG: Request URL = ${finalRequest.url}")
        Log.d("AuthInterceptor", "DEBUG: Request method = ${finalRequest.method}")
        return chain.proceed(finalRequest)
    }
}
