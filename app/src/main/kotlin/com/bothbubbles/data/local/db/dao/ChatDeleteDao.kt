package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Delete operations for chats.
 * Separated from main ChatDao for better organization.
 */
@Dao
interface ChatDeleteDao {

    @Query("UPDATE chats SET date_deleted = :timestamp WHERE guid = :guid")
    suspend fun softDeleteChat(guid: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET date_deleted = NULL WHERE guid = :guid")
    suspend fun restoreChat(guid: String)

    @Query("DELETE FROM chats WHERE guid = :guid")
    suspend fun deleteChat(guid: String)

    @Query("DELETE FROM chats WHERE guid = :guid")
    suspend fun deleteChatByGuid(guid: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAllChats()

    @Query("""
        DELETE FROM chats
        WHERE guid LIKE 'iMessage%'
        OR (guid NOT LIKE 'sms:%' AND guid NOT LIKE 'mms:%')
    """)
    suspend fun deleteServerChats()
}
