package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fetch status for link previews
 */
enum class LinkPreviewFetchStatus {
    PENDING,    // Not yet fetched
    LOADING,    // Currently being fetched
    SUCCESS,    // Successfully fetched with metadata
    FAILED,     // Fetch failed (network error, timeout, etc.)
    NO_PREVIEW  // URL doesn't support previews (robots.txt blocked, no meta tags, etc.)
}

/**
 * Entity for caching link preview metadata.
 *
 * Link previews are cached based on URL hash to avoid re-fetching metadata
 * for the same URL across different messages.
 */
@Entity(
    tableName = "link_previews",
    indices = [
        Index(value = ["url_hash"], unique = true),
        Index(value = ["last_accessed"]) // For LRU eviction
    ]
)
data class LinkPreviewEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * The original URL that was fetched
     */
    @ColumnInfo(name = "url")
    val url: String,

    /**
     * SHA-256 hash of normalized URL for efficient lookups
     */
    @ColumnInfo(name = "url_hash")
    val urlHash: String,

    /**
     * Extracted domain for display (e.g., "youtube.com")
     */
    @ColumnInfo(name = "domain")
    val domain: String,

    /**
     * Page title from og:title or <title> tag
     */
    @ColumnInfo(name = "title")
    val title: String? = null,

    /**
     * Page description from og:description or meta description
     */
    @ColumnInfo(name = "description")
    val description: String? = null,

    /**
     * Preview image URL from og:image
     */
    @ColumnInfo(name = "image_url")
    val imageUrl: String? = null,

    /**
     * Site favicon URL
     */
    @ColumnInfo(name = "favicon_url")
    val faviconUrl: String? = null,

    /**
     * Site name from og:site_name
     */
    @ColumnInfo(name = "site_name")
    val siteName: String? = null,

    /**
     * Content type from og:type (article, video, website, etc.)
     */
    @ColumnInfo(name = "content_type")
    val contentType: String? = null,

    /**
     * Direct video playback URL (for Phase 2)
     * Used for platforms like Reddit that expose direct video URLs
     */
    @ColumnInfo(name = "video_url")
    val videoUrl: String? = null,

    /**
     * Video duration in seconds (for Phase 2)
     */
    @ColumnInfo(name = "video_duration")
    val videoDuration: Long? = null,

    /**
     * oEmbed HTML embed code (for video/rich content embeds)
     */
    @ColumnInfo(name = "embed_html")
    val embedHtml: String? = null,

    /**
     * Author/creator name from oEmbed
     */
    @ColumnInfo(name = "author_name")
    val authorName: String? = null,

    /**
     * Author/creator profile URL from oEmbed
     */
    @ColumnInfo(name = "author_url")
    val authorUrl: String? = null,

    /**
     * Current fetch status
     */
    @ColumnInfo(name = "fetch_status")
    val fetchStatus: String = LinkPreviewFetchStatus.PENDING.name,

    /**
     * Timestamp when this preview was first created
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Timestamp when this preview was last accessed (for LRU eviction)
     */
    @ColumnInfo(name = "last_accessed")
    val lastAccessed: Long = System.currentTimeMillis(),

    /**
     * Timestamp when this preview expires and should be re-fetched
     * Default: 7 days from creation
     */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
) {
    /**
     * Whether the preview has been successfully fetched
     */
    val isSuccess: Boolean
        get() = fetchStatus == LinkPreviewFetchStatus.SUCCESS.name

    /**
     * Whether the preview is currently being fetched
     */
    val isLoading: Boolean
        get() = fetchStatus == LinkPreviewFetchStatus.LOADING.name

    /**
     * Whether the preview fetch failed
     */
    val isFailed: Boolean
        get() = fetchStatus == LinkPreviewFetchStatus.FAILED.name

    /**
     * Whether the preview has no available metadata
     */
    val hasNoPreview: Boolean
        get() = fetchStatus == LinkPreviewFetchStatus.NO_PREVIEW.name

    /**
     * Whether this preview has expired and should be re-fetched
     */
    val isExpired: Boolean
        get() = System.currentTimeMillis() > expiresAt

    /**
     * Whether this is a video content type
     */
    val isVideo: Boolean
        get() = contentType?.lowercase()?.contains("video") == true || videoUrl != null

    /**
     * Display name for the site (site_name or domain)
     */
    val displaySiteName: String
        get() = siteName ?: domain

    /**
     * Whether there is enough metadata to display a preview
     */
    val hasDisplayableContent: Boolean
        get() = isSuccess && (title != null || description != null || imageUrl != null)

    companion object {
        /**
         * Creates a pending preview entry for a URL
         */
        fun createPending(url: String, urlHash: String, domain: String): LinkPreviewEntity {
            return LinkPreviewEntity(
                url = url,
                urlHash = urlHash,
                domain = domain,
                fetchStatus = LinkPreviewFetchStatus.PENDING.name
            )
        }
    }
}
