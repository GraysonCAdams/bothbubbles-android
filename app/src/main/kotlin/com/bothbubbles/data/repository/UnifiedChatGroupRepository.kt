package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for unified chat group operations.
 *
 * Unified chat groups merge iMessage and SMS conversations with the same contact,
 * allowing users to see all messages in a single thread.
 */
@Singleton
class UnifiedChatGroupRepository @Inject constructor(
    private val unifiedChatGroupDao: UnifiedChatGroupDao
) {
    // ===== Query Operations =====

    /**
     * Observe active (non-archived) unified groups.
     */
    fun observeActiveGroups(): Flow<List<UnifiedChatGroupEntity>> =
        unifiedChatGroupDao.observeActiveGroups()

    /**
     * Get active groups with pagination.
     */
    suspend fun getActiveGroupsPaginated(limit: Int, offset: Int): List<UnifiedChatGroupEntity> =
        unifiedChatGroupDao.getActiveGroupsPaginated(limit, offset)

    /**
     * Get the count of active groups.
     */
    suspend fun getActiveGroupCount(): Int =
        unifiedChatGroupDao.getActiveGroupCount()

    /**
     * Observe the count of active groups.
     */
    fun observeActiveGroupCount(): Flow<Int> =
        unifiedChatGroupDao.observeActiveGroupCount()

    /**
     * Observe the count of archived groups.
     */
    fun observeArchivedGroupCount(): Flow<Int> =
        unifiedChatGroupDao.observeArchivedGroupCount()

    /**
     * Observe the count of starred groups.
     */
    fun observeStarredGroupCount(): Flow<Int> =
        unifiedChatGroupDao.observeStarredGroupCount()

    /**
     * Observe the total unread count across all active groups.
     */
    fun observeTotalUnreadCount(): Flow<Int?> =
        unifiedChatGroupDao.observeTotalUnreadCount()

    /**
     * Get a group by its ID.
     */
    suspend fun getGroupById(groupId: Long): UnifiedChatGroupEntity? =
        unifiedChatGroupDao.getGroupById(groupId)

    /**
     * Observe a group by its ID.
     */
    fun observeGroupById(groupId: Long): Flow<UnifiedChatGroupEntity?> =
        unifiedChatGroupDao.observeGroupById(groupId)

    /**
     * Get the unified group that contains a specific chat.
     */
    suspend fun getGroupForChat(chatGuid: String): UnifiedChatGroupEntity? =
        unifiedChatGroupDao.getGroupForChat(chatGuid)

    /**
     * Observe the unified group for a specific chat.
     */
    fun observeGroupForChat(chatGuid: String): Flow<UnifiedChatGroupEntity?> =
        unifiedChatGroupDao.observeGroupForChat(chatGuid)

    /**
     * Check if a chat is part of a unified group.
     */
    suspend fun isChatInUnifiedGroup(chatGuid: String): Boolean =
        unifiedChatGroupDao.isChatInUnifiedGroup(chatGuid)

    /**
     * Get all chat GUIDs for a specific group.
     */
    suspend fun getChatGuidsForGroup(groupId: Long): List<String> =
        unifiedChatGroupDao.getChatGuidsForGroup(groupId)

    /**
     * Batch fetch chat GUIDs for multiple groups.
     */
    suspend fun getChatGuidsForGroups(groupIds: List<Long>): List<UnifiedChatMember> =
        unifiedChatGroupDao.getChatGuidsForGroups(groupIds)

    /**
     * Observe chat GUIDs for a group.
     */
    fun observeChatGuidsForGroup(groupId: Long): Flow<List<String>> =
        unifiedChatGroupDao.observeChatGuidsForGroup(groupId)

    // ===== Filtered Queries for Select All Feature =====

    /**
     * Get count of unified groups matching filter criteria.
     * Used for Gmail-style "Select All" to count all matching conversations.
     *
     * @param filter The conversation filter to apply
     * @param categoryFilter Optional category filter
     * @return Count of matching unified groups (excluding pinned)
     */
    suspend fun getFilteredGroupCount(
        filter: com.bothbubbles.ui.conversations.ConversationFilter,
        categoryFilter: String?
    ): Int {
        val (includeSpam, unreadOnly) = filterToParams(filter)
        return unifiedChatGroupDao.getFilteredGroupCount(includeSpam, unreadOnly, categoryFilter)
    }

    /**
     * Get primary GUIDs of unified groups matching filter criteria (paginated).
     */
    suspend fun getFilteredGroupGuids(
        filter: com.bothbubbles.ui.conversations.ConversationFilter,
        categoryFilter: String?,
        limit: Int,
        offset: Int
    ): List<String> {
        val (includeSpam, unreadOnly) = filterToParams(filter)
        return unifiedChatGroupDao.getFilteredGroupGuids(includeSpam, unreadOnly, categoryFilter, limit, offset)
    }

    /**
     * Convert ConversationFilter to database query parameters.
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

    // ===== Mutation Operations =====

    /**
     * Update the pin status for a group.
     */
    suspend fun updatePinStatus(groupId: Long, isPinned: Boolean, pinIndex: Int?) {
        unifiedChatGroupDao.updatePinStatus(groupId, isPinned, pinIndex)
    }

    /**
     * Update the archive status for a group.
     */
    suspend fun updateArchiveStatus(groupId: Long, isArchived: Boolean) {
        unifiedChatGroupDao.updateArchiveStatus(groupId, isArchived)
    }

    /**
     * Update the starred status for a group.
     */
    suspend fun updateStarredStatus(groupId: Long, isStarred: Boolean) {
        unifiedChatGroupDao.updateStarredStatus(groupId, isStarred)
    }

    /**
     * Update the mute status for a group.
     */
    suspend fun updateMuteStatus(groupId: Long, muteType: String?) {
        unifiedChatGroupDao.updateMuteStatus(groupId, muteType)
    }

    /**
     * Update the snooze until timestamp for a group.
     */
    suspend fun updateSnoozeUntil(groupId: Long, snoozeUntil: Long?) {
        unifiedChatGroupDao.updateSnoozeUntil(groupId, snoozeUntil)
    }

    /**
     * Update the display name for a group.
     */
    suspend fun updateDisplayName(groupId: Long, displayName: String?) {
        unifiedChatGroupDao.updateDisplayName(groupId, displayName)
    }

    /**
     * Update the unread count for a group.
     */
    suspend fun updateUnreadCount(groupId: Long, count: Int) {
        unifiedChatGroupDao.updateUnreadCount(groupId, count)
    }

    /**
     * Atomically increment the unread count for a group.
     */
    suspend fun incrementUnreadCount(groupId: Long) {
        unifiedChatGroupDao.incrementUnreadCount(groupId)
    }

    /**
     * Update all cached message fields for a group.
     */
    suspend fun updateLatestMessageFull(
        groupId: Long,
        date: Long,
        text: String?,
        guid: String?,
        isFromMe: Boolean,
        hasAttachments: Boolean,
        source: String?,
        dateDelivered: Long?,
        dateRead: Long?,
        error: Int
    ) {
        unifiedChatGroupDao.updateLatestMessageFull(
            groupId, date, text, guid, isFromMe, hasAttachments,
            source, dateDelivered, dateRead, error
        )
    }

    // ===== Delete Operations =====

    /**
     * Delete all unified group data.
     */
    suspend fun deleteAllData() {
        unifiedChatGroupDao.deleteAllData()
    }

    /**
     * Delete a specific group.
     */
    suspend fun deleteGroup(groupId: Long) {
        unifiedChatGroupDao.deleteGroup(groupId)
    }

    /**
     * Delete orphaned groups (groups with no members).
     */
    suspend fun deleteOrphanedGroups(): Int =
        unifiedChatGroupDao.deleteOrphanedGroups()
}
