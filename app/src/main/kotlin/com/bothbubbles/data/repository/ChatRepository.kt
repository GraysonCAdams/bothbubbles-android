package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.TombstoneDao
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.core.network.api.BothBubblesApi
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main repository for chat operations.
 * Complex operations have been extracted to:
 * - ChatSyncOperations: Remote sync operations
 * - ChatParticipantOperations: Participant management
 * - UnifiedGroupOperations: Unified group linking
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val tombstoneDao: TombstoneDao,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val api: BothBubblesApi,
    private val syncOps: ChatSyncOperations,
    private val participantOps: ChatParticipantOperations
) {

    // ===== Local Query Operations =====

    fun observeAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    fun observeActiveChats(): Flow<List<ChatEntity>> = chatDao.getActiveChats()

    fun observeArchivedChats(): Flow<List<ChatEntity>> = chatDao.getArchivedChats()

    fun observeStarredChats(): Flow<List<ChatEntity>> = chatDao.getStarredChats()

    fun observeChat(guid: String): Flow<ChatEntity?> = chatDao.observeChatByGuid(guid)

    suspend fun getChat(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)

    suspend fun getChatsByGuids(guids: List<String>): List<ChatEntity> = chatDao.getChatsByGuids(guids)

    suspend fun getChatCount(): Int = chatDao.getChatCount()

    fun observeArchivedChatCount(): Flow<Int> = chatDao.getArchivedChatCount()

    fun observeStarredChatCount(): Flow<Int> = chatDao.getStarredChatCount()

    fun observeUnreadChatCount(): Flow<Int> = chatDao.getUnreadChatCount()

    fun observeTotalUnreadMessageCount(): Flow<Int> = chatDao.observeTotalUnreadMessageCount()

    // ===== Group/Non-Group Chat Queries =====

    suspend fun getGroupChatsPaginated(limit: Int, offset: Int): List<ChatEntity> =
        chatDao.getGroupChatsPaginated(limit, offset)

    suspend fun getNonGroupChatsPaginated(limit: Int, offset: Int): List<ChatEntity> =
        chatDao.getNonGroupChatsPaginated(limit, offset)

    suspend fun getGroupChatCount(): Int = chatDao.getGroupChatCount()

    suspend fun getNonGroupChatCount(): Int = chatDao.getNonGroupChatCount()

    fun observeGroupChatCount(): Flow<Int> = chatDao.observeGroupChatCount()

    fun observeNonGroupChatCount(): Flow<Int> = chatDao.observeNonGroupChatCount()

    fun searchGroupChats(query: String): Flow<List<ChatEntity>> = chatDao.searchGroupChats(query)

    fun getRecentGroupChats(): Flow<List<ChatEntity>> = chatDao.getRecentGroupChats()

    // ===== Filtered Queries for Select All Feature =====

    /**
     * Get count of group chats matching filter criteria.
     * Used for Gmail-style "Select All" to count all matching conversations.
     *
     * @param filter The conversation filter to apply
     * @param categoryFilter Optional category filter
     * @return Count of matching group chats (excluding pinned)
     */
    suspend fun getFilteredGroupChatCount(
        filter: com.bothbubbles.ui.conversations.ConversationFilter,
        categoryFilter: String?
    ): Int {
        val (includeSpam, unreadOnly) = filterToParams(filter)
        return chatDao.getFilteredGroupChatCount(includeSpam, unreadOnly, categoryFilter)
    }

    /**
     * Get count of non-group chats matching filter criteria.
     */
    suspend fun getFilteredNonGroupChatCount(
        filter: com.bothbubbles.ui.conversations.ConversationFilter,
        categoryFilter: String?
    ): Int {
        val (includeSpam, unreadOnly) = filterToParams(filter)
        return chatDao.getFilteredNonGroupChatCount(includeSpam, unreadOnly, categoryFilter)
    }

    /**
     * Get GUIDs of group chats matching filter criteria (paginated).
     */
    suspend fun getFilteredGroupChatGuids(
        filter: com.bothbubbles.ui.conversations.ConversationFilter,
        categoryFilter: String?,
        limit: Int,
        offset: Int
    ): List<String> {
        val (includeSpam, unreadOnly) = filterToParams(filter)
        return chatDao.getFilteredGroupChatGuids(includeSpam, unreadOnly, categoryFilter, limit, offset)
    }

    /**
     * Get GUIDs of non-group chats matching filter criteria (paginated).
     */
    suspend fun getFilteredNonGroupChatGuids(
        filter: com.bothbubbles.ui.conversations.ConversationFilter,
        categoryFilter: String?,
        limit: Int,
        offset: Int
    ): List<String> {
        val (includeSpam, unreadOnly) = filterToParams(filter)
        return chatDao.getFilteredNonGroupChatGuids(includeSpam, unreadOnly, categoryFilter, limit, offset)
    }

    /**
     * Convert ConversationFilter to database query parameters.
     * Note: UNKNOWN_SENDERS and KNOWN_SENDERS filters are handled at the UI layer
     * since they require contact resolution that can't be done in SQL.
     */
    private fun filterToParams(filter: com.bothbubbles.ui.conversations.ConversationFilter): Pair<Boolean, Boolean> {
        return when (filter) {
            com.bothbubbles.ui.conversations.ConversationFilter.ALL -> false to false
            com.bothbubbles.ui.conversations.ConversationFilter.UNREAD -> false to true
            com.bothbubbles.ui.conversations.ConversationFilter.SPAM -> true to false
            // For unknown/known senders, we filter on the UI side
            com.bothbubbles.ui.conversations.ConversationFilter.UNKNOWN_SENDERS -> false to false
            com.bothbubbles.ui.conversations.ConversationFilter.KNOWN_SENDERS -> false to false
        }
    }

    // ===== Participant Operations (delegated) =====

    suspend fun getParticipantsForChat(chatGuid: String): List<HandleEntity> =
        participantOps.getParticipantsForChat(chatGuid)

    fun observeParticipantsForChat(chatGuid: String): Flow<List<HandleEntity>> =
        participantOps.observeParticipantsForChat(chatGuid)

    /**
     * Observe participants from multiple chats (for merged/unified conversations).
     * Combines and deduplicates participants from all specified chats.
     */
    fun observeParticipantsForChats(chatGuids: List<String>): Flow<List<HandleEntity>> =
        participantOps.observeParticipantsForChats(chatGuids)

    /**
     * Get participants from multiple chats (one-shot query for merged/unified conversations).
     * Combines and deduplicates participants from all specified chats.
     * PERF: Uses batch DAO query instead of N+1 pattern.
     */
    suspend fun getParticipantsForChats(chatGuids: List<String>): List<HandleEntity> =
        participantOps.getParticipantsForChats(chatGuids)

    /**
     * Get participants for multiple chats, grouped by chat GUID.
     * PERF: Fetches all participants in a single query and groups them.
     * @return Map of chat GUID to list of participants for that chat.
     */
    suspend fun getParticipantsGroupedByChat(chatGuids: List<String>): Map<String, List<HandleEntity>> =
        participantOps.getParticipantsGroupedByChat(chatGuids)

    /**
     * Find the "best" participant from a list - prefers one with saved contact info.
     * Used for displaying names/avatars in conversation lists.
     *
     * Priority:
     * 1. Participant with cachedDisplayName (saved contact)
     * 2. First participant in the list
     */
    fun getBestParticipant(participants: List<HandleEntity>): HandleEntity? =
        participantOps.getBestParticipant(participants)

    /**
     * Resolve the display title for a chat using consistent logic.
     *
     * For group chats: use explicit group name or generate from participant names
     * For 1:1 chats: prefer participant's displayName (from contacts or inferred),
     *                fallback to chat displayName, then formatted identifier
     *
     * @param chat The chat entity
     * @param participants The list of participants in the chat
     * @return The resolved display title
     */
    fun resolveChatTitle(chat: ChatEntity, participants: List<HandleEntity>): String {
        // For group chats: use explicit group name or generate from participants
        if (chat.isGroup) {
            return chat.displayName?.takeIf { it.isNotBlank() }
                ?: participants.take(3).joinToString(", ") { it.displayName }
                    .let { names -> if (participants.size > 3) "$names +${participants.size - 3}" else names }
                    .ifEmpty { PhoneNumberFormatter.format(chat.chatIdentifier ?: "") }
        }

        // For 1:1 chats: prefer participant's displayName (handles contact lookup, inferred names)
        val primaryParticipant = participants.firstOrNull()
        return primaryParticipant?.displayName
            ?: chat.displayName?.takeIf { it.isNotBlank() }
            ?: PhoneNumberFormatter.format(chat.chatIdentifier ?: primaryParticipant?.address ?: "")
    }

    /**
     * Get the first participant's phone number/address for a chat.
     * Used for blocking functionality.
     */
    suspend fun getChatParticipantAddress(chatGuid: String): String? =
        participantOps.getChatParticipantAddress(chatGuid)

    /**
     * Update cached contact info for a handle by address.
     * Used when a contact is added/modified in the device contacts.
     */
    suspend fun updateHandleCachedContactInfo(address: String, displayName: String?, avatarPath: String? = null) =
        participantOps.updateHandleCachedContactInfo(address, displayName, avatarPath)

    /**
     * Refresh contact info (display name and photo) for all handles from device contacts.
     * This should be called when:
     * - READ_CONTACTS permission is newly granted
     * - App starts with permission already granted (to catch contact changes)
     * Returns the number of handles updated.
     */
    suspend fun refreshAllContactInfo(): Int =
        participantOps.refreshAllContactInfo()

    // ===== Unified Group Operations =====

    /**
     * Get the unified group that contains a specific chat.
     * Used for counterpart sync to find if a chat is part of a unified group.
     *
     * @param chatGuid The chat GUID to find the unified group for
     * @return The unified group entity if the chat is part of one, null otherwise
     */
    suspend fun getUnifiedGroupForChat(chatGuid: String) =
        unifiedChatGroupDao.getGroupForChat(chatGuid)

    // ===== Local Mutation Operations =====

    suspend fun insertChat(chat: ChatEntity): Result<Unit> = runCatching {
        chatDao.insertChat(chat)
    }

    suspend fun updateDisplayName(chatGuid: String, displayName: String?): Result<Unit> = runCatching {
        chatDao.updateDisplayName(chatGuid, displayName)
    }

    suspend fun updateCustomAvatarPath(chatGuid: String, path: String?): Result<Unit> = runCatching {
        chatDao.updateCustomAvatarPath(chatGuid, path)
    }

    suspend fun deleteAllChats(): Result<Unit> = runCatching {
        chatDao.deleteAllChats()
    }

    suspend fun deleteAllChatHandleCrossRefs(): Result<Unit> = runCatching {
        chatDao.deleteAllChatHandleCrossRefs()
    }

    // ===== Remote Operations (delegated) =====

    /**
     * Get total message count from server.
     * Used for accurate progress tracking during initial sync.
     *
     * @param after Optional timestamp to count messages after (epoch ms)
     * @return Result containing total message count
     */
    suspend fun getServerMessageCount(after: Long? = null): Result<Int> = runCatching {
        val response = api.getMessageCount(after = after)
        if (response.isSuccessful) {
            response.body()?.data?.total
                ?: throw Exception("No message count in response")
        } else {
            throw Exception("Failed to get message count: ${response.code()}")
        }
    }

    /**
     * Fetch all chats from server and sync to local database.
     * Uses transactional operations to ensure data consistency.
     * Retries with exponential backoff on transient network errors.
     */
    suspend fun syncChats(
        limit: Int = 100,
        offset: Int = 0
    ): Result<List<ChatEntity>> = syncOps.syncChats(limit, offset)

    /**
     * Fetch a single chat from server.
     * Uses transactional sync to ensure chat + participants are saved atomically.
     * Retries with exponential backoff on transient network errors.
     */
    suspend fun fetchChat(guid: String): Result<ChatEntity> = syncOps.fetchChat(guid)

    /**
     * Mark chat as read on server and locally.
     * Uses optimistic local-first update for instant UI feedback.
     */
    suspend fun markChatAsRead(guid: String): Result<Unit> = runCatching {
        // Update locally first for immediate UI feedback
        chatDao.updateUnreadStatus(guid, false)
        chatDao.updateUnreadCount(guid, 0)

        // Also update the unified group's unread count for badge sync
        unifiedChatGroupDao.getGroupForChat(guid)?.let { group ->
            unifiedChatGroupDao.updateUnreadCount(group.id, 0)
        }

        // Sync to server (fire-and-forget, don't block local update)
        try {
            val response = api.markChatRead(guid)
            if (!response.isSuccessful) {
                Timber.w("Failed to mark chat as read on server: ${response.code()}")
            }
        } catch (e: IllegalArgumentException) {
            // Known issue: Moshi can't deserialize ApiResponse<Unit> because Unit is not a data class
            // TODO: Fix by changing API return type to Response<Unit> or adding UnitJsonAdapter to Moshi
            // See: core/network/src/main/kotlin/com/bothbubbles/core/network/api/BothBubblesApi.kt
            Timber.w("Moshi converter issue for markChatRead (ApiResponse<Unit>): ${e.message}")
        } catch (e: Exception) {
            Timber.w(e, "Failed to sync read status to server for chat: $guid")
        }
    }

    /**
     * Mark chat as unread on server and locally.
     * Uses optimistic local-first update for instant UI feedback.
     */
    suspend fun markChatAsUnread(guid: String): Result<Unit> = runCatching {
        // Update locally first for immediate UI feedback
        chatDao.updateUnreadStatus(guid, true)
        // Set unread count to 1 to indicate there are unread messages
        chatDao.updateUnreadCount(guid, 1)

        // Also update the unified group's unread count for badge sync
        unifiedChatGroupDao.getGroupForChat(guid)?.let { group ->
            unifiedChatGroupDao.updateUnreadCount(group.id, 1)
        }

        // Sync to server (fire-and-forget, don't block local update)
        try {
            val response = api.markChatUnread(guid)
            if (!response.isSuccessful) {
                Timber.w("Failed to mark chat as unread on server: ${response.code()}")
            }
        } catch (e: IllegalArgumentException) {
            // Known issue: Moshi can't deserialize ApiResponse<Unit> because Unit is not a data class
            // TODO: Fix by changing API return type to Response<Unit> or adding UnitJsonAdapter to Moshi
            Timber.w("Moshi converter issue for markChatUnread (ApiResponse<Unit>): ${e.message}")
        } catch (e: Exception) {
            Timber.w(e, "Failed to sync unread status to server for chat: $guid")
        }
    }

    /**
     * Mark all chats as read locally (batch operation)
     */
    suspend fun markAllChatsAsRead(): Result<Int> = runCatching {
        val count = chatDao.markAllChatsAsRead()
        // Also update all unified groups for badge sync
        unifiedChatGroupDao.markAllGroupsAsRead()
        count
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
     * Delete a chat locally.
     * Records a tombstone to prevent resurrection during sync.
     */
    suspend fun deleteChat(guid: String): Result<Unit> = runCatching {
        chatDao.deleteChatWithDependencies(guid, tombstoneDao, messageDao)
    }

    // ===== Per-Chat Notification Settings =====
    // Note: Sound, vibration, importance, and other customizations are handled by
    // Android's per-conversation notification channels. Users customize these via
    // Android Settings > Apps > BothBubbles > Notifications > [Conversation].

    suspend fun setNotificationsEnabled(guid: String, enabled: Boolean): Result<Unit> = runCatching {
        chatDao.updateNotificationsEnabled(guid, enabled)
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
    suspend fun updateDraftText(guid: String, text: String?): Result<Unit> = runCatching {
        chatDao.updateDraftText(guid, text?.takeIf { it.isNotBlank() })
    }

    /**
     * Update the last message info for a chat
     */
    suspend fun updateLastMessage(chatGuid: String, date: Long): Result<Unit> = runCatching {
        chatDao.updateLatestMessageDate(chatGuid, date)
    }

    /**
     * Update the preferred send mode for a chat (iMessage vs SMS toggle).
     *
     * @param chatGuid The chat GUID
     * @param mode The preferred mode ("imessage", "sms", or null for automatic)
     * @param manuallySet Whether the user manually set this preference
     */
    suspend fun updatePreferredSendMode(chatGuid: String, mode: String?, manuallySet: Boolean): Result<Unit> = runCatching {
        chatDao.updatePreferredSendMode(chatGuid, mode, manuallySet)
    }

    // ===== Data Cleanup =====

    /**
     * Clear invalid display names from the database.
     * This fixes existing data where display names contain internal identifiers
     * like "(smsfp)", "(smsft)", or look like chat GUIDs (e.g., "c46271").
     * Should be called once on app startup.
     */
    suspend fun cleanupInvalidDisplayNames(): Result<Int> = runCatching {
        val chatCount = chatDao.clearInvalidDisplayNames()
        val groupCount = unifiedChatGroupDao.clearInvalidDisplayNames()
        val totalCount = chatCount + groupCount
        if (totalCount > 0) {
            Timber.i("Cleaned up $totalCount invalid display names (chats: $chatCount, groups: $groupCount)")
        }
        totalCount
    }
}
