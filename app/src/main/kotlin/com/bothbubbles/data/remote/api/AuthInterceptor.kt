package com.bothbubbles.data.remote.api

import com.bothbubbles.data.local.prefs.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that dynamically sets the base URL from settings
 * and adds authentication and custom headers to all requests
 */
class AuthInterceptor @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // Skip Socket.IO requests - they handle auth via their own query parameter
        // to avoid duplicate guid parameters
        if (originalUrl.encodedPath.contains("socket.io")) {
            return chain.proceed(originalRequest)
        }

        // Get server address from settings (this is the actual user-configured URL)
        val serverAddress = runBlocking {
            settingsDataStore.serverAddress.first()
        }

        // Get auth key from settings
        val authKey = runBlocking {
            settingsDataStore.guidAuthKey.first()
        }

        // Get custom headers from settings
        val customHeaders = runBlocking {
            settingsDataStore.customHeaders.first()
        }

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

        return chain.proceed(requestBuilder.build())
    }
}
