package com.bothbubbles.data.remote.api

import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that dynamically sets the base URL from settings
 * and adds authentication and custom headers to all requests.
 *
 * Uses cached values with initialization tracking to avoid race conditions
 * on app startup while not blocking OkHttp's network threads unnecessarily.
 */
class AuthInterceptor @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : Interceptor {

    private data class CachedCredentials(
        val serverAddress: String = "",
        val authKey: String = "",
        val customHeaders: Map<String, String> = emptyMap()
    )

    private val _credentials = MutableStateFlow(CachedCredentials())
    private val _initialized = MutableStateFlow(false)

    init {
        // Combine all settings flows and update cache atomically
        combine(
            settingsDataStore.serverAddress,
            settingsDataStore.guidAuthKey,
            settingsDataStore.customHeaders
        ) { address, authKey, headers ->
            CachedCredentials(
                serverAddress = address,
                authKey = authKey,
                customHeaders = headers
            )
        }
        .onEach { credentials ->
            _credentials.value = credentials
            _initialized.value = true
        }
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

        // Get credentials, waiting for initialization if needed
        val credentials = getCredentialsBlocking()
        val serverAddress = credentials.serverAddress
        val authKey = credentials.authKey
        val customHeaders = credentials.customHeaders

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
        Log.d(TAG, "Request URL = ${finalRequest.url}")
        Log.d(TAG, "Request method = ${finalRequest.method}")
        return chain.proceed(finalRequest)
    }

    /**
     * Get credentials, waiting for initialization if needed.
     * Uses bounded timeout to prevent indefinite blocking.
     *
     * This runBlocking is acceptable because:
     * 1. We're already on OkHttp's background thread pool
     * 2. We need synchronous response for the interceptor
     * 3. We have bounded timeout (3 seconds max)
     * 4. DataStore guarantees first emission (stored value or default)
     */
    private fun getCredentialsBlocking(): CachedCredentials {
        // Fast path: already initialized
        if (_initialized.value) {
            return _credentials.value
        }

        // Slow path: wait for initialization with timeout
        return runBlocking {
            withTimeoutOrNull(INIT_TIMEOUT_MS) {
                _initialized.first { it }
                _credentials.value
            } ?: run {
                Log.w(TAG, "Credentials initialization timed out")
                _credentials.value
            }
        }
    }

    companion object {
        private const val TAG = "AuthInterceptor"
        private const val INIT_TIMEOUT_MS = 3000L
    }
}
