package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.HandleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HandleDao {

    // ===== Queries =====

    @Query("SELECT * FROM handles ORDER BY cached_display_name ASC")
    fun getAllHandles(): Flow<List<HandleEntity>>

    @Query("SELECT * FROM handles")
    suspend fun getAllHandlesOnce(): List<HandleEntity>

    @Query("SELECT * FROM handles WHERE id = :id")
    suspend fun getHandleById(id: Long): HandleEntity?

    /**
     * PERF: Batch fetch handles by multiple IDs in a single query.
     * Much more efficient than calling getHandleById() in a loop.
     */
    @Query("SELECT * FROM handles WHERE id IN (:ids)")
    suspend fun getHandlesByIds(ids: List<Long>): List<HandleEntity>

    @Query("SELECT * FROM handles WHERE address = :address AND service = :service")
    suspend fun getHandleByAddressAndService(address: String, service: String): HandleEntity?

    @Query("SELECT * FROM handles WHERE address = :address")
    suspend fun getHandlesByAddress(address: String): List<HandleEntity>

    @Query("SELECT * FROM handles WHERE address = :address LIMIT 1")
    suspend fun getHandleByAddressAny(address: String): HandleEntity?

    @Query("""
        SELECT * FROM handles
        WHERE address LIKE '%' || :query || '%'
           OR cached_display_name LIKE '%' || :query || '%'
        ORDER BY cached_display_name ASC
    """)
    fun searchHandles(query: String): Flow<List<HandleEntity>>

    /**
     * Get handles that have recent 1-on-1 conversations, ordered by most recent.
     * Used for the "Recent" section in the chat creator.
     */
    @Query("""
        SELECT h.* FROM handles h
        INNER JOIN chat_handle_cross_ref chr ON h.id = chr.handle_id
        INNER JOIN chats c ON chr.chat_guid = c.guid
        WHERE c.is_group = 0 AND c.date_deleted IS NULL
        GROUP BY h.address
        ORDER BY MAX(c.latest_message_date) DESC
        LIMIT 4
    """)
    fun getRecentContacts(): Flow<List<HandleEntity>>

    @Query("SELECT COUNT(*) FROM handles")
    suspend fun getHandleCount(): Int

    // ===== Inserts/Updates =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHandle(handle: HandleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHandles(handles: List<HandleEntity>)

    @Update
    suspend fun updateHandle(handle: HandleEntity)

    @Query("UPDATE handles SET color = :color WHERE id = :id")
    suspend fun updateColor(id: Long, color: String?)

    @Query("UPDATE handles SET cached_display_name = :displayName, cached_avatar_path = :avatarPath WHERE id = :id")
    suspend fun updateCachedContactInfo(id: Long, displayName: String?, avatarPath: String?)

    @Query("UPDATE handles SET default_phone = :phone WHERE id = :id")
    suspend fun updateDefaultPhone(id: Long, phone: String?)

    @Query("UPDATE handles SET default_email = :email WHERE id = :id")
    suspend fun updateDefaultEmail(id: Long, email: String?)

    @Query("UPDATE handles SET inferred_name = :inferredName WHERE id = :id")
    suspend fun updateInferredName(id: Long, inferredName: String?)

    @Query("UPDATE handles SET inferred_name = NULL WHERE id = :id")
    suspend fun clearInferredName(id: Long)

    @Query("UPDATE handles SET inferred_name = NULL WHERE address = :address")
    suspend fun clearInferredNameByAddress(address: String)

    // ===== Spam =====

    @Query("UPDATE handles SET spam_report_count = spam_report_count + 1 WHERE id = :id")
    suspend fun incrementSpamReportCount(id: Long)

    @Query("UPDATE handles SET spam_report_count = spam_report_count + 1 WHERE address = :address")
    suspend fun incrementSpamReportCountByAddress(address: String)

    @Query("UPDATE handles SET is_whitelisted = :isWhitelisted WHERE id = :id")
    suspend fun updateWhitelisted(id: Long, isWhitelisted: Boolean)

    @Query("UPDATE handles SET is_whitelisted = :isWhitelisted WHERE address = :address")
    suspend fun updateWhitelistedByAddress(address: String, isWhitelisted: Boolean)

    @Query("UPDATE handles SET spam_report_count = 0, is_whitelisted = 0 WHERE id = :id")
    suspend fun resetSpamStatus(id: Long)

    // ===== Deletes =====

    @Query("DELETE FROM handles WHERE id = :id")
    suspend fun deleteHandle(id: Long)

    @Query("DELETE FROM handles")
    suspend fun deleteAllHandles()

    // ===== Upsert =====

    /**
     * Upsert a handle, preserving existing good values.
     * When updating, new values only override existing if they are non-blank.
     * This prevents accidental data loss from transient lookup failures.
     */
    @Transaction
    suspend fun upsertHandle(handle: HandleEntity): Long {
        val existing = getHandleByAddressAndService(handle.address, handle.service)
        return if (existing != null) {
            // Smart merge: prefer new non-blank values, otherwise keep existing
            val merged = existing.copy(
                originalRowId = handle.originalRowId ?: existing.originalRowId,
                formattedAddress = handle.formattedAddress?.takeIf { it.isNotBlank() } ?: existing.formattedAddress,
                country = handle.country?.takeIf { it.isNotBlank() } ?: existing.country,
                color = handle.color ?: existing.color,
                defaultEmail = handle.defaultEmail?.takeIf { it.isNotBlank() } ?: existing.defaultEmail,
                defaultPhone = handle.defaultPhone?.takeIf { it.isNotBlank() } ?: existing.defaultPhone,
                // Contact info: prefer new non-blank values, otherwise keep existing
                cachedDisplayName = handle.cachedDisplayName?.takeIf { it.isNotBlank() } ?: existing.cachedDisplayName,
                cachedAvatarPath = handle.cachedAvatarPath?.takeIf { it.isNotBlank() } ?: existing.cachedAvatarPath,
                inferredName = handle.inferredName?.takeIf { it.isNotBlank() } ?: existing.inferredName
                // Note: spamReportCount and isWhitelisted are preserved from existing
                // They are managed by dedicated methods (incrementSpamReportCount, updateWhitelisted)
            )
            updateHandle(merged)
            existing.id
        } else {
            insertHandle(handle)
        }
    }
}
