package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef

/**
 * Transaction operations for chats.
 * Separated from main ChatDao for better organization.
 */
@Dao
interface ChatTransactionDao : ChatUpdateDao, ChatParticipantDao {

    @Query("""
        SELECT guid, fallback_reason AS reason, fallback_updated_at AS updatedAt
        FROM chats
        WHERE is_sms_fallback = 1
    """)
    suspend fun getChatsInFallback(): List<ChatFallbackProjection>

    @Transaction
    suspend fun insertChatWithParticipants(chat: ChatEntity, participantIds: List<Long>) {
        insertChat(chat)
        val crossRefs = participantIds.map { ChatHandleCrossRef(chat.guid, it) }
        insertChatHandleCrossRefs(crossRefs)
    }

    @Transaction
    suspend fun updateChatParticipants(chatGuid: String, participantIds: List<Long>) {
        deleteParticipantsForChat(chatGuid)
        val crossRefs = participantIds.map { ChatHandleCrossRef(chatGuid, it) }
        insertChatHandleCrossRefs(crossRefs)
    }

    @Transaction
    suspend fun syncChatWithParticipants(chat: ChatEntity, participantIds: List<Long>) {
        insertChat(chat)
        deleteParticipantsForChat(chat.guid)
        val crossRefs = participantIds.map { ChatHandleCrossRef(chat.guid, it) }
        insertChatHandleCrossRefs(crossRefs)
    }

    @Transaction
    suspend fun syncChatsWithParticipants(chatParticipantPairs: List<Pair<ChatEntity, List<Long>>>) {
        chatParticipantPairs.forEach { (chat, participantIds) ->
            insertChat(chat)
            deleteParticipantsForChat(chat.guid)
            val crossRefs = participantIds.map { ChatHandleCrossRef(chat.guid, it) }
            insertChatHandleCrossRefs(crossRefs)
        }
    }

}

data class ChatFallbackProjection(
    val guid: String,
    val reason: String?,
    val updatedAt: Long?
)
