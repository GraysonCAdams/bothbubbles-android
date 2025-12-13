package com.bothbubbles.data.repository

import android.util.Log
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
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.NetworkConfig
import com.bothbubbles.util.retryWithBackoff
import com.bothbubbles.util.retryWithRateLimitAwareness
import com.bothbubbles.util.error.NetworkError
import com.bothbubbles.util.error.safeCall
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val api: BothBubblesApi,
    private val androidContactsService: AndroidContactsService
) {
    companion object {
        private const val TAG = "ChatRepository"
    }

    // ===== Local Operations =====

    fun observeAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    fun observeActiveChats(): Flow<List<ChatEntity>> = chatDao.getActiveChats()

    fun observeArchivedChats(): Flow<List<ChatEntity>> = chatDao.getArchivedChats()

    fun observeStarredChats(): Flow<List<ChatEntity>> = chatDao.getStarredChats()

    fun observeChat(guid: String): Flow<ChatEntity?> = chatDao.observeChatByGuid(guid)

    suspend fun getChat(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)

    suspend fun getChatByGuid(guid: String): ChatEntity? = chatDao.getChatByGuid(guid)

    suspend fun getChatsByGuids(guids: List<String>): List<ChatEntity> = chatDao.getChatsByGuids(guids)

    suspend fun getChatCount(): Int = chatDao.getChatCount()

    fun observeArchivedChatCount(): Flow<Int> = chatDao.getArchivedChatCount()

    fun observeStarredChatCount(): Flow<Int> = chatDao.getStarredChatCount()

    fun observeUnreadChatCount(): Flow<Int> = chatDao.getUnreadChatCount()

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

    suspend fun getParticipantsForChat(chatGuid: String): List<HandleEntity> =
        chatDao.getParticipantsForChat(chatGuid)

    // ===== Local Mutation Operations =====

    suspend fun insertChat(chat: ChatEntity) {
        chatDao.insertChat(chat)
    }

    suspend fun updateDisplayName(chatGuid: String, displayName: String?) {
        chatDao.updateDisplayName(chatGuid, displayName)
    }

    suspend fun updateCustomAvatarPath(chatGuid: String, path: String?) {
        chatDao.updateCustomAvatarPath(chatGuid, path)
    }

    suspend fun deleteAllChats() {
        chatDao.deleteAllChats()
    }

    suspend fun deleteAllChatHandleCrossRefs() {
        chatDao.deleteAllChatHandleCrossRefs()
    }

    fun observeParticipantsForChat(chatGuid: String): Flow<List<HandleEntity>> =
        chatDao.observeParticipantsForChat(chatGuid)

    /**
     * Observe participants from multiple chats (for merged/unified conversations).
     * Combines and deduplicates participants from all specified chats.
     */
    fun observeParticipantsForChats(chatGuids: List<String>): Flow<List<HandleEntity>> {
        if (chatGuids.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        if (chatGuids.size == 1) return observeParticipantsForChat(chatGuids.first())

        return kotlinx.coroutines.flow.combine(
            chatGuids.map { guid -> chatDao.observeParticipantsForChat(guid) }
        ) { participantLists ->
            participantLists.flatMap { it.toList() }.distinctBy { it.id }
        }
    }

    /**
     * Get participants from multiple chats (one-shot query for merged/unified conversations).
     * Combines and deduplicates participants from all specified chats.
     * PERF: Uses batch DAO query instead of N+1 pattern.
     */
    suspend fun getParticipantsForChats(chatGuids: List<String>): List<HandleEntity> {
        if (chatGuids.isEmpty()) return emptyList()
        return chatDao.getParticipantsForChats(chatGuids)
    }

    /**
     * Get participants for multiple chats, grouped by chat GUID.
     * PERF: Fetches all participants in a single query and groups them.
     * @return Map of chat GUID to list of participants for that chat.
     */
    suspend fun getParticipantsGroupedByChat(chatGuids: List<String>): Map<String, List<HandleEntity>> {
        if (chatGuids.isEmpty()) return emptyMap()
        return chatDao.getParticipantsWithChatGuids(chatGuids)
            .groupBy({ it.chatGuid }, { it.handle })
    }

    /**
     * Find the "best" participant from a list - prefers one with saved contact info.
     * Used for displaying names/avatars in conversation lists.
     *
     * Priority:
     * 1. Participant with cachedDisplayName (saved contact)
     * 2. First participant in the list
     */
    fun getBestParticipant(participants: List<HandleEntity>): HandleEntity? {
        return participants.find { it.cachedDisplayName != null }
            ?: participants.firstOrNull()
    }

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
     * Fetch all chats from server and sync to local database.
     * Uses transactional operations to ensure data consistency.
     * Retries with exponential backoff on transient network errors.
     */
    suspend fun syncChats(
        limit: Int = 100,
        offset: Int = 0
    ): Result<List<ChatEntity>> = safeCall {
        retryWithBackoff(
            times = NetworkConfig.SYNC_RETRY_ATTEMPTS,
            initialDelayMs = NetworkConfig.SYNC_INITIAL_DELAY_MS,
            maxDelayMs = NetworkConfig.SYNC_MAX_DELAY_MS
        ) {
            val response = retryWithRateLimitAwareness {
                api.queryChats(
                    ChatQueryRequest(
                        with = listOf("participants", "lastmessage"),
                        limit = limit,
                        offset = offset,
                        sort = "lastmessage"
                    )
                )
            }

            val body = response.body()
            if (!response.isSuccessful || body == null) {
                throw NetworkError.ServerError(response.code(), body?.message)
            }

            val chatDtos = body.data.orEmpty()
            val chats = chatDtos.map { it.toEntity() }

            // Prepare all participant data
            val participantData = chatDtos.zip(chats).map { (chatDto, chat) ->
                val handleIds = upsertHandlesForChat(chatDto)
                chat to handleIds
            }

            // Single transactional call
            chatDao.syncChatsWithParticipants(participantData)

            // Post-sync linking
            chatDtos.forEach { chatDto ->
                val participants = chatDto.participants
                if (participants != null && participants.size == 1) {
                    val participant = participants.first()
                    val normalizedPhone = PhoneAndCodeParsingUtils.normalizePhoneNumber(participant.address)
                    linkChatToUnifiedGroup(chatDto.guid, normalizedPhone, chatDto.displayName)
                }
            }

            chats
        }
    }

    /**
     * Fetch a single chat from server.
     * Uses transactional sync to ensure chat + participants are saved atomically.
     * Retries with exponential backoff on transient network errors.
     */
    suspend fun fetchChat(guid: String): Result<ChatEntity> = safeCall {
        retryWithBackoff(
            times = NetworkConfig.DEFAULT_RETRY_ATTEMPTS,
            initialDelayMs = NetworkConfig.DEFAULT_INITIAL_DELAY_MS,
            maxDelayMs = NetworkConfig.DEFAULT_MAX_DELAY_MS
        ) {
            val response = retryWithRateLimitAwareness {
                api.getChat(guid)
            }

            val body = response.body()
            if (!response.isSuccessful || body == null) {
                throw NetworkError.ServerError(response.code(), body?.message)
            }

            val chatDto = body.data ?: throw NetworkError.ServerError(404, "Chat not found")
            val chat = chatDto.toEntity()

            // Atomically sync chat with participants
            syncChatParticipants(chatDto, chat)

            chat
        }
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

    /**
     * Update the preferred send mode for a chat (iMessage vs SMS toggle).
     *
     * @param chatGuid The chat GUID
     * @param mode The preferred mode ("imessage", "sms", or null for automatic)
     * @param manuallySet Whether the user manually set this preference
     */
    suspend fun updatePreferredSendMode(chatGuid: String, mode: String?, manuallySet: Boolean) {
        chatDao.updatePreferredSendMode(chatGuid, mode, manuallySet)
    }

    // ===== Data Cleanup =====

    /**
     * Clear invalid display names from the database.
     * This fixes existing data where display names contain internal identifiers
     * like "(smsfp)", "(smsft)", or look like chat GUIDs (e.g., "c46271").
     * Should be called once on app startup.
     */
    suspend fun cleanupInvalidDisplayNames(): Int {
        val chatCount = chatDao.clearInvalidDisplayNames()
        val groupCount = unifiedChatGroupDao.clearInvalidDisplayNames()
        val totalCount = chatCount + groupCount
        if (totalCount > 0) {
            Log.i(TAG, "Cleaned up $totalCount invalid display names (chats: $chatCount, groups: $groupCount)")
        }
        return totalCount
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

    /**
     * Refresh contact info (display name and photo) for all handles from device contacts.
     * This should be called when:
     * - READ_CONTACTS permission is newly granted
     * - App starts with permission already granted (to catch contact changes)
     * Returns the number of handles updated.
     */
    suspend fun refreshAllContactInfo(): Int {
        if (!androidContactsService.hasReadPermission()) {
            Log.d(TAG, "refreshAllContactInfo: No READ_CONTACTS permission, skipping")
            return 0
        }

        var updatedCount = 0
        try {
            val allHandles = handleDao.getAllHandlesOnce()
            Log.d(TAG, "refreshAllContactInfo: Refreshing contact info for ${allHandles.size} handles")

            for (handle in allHandles) {
                val displayName = androidContactsService.getContactDisplayName(handle.address)
                val photoUri = androidContactsService.getContactPhotoUri(handle.address)

                // Only update if we found contact info or if there's existing cached info to clear
                if (displayName != handle.cachedDisplayName || photoUri != handle.cachedAvatarPath) {
                    handleDao.updateCachedContactInfo(handle.id, displayName, photoUri)
                    updatedCount++
                }
            }

            Log.d(TAG, "refreshAllContactInfo: Updated $updatedCount handles")
        } catch (e: Exception) {
            Log.e(TAG, "refreshAllContactInfo: Error refreshing contact info", e)
        }

        return updatedCount
    }

    /**
     * Upsert handles for a chat and return their IDs.
     */
    private suspend fun upsertHandlesForChat(chatDto: ChatDto): List<Long> {
        return chatDto.participants?.map { handleDto ->
            // Strip service suffixes (e.g., "(filtered)", "(smsft)") before contact lookup
            val cleanAddress = PhoneNumberFormatter.stripServiceSuffix(handleDto.address)

            // Look up contact info from device contacts using clean address
            val contactName = androidContactsService.getContactDisplayName(cleanAddress)
            val contactPhotoUri = androidContactsService.getContactPhotoUri(cleanAddress)

            val handle = HandleEntity(
                address = handleDto.address,  // Keep original for server sync
                service = handleDto.service,
                country = handleDto.country,
                formattedAddress = handleDto.formattedAddress
                    ?: PhoneNumberFormatter.format(cleanAddress),  // Generate if server doesn't provide
                defaultEmail = handleDto.defaultEmail,
                defaultPhone = handleDto.defaultPhone,
                originalRowId = handleDto.originalRowId,
                cachedDisplayName = contactName,
                cachedAvatarPath = contactPhotoUri
            )
            // Use upsert to get existing handle ID (insertHandle with REPLACE breaks cross-refs)
            handleDao.upsertHandle(handle)
        } ?: emptyList()
    }

    /**
     * Sync a chat's participants atomically.
     * This ensures all participants are updated together or not at all,
     * preventing partial state if the app crashes during sync.
     */
    private suspend fun syncChatParticipants(chatDto: ChatDto, chat: ChatEntity) {
        // Step 1: Upsert all handles and collect their IDs (handles exist independently)
        val handleIds = upsertHandlesForChat(chatDto)

        // Step 2: Atomically sync chat with participants (transactional)
        chatDao.syncChatWithParticipants(chat, handleIds)

        // Step 3: For single-contact iMessage chats, link to unified group
        // (This has its own transaction handling in UnifiedChatGroupDao)
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
        // Skip if identifier is empty or looks like an email/RCS address (not a phone)
        // This prevents all non-phone chats from being lumped into a single group
        if (normalizedPhone.isBlank() || normalizedPhone.contains("@")) {
            Log.d(TAG, "linkChatToUnifiedGroup: Skipping $chatGuid - invalid identifier '$normalizedPhone'")
            return
        }

        Log.d(TAG, "linkChatToUnifiedGroup: Linking $chatGuid to group for '$normalizedPhone'")

        try {
            // Check if this chat is already in a unified group
            if (unifiedChatGroupDao.isChatInUnifiedGroup(chatGuid)) {
                Log.d(TAG, "linkChatToUnifiedGroup: $chatGuid is already in a unified group")
                return
            }

            // Check if unified group already exists for this phone number
            var group = unifiedChatGroupDao.getGroupByIdentifier(normalizedPhone)
            Log.d(TAG, "linkChatToUnifiedGroup: Existing group for '$normalizedPhone': ${group?.id}")

            if (group == null) {
                // Create new unified group with this iMessage chat as primary
                // Clean the displayName: strip service suffixes
                val cleanedDisplayName = displayName
                    ?.let { PhoneNumberFormatter.stripServiceSuffix(it) }
                    ?.takeIf { it.isValidDisplayName() }

                val newGroup = UnifiedChatGroupEntity(
                    identifier = normalizedPhone,
                    primaryChatGuid = chatGuid,
                    displayName = cleanedDisplayName
                )
                // Use atomic method to prevent FOREIGN KEY errors
                group = unifiedChatGroupDao.getOrCreateGroupAndAddMember(newGroup, chatGuid)
                Log.d(TAG, "linkChatToUnifiedGroup: Created/got group id=${group.id} and added member $chatGuid")

                // If SMS created the group first (during concurrent sync), claim primary for iMessage
                if (group.primaryChatGuid != chatGuid &&
                    (group.primaryChatGuid.startsWith("sms;") || group.primaryChatGuid.startsWith("mms;"))) {
                    Log.d(TAG, "linkChatToUnifiedGroup: Claiming primary for iMessage (was ${group.primaryChatGuid})")
                    unifiedChatGroupDao.updatePrimaryChatGuid(group.id, chatGuid)
                }
            } else {
                // Group exists, add this iMessage chat to it
                Log.d(TAG, "linkChatToUnifiedGroup: Adding $chatGuid to existing group ${group.id}")
                unifiedChatGroupDao.insertMember(
                    UnifiedChatMember(groupId = group.id, chatGuid = chatGuid)
                )

                // If SMS is currently primary, claim it for iMessage
                if (group.primaryChatGuid.startsWith("sms;") || group.primaryChatGuid.startsWith("mms;")) {
                    Log.d(TAG, "linkChatToUnifiedGroup: Claiming primary for iMessage (was ${group.primaryChatGuid})")
                    unifiedChatGroupDao.updatePrimaryChatGuid(group.id, chatGuid)
                }
            }

            Log.d(TAG, "linkChatToUnifiedGroup: Successfully linked $chatGuid to group ${group.id}")
        } catch (e: Exception) {
            Log.e(TAG, "linkChatToUnifiedGroup: FAILED to link $chatGuid to group for '$normalizedPhone'", e)
            throw e
        }
    }

    private fun ChatDto.toEntity(): ChatEntity {
        // Clean the displayName: strip service suffixes and validate
        val cleanedDisplayName = displayName
            ?.let { PhoneNumberFormatter.stripServiceSuffix(it) }
            ?.takeIf { it.isValidDisplayName() }

        return ChatEntity(
            guid = guid,
            chatIdentifier = chatIdentifier,
            displayName = cleanedDisplayName,
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

/**
 * Check if a string is a valid display name (not an internal identifier).
 * Filters out:
 * - Blank strings
 * - Internal iMessage identifiers like "(smsft_rm)", "(ft_rm)", etc.
 * - SMS forwarding service suffixes like "(smsfp)", "(smsft)", "(smsft_fi)"
 * - Chat protocol prefixes
 * - Short alphanumeric strings that look like chat GUIDs
 */
private fun String.isValidDisplayName(): Boolean {
    if (isBlank()) return false

    // Filter out internal identifiers wrapped in parentheses (e.g., "(smsft_rm)")
    if (startsWith("(") && endsWith(")")) return false

    // Filter out protocol-like strings
    if (contains(";-;") || contains(";+;")) return false

    // Filter out SMS/FT service suffixes (e.g., "38772(smsfp)", "+17035439474(smsft)", "12345(smsft_fi)")
    // These are internal identifiers the server includes for SMS text forwarding chats
    // Pattern matches (sms*) or (ft*) at end of string
    if (Regex("\\((sms|ft)[a-z_]*\\)$", RegexOption.IGNORE_CASE).containsMatchIn(this)) return false

    // Filter out short alphanumeric strings that look like chat GUIDs (e.g., "c46271")
    // Valid display names are typically longer and contain more than just alphanumerics
    if (Regex("^[a-z][0-9a-z]{4,7}$").matches(this)) return false

    return true
}
