package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.LinkPreviewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkPreviewDao {

    // ===== Queries =====

    /**
     * Get a link preview by its URL hash
     */
    @Query("SELECT * FROM link_previews WHERE url_hash = :urlHash")
    suspend fun getByUrlHash(urlHash: String): LinkPreviewEntity?

    /**
     * Observe a link preview by its URL hash (for reactive UI)
     */
    @Query("SELECT * FROM link_previews WHERE url_hash = :urlHash")
    fun observeByUrlHash(urlHash: String): Flow<LinkPreviewEntity?>

    /**
     * Get multiple link previews by their URL hashes (batch lookup)
     */
    @Query("SELECT * FROM link_previews WHERE url_hash IN (:urlHashes)")
    suspend fun getByUrlHashes(urlHashes: List<String>): List<LinkPreviewEntity>

    /**
     * Get all pending fetches (for background processing)
     */
    @Query("""
        SELECT * FROM link_previews
        WHERE fetch_status = 'PENDING'
        ORDER BY created_at DESC
        LIMIT :limit
    """)
    suspend fun getPendingFetches(limit: Int = 10): List<LinkPreviewEntity>

    /**
     * Get failed fetches that can be retried (older than 1 hour)
     */
    @Query("""
        SELECT * FROM link_previews
        WHERE fetch_status = 'FAILED'
        AND last_accessed < :olderThan
        ORDER BY created_at DESC
        LIMIT :limit
    """)
    suspend fun getRetryableFetches(
        olderThan: Long = System.currentTimeMillis() - (60 * 60 * 1000), // 1 hour ago
        limit: Int = 10
    ): List<LinkPreviewEntity>

    /**
     * Get count of cached previews
     */
    @Query("SELECT COUNT(*) FROM link_previews")
    suspend fun getPreviewCount(): Int

    /**
     * Get count of successful previews
     */
    @Query("SELECT COUNT(*) FROM link_previews WHERE fetch_status = 'SUCCESS'")
    suspend fun getSuccessfulPreviewCount(): Int

    /**
     * Search link previews by title (for message search feature).
     * Returns URLs whose titles contain the search query.
     */
    @Query("""
        SELECT * FROM link_previews
        WHERE fetch_status = 'SUCCESS'
        AND title IS NOT NULL
        AND title LIKE '%' || :query || '%'
        ORDER BY last_accessed DESC
        LIMIT :limit
    """)
    suspend fun searchByTitle(query: String, limit: Int = 50): List<LinkPreviewEntity>

    // ===== Inserts/Updates =====

    /**
     * Insert or replace a link preview
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(linkPreview: LinkPreviewEntity): Long

    /**
     * Insert or replace multiple link previews
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(linkPreviews: List<LinkPreviewEntity>)

    /**
     * Update an existing link preview
     */
    @Update
    suspend fun update(linkPreview: LinkPreviewEntity)

    /**
     * Update the fetch status for a URL
     */
    @Query("UPDATE link_previews SET fetch_status = :status, last_accessed = :timestamp WHERE url_hash = :urlHash")
    suspend fun updateFetchStatus(
        urlHash: String,
        status: String,
        timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Update the last accessed timestamp (for LRU tracking)
     */
    @Query("UPDATE link_previews SET last_accessed = :timestamp WHERE url_hash = :urlHash")
    suspend fun updateLastAccessed(urlHash: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Update preview metadata after successful fetch
     */
    @Query("""
        UPDATE link_previews SET
            title = :title,
            description = :description,
            image_url = :imageUrl,
            favicon_url = :faviconUrl,
            site_name = :siteName,
            content_type = :contentType,
            video_url = :videoUrl,
            video_duration = :videoDuration,
            fetch_status = 'SUCCESS',
            last_accessed = :timestamp
        WHERE url_hash = :urlHash
    """)
    suspend fun updateMetadata(
        urlHash: String,
        title: String?,
        description: String?,
        imageUrl: String?,
        faviconUrl: String?,
        siteName: String?,
        contentType: String?,
        videoUrl: String? = null,
        videoDuration: Long? = null,
        timestamp: Long = System.currentTimeMillis()
    )

    // ===== Deletes/Eviction =====

    /**
     * Delete a link preview by URL hash
     */
    @Query("DELETE FROM link_previews WHERE url_hash = :urlHash")
    suspend fun deleteByUrlHash(urlHash: String)

    /**
     * Delete all expired previews
     */
    @Query("DELETE FROM link_previews WHERE expires_at < :timestamp")
    suspend fun deleteExpired(timestamp: Long = System.currentTimeMillis())

    /**
     * Evict old entries, keeping only the most recently accessed ones
     * Uses LRU (Least Recently Used) eviction strategy
     */
    @Query("""
        DELETE FROM link_previews
        WHERE id NOT IN (
            SELECT id FROM link_previews
            ORDER BY last_accessed DESC
            LIMIT :keepCount
        )
    """)
    suspend fun evictOldEntries(keepCount: Int = 1000)

    /**
     * Delete all link previews (clear cache)
     */
    @Query("DELETE FROM link_previews")
    suspend fun deleteAll()

    /**
     * Delete all failed previews (for retry)
     */
    @Query("DELETE FROM link_previews WHERE fetch_status = 'FAILED'")
    suspend fun deleteAllFailed()
}
