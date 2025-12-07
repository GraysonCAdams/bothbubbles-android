package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.HandleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HandleDao {

    // ===== Queries =====

    @Query("SELECT * FROM handles ORDER BY cached_display_name ASC")
    fun getAllHandles(): Flow<List<HandleEntity>>

    @Query("SELECT * FROM handles WHERE id = :id")
    suspend fun getHandleById(id: Long): HandleEntity?

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

    suspend fun upsertHandle(handle: HandleEntity): Long {
        val existing = getHandleByAddressAndService(handle.address, handle.service)
        return if (existing != null) {
            updateHandle(handle.copy(id = existing.id))
            existing.id
        } else {
            insertHandle(handle)
        }
    }
}
