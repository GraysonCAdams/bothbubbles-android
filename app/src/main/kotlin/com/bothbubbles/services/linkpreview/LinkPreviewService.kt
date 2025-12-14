package com.bothbubbles.services.linkpreview

import android.content.Context
import android.util.Log
import com.bothbubbles.data.local.prefs.FeaturePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for fetching link preview metadata from URLs.
 *
 * Uses oEmbed API for supported platforms (YouTube, Twitter, TikTok, Reddit, etc.)
 * and falls back to Open Graph meta tag parsing for other sites.
 *
 * Architecture:
 * - OEmbedProviderHandler: Handles oEmbed provider lookup and fetching
 * - MapsPreviewHandler: Generates previews for Google Maps / Apple Maps URLs
 * - OpenGraphParser: Parses Open Graph meta tags from HTML
 * - UrlResolver: Resolves relative URLs
 */
@Singleton
class LinkPreviewService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val featurePreferences: FeaturePreferences
) {
    companion object {
        private const val TAG = "LinkPreviewService"
        private const val TIMEOUT_MS = 8000L
        private const val MAX_CONTENT_LENGTH = 512 * 1024 // 512KB max for HTML
        private const val USER_AGENT = "BlueBubbles/1.0 (Android; Link Preview Bot)"
        private const val MAX_CONCURRENT_REQUESTS = 5
    }

    // Rate limiting: max concurrent requests
    private val rateLimiter = Semaphore(MAX_CONCURRENT_REQUESTS)

    // Separate OkHttpClient for link previews (no auth interceptor)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // Extracted handler components
    private val oembedHandler by lazy {
        OEmbedProviderHandler(httpClient, USER_AGENT)
    }

    private val mapsHandler by lazy {
        MapsPreviewHandler(context)
    }

    private val openGraphParser by lazy {
        OpenGraphParser(httpClient, USER_AGENT, MAX_CONTENT_LENGTH)
    }

    private val urlResolver by lazy {
        UrlResolver()
    }

    /**
     * Fetches metadata from a URL using oEmbed if available, falling back to Open Graph.
     * Respects the user's link preview preference setting.
     */
    suspend fun fetchMetadata(url: String): LinkMetadataResult = withContext(Dispatchers.IO) {
        // Check if link previews are enabled in user settings
        val enabled = featurePreferences.linkPreviewsEnabled.first()
        if (!enabled) {
            return@withContext LinkMetadataResult.NoPreview
        }

        rateLimiter.withPermit {
            try {
                // First, try maps preview for Google Maps / Apple Maps URLs
                val mapsResult = mapsHandler.tryMapsPreview(url)
                if (mapsResult != null) {
                    Log.d(TAG, "Maps preview successful for: $url")
                    return@withPermit mapsResult
                }

                // Second, try oEmbed for supported providers
                val oembedResult = oembedHandler.tryOEmbed(url)
                if (oembedResult != null) {
                    Log.d(TAG, "oEmbed successful for: $url")
                    return@withPermit oembedResult
                }

                // Fall back to Open Graph parsing
                Log.d(TAG, "Falling back to Open Graph for: $url")
                openGraphParser.fetchOpenGraphMetadata(url, urlResolver)

            } catch (e: IOException) {
                Log.w(TAG, "Network error fetching metadata for $url", e)
                LinkMetadataResult.Error(e.message ?: "Network error")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching metadata for $url", e)
                LinkMetadataResult.Error(e.message ?: "Unknown error")
            }
        }
    }
}
