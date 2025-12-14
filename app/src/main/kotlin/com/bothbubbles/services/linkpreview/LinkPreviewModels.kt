package com.bothbubbles.services.linkpreview

/**
 * Result of fetching link preview metadata
 */
sealed class LinkMetadataResult {
    data class Success(val metadata: LinkMetadata) : LinkMetadataResult()
    data class Error(val message: String) : LinkMetadataResult()
    data object NoPreview : LinkMetadataResult()
}

/**
 * Extracted metadata from a URL
 */
data class LinkMetadata(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val faviconUrl: String?,
    val siteName: String?,
    val contentType: String?,
    val embedHtml: String? = null,
    val authorName: String? = null,
    val authorUrl: String? = null
)

/**
 * oEmbed provider configuration
 */
internal data class OEmbedProvider(
    val name: String,
    val endpoint: String,
    val urlPatterns: List<Regex>
)

/**
 * Maps URL pattern configuration for coordinate extraction
 */
internal data class MapsPattern(
    val regex: Regex,
    val siteName: String
)
