package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.ChatDto
import com.bothbubbles.data.remote.api.dto.ChatQueryRequest
import com.bothbubbles.ui.components.PhoneAndCodeParsingUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val api: BothBubblesApi
) {
    // ===== Local Operations =====

    fun observeAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    fun observeActiveChats(): Flow<List<ChatEntity>> = chatDao.getActiveChats()

    fun observeArchivedChats(): Flow<List<ChatEntity>> = chatDao.getArchivedChats()

    fun observeStarredChats(): Flow<List<ChatEntity>> = chatDao.getStarredChats()

    fun observeChat(guid: String): Flow<ChatEntity?> = chatDao.observeChatByGuid(guid)

    suspend fun getChat(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)

    suspend fun getChatCount(): Int = chatDao.getChatCount()

    fun observeArchivedChatCount(): Flow<Int> = chatDao.getArchivedChatCount()

    fun observeStarredChatCount(): Flow<Int> = chatDao.getStarredChatCount()

    fun observeUnreadChatCount(): Flow<Int> = chatDao.getUnreadChatCount()

    fun observeParticipantsForChat(chatGuid: String): Flow<List<HandleEntity>> =
        chatDao.observeParticipantsForChat(chatGuid)

    /**
     * Get the first participant's phone number/address for a chat.
     * Used for blocking functionality.
     */
    suspend fun getChatParticipantAddress(chatGuid: String): String? {
        val participants = chatDao.getParticipantsForChat(chatGuid)
        return participants.firstOrNull()?.address
    }

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
     * Mark chat as unread locally
     */
    suspend fun markChatAsUnread(guid: String): Result<Unit> = runCatching {
        chatDao.updateUnreadStatus(guid, true)
        // Set unread count to 1 to indicate there are unread messages
        chatDao.updateUnreadCount(guid, 1)
    }

    /**
     * Mark all chats as read locally (batch operation)
     */
    suspend fun markAllChatsAsRead(): Result<Int> = runCatching {
        chatDao.markAllChatsAsRead()
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
     * Star/unstar a chat (local only)
     */
    suspend fun setStarred(guid: String, isStarred: Boolean): Result<Unit> = runCatching {
        chatDao.updateStarredStatus(guid, isStarred)
    }

    /**
     * Delete a chat locally
     */
    suspend fun deleteChat(guid: String): Result<Unit> = runCatching {
        messageDao.deleteMessagesForChat(guid)
        chatDao.deleteChatByGuid(guid)
    }

    // ===== Per-Chat Notification Settings =====

    suspend fun setNotificationsEnabled(guid: String, enabled: Boolean): Result<Unit> = runCatching {
        chatDao.updateNotificationsEnabled(guid, enabled)
    }

    suspend fun setNotificationPriority(guid: String, priority: String): Result<Unit> = runCatching {
        chatDao.updateNotificationPriority(guid, priority)
    }

    suspend fun setBubbleEnabled(guid: String, enabled: Boolean): Result<Unit> = runCatching {
        chatDao.updateBubbleEnabled(guid, enabled)
    }

    suspend fun setPopOnScreen(guid: String, enabled: Boolean): Result<Unit> = runCatching {
        chatDao.updatePopOnScreen(guid, enabled)
    }

    suspend fun setNotificationSound(guid: String, sound: String?): Result<Unit> = runCatching {
        chatDao.updateNotificationSound(guid, sound)
    }

    suspend fun setLockScreenVisibility(guid: String, visibility: String): Result<Unit> = runCatching {
        chatDao.updateLockScreenVisibility(guid, visibility)
    }

    suspend fun setShowNotificationDot(guid: String, enabled: Boolean): Result<Unit> = runCatching {
        chatDao.updateShowNotificationDot(guid, enabled)
    }

    suspend fun setVibrationEnabled(guid: String, enabled: Boolean): Result<Unit> = runCatching {
        chatDao.updateVibrationEnabled(guid, enabled)
    }

    /**
     * Snooze notifications for a chat until a specific time.
     * @param guid The chat GUID
     * @param snoozeUntil Epoch timestamp when snooze expires, -1 for indefinite, null to unsnooze
     */
    suspend fun setSnoozeUntil(guid: String, snoozeUntil: Long?): Result<Unit> = runCatching {
        chatDao.updateSnoozeUntil(guid, snoozeUntil)
    }

    /**
     * Snooze notifications for a chat for a specific duration.
     * @param guid The chat GUID
     * @param durationMs Duration in milliseconds, or -1 for indefinite
     */
    suspend fun snoozeChat(guid: String, durationMs: Long): Result<Unit> = runCatching {
        val snoozeUntil = if (durationMs == -1L) -1L else System.currentTimeMillis() + durationMs
        chatDao.updateSnoozeUntil(guid, snoozeUntil)
    }

    /**
     * Unsnooze a chat (remove snooze)
     */
    suspend fun unsnoozeChat(guid: String): Result<Unit> = runCatching {
        chatDao.updateSnoozeUntil(guid, null)
    }

    /**
     * Update draft text for a chat
     */
    suspend fun updateDraftText(guid: String, text: String?) {
        chatDao.updateDraftText(guid, text?.takeIf { it.isNotBlank() })
    }

    /**
     * Update the last message info for a chat
     */
    suspend fun updateLastMessage(chatGuid: String, text: String?, date: Long) {
        chatDao.updateLatestMessageDate(chatGuid, date)
    }

    // ===== Private Helpers =====

    /**
     * Update cached contact info for a handle by address.
     * Used when a contact is added/modified in the device contacts.
     */
    suspend fun updateHandleCachedContactInfo(address: String, displayName: String?, avatarPath: String? = null) {
        val handles = handleDao.getHandlesByAddress(address)
        handles.forEach { handle ->
            handleDao.updateCachedContactInfo(handle.id, displayName, avatarPath)
        }
    }

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

        // For single-contact iMessage chats, link to unified group
        val participants = chatDto.participants
        if (participants != null && participants.size == 1) {
            val participant = participants.first()
            val normalizedPhone = PhoneAndCodeParsingUtils.normalizePhoneNumber(participant.address)
            linkChatToUnifiedGroup(chatDto.guid, normalizedPhone, chatDto.displayName)
        }
    }

    /**
     * Link an iMessage chat to a unified group, creating the group if necessary.
     * This enables merging iMessage and SMS conversations for the same contact.
     */
    private suspend fun linkChatToUnifiedGroup(
        chatGuid: String,
        normalizedPhone: String,
        displayName: String?
    ) {
        // Check if this chat is already in a unified group
        if (unifiedChatGroupDao.isChatInUnifiedGroup(chatGuid)) {
            return
        }

        // Check if unified group already exists for this phone number
        var group = unifiedChatGroupDao.getGroupByIdentifier(normalizedPhone)

        if (group == null) {
            // Create new unified group with this iMessage chat as primary
            val newGroup = UnifiedChatGroupEntity(
                identifier = normalizedPhone,
                primaryChatGuid = chatGuid,
                displayName = displayName
            )
            val groupId = unifiedChatGroupDao.insertGroup(newGroup)
            group = newGroup.copy(id = groupId)
        }

        // Add this iMessage chat to the unified group
        unifiedChatGroupDao.insertMember(
            UnifiedChatMember(groupId = group.id, chatGuid = chatGuid)
        )
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
