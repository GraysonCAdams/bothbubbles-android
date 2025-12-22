package com.bothbubbles.data.repository

import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.ui.conversations.ConversationFilter
import com.bothbubbles.util.UnifiedChatIdGenerator
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for unified chat operations.
 *
 * Unified chats represent conversations that may combine multiple protocol-specific
 * chats (iMessage, SMS, MMS) for the same contact. This is the single source of truth
 * for conversation-level state.
 */
@Singleton
class UnifiedChatRepository @Inject constructor(
    private val unifiedChatDao: UnifiedChatDao
) {
    // ==================== Observe Queries ====================

    /**
     * Observe active (non-archived) unified chats.
     */
    fun observeActiveChats(): Flow<List<UnifiedChatEntity>> =
        unifiedChatDao.observeActiveChats()

    /**
     * Observe archived unified chats.
     */
    fun observeArchivedChats(): Flow<List<UnifiedChatEntity>> =
        unifiedChatDao.observeArchivedChats()

    /**
     * Observe starred unified chats.
     */
    fun observeStarredChats(): Flow<List<UnifiedChatEntity>> =
        unifiedChatDao.observeStarredChats()

    /**
     * Observe all unified chats.
     */
    fun observeAllChats(): Flow<List<UnifiedChatEntity>> =
        unifiedChatDao.observeAllChats()

    /**
     * Observe a unified chat by ID.
     */
    fun observeById(id: String): Flow<UnifiedChatEntity?> =
        unifiedChatDao.observeById(id)

    /**
     * Alias for observeById for API consistency.
     */
    fun observeChat(id: String): Flow<UnifiedChatEntity?> =
        observeById(id)

    // ==================== Count Queries ====================

    /**
     * Observe the count of active chats.
     */
    fun observeActiveCount(): Flow<Int> =
        unifiedChatDao.observeActiveCount()

    /**
     * Observe the count of archived chats.
     */
    fun observeArchivedCount(): Flow<Int> =
        unifiedChatDao.observeArchivedCount()

    /**
     * Observe the count of starred chats.
     */
    fun observeStarredCount(): Flow<Int> =
        unifiedChatDao.observeStarredCount()

    /**
     * Observe the total unread count across all active chats.
     */
    fun observeTotalUnreadCount(): Flow<Int> =
        unifiedChatDao.observeTotalUnreadCount()

    // ==================== Get Queries ====================

    /**
     * Get active chats with pagination.
     */
    suspend fun getActiveChats(limit: Int, offset: Int): List<UnifiedChatEntity> =
        unifiedChatDao.getActiveChats(limit, offset)

    /**
     * Get archived chats with pagination.
     */
    suspend fun getArchivedChats(limit: Int, offset: Int): List<UnifiedChatEntity> =
        unifiedChatDao.getArchivedChats(limit, offset)

    /**
     * Get all unified chats.
     */
    suspend fun getAllChats(): List<UnifiedChatEntity> =
        unifiedChatDao.getAllChats()

    /**
     * Get a unified chat by ID.
     */
    suspend fun getById(id: String): UnifiedChatEntity? =
        unifiedChatDao.getById(id)

    /**
     * Get a unified chat by normalized address.
     */
    suspend fun getByNormalizedAddress(address: String): UnifiedChatEntity? =
        unifiedChatDao.getByNormalizedAddress(address)

    /**
     * Get a unified chat by source chat ID (e.g., "iMessage;-;+1234567890").
     */
    suspend fun getBySourceId(sourceId: String): UnifiedChatEntity? =
        unifiedChatDao.getBySourceId(sourceId)

    /**
     * Search unified chats by display name or address.
     */
    suspend fun search(query: String, limit: Int = 10): List<UnifiedChatEntity> =
        unifiedChatDao.search(query, limit)

    /**
     * Get count of active chats.
     */
    suspend fun getActiveCount(): Int =
        unifiedChatDao.getActiveCount()

    /**
     * Get multiple unified chats by their IDs.
     */
    suspend fun getByIds(ids: List<String>): List<UnifiedChatEntity> =
        unifiedChatDao.getByIds(ids)

    // ==================== Filtered Queries ====================

    /**
     * Get count of chats matching filter criteria.
     */
    suspend fun getFilteredCount(
        filter: ConversationFilter,
        categoryFilter: String?
    ): Int {
        val (includeSpam, unreadOnly) = filterToParams(filter)
        return unifiedChatDao.getFilteredCount(includeSpam, unreadOnly, categoryFilter)
    }

    /**
     * Get IDs of chats matching filter criteria (paginated).
     */
    suspend fun getFilteredIds(
        filter: ConversationFilter,
        categoryFilter: String?,
        limit: Int,
        offset: Int
    ): List<String> {
        val (includeSpam, unreadOnly) = filterToParams(filter)
        return unifiedChatDao.getFilteredIds(includeSpam, unreadOnly, categoryFilter, limit, offset)
    }

    private fun filterToParams(filter: ConversationFilter): Pair<Boolean, Boolean> {
        return when (filter) {
            ConversationFilter.ALL -> false to false
            ConversationFilter.UNREAD -> false to true
            ConversationFilter.SPAM -> true to false
            // For unknown/known senders, we filter on the UI side
            ConversationFilter.UNKNOWN_SENDERS -> false to false
            ConversationFilter.KNOWN_SENDERS -> false to false
        }
    }

    // ==================== Create/Update ====================

    /**
     * Get or create a unified chat for a given address.
     *
     * @param address The phone number or email (will be normalized)
     * @param sourceId The source chat ID (e.g., "iMessage;-;+1234567890")
     * @param displayName Optional display name
     * @param isGroup Whether this is a group chat
     * @return The existing or newly created unified chat
     */
    suspend fun getOrCreate(
        address: String,
        sourceId: String,
        displayName: String? = null,
        isGroup: Boolean = false
    ): UnifiedChatEntity {
        val normalizedAddress = if (isGroup) {
            // For groups, use the source ID as the identifier
            sourceId
        } else {
            PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
        }

        val chat = UnifiedChatEntity(
            id = UnifiedChatIdGenerator.generate(),
            normalizedAddress = normalizedAddress,
            sourceId = sourceId,
            displayName = displayName,
            isGroup = isGroup
        )

        return unifiedChatDao.getOrCreate(chat)
    }

    /**
     * Update the full unified chat entity.
     */
    suspend fun update(chat: UnifiedChatEntity) {
        unifiedChatDao.update(chat)
    }

    /**
     * Update the source ID (preferred channel) for a unified chat.
     * Called when iMessage becomes available for a previously SMS-only chat.
     */
    suspend fun updateSourceId(id: String, sourceId: String) {
        unifiedChatDao.updateSourceId(id, sourceId)
    }

    // ==================== Latest Message Updates ====================

    /**
     * Update the cached latest message for a unified chat.
     * Only updates if the message is newer than the current latest.
     *
     * @return true if the update was performed
     */
    suspend fun updateLatestMessageIfNewer(
        id: String,
        date: Long,
        text: String?,
        guid: String?,
        isFromMe: Boolean,
        hasAttachments: Boolean,
        source: String?,
        dateDelivered: Long? = null,
        dateRead: Long? = null,
        error: Int = 0
    ): Boolean {
        return unifiedChatDao.updateLatestMessageIfNewer(
            id, date, text, guid, isFromMe, hasAttachments,
            source, dateDelivered, dateRead, error
        )
    }

    /**
     * Force update the cached latest message (regardless of date).
     * Used when recalculating after message deletion.
     */
    suspend fun updateLatestMessage(
        id: String,
        date: Long,
        text: String?,
        guid: String?,
        isFromMe: Boolean,
        hasAttachments: Boolean,
        source: String?,
        dateDelivered: Long? = null,
        dateRead: Long? = null,
        error: Int = 0
    ) {
        unifiedChatDao.updateLatestMessage(
            id, date, text, guid, isFromMe, hasAttachments,
            source, dateDelivered, dateRead, error
        )
    }

    // ==================== Unread Count ====================

    /**
     * Increment the unread count for a unified chat.
     */
    suspend fun incrementUnreadCount(id: String) {
        unifiedChatDao.incrementUnreadCount(id)
    }

    /**
     * Mark a unified chat as read (reset unread count to 0).
     */
    suspend fun markAsRead(id: String) {
        unifiedChatDao.markAsRead(id)
    }

    /**
     * Mark all unified chats as read.
     * @return Number of chats marked as read
     */
    suspend fun markAllAsRead(): Int {
        return unifiedChatDao.markAllAsRead()
    }

    /**
     * Batch mark multiple chats as read.
     */
    suspend fun batchMarkAsRead(ids: List<String>) {
        unifiedChatDao.batchMarkAsRead(ids)
    }

    // ==================== State Updates ====================

    /**
     * Update the display name for a unified chat.
     */
    suspend fun updateDisplayName(id: String, displayName: String?) {
        unifiedChatDao.updateDisplayName(id, displayName)
    }

    /**
     * Update the pin status for a unified chat.
     */
    suspend fun updatePinStatus(id: String, isPinned: Boolean, pinIndex: Int?) {
        unifiedChatDao.updatePinStatus(id, isPinned, pinIndex)
    }

    /**
     * Update the archive status for a unified chat.
     */
    suspend fun updateArchiveStatus(id: String, isArchived: Boolean) {
        unifiedChatDao.updateArchiveStatus(id, isArchived)
    }

    /**
     * Batch update archive status for multiple chats.
     */
    suspend fun batchUpdateArchiveStatus(ids: List<String>, isArchived: Boolean) {
        unifiedChatDao.batchUpdateArchiveStatus(ids, isArchived)
    }

    /**
     * Update the starred status for a unified chat.
     */
    suspend fun updateStarredStatus(id: String, isStarred: Boolean) {
        unifiedChatDao.updateStarredStatus(id, isStarred)
    }

    /**
     * Update the mute status for a unified chat.
     */
    suspend fun updateMuteStatus(id: String, muteType: String?, muteArgs: String? = null) {
        unifiedChatDao.updateMuteStatus(id, muteType, muteArgs)
    }

    /**
     * Update the snooze until timestamp for a unified chat.
     */
    suspend fun updateSnoozeUntil(id: String, snoozeUntil: Long?) {
        unifiedChatDao.updateSnoozeUntil(id, snoozeUntil)
    }

    /**
     * Update the notifications enabled status for a unified chat.
     */
    suspend fun updateNotificationsEnabled(id: String, enabled: Boolean) {
        unifiedChatDao.updateNotificationsEnabled(id, enabled)
    }

    /**
     * Update the SMS fallback status for a unified chat.
     */
    suspend fun updateSmsFallbackStatus(id: String, isFallback: Boolean, reason: String?) {
        unifiedChatDao.updateSmsFallbackStatus(id, isFallback, reason, System.currentTimeMillis())
    }

    /**
     * Update the spam status for a unified chat.
     */
    suspend fun updateSpamStatus(id: String, isSpam: Boolean, spamScore: Int = 0) {
        unifiedChatDao.updateSpamStatus(id, isSpam, spamScore)
    }

    /**
     * Batch update spam status for multiple chats.
     */
    suspend fun batchUpdateSpamStatus(ids: List<String>, isSpam: Boolean) {
        unifiedChatDao.batchUpdateSpamStatus(ids, isSpam)
    }

    /**
     * Update the category for a unified chat.
     */
    suspend fun updateCategory(id: String, category: String?, confidence: Int = 0) {
        unifiedChatDao.updateCategory(id, category, confidence, System.currentTimeMillis())
    }

    /**
     * Update the preferred send mode for a unified chat.
     */
    suspend fun updatePreferredSendMode(id: String, mode: String?, manuallySet: Boolean = true) {
        unifiedChatDao.updatePreferredSendMode(id, mode, manuallySet)
    }

    /**
     * Update the draft text for a unified chat.
     */
    suspend fun updateTextFieldText(id: String, text: String?) {
        unifiedChatDao.updateTextFieldText(id, text)
    }

    /**
     * Update the custom avatar path for a unified chat.
     */
    suspend fun updateCustomAvatarPath(id: String, path: String?) {
        unifiedChatDao.updateCustomAvatarPath(id, path)
    }

    /**
     * Update the server group photo for a unified chat.
     */
    suspend fun updateServerGroupPhoto(id: String, path: String?, guid: String?) {
        unifiedChatDao.updateServerGroupPhoto(id, path, guid)
    }

    // ==================== Delete ====================

    /**
     * Soft delete a unified chat.
     */
    suspend fun softDelete(id: String) {
        unifiedChatDao.softDelete(id)
    }

    /**
     * Batch soft delete multiple unified chats.
     */
    suspend fun batchSoftDelete(ids: List<String>) {
        unifiedChatDao.batchSoftDelete(ids)
    }

    /**
     * Permanently delete a unified chat.
     */
    suspend fun delete(id: String) {
        unifiedChatDao.delete(id)
    }

    /**
     * Delete all unified chats.
     */
    suspend fun deleteAll() {
        unifiedChatDao.deleteAll()
    }

    /**
     * Clear display names that contain service suffixes.
     */
    suspend fun clearInvalidDisplayNames(): Int {
        return unifiedChatDao.clearInvalidDisplayNames()
    }
}
