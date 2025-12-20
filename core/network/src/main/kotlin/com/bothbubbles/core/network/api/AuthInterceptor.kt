package com.bothbubbles.core.network.api

import com.bothbubbles.core.network.AuthCredentialsProvider
import timber.log.Timber
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that dynamically sets the base URL from settings
 * and adds authentication and custom headers to all requests.
 *
 * IMPORTANT: Must call preInitialize() before making any network requests.
 * Failing to initialize will cause all requests to throw IllegalStateException.
 */
class AuthInterceptor @Inject constructor(
    private val credentialsProvider: AuthCredentialsProvider
) : Interceptor {

    private data class CachedCredentials(
        val serverAddress: String = "",
        val authKey: String = "",
        val customHeaders: Map<String, String> = emptyMap()
    )

    @Volatile
    private var cachedCredentials: CachedCredentials? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // Skip Socket.IO requests - they handle auth via their own query parameter
        // to avoid duplicate guid parameters
        if (originalUrl.encodedPath.contains("socket.io")) {
            return chain.proceed(originalRequest)
        }

        // Get credentials from cache (fail fast if not initialized)
        val credentials = cachedCredentials
            ?: throw IllegalStateException(
                "AuthInterceptor not initialized. Call preInitialize() before making network requests."
            )
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
        Timber.d("Request to endpoint: ${finalRequest.url.encodedPath}")
        Timber.d("Request method = ${finalRequest.method}")

        val response = chain.proceed(finalRequest)
        return response
    }

    /**
     * Pre-initialize credentials from the provider.
     * MUST be called before making any network requests.
     * Call this during app initialization or after settings change.
     */
    suspend fun preInitialize() {
        val serverAddress = credentialsProvider.getServerAddress()
        val authKey = credentialsProvider.getAuthKey()
        val customHeaders = credentialsProvider.getCustomHeaders()

        cachedCredentials = CachedCredentials(
            serverAddress = serverAddress,
            authKey = authKey,
            customHeaders = customHeaders
        )
        Timber.d("AuthInterceptor initialized with server: ${serverAddress.take(30)}...")
    }

    /**
     * Clear cached credentials and re-fetch from provider.
     * Call when settings change (server URL, auth key, custom headers).
     */
    suspend fun invalidateCache() {
        preInitialize()
    }
}
