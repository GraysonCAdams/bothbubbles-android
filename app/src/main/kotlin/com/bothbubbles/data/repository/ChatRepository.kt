package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.TombstoneDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for chat (protocol channel) operations.
 *
 * ChatEntity represents protocol-specific channels (iMessage, SMS, etc.).
 * UI state operations (pinned, archived, starred, unread, etc.) should use
 * [UnifiedChatRepository] instead.
 *
 * Complex operations have been extracted to:
 * - ChatSyncOperations: Remote sync operations
 * - ChatParticipantOperations: Participant management
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val tombstoneDao: TombstoneDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val api: BothBubblesApi,
    private val syncOps: ChatSyncOperations,
    private val participantOps: ChatParticipantOperations
) {

    // ===== Local Query Operations =====

    fun observeAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    fun observeChat(guid: String): Flow<ChatEntity?> = chatDao.observeChatByGuid(guid)

    /**
     * Observe the unified chat entity for a given chat GUID.
     * Returns the UnifiedChatEntity that contains this chat as its source.
     */
    fun observeUnifiedChatForChat(chatGuid: String) = unifiedChatDao.observeBySourceId(chatGuid)

    suspend fun getChat(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)

    suspend fun getChatsByGuids(guids: List<String>): List<ChatEntity> = chatDao.getChatsByGuids(guids)

    suspend fun getChatCount(): Int = chatDao.getChatCount()

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

    /**
     * Get the last message date for each chat identifier (phone/email).
     * Used for contact deduplication - determines which handle was most recently used.
     *
     * @return Map of normalized address to last message timestamp
     */
    suspend fun getLastMessageDatePerAddress(): Map<String, Long> {
        return chatDao.getLastMessageDatePerAddress().associate {
            normalizeAddress(it.chatIdentifier) to it.latestMessageDate
        }
    }

    /**
     * Get the service type from the most recently active chat that has messages.
     * Returns null if no chat with messages exists for this address.
     *
     * This is used to determine the correct service for a contact based on
     * actual conversation history, rather than trusting stale cached handles.
     *
     * @param normalizedAddress The normalized phone number or email address
     * @return "iMessage" or "SMS", or null if no active chat exists
     */
    suspend fun getServiceFromMostRecentActiveChat(normalizedAddress: String): String? {
        // Try the normalized address first
        chatDao.getMostRecentActiveChatService(normalizedAddress)?.let {
            return it.service
        }
        // Try with + prefix for phone numbers
        if (!normalizedAddress.contains("@") && !normalizedAddress.startsWith("+")) {
            chatDao.getMostRecentActiveChatService("+$normalizedAddress")?.let {
                return it.service
            }
        }
        return null
    }

    /**
     * Get map of address -> service for all addresses with active chats (chats with messages).
     * Used by ContactLoadDelegate and SuggestionDelegate for activity-based service display.
     *
     * For each address, returns the service type from the most recently active chat.
     * Addresses with no chats that have messages are not included in the result.
     *
     * @return Map of normalized address to service type ("iMessage" or "SMS")
     */
    suspend fun getServiceMapFromActiveChats(): Map<String, String> {
        val results = chatDao.getServiceMapFromActiveChats()
        // Group by normalized address and pick the one with the most recent message
        return results
            .groupBy { normalizeAddress(it.chatIdentifier) }
            .mapValues { (_, chats) ->
                // Take the service from the chat with the most recent message
                chats.maxByOrNull { it.latestMessageDate }?.service ?: "SMS"
            }
    }

    /**
     * Normalize an address for lookup purposes.
     */
    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            address.lowercase()
        } else {
            address.replace(Regex("[^0-9+]"), "")
        }
    }

    // ===== Unified Chat Operations =====

    /**
     * Get chats belonging to a unified conversation.
     */
    suspend fun getChatsForUnifiedChat(unifiedChatId: String): List<ChatEntity> =
        chatDao.getChatsForUnifiedChat(unifiedChatId)

    /**
     * Get chat GUIDs for a unified conversation.
     */
    suspend fun getChatGuidsForUnifiedChat(unifiedChatId: String): List<String> =
        chatDao.getChatGuidsForUnifiedChat(unifiedChatId)

    /**
     * Batch get chat GUIDs for multiple unified chats.
     * Returns map of unified chat ID to list of chat GUIDs.
     * PERF: Single query instead of N+1 pattern.
     */
    suspend fun getChatGuidsForUnifiedChats(unifiedChatIds: List<String>): Map<String, List<String>> {
        if (unifiedChatIds.isEmpty()) return emptyMap()
        return chatDao.getChatGuidsForUnifiedChats(unifiedChatIds)
            .groupBy { it.unifiedChatId }
            .mapValues { (_, list) -> list.map { it.chatGuid } }
    }

    /**
     * Check which chat GUIDs are linked to any unified chat.
     * Used to filter out chats that shouldn't appear as orphans in conversation list.
     */
    suspend fun getChatsLinkedToUnifiedChats(chatGuids: List<String>): Set<String> {
        if (chatGuids.isEmpty()) return emptySet()
        return chatDao.getChatsLinkedToUnifiedChats(chatGuids).toSet()
    }

    /**
     * Link a chat to a unified conversation.
     */
    suspend fun setUnifiedChatId(chatGuid: String, unifiedChatId: String): Result<Unit> = runCatching {
        chatDao.setUnifiedChatId(chatGuid, unifiedChatId)
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

    // ===== Local Mutation Operations =====

    suspend fun insertChat(chat: ChatEntity): Result<Unit> = runCatching {
        chatDao.insertChat(chat)
    }

    suspend fun updateDisplayName(chatGuid: String, displayName: String?): Result<Unit> = runCatching {
        chatDao.updateDisplayName(chatGuid, displayName)
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
     * Update the last message date for a chat
     */
    suspend fun updateLatestMessageDate(chatGuid: String, date: Long): Result<Unit> = runCatching {
        chatDao.updateLatestMessageDate(chatGuid, date)
    }

    /**
     * Delete a chat locally.
     * Records a tombstone to prevent resurrection during sync.
     */
    suspend fun deleteChat(guid: String): Result<Unit> = runCatching {
        chatDao.deleteChatWithDependencies(guid, tombstoneDao, messageDao)
    }

    /**
     * Mark a chat as read by its GUID.
     * Looks up the unified chat ID and marks it as read.
     */
    suspend fun markChatAsRead(chatGuid: String): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.markAsRead(unifiedId)
        }
    }

    /**
     * Mark a chat as unread by its GUID.
     * Looks up the unified chat ID and sets unread count to 1.
     */
    suspend fun markChatAsUnread(chatGuid: String): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateUnreadCount(unifiedId, 1)
        }
    }

    /**
     * Snooze a chat by its GUID.
     * Looks up the unified chat ID and updates snooze until time.
     */
    suspend fun snoozeChat(chatGuid: String, durationMs: Long): Result<Unit> = runCatching {
        val snoozeUntil = if (durationMs == -1L) -1L else System.currentTimeMillis() + durationMs
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateSnoozeUntil(unifiedId, snoozeUntil)
        }
    }

    /**
     * Unsnooze a chat by its GUID.
     * Looks up the unified chat ID and clears snooze until time.
     */
    suspend fun unsnoozeChat(chatGuid: String): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateSnoozeUntil(unifiedId, null)
        }
    }

    /**
     * Set muted status for a chat by its GUID.
     * Looks up the unified chat ID and updates notifications enabled.
     */
    suspend fun setMuted(chatGuid: String, muted: Boolean): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateNotificationsEnabled(unifiedId, !muted)
        }
    }

    /**
     * Set archived status for a chat by its GUID.
     * Looks up the unified chat ID and updates archive status.
     */
    suspend fun setArchived(chatGuid: String, archived: Boolean): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateArchiveStatus(unifiedId, archived)
        }
    }

    /**
     * Set starred status for a chat by its GUID.
     * Looks up the unified chat ID and updates starred status.
     */
    suspend fun setStarred(chatGuid: String, starred: Boolean): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateStarredStatus(unifiedId, starred)
        }
    }

    /**
     * Update custom avatar path for a chat by its GUID.
     * Looks up the unified chat ID and updates the custom avatar path.
     */
    suspend fun updateCustomAvatarPath(chatGuid: String, path: String?): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateCustomAvatarPath(unifiedId, path)
        }
    }

    /**
     * Find a chat GUID by normalized address.
     * Uses the unified chat's sourceId which is the preferred chat GUID.
     *
     * @param address The normalized phone number or email
     * @return The chat GUID or null if not found
     */
    suspend fun findChatGuidByAddress(address: String): String? {
        return unifiedChatDao.getByNormalizedAddress(address)?.sourceId
    }

    /**
     * Update draft text for a chat by its GUID.
     * Looks up the unified chat ID and updates the textFieldText.
     */
    suspend fun updateDraftText(chatGuid: String, text: String?): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateTextFieldText(unifiedId, text)
        }
    }

    /**
     * Get draft text for a chat by its GUID.
     * Looks up the unified chat and returns the textFieldText.
     */
    suspend fun getDraftText(chatGuid: String): String? {
        val chat = chatDao.getChatByGuid(chatGuid)
        return chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.getById(unifiedId)?.textFieldText
        }
    }

    /**
     * Update preferred send mode for a chat by its GUID.
     * Looks up the unified chat ID and updates the send mode preference.
     */
    suspend fun updatePreferredSendMode(chatGuid: String, mode: String?, manuallySet: Boolean): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updatePreferredSendMode(unifiedId, mode, manuallySet)
        }
    }

    /**
     * Get send mode preference for a chat by its GUID.
     * Returns a pair of (preferredSendMode, sendModeManuallySet) or null if not found.
     */
    suspend fun getSendModePreference(chatGuid: String): Pair<String?, Boolean>? {
        val chat = chatDao.getChatByGuid(chatGuid)
        return chat?.unifiedChatId?.let { unifiedId ->
            val unifiedChat = unifiedChatDao.getById(unifiedId)
            unifiedChat?.let { it.preferredSendMode to it.sendModeManuallySet }
        }
    }

    /**
     * Observe archived chat count from unified chats.
     */
    fun observeArchivedChatCount(): Flow<Int> =
        unifiedChatDao.observeArchivedCount()

    /**
     * Observe unread chat count from unified chats.
     */
    fun observeUnreadChatCount(): Flow<Int> =
        unifiedChatDao.observeTotalUnreadCount()

    /**
     * Mark all unified chats as read.
     */
    suspend fun markAllChatsAsRead(): Result<Unit> = runCatching {
        unifiedChatDao.markAllAsRead()
    }

    /**
     * Set notifications enabled/disabled for a chat by its GUID.
     * Looks up the unified chat ID and updates the notifications enabled flag.
     */
    suspend fun setNotificationsEnabled(chatGuid: String, enabled: Boolean): Result<Unit> = runCatching {
        val chat = chatDao.getChatByGuid(chatGuid)
        chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateNotificationsEnabled(unifiedId, enabled)
        }
    }

    /**
     * Get notifications enabled status for a chat by its GUID.
     */
    suspend fun getNotificationsEnabled(chatGuid: String): Boolean {
        val chat = chatDao.getChatByGuid(chatGuid)
        return chat?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.getById(unifiedId)?.notificationsEnabled
        } ?: true
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
        val unifiedCount = unifiedChatDao.clearInvalidDisplayNames()
        val totalCount = chatCount + unifiedCount
        if (totalCount > 0) {
            Timber.i("Cleaned up $totalCount invalid display names (chats: $chatCount, unified: $unifiedCount)")
        }
        totalCount
    }
}
