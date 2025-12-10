package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

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

    @Query("""
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON a.message_guid = m.guid
        WHERE m.chat_guid = :chatGuid
          AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
          AND a.local_path IS NOT NULL
        ORDER BY m.date_created DESC
    """)
    suspend fun getCachedMediaForChat(chatGuid: String): List<AttachmentEntity>

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
