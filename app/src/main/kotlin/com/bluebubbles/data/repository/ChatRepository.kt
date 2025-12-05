package com.bluebubbles.data.repository

import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.ChatHandleCrossRef
import com.bluebubbles.data.local.db.entity.HandleEntity
import com.bluebubbles.data.remote.api.BlueBubblesApi
import com.bluebubbles.data.remote.api.dto.ChatDto
import com.bluebubbles.data.remote.api.dto.ChatQueryRequest
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val api: BlueBubblesApi
) {
    // ===== Local Operations =====

    fun observeAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    fun observeActiveChats(): Flow<List<ChatEntity>> = chatDao.getActiveChats()

    fun observeArchivedChats(): Flow<List<ChatEntity>> = chatDao.getArchivedChats()

    fun observeChat(guid: String): Flow<ChatEntity?> = chatDao.observeChatByGuid(guid)

    suspend fun getChat(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)

    suspend fun getChatCount(): Int = chatDao.getChatCount()

    // ===== Remote Operations =====

    /**
     * Fetch all chats from server and sync to local database
     */
    suspend fun syncChats(
        limit: Int = 100,
        offset: Int = 0
    ): Result<List<ChatEntity>> = runCatching {
        val response = api.queryChats(
            ChatQueryRequest(
                with = listOf("participants", "lastmessage"),
                limit = limit,
                offset = offset,
                sort = "lastmessage"
            )
        )

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            throw Exception(body?.message ?: "Failed to fetch chats")
        }

        val chats = body.data.orEmpty().map { it.toEntity() }

        // Insert chats and their participants
        chats.forEach { chat ->
            chatDao.insertChat(chat)
        }

        // Also sync participants (handles)
        body.data.orEmpty().forEach { chatDto ->
            syncChatParticipants(chatDto)
        }

        chats
    }

    /**
     * Fetch a single chat from server
     */
    suspend fun fetchChat(guid: String): Result<ChatEntity> = runCatching {
        val response = api.getChat(guid)

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            throw Exception(body?.message ?: "Failed to fetch chat")
        }

        val chatDto = body.data ?: throw Exception("Chat not found")
        val chat = chatDto.toEntity()

        chatDao.insertChat(chat)
        syncChatParticipants(chatDto)

        chat
    }

    /**
     * Mark chat as read on server and locally
     */
    suspend fun markChatAsRead(guid: String): Result<Unit> = runCatching {
        // Update locally first for immediate UI feedback
        chatDao.updateUnreadStatus(guid, false)
        chatDao.updateUnreadCount(guid, 0)

        // Then sync to server
        api.markChatRead(guid)
    }

    /**
     * Update chat pin status
     */
    suspend fun setPinned(guid: String, isPinned: Boolean, pinIndex: Int? = null): Result<Unit> = runCatching {
        chatDao.updatePinStatus(guid, isPinned, pinIndex)
    }

    /**
     * Update chat mute status
     */
    suspend fun setMuted(guid: String, isMuted: Boolean): Result<Unit> = runCatching {
        val muteType = if (isMuted) "muted" else null
        chatDao.updateMuteStatus(guid, muteType, null)
    }

    /**
     * Archive a chat
     */
    suspend fun setArchived(guid: String, isArchived: Boolean): Result<Unit> = runCatching {
        chatDao.updateArchiveStatus(guid, isArchived)
    }

    /**
     * Delete a chat locally
     */
    suspend fun deleteChat(guid: String): Result<Unit> = runCatching {
        messageDao.deleteMessagesForChat(guid)
        chatDao.deleteChatByGuid(guid)
    }

    /**
     * Update the last message info for a chat
     */
    suspend fun updateLastMessage(chatGuid: String, text: String?, date: Long) {
        chatDao.updateLatestMessageDate(chatGuid, date)
    }

    // ===== Private Helpers =====

    private suspend fun syncChatParticipants(chatDto: ChatDto) {
        chatDto.participants?.forEach { handleDto ->
            val handle = HandleEntity(
                address = handleDto.address,
                service = handleDto.service,
                country = handleDto.country,
                formattedAddress = handleDto.formattedAddress,
                defaultEmail = handleDto.defaultEmail,
                defaultPhone = handleDto.defaultPhone,
                originalRowId = handleDto.originalRowId
            )
            // Insert and get the ID
            val handleId = handleDao.insertHandle(handle)

            // Create cross-reference using the handle ID
            val crossRef = ChatHandleCrossRef(
                chatGuid = chatDto.guid,
                handleId = handleId
            )
            chatDao.insertChatHandleCrossRef(crossRef)
        }
    }

    private fun ChatDto.toEntity(): ChatEntity {
        return ChatEntity(
            guid = guid,
            chatIdentifier = chatIdentifier,
            displayName = displayName,
            isGroup = (participants?.size ?: 0) > 1,
            lastMessageDate = lastMessage?.dateCreated,
            lastMessageText = lastMessage?.text,
            latestMessageDate = lastMessage?.dateCreated,
            unreadCount = 0,
            hasUnreadMessage = hasUnreadMessage,
            isPinned = isPinned,
            isArchived = isArchived,
            style = style,
            autoSendReadReceipts = true,
            autoSendTypingIndicators = true
        )
    }
}
