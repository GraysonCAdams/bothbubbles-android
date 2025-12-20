package com.bothbubbles.services.linkpreview

import timber.log.Timber
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Parses Open Graph meta tags and standard HTML metadata from web pages.
 *
 * This is the fallback method when oEmbed is not available for a URL.
 * Extracts metadata from og:, twitter:, and standard meta tags.
 */
internal class OpenGraphParser(
    private val httpClient: OkHttpClient,
    private val userAgent: String,
    private val maxContentLength: Int
) {
    /**
     * Fetches and parses Open Graph metadata from a URL
     */
    fun fetchOpenGraphMetadata(url: String, urlResolver: UrlResolver): LinkMetadataResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("HTTP error ${response.code} for $url")
                return LinkMetadataResult.Error("HTTP ${response.code}")
            }

            val contentType = response.header("Content-Type") ?: ""
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0

            // Check content length
            if (contentLength > maxContentLength) {
                Timber.w("Content too large ($contentLength bytes) for $url")
                return LinkMetadataResult.Error("Content too large")
            }

            // For images/videos, return basic metadata
            when {
                contentType.startsWith("image/") -> {
                    return LinkMetadataResult.Success(
                        LinkMetadata(
                            title = null,
                            description = null,
                            imageUrl = url,
                            faviconUrl = null,
                            siteName = null,
                            contentType = "image"
                        )
                    )
                }
                contentType.startsWith("video/") -> {
                    return LinkMetadataResult.Success(
                        LinkMetadata(
                            title = null,
                            description = null,
                            imageUrl = null,
                            faviconUrl = null,
                            siteName = null,
                            contentType = "video"
                        )
                    )
                }
                !contentType.contains("html") -> {
                    Timber.d("Non-HTML content type: $contentType for $url")
                    return LinkMetadataResult.NoPreview
                }
            }

            // Parse HTML for Open Graph / meta tags
            val html = response.body?.string()
            if (html.isNullOrBlank()) {
                return LinkMetadataResult.Error("Empty response body")
            }

            // Limit HTML size to prevent memory issues
            val truncatedHtml = if (html.length > maxContentLength) {
                html.substring(0, maxContentLength)
            } else {
                html
            }

            return parseHtmlMetadata(truncatedHtml, url, urlResolver)
        }
    }

    /**
     * Parses HTML to extract Open Graph and meta tag metadata
     */
    private fun parseHtmlMetadata(html: String, url: String, urlResolver: UrlResolver): LinkMetadataResult {
        val title = extractMetaContent(html, "og:title")
            ?: extractMetaContent(html, "twitter:title")
            ?: extractTitleTag(html)

        val description = extractMetaContent(html, "og:description")
            ?: extractMetaContent(html, "twitter:description")
            ?: extractMetaContent(html, "description")

        var imageUrl = extractMetaContent(html, "og:image")
            ?: extractMetaContent(html, "twitter:image")
            ?: extractMetaContent(html, "twitter:image:src")

        val siteName = extractMetaContent(html, "og:site_name")
            ?: extractMetaContent(html, "application-name")

        val faviconUrl = extractFavicon(html)

        val contentType = extractMetaContent(html, "og:type") ?: "website"

        // Resolve relative URLs
        imageUrl = imageUrl?.let { urlResolver.resolveUrl(it, url) }

        // Check if we got any useful metadata
        if (title == null && description == null && imageUrl == null) {
            return LinkMetadataResult.NoPreview
        }

        return LinkMetadataResult.Success(
            LinkMetadata(
                title = title?.take(200)?.trim(),
                description = description?.take(500)?.trim(),
                imageUrl = imageUrl,
                faviconUrl = urlResolver.resolveUrl(faviconUrl, url),
                siteName = siteName?.take(100)?.trim(),
                contentType = contentType
            )
        )
    }

    /**
     * Extracts content from meta tags with property or name attribute
     */
    private fun extractMetaContent(html: String, property: String): String? {
        // Match property="..." content="..."
        val propertyRegex = """<meta[^>]+property=["']$property["'][^>]+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        propertyRegex.find(html)?.groupValues?.get(1)?.let { return decodeHtmlEntities(it) }

        // Match content="..." property="..."
        val propertyAltRegex = """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']$property["']""".toRegex(RegexOption.IGNORE_CASE)
        propertyAltRegex.find(html)?.groupValues?.get(1)?.let { return decodeHtmlEntities(it) }

        // Match name="..." content="..."
        val nameRegex = """<meta[^>]+name=["']$property["'][^>]+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        nameRegex.find(html)?.groupValues?.get(1)?.let { return decodeHtmlEntities(it) }

        // Match content="..." name="..."
        val nameAltRegex = """<meta[^>]+content=["']([^"']+)["'][^>]+name=["']$property["']""".toRegex(RegexOption.IGNORE_CASE)
        nameAltRegex.find(html)?.groupValues?.get(1)?.let { return decodeHtmlEntities(it) }

        return null
    }

    /**
     * Extracts the page title from the <title> tag
     */
    private fun extractTitleTag(html: String): String? {
        val regex = """<title[^>]*>([^<]+)</title>""".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)?.let { decodeHtmlEntities(it.trim()) }
    }

    /**
     * Extracts the favicon URL from link tags or uses default
     */
    private fun extractFavicon(html: String): String? {
        // Try to find icon link tags
        val iconRegex = """<link[^>]+rel=["'](?:shortcut\s+)?icon["'][^>]+href=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        iconRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Try alternate order
        val iconAltRegex = """<link[^>]+href=["']([^"']+)["'][^>]+rel=["'](?:shortcut\s+)?icon["']""".toRegex(RegexOption.IGNORE_CASE)
        iconAltRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Try apple-touch-icon
        val appleIconRegex = """<link[^>]+rel=["']apple-touch-icon["'][^>]+href=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        appleIconRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Default to /favicon.ico
        return "/favicon.ico"
    }

    /**
     * Decodes common HTML entities
     */
    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#x27;", "'")
            .replace("&#x2F;", "/")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
    }
}
