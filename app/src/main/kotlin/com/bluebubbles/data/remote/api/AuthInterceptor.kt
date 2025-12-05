package com.bluebubbles.data.remote.api

import com.bluebubbles.data.local.prefs.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that adds authentication and custom headers to all requests
 */
class AuthInterceptor @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // Get auth key from settings
        val authKey = runBlocking {
            settingsDataStore.guidAuthKey.first()
        }

        // Get custom headers from settings
        val customHeaders = runBlocking {
            settingsDataStore.customHeaders.first()
        }

        // Add auth key as query parameter
        val urlWithAuth = originalUrl.newBuilder()
            .addQueryParameter("guid", authKey)
            .build()

        // Build new request with updated URL and headers
        val requestBuilder = originalRequest.newBuilder()
            .url(urlWithAuth)

        // Add custom headers (for ngrok, zrok, etc.)
        customHeaders.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        // Add default headers for tunnel services
        if (originalUrl.host.contains("ngrok")) {
            requestBuilder.addHeader("ngrok-skip-browser-warning", "true")
        }
        if (originalUrl.host.contains("zrok")) {
            requestBuilder.addHeader("skip_zrok_interstitial", "true")
        }

        return chain.proceed(requestBuilder.build())
    }
}
