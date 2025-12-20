package com.bothbubbles.services.linkpreview

import timber.log.Timber
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Handles oEmbed provider lookup and metadata fetching for supported platforms.
 *
 * Supports: YouTube, Twitter/X, TikTok, Vimeo, Spotify, SoundCloud, Imgur, Giphy
 *
 * Note: Reddit is intentionally NOT included here. Reddit's oEmbed returns no thumbnail,
 * but their OpenGraph tags (served to facebookexternalhit User-Agent) include rich
 * preview images with rendered comment/post screenshots via share.redd.it.
 */
internal class OEmbedProviderHandler(
    private val httpClient: OkHttpClient,
    private val userAgent: String
) {
    companion object {
        // oEmbed providers with their endpoints and URL patterns
        private val OEMBED_PROVIDERS = listOf(
            OEmbedProvider(
                name = "YouTube",
                endpoint = "https://www.youtube.com/oembed?url=%s&format=json",
                urlPatterns = listOf(
                    """youtube\.com/watch""".toRegex(),
                    """youtu\.be/""".toRegex(),
                    """youtube\.com/shorts/""".toRegex(),
                    """youtube\.com/embed/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Twitter/X",
                endpoint = "https://publish.twitter.com/oembed?url=%s",
                urlPatterns = listOf(
                    """(?:twitter|x)\.com/[^/]+/status/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "TikTok",
                endpoint = "https://www.tiktok.com/oembed?url=%s",
                urlPatterns = listOf(
                    """tiktok\.com/""".toRegex(),
                    """vm\.tiktok\.com/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Vimeo",
                endpoint = "https://vimeo.com/api/oembed.json?url=%s",
                urlPatterns = listOf(
                    """vimeo\.com/\d+""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Spotify",
                endpoint = "https://open.spotify.com/oembed?url=%s",
                urlPatterns = listOf(
                    """open\.spotify\.com/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "SoundCloud",
                endpoint = "https://soundcloud.com/oembed?url=%s&format=json",
                urlPatterns = listOf(
                    """soundcloud\.com/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Imgur",
                endpoint = "https://api.imgur.com/oembed.json?url=%s",
                urlPatterns = listOf(
                    """imgur\.com/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Giphy",
                endpoint = "https://giphy.com/services/oembed?url=%s",
                urlPatterns = listOf(
                    """giphy\.com/""".toRegex()
                )
            )
        )
    }

    /**
     * Tries to fetch metadata via oEmbed for supported providers
     */
    fun tryOEmbed(url: String): LinkMetadataResult? {
        // Find matching provider
        val provider = OEMBED_PROVIDERS.find { provider ->
            provider.urlPatterns.any { pattern -> pattern.containsMatchIn(url) }
        } ?: return null

        Timber.d("Using oEmbed provider: ${provider.name} for $url")

        return try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val oembedUrl = provider.endpoint.format(encodedUrl)

            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Timber.w("oEmbed HTTP error ${response.code} for ${provider.name}")
                return null
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Timber.w("Empty oEmbed response for ${provider.name}")
                return null
            }

            parseOEmbedResponse(body, provider.name)

        } catch (e: Exception) {
            Timber.w("oEmbed failed for ${provider.name}: ${e.message}")
            null
        }
    }

    /**
     * Parses an oEmbed JSON response into LinkMetadata
     */
    private fun parseOEmbedResponse(json: String, providerName: String): LinkMetadataResult {
        return try {
            val obj = JSONObject(json)

            val title = obj.optString("title").takeIf { it.isNotBlank() }
            val authorName = obj.optString("author_name").takeIf { it.isNotBlank() }
            val authorUrl = obj.optString("author_url").takeIf { it.isNotBlank() }
            val thumbnailUrl = obj.optString("thumbnail_url").takeIf { it.isNotBlank() }
            val html = obj.optString("html").takeIf { it.isNotBlank() }
            val type = obj.optString("type").takeIf { it.isNotBlank() } ?: "link"
            val providerNameFromResponse = obj.optString("provider_name").takeIf { it.isNotBlank() }

            // For video types, try to extract a description from the embed
            val description = when {
                authorName != null && type == "video" -> "Video by $authorName"
                authorName != null && type == "rich" -> "Post by $authorName"
                else -> null
            }

            // Check if we got useful metadata
            if (title == null && thumbnailUrl == null) {
                Timber.d("No useful metadata from oEmbed for $providerName")
                return LinkMetadataResult.NoPreview
            }

            LinkMetadataResult.Success(
                LinkMetadata(
                    title = title?.take(200)?.trim(),
                    description = description?.take(500)?.trim(),
                    imageUrl = thumbnailUrl,
                    faviconUrl = null, // oEmbed doesn't provide favicon
                    siteName = providerNameFromResponse ?: providerName,
                    contentType = type,
                    embedHtml = html,
                    authorName = authorName,
                    authorUrl = authorUrl
                )
            )
        } catch (e: Exception) {
            Timber.w("Failed to parse oEmbed JSON: ${e.message}")
            LinkMetadataResult.Error("Failed to parse oEmbed response")
        }
    }
}
