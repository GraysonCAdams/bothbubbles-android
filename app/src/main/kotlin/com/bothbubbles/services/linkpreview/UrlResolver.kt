package com.bothbubbles.services.linkpreview

import timber.log.Timber
import java.net.URI

/**
 * Utility for resolving and normalizing URLs.
 *
 * Handles relative URLs, protocol-relative URLs, and validates URL formats.
 */
internal class UrlResolver {
    /**
     * Resolves a potentially relative URL against a base URL
     */
    fun resolveUrl(url: String?, baseUrl: String): String? {
        if (url == null) return null

        return try {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> {
                    val base = URI(baseUrl)
                    "${base.scheme}://${base.host}$url"
                }
                else -> {
                    val basePath = baseUrl.substringBeforeLast("/")
                    "$basePath/$url"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to resolve URL: $url against $baseUrl")
            null
        }
    }
}
