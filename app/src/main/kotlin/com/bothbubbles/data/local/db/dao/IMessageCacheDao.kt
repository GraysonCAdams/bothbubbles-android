package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.IMessageAvailabilityCacheEntity

@Dao
interface IMessageCacheDao {

    // ===== Queries =====

    @Query("SELECT * FROM bb_imessage_cache WHERE normalized_address = :address")
    suspend fun getCache(address: String): IMessageAvailabilityCacheEntity?

    @Query("SELECT * FROM bb_imessage_cache WHERE check_result = 'UNREACHABLE'")
    suspend fun getUnreachableAddresses(): List<IMessageAvailabilityCacheEntity>

    @Query("SELECT * FROM bb_imessage_cache WHERE session_id != :currentSessionId")
    suspend fun getEntriesFromPreviousSessions(currentSessionId: String): List<IMessageAvailabilityCacheEntity>

    @Query("SELECT COUNT(*) FROM bb_imessage_cache")
    suspend fun getCacheSize(): Int

    // ===== Inserts/Updates =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(cache: IMessageAvailabilityCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(caches: List<IMessageAvailabilityCacheEntity>)

    // ===== Deletes =====

    @Query("DELETE FROM bb_imessage_cache WHERE normalized_address = :address")
    suspend fun delete(address: String)

    @Query("DELETE FROM bb_imessage_cache WHERE expires_at > 0 AND expires_at < :now")
    suspend fun deleteExpired(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM bb_imessage_cache WHERE check_result = 'UNREACHABLE'")
    suspend fun deleteAllUnreachable()

    @Query("DELETE FROM bb_imessage_cache")
    suspend fun deleteAll()
}
