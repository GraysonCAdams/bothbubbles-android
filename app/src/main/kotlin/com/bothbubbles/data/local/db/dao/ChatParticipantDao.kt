package com.bothbubbles.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Participant management operations for chats.
 * Separated from main ChatDao for better organization.
 */
@Dao
interface ChatParticipantDao {

    @Query("""
        SELECT h.* FROM handles h
        INNER JOIN chat_handle_cross_ref chr ON h.id = chr.handle_id
        WHERE chr.chat_guid = :chatGuid
    """)
    suspend fun getParticipantsForChat(chatGuid: String): List<HandleEntity>

    @Query("""
        SELECT DISTINCT h.* FROM handles h
        INNER JOIN chat_handle_cross_ref chr ON h.id = chr.handle_id
        WHERE chr.chat_guid IN (:chatGuids)
    """)
    suspend fun getParticipantsForChats(chatGuids: List<String>): List<HandleEntity>

    @Query("""
        SELECT h.*, chr.chat_guid as chatGuid FROM handles h
        INNER JOIN chat_handle_cross_ref chr ON h.id = chr.handle_id
        WHERE chr.chat_guid IN (:chatGuids)
    """)
    suspend fun getParticipantsWithChatGuids(chatGuids: List<String>): List<HandleWithChatGuid>

    @Query("""
        SELECT h.* FROM handles h
        INNER JOIN chat_handle_cross_ref chr ON h.id = chr.handle_id
        WHERE chr.chat_guid = :chatGuid
    """)
    fun observeParticipantsForChat(chatGuid: String): Flow<List<HandleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatHandleCrossRef(crossRef: ChatHandleCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatHandleCrossRefs(crossRefs: List<ChatHandleCrossRef>)

    @Query("DELETE FROM chat_handle_cross_ref WHERE chat_guid = :chatGuid")
    suspend fun deleteParticipantsForChat(chatGuid: String)

    @Query("DELETE FROM chat_handle_cross_ref")
    suspend fun deleteAllChatHandleCrossRefs()

    @Query("""
        DELETE FROM chat_handle_cross_ref
        WHERE chat_guid LIKE 'iMessage%'
        OR (chat_guid NOT LIKE 'sms:%' AND chat_guid NOT LIKE 'mms:%')
    """)
    suspend fun deleteServerChatCrossRefs()
}

/**
 * Handle with its associated chat GUID for grouping participants by chat.
 */
data class HandleWithChatGuid(
    @Embedded val handle: HandleEntity,
    @ColumnInfo(name = "chatGuid") val chatGuid: String
)
