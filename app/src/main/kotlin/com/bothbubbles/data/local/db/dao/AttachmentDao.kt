package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data class for attachment with message date, used for gallery grouping.
 */
data class AttachmentWithDate(
    @androidx.room.Embedded val attachment: AttachmentEntity,
    @androidx.room.ColumnInfo(name = "dateCreated") val dateCreated: Long
)

/**
 * Data class for media attachment with sender information, used in media viewer.
 */
data class MediaWithSender(
    @androidx.room.Embedded val attachment: AttachmentEntity,
    @androidx.room.ColumnInfo(name = "is_from_me") val isFromMe: Boolean,
    @androidx.room.ColumnInfo(name = "sender_address") val senderAddress: String?,
    @androidx.room.ColumnInfo(name = "date_created") val dateCreated: Long,
    // Handle info (nullable for "from me" messages or if handle not found)
    @androidx.room.ColumnInfo(name = "cached_display_name") val displayName: String?,
    @androidx.room.ColumnInfo(name = "cached_avatar_path") val avatarPath: String?,
    @androidx.room.ColumnInfo(name = "formatted_address") val formattedAddress: String?
)

@Dao
interface AttachmentDao {

    // ===== Queries =====

    @Query("SELECT * FROM attachments WHERE message_guid = :messageGuid")
    suspend fun getAttachmentsForMessage(messageGuid: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE message_guid IN (:messageGuids)")
    suspend fun getAttachmentsForMessages(messageGuids: List<String>): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE message_guid = :messageGuid")
    fun observeAttachmentsForMessage(messageGuid: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE guid = :guid")
    suspend fun getAttachmentByGuid(guid: String): AttachmentEntity?

    /**
     * Check if an attachment with the same message and file name already exists.
     * Used to detect content duplicates when the server sends the same attachment
     * with different GUIDs (e.g., proper server GUID vs fallback at_0_ format).
     */
    @Query("""
        SELECT * FROM attachments
        WHERE message_guid = :messageGuid
        AND transfer_name = :transferName
        LIMIT 1
    """)
    suspend fun getAttachmentByMessageAndName(messageGuid: String, transferName: String): AttachmentEntity?

    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid
        ORDER BY m.date_created DESC
    """)
    fun getAttachmentsForChat(chatGuid: String): Flow<List<AttachmentEntity>>

    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid AND a.mime_type LIKE 'image/%'
        ORDER BY m.date_created DESC
    """)
    fun getImagesForChat(chatGuid: String): Flow<List<AttachmentEntity>>

    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid AND a.mime_type LIKE 'video/%'
        ORDER BY m.date_created DESC
    """)
    fun getVideosForChat(chatGuid: String): Flow<List<AttachmentEntity>>

    /**
     * Data class for video attachment with sender information, used for Reels feed.
     */
    data class VideoWithSender(
        @androidx.room.Embedded val attachment: AttachmentEntity,
        @androidx.room.ColumnInfo(name = "chat_guid") val chatGuid: String,
        @androidx.room.ColumnInfo(name = "is_from_me") val isFromMe: Boolean,
        @androidx.room.ColumnInfo(name = "sender_address") val senderAddress: String?,
        @androidx.room.ColumnInfo(name = "date_created") val dateCreated: Long,
        @androidx.room.ColumnInfo(name = "cached_display_name") val displayName: String?,
        @androidx.room.ColumnInfo(name = "cached_avatar_path") val avatarPath: String?,
        @androidx.room.ColumnInfo(name = "formatted_address") val formattedAddress: String?
    )

    /**
     * Get video attachments with sender information for Reels feed.
     * Only returns downloaded videos (local_path IS NOT NULL) that are not hidden.
     *
     * Note: Joins on sender_address instead of handle_id because handle_id may not
     * be correctly set on all messages (especially in group chats or after migrations).
     * Uses a subquery to pick the best matching handle (preferring iMessage over SMS).
     */
    @Query("""
        SELECT
            a.*,
            m.chat_guid,
            m.is_from_me,
            m.sender_address,
            m.date_created,
            h.cached_display_name,
            h.cached_avatar_path,
            h.formatted_address
        FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        LEFT JOIN handles h ON h.id = (
            SELECT h2.id FROM handles h2
            WHERE h2.address = m.sender_address
            ORDER BY CASE h2.service WHEN 'iMessage' THEN 0 ELSE 1 END
            LIMIT 1
        )
        WHERE m.chat_guid = :chatGuid
          AND a.mime_type LIKE 'video/%'
          AND a.hide_attachment = 0
          AND a.local_path IS NOT NULL
        ORDER BY m.date_created DESC
    """)
    suspend fun getVideoAttachmentsWithSenderForChat(chatGuid: String): List<VideoWithSender>

    /**
     * Get media attachments (images and videos) with their message dates for gallery grouping.
     */
    @Query("""
        SELECT a.*, m.date_created as dateCreated FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid
          AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
          AND a.hide_attachment = 0
        ORDER BY m.date_created DESC
    """)
    fun getMediaWithDatesForChat(chatGuid: String): Flow<List<AttachmentWithDate>>

    /**
     * Get media attachments for multiple chats (merged conversations) with dates.
     */
    @Query("""
        SELECT a.*, m.date_created as dateCreated FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid IN (:chatGuids)
          AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
          AND a.hide_attachment = 0
        ORDER BY m.date_created DESC
    """)
    fun getMediaWithDatesForChats(chatGuids: List<String>): Flow<List<AttachmentWithDate>>

    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid
          AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
          AND a.local_path IS NOT NULL
        ORDER BY m.date_created DESC
    """)
    suspend fun getCachedMediaForChat(chatGuid: String): List<AttachmentEntity>

    /**
     * Get all media attachments for a chat with sender information.
     * Used by MediaViewer to show sender avatar/name and allow swiping through all media.
     * Includes media regardless of download status (unlike getCachedMediaForChat).
     */
    @Query("""
        SELECT
            a.*,
            m.is_from_me,
            m.sender_address,
            m.date_created,
            h.cached_display_name,
            h.cached_avatar_path,
            h.formatted_address
        FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        LEFT JOIN handles h ON m.handle_id = h.id
        WHERE m.chat_guid = :chatGuid
          AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
          AND a.hide_attachment = 0
        ORDER BY m.date_created DESC
    """)
    suspend fun getMediaWithSenderForChat(chatGuid: String): List<MediaWithSender>

    @Query("""
        SELECT COUNT(*) FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid AND a.mime_type LIKE 'image/%'
    """)
    fun observeImageCountForChat(chatGuid: String): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid AND (
            a.mime_type LIKE 'video/%' OR
            a.mime_type LIKE 'audio/%' OR
            a.mime_type LIKE 'application/%'
        )
    """)
    fun observeOtherMediaCountForChat(chatGuid: String): Flow<Int>

    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid AND a.mime_type LIKE 'image/%'
        ORDER BY m.date_created DESC
        LIMIT :limit
    """)
    fun observeRecentImagesForChat(chatGuid: String, limit: Int = 5): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE local_path IS NULL ORDER BY id DESC LIMIT :limit")
    suspend fun getPendingDownloads(limit: Int = 50): List<AttachmentEntity>

    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid AND a.local_path IS NULL
        ORDER BY m.date_created DESC
    """)
    suspend fun getPendingDownloadsForChat(chatGuid: String): List<AttachmentEntity>

    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid IN (:chatGuids) AND a.local_path IS NULL
        ORDER BY m.date_created DESC
    """)
    suspend fun getPendingDownloadsForChats(chatGuids: List<String>): List<AttachmentEntity>

    @Query("SELECT COUNT(*) FROM attachments")
    suspend fun getAttachmentCount(): Int

    @Query("SELECT SUM(total_bytes) FROM attachments WHERE local_path IS NOT NULL")
    suspend fun getTotalDownloadedSize(): Long?

    /**
     * Search attachments by filename within specific chats.
     * Used for finding messages with matching attachment names.
     *
     * @param query The search query to match against transfer_name
     * @param chatGuids List of chat GUIDs to search within
     * @param limit Maximum number of results to return
     */
    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid IN (:chatGuids)
        AND m.date_deleted IS NULL
        AND a.transfer_name LIKE '%' || :query || '%'
        ORDER BY m.date_created DESC
        LIMIT :limit
    """)
    suspend fun searchAttachmentsByName(query: String, chatGuids: List<String>, limit: Int = 50): List<AttachmentEntity>

    // ===== Inserts/Updates =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: AttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Update
    suspend fun updateAttachment(attachment: AttachmentEntity)

    @Query("UPDATE attachments SET local_path = :localPath WHERE guid = :guid")
    suspend fun updateLocalPath(guid: String, localPath: String?)

    @Query("UPDATE attachments SET blurhash = :blurhash WHERE guid = :guid")
    suspend fun updateBlurhash(guid: String, blurhash: String?)

    @Query("UPDATE attachments SET thumbnail_path = :thumbnailPath WHERE guid = :guid")
    suspend fun updateThumbnailPath(guid: String, thumbnailPath: String?)

    @Query("UPDATE attachments SET height = :height, width = :width WHERE guid = :guid")
    suspend fun updateDimensions(guid: String, height: Int, width: Int)

    // ===== Transfer State Updates =====

    /**
     * Update the transfer state for an attachment.
     */
    @Query("UPDATE attachments SET transfer_state = :state WHERE guid = :guid")
    suspend fun updateTransferState(guid: String, state: String)

    /**
     * Update both transfer state and progress.
     */
    @Query("UPDATE attachments SET transfer_state = :state, transfer_progress = :progress WHERE guid = :guid")
    suspend fun updateTransferProgress(guid: String, state: String, progress: Float)

    /**
     * Mark an attachment as failed with the given state.
     */
    @Query("UPDATE attachments SET transfer_state = 'FAILED', transfer_progress = 0 WHERE guid = :guid")
    suspend fun markTransferFailed(guid: String)

    /**
     * Mark an attachment as failed with error details.
     */
    @Query("""
        UPDATE attachments SET
            transfer_state = 'FAILED',
            transfer_progress = 0,
            error_type = :errorType,
            error_message = :errorMessage,
            retry_count = retry_count + 1
        WHERE guid = :guid
    """)
    suspend fun markTransferFailedWithError(guid: String, errorType: String, errorMessage: String)

    /**
     * Clear error state and reset for retry.
     */
    @Query("""
        UPDATE attachments SET
            transfer_state = 'PENDING',
            error_type = NULL,
            error_message = NULL
        WHERE guid = :guid
    """)
    suspend fun clearErrorForRetry(guid: String)

    /**
     * Get all failed attachments.
     */
    @Query("SELECT * FROM attachments WHERE transfer_state = 'FAILED'")
    suspend fun getFailedAttachments(): List<AttachmentEntity>

    /**
     * Get failed attachments for a chat.
     */
    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid AND a.transfer_state = 'FAILED'
        ORDER BY m.date_created DESC
    """)
    suspend fun getFailedAttachmentsForChat(chatGuid: String): List<AttachmentEntity>

    /**
     * Get retryable failed attachments (retry_count < max).
     */
    @Query("SELECT * FROM attachments WHERE transfer_state = 'FAILED' AND retry_count < :maxRetries")
    suspend fun getRetryableFailedAttachments(maxRetries: Int): List<AttachmentEntity>

    /**
     * Mark an attachment as downloaded and set its local path.
     */
    @Query("UPDATE attachments SET transfer_state = 'DOWNLOADED', transfer_progress = 1.0, local_path = :localPath WHERE guid = :guid")
    suspend fun markDownloaded(guid: String, localPath: String)

    /**
     * Mark an attachment as uploaded (outbound attachment finished uploading).
     */
    @Query("UPDATE attachments SET transfer_state = 'UPLOADED', transfer_progress = 1.0 WHERE guid = :guid")
    suspend fun markUploaded(guid: String)

    /**
     * Get attachments by transfer state.
     */
    @Query("SELECT * FROM attachments WHERE transfer_state = :state")
    suspend fun getByTransferState(state: String): List<AttachmentEntity>

    /**
     * Get pending downloads for a chat using transfer state.
     */
    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid AND a.transfer_state IN ('PENDING', 'FAILED')
        ORDER BY m.date_created DESC
    """)
    suspend fun getPendingDownloadsByState(chatGuid: String): List<AttachmentEntity>

    /**
     * Get all attachments currently uploading.
     */
    @Query("SELECT * FROM attachments WHERE transfer_state = 'UPLOADING'")
    suspend fun getUploadingAttachments(): List<AttachmentEntity>

    /**
     * Get all attachments currently downloading.
     */
    @Query("SELECT * FROM attachments WHERE transfer_state = 'DOWNLOADING'")
    suspend fun getDownloadingAttachments(): List<AttachmentEntity>

    // ===== Reels Feed =====

    /**
     * Mark a video attachment as viewed in the Reels feed.
     */
    @Query("UPDATE attachments SET viewed_in_reels = 1 WHERE guid = :guid")
    suspend fun markViewedInReels(guid: String)

    // ===== Cleanup =====

    /**
     * Delete orphaned temp attachments whose parent message no longer exists.
     * This handles cleanup after race conditions in message send flow.
     */
    @Query("""
        DELETE FROM attachments
        WHERE guid LIKE 'temp-%'
        AND message_guid NOT IN (SELECT guid FROM messages)
    """)
    suspend fun deleteOrphanedTempAttachments(): Int

    // ===== Deletes =====

    @Query("DELETE FROM attachments WHERE guid = :guid")
    suspend fun deleteAttachment(guid: String)

    @Query("DELETE FROM attachments WHERE message_guid = :messageGuid")
    suspend fun deleteAttachmentsForMessage(messageGuid: String)

    @Query("DELETE FROM attachments")
    suspend fun deleteAllAttachments()

    // Clear local files (for storage management)
    @Query("UPDATE attachments SET local_path = NULL")
    suspend fun clearAllLocalPaths()

    @Query("UPDATE attachments SET thumbnail_path = NULL")
    suspend fun clearAllThumbnailPaths()
}
