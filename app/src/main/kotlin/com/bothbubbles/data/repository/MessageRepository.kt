package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.SocialMediaLinkDao
import com.bothbubbles.data.local.db.dao.TombstoneDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.ReactionClassifier
import com.bothbubbles.data.local.db.entity.SyncSource
import com.bothbubbles.core.model.entity.SocialMediaLinkEntity
import com.bothbubbles.core.model.entity.SocialMediaPlatform
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.core.network.api.dto.MessageQueryRequest
import com.bothbubbles.services.sync.SyncRangeTracker
import com.bothbubbles.util.NetworkConfig
import com.bothbubbles.util.parsing.HtmlEntityDecoder
import com.bothbubbles.util.retryWithBackoff
import com.bothbubbles.util.retryWithRateLimitAwareness
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for message data operations.
 *
 * This repository follows clean architecture principles:
 * - Provides data access operations (CRUD, queries, sync)
 * - Does NOT depend on services layer
 *
 * For sending messages, use MessageSendingService directly.
 * For incoming message handling, use IncomingMessageHandler directly.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val tombstoneDao: TombstoneDao,
    private val socialMediaLinkDao: SocialMediaLinkDao,
    private val api: BothBubblesApi,
    private val syncRangeTracker: SyncRangeTracker,
    private val attachmentRepository: AttachmentRepository,
    private val chatSyncOperations: ChatSyncOperations
) {

    companion object {
        // Instagram URL patterns - matches IncomingMessageHandler
        private val INSTAGRAM_PATTERN = Regex(
            """https?://(?:www\.)?instagram\.com/(?:reel|reels|p|share/reel|share/p)/[A-Za-z0-9_-]+[^\s]*"""
        )

        // TikTok URL patterns - matches IncomingMessageHandler
        private val TIKTOK_PATTERN = Regex(
            """https?://(?:www\.|vm\.|vt\.)?tiktok\.com/[^\s]+"""
        )
    }

    // ===== Local Query Operations =====

    fun observeMessagesForChat(chatGuid: String, limit: Int, offset: Int): Flow<List<MessageEntity>> =
        messageDao.observeMessagesForChat(chatGuid, limit, offset)

    /**
     * Observe messages for a chat, automatically resolving unified groups.
     *
     * If the chat is part of a unified group (merged iMessage + SMS), this returns
     * messages from ALL chats in the group. Otherwise, returns messages from just
     * the specified chat.
     *
     * This is the preferred method for UI components that need blended conversations.
     *
     * @param chatGuid Any chat GUID that may be part of a unified group
     * @param limit Maximum number of messages to return
     */
    suspend fun observeMessagesForUnifiedChat(chatGuid: String, limit: Int): Flow<List<MessageEntity>> {
        val mergedGuids = resolveUnifiedChatGuids(chatGuid)
        return observeRecentMessages(mergedGuids, limit)
    }

    /**
     * Get recent messages for a chat preview (one-shot, non-reactive).
     * Automatically resolves unified groups to include all merged chats.
     * Returns messages sorted newest-first for display in preview.
     *
     * @param chatGuid Any chat GUID that may be part of a unified group
     * @param limit Maximum number of messages to return (default 10)
     * @return List of messages sorted by date (newest first)
     */
    suspend fun getRecentMessagesForPreview(chatGuid: String, limit: Int = 10): List<MessageEntity> {
        val mergedGuids = resolveUnifiedChatGuids(chatGuid)
        return messageDao.getMessagesByPosition(mergedGuids, limit, 0)
    }

    /**
     * Resolve all chat GUIDs for a unified chat containing the given chat.
     *
     * In the new architecture, chats have a `unifiedChatId` field. This method finds
     * all chats that share the same unified chat ID.
     *
     * @return List of all chat GUIDs in the unified chat, or just [chatGuid] if not linked
     */
    suspend fun resolveUnifiedChatGuids(chatGuid: String): List<String> {
        val chat = chatDao.getChatByGuid(chatGuid)
        val unifiedChatId = chat?.unifiedChatId
        return if (unifiedChatId != null) {
            chatDao.getChatGuidsForUnifiedChat(unifiedChatId)
        } else {
            listOf(chatGuid)
        }
    }

    /**
     * Observe messages from multiple chats (for merged iMessage + SMS conversations).
     */
    fun observeMessagesForChats(chatGuids: List<String>, limit: Int, offset: Int): Flow<List<MessageEntity>> =
        messageDao.observeMessagesForChats(chatGuids, limit, offset)

    /**
     * Cursor-based pagination: observe messages before a timestamp.
     * Used for windowed loading when scrolling to older messages.
     */
    fun observeMessagesBefore(chatGuid: String, beforeTimestamp: Long, limit: Int): Flow<List<MessageEntity>> =
        messageDao.observeMessagesBefore(chatGuid, beforeTimestamp, limit)

    /**
     * Cursor-based pagination for merged chats: observe messages before a timestamp.
     */
    fun observeMessagesBeforeForChats(chatGuids: List<String>, beforeTimestamp: Long, limit: Int): Flow<List<MessageEntity>> =
        messageDao.observeMessagesBeforeForChats(chatGuids, beforeTimestamp, limit)

    // ===== Cursor-Based Pagination =====

    /**
     * Observe recent messages with a dynamic limit (cursor-based pagination).
     * The main driver for the new chat message list.
     *
     * Room Flow automatically emits when:
     * - New messages are inserted
     * - Messages are updated (delivery status, reactions)
     * - Messages are soft-deleted
     *
     * @param chatGuids List of chat GUIDs (supports merged iMessage + SMS)
     * @param limit Dynamic limit that grows as user scrolls up
     */
    fun observeRecentMessages(chatGuids: List<String>, limit: Int): Flow<List<MessageEntity>> =
        messageDao.observeRecentMessages(chatGuids, limit)

    /**
     * Observe messages within a time window (for Archive/Jump-to-message mode).
     *
     * @param chatGuids List of chat GUIDs
     * @param windowStart Start of time window (targetTimestamp - windowMs)
     * @param windowEnd End of time window (targetTimestamp + windowMs)
     */
    fun observeMessagesInWindow(
        chatGuids: List<String>,
        windowStart: Long,
        windowEnd: Long
    ): Flow<List<MessageEntity>> =
        messageDao.observeMessagesInWindow(chatGuids, windowStart, windowEnd)

    /**
     * Count messages for cursor pagination checks.
     * Used to determine if more messages are available locally.
     */
    suspend fun countMessagesForCursor(chatGuids: List<String>): Int =
        messageDao.countMessagesForCursor(chatGuids)

    fun observeMessage(guid: String): Flow<MessageEntity?> =
        messageDao.observeMessageByGuid(guid)

    suspend fun getMessage(guid: String): MessageEntity? =
        messageDao.getMessageByGuid(guid)

    fun getReactionsForMessage(messageGuid: String): Flow<List<MessageEntity>> =
        messageDao.getReactionsForMessage(messageGuid)

    fun getRepliesForMessage(messageGuid: String): Flow<List<MessageEntity>> =
        messageDao.getRepliesForMessage(messageGuid)

    /**
     * Batch fetch messages by their GUIDs.
     * Used for efficiently loading reply preview data.
     */
    suspend fun getMessagesByGuids(guids: List<String>): List<MessageEntity> =
        messageDao.getMessagesByGuids(guids)

    /**
     * Get all messages in a thread (the original message + all replies to it).
     * Used for displaying the thread overlay when user taps a reply indicator.
     */
    suspend fun getThreadMessages(originGuid: String): List<MessageEntity> =
        messageDao.getThreadMessages(originGuid)

    fun searchMessages(query: String, limit: Int = 100): Flow<List<MessageEntity>> =
        messageDao.searchMessages(query, limit)

    /**
     * Search messages by text content with optional date range filtering.
     * @param query The search query (matched against text and subject)
     * @param startDate Start of date range in milliseconds (null = no lower bound)
     * @param endDate End of date range in milliseconds (null = no upper bound)
     * @param limit Maximum number of results to return
     */
    fun searchMessagesInDateRange(
        query: String,
        startDate: Long?,
        endDate: Long?,
        limit: Int = 100
    ): Flow<List<MessageEntity>> =
        messageDao.searchMessagesInDateRange(query, startDate, endDate, limit)

    /**
     * Get messages within a date range (no text query required).
     * Use this for date-only browsing of messages.
     * @param startDate Start of date range in milliseconds
     * @param endDate End of date range in milliseconds
     * @param limit Maximum number of results to return
     */
    fun getMessagesInDateRange(
        startDate: Long,
        endDate: Long,
        limit: Int = 100
    ): Flow<List<MessageEntity>> =
        messageDao.getMessagesInDateRange(startDate, endDate, limit)

    /**
     * Get messages containing URLs for link gallery.
     */
    fun getMessagesWithUrlsForChat(chatGuid: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesWithUrlsForChat(chatGuid)

    /**
     * Get messages containing URLs for link gallery with pagination.
     */
    suspend fun getMessagesWithUrlsForChatPaged(
        chatGuid: String,
        limit: Int,
        offset: Int
    ): List<MessageEntity> =
        messageDao.getMessagesWithUrlsForChatPaged(chatGuid, limit, offset)

    /**
     * Count messages containing URLs for link gallery.
     */
    suspend fun countMessagesWithUrlsForChat(chatGuid: String): Int =
        messageDao.countMessagesWithUrlsForChat(chatGuid)

    /**
     * Get the latest message for a specific chat.
     */
    suspend fun getLatestMessageForChat(chatGuid: String): MessageEntity? =
        messageDao.getLatestMessageForChat(chatGuid)

    /**
     * Batch fetch the latest messages for multiple chats.
     */
    suspend fun getLatestMessagesForChats(chatGuids: List<String>): List<MessageEntity> =
        messageDao.getLatestMessagesForChats(chatGuids)

    /**
     * Get the message by its GUID.
     */
    suspend fun getMessageByGuid(guid: String): MessageEntity? =
        messageDao.getMessageByGuid(guid)

    /**
     * Delete all messages from the database.
     */
    suspend fun deleteAllMessages(): Result<Unit> = runCatching {
        messageDao.deleteAllMessages()
    }

    // ===== Paging Operations =====

    /**
     * Get message count for multiple chats (used by paging).
     */
    suspend fun getMessageCountForChats(chatGuids: List<String>): Int =
        messageDao.getMessageCountForChats(chatGuids)

    /**
     * Observe message count for multiple chats (used by paging).
     */
    fun observeMessageCountForChats(chatGuids: List<String>): Flow<Int> =
        messageDao.observeMessageCountForChats(chatGuids)

    /**
     * Get messages by position for pagination (most recent first).
     */
    suspend fun getMessagesByPosition(chatGuids: List<String>, limit: Int, offset: Int): List<MessageEntity> =
        messageDao.getMessagesByPosition(chatGuids, limit, offset)

    /**
     * Get the position of a message within a chat list.
     */
    suspend fun getMessagePosition(chatGuids: List<String>, guid: String): Int =
        messageDao.getMessagePosition(chatGuids, guid)

    /**
     * Batch get reactions for multiple messages.
     */
    suspend fun getReactionsForMessages(messageGuids: List<String>): List<MessageEntity> =
        messageDao.getReactionsForMessages(messageGuids)

    // ===== Remote Sync Operations =====

    /**
     * Fetch messages for a chat from server.
     * Retries with exponential backoff on transient network errors.
     */
    suspend fun syncMessagesForChat(
        chatGuid: String,
        limit: Int = 50,
        offset: Int = 0,
        after: Long? = null,
        before: Long? = null,
        syncSource: SyncSource = SyncSource.ON_DEMAND
    ): Result<List<MessageEntity>> = runCatching {
        retryWithBackoff(
            times = NetworkConfig.SYNC_RETRY_ATTEMPTS,
            initialDelayMs = NetworkConfig.SYNC_INITIAL_DELAY_MS,
            maxDelayMs = NetworkConfig.SYNC_MAX_DELAY_MS
        ) {
            // Ensure chat exists before inserting messages (foreign key constraint)
            // This is a fast indexed lookup - only fetches from server if missing
            if (chatDao.getChatByGuid(chatGuid) == null) {
                Timber.d("Chat $chatGuid missing from local DB, fetching from server first")
                chatSyncOperations.fetchChat(chatGuid).onFailure { e ->
                    Timber.e(e, "Failed to fetch chat $chatGuid")
                    throw e
                }
            }

            val response = retryWithRateLimitAwareness {
                api.getChatMessages(
                    guid = chatGuid,
                    limit = limit,
                    offset = offset,
                    sort = "DESC",
                    before = before,
                    after = after
                )
            }

            val body = response.body()
            if (!response.isSuccessful || body == null) {
                throw Exception(body?.message ?: "Failed to fetch messages")
            }

            val allMessages = body.data.orEmpty().map { it.toEntity(chatGuid) }

            // Filter out tombstoned messages (user deleted, should not resurrect)
            val tombstonedGuids = tombstoneDao.findTombstoned(allMessages.map { it.guid })
            val messages = if (tombstonedGuids.isEmpty()) {
                allMessages
            } else {
                Timber.d("Skipping ${tombstonedGuids.size} tombstoned messages during sync")
                allMessages.filter { it.guid !in tombstonedGuids }
            }

            // Batch insert messages (more efficient than individual inserts)
            messageDao.insertMessages(messages)

            // Update unified chat preview with the newest message
            // This ensures conversation list shows correct preview after sync
            if (messages.isNotEmpty()) {
                val newestMessage = messages.maxByOrNull { it.dateCreated }
                if (newestMessage != null) {
                    val chat = chatDao.getChatByGuid(chatGuid)
                    val unifiedChatId = chat?.unifiedChatId
                    if (unifiedChatId != null) {
                        unifiedChatDao.updateLatestMessageIfNewer(
                            id = unifiedChatId,
                            date = newestMessage.dateCreated,
                            text = newestMessage.text,
                            guid = newestMessage.guid,
                            isFromMe = newestMessage.isFromMe,
                            hasAttachments = newestMessage.hasAttachments,
                            source = newestMessage.messageSource,
                            dateDelivered = newestMessage.dateDelivered,
                            dateRead = newestMessage.dateRead,
                            error = newestMessage.error
                        )
                    }
                }
            }

            // Sync attachments via AttachmentRepository (data layer, no service dependency)
            body.data.orEmpty().forEach { messageDto ->
                attachmentRepository.syncAttachmentsFromDto(messageDto)
            }

            // Store social media links for Reels feed
            // This ensures links from synced messages are tracked, not just real-time pushes
            storeSocialMediaLinks(messages, chatGuid, body.data.orEmpty())

            // Record the synced range for sparse pagination tracking
            if (messages.isNotEmpty()) {
                val oldestTimestamp = messages.minOf { it.dateCreated }
                val newestTimestamp = messages.maxOf { it.dateCreated }
                Timber.d("Sync for $chatGuid: ${messages.size} messages, recording range $oldestTimestamp-$newestTimestamp")
                syncRangeTracker.recordSyncedRange(
                    chatGuid = chatGuid,
                    startTimestamp = oldestTimestamp,
                    endTimestamp = newestTimestamp,
                    source = syncSource
                )
            } else if (syncSource == SyncSource.REPAIR) {
                // For REPAIR sync with no messages found, record a marker sync range
                // to prevent the chat from being flagged as needing repair again.
                // Use the chat's latestMessageDate as the marker timestamp.
                val chat = chatDao.getChatByGuid(chatGuid)
                val markerTimestamp = chat?.latestMessageDate ?: System.currentTimeMillis()
                Timber.d("REPAIR sync found 0 messages for $chatGuid, recording marker range at $markerTimestamp")
                syncRangeTracker.recordSyncedRange(
                    chatGuid = chatGuid,
                    startTimestamp = markerTimestamp,
                    endTimestamp = markerTimestamp,
                    source = syncSource
                )
            }

            messages
        }
    }

    /**
     * Sync messages for a specific timestamp range.
     * Used by sparse pagination when scrolling to unsynced regions.
     *
     * @param chatGuid The chat to sync
     * @param startTimestamp Oldest timestamp to fetch
     * @param endTimestamp Newest timestamp to fetch
     * @return Messages synced, or empty list if range was already synced
     */
    suspend fun syncMessagesForRange(
        chatGuid: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): Result<List<MessageEntity>> {
        // Check if range is already synced
        if (syncRangeTracker.isRangeFullySynced(chatGuid, startTimestamp, endTimestamp)) {
            Timber.d("Range already synced for $chatGuid: $startTimestamp - $endTimestamp")
            return Result.success(emptyList())
        }

        // Sync with timestamp bounds
        return syncMessagesForChat(
            chatGuid = chatGuid,
            after = startTimestamp,
            before = endTimestamp,
            limit = 100, // Reasonable limit for a range
            syncSource = SyncSource.ON_DEMAND
        )
    }

    /**
     * Check if more messages are available from the server before a timestamp.
     * Used by sparse pagination to determine if there's more history to load.
     */
    suspend fun hasMoreMessagesBeforeTimestamp(chatGuid: String, beforeTimestamp: Long): Boolean {
        val oldestSynced = syncRangeTracker.getOldestSyncedTimestamp(chatGuid)
        // If we have no sync data, or if the requested timestamp is older than our oldest sync,
        // there might be more messages on the server
        return oldestSynced == null || beforeTimestamp < oldestSynced
    }

    /**
     * Fetch all messages across all chats since a given timestamp.
     * This is much more efficient than iterating through each chat for incremental sync.
     * Returns the number of new messages synced.
     * Retries with exponential backoff on transient network errors.
     */
    suspend fun syncMessagesGlobally(
        after: Long,
        limit: Int = 1000
    ): Result<Int> = runCatching {
        retryWithBackoff(
            times = NetworkConfig.SYNC_RETRY_ATTEMPTS,
            initialDelayMs = NetworkConfig.SYNC_INITIAL_DELAY_MS,
            maxDelayMs = NetworkConfig.SYNC_MAX_DELAY_MS
        ) {
            val response = retryWithRateLimitAwareness {
                api.queryMessages(
                    MessageQueryRequest(
                        after = after,
                        limit = limit,
                        sort = "DESC"
                    )
                )
            }

            val body = response.body()
            if (!response.isSuccessful || body == null) {
                throw Exception(body?.message ?: "Failed to fetch messages")
            }

            val messageDtos = body.data.orEmpty()

            // Transform messages with their chat GUIDs, filtering out ones without chats
            val messagesWithChats = messageDtos.mapNotNull { messageDto ->
                val chatGuid = messageDto.chats?.firstOrNull()?.guid
                if (chatGuid != null) {
                    messageDto to messageDto.toEntity(chatGuid)
                } else {
                    Timber.w("Message ${messageDto.guid} has no associated chat, skipping")
                    null
                }
            }

            val allGuids = messagesWithChats.map { it.second.guid }

            // Filter out tombstoned messages (user deleted, should not resurrect)
            val tombstonedGuids = tombstoneDao.findTombstoned(allGuids)
            val filteredMessagesWithChats = if (tombstonedGuids.isEmpty()) {
                messagesWithChats
            } else {
                Timber.d("Skipping ${tombstonedGuids.size} tombstoned messages during global sync")
                messagesWithChats.filter { it.second.guid !in tombstonedGuids }
            }

            // Batch insert all valid messages (more efficient than individual inserts)
            val messages = filteredMessagesWithChats.map { it.second }
            messageDao.insertMessages(messages)

            // Sync attachments for all messages via AttachmentRepository (data layer, no service dependency)
            filteredMessagesWithChats.forEach { (messageDto, _) ->
                attachmentRepository.syncAttachmentsFromDto(messageDto)
            }

            val syncedCount = filteredMessagesWithChats.size
            Timber.i("Global message sync: fetched ${messageDtos.size}, synced $syncedCount")
            syncedCount
        }
    }

    // ===== Local Mutation Operations =====

    /**
     * Check if a chat is a local SMS/MMS/RCS chat
     */
    fun isLocalSmsChat(chatGuid: String): Boolean {
        return chatGuid.startsWith("sms;-;", ignoreCase = true) ||
            chatGuid.startsWith("mms;-;", ignoreCase = true) ||
            chatGuid.startsWith("RCS;-;", ignoreCase = true)
    }

    /**
     * Get the message source type for a chat
     */
    suspend fun getMessageSourceForChat(chatGuid: String): MessageSource {
        return when {
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.LOCAL_SMS
            chatGuid.startsWith("mms;-;", ignoreCase = true) -> MessageSource.LOCAL_MMS
            chatGuid.startsWith("RCS;-;", ignoreCase = true) -> MessageSource.LOCAL_SMS
            else -> MessageSource.IMESSAGE
        }
    }

    /**
     * Delete a message locally.
     * Records a tombstone to prevent resurrection during sync.
     */
    suspend fun deleteMessageLocally(messageGuid: String): Result<Unit> = runCatching {
        tombstoneDao.recordDeletedMessage(messageGuid)
        messageDao.deleteMessage(messageGuid)
    }

    /**
     * Soft delete multiple messages locally.
     * Sets date_deleted on each message and records tombstones to prevent resurrection during sync.
     *
     * @param guids List of message GUIDs to delete
     */
    suspend fun softDeleteMessages(guids: List<String>): Result<Unit> = runCatching {
        if (guids.isEmpty()) return@runCatching
        // Record tombstones to prevent resurrection during sync
        guids.forEach { guid ->
            tombstoneDao.recordDeletedMessage(guid)
        }
        // Soft delete by setting date_deleted
        messageDao.softDeleteMessages(guids)
    }

    /**
     * Mark a message as failed with an error message and error code.
     * Called when server sends a message-send-error event.
     *
     * @param messageGuid The GUID of the failed message
     * @param errorMessage Human-readable error message
     * @param errorCode Numeric error code (e.g., 22 = not registered with iMessage). Default is 1 (generic error).
     */
    suspend fun markMessageAsFailed(messageGuid: String, errorMessage: String, errorCode: Int = 1): Result<Unit> = runCatching {
        // Update the message error code and error message
        messageDao.updateMessageError(messageGuid, errorCode, errorMessage)
        Timber.d("Marked message $messageGuid as failed (code $errorCode): $errorMessage")
    }

    /**
     * Mark a message's effect as played by setting the datePlayed timestamp.
     */
    suspend fun markEffectPlayed(messageGuid: String): Result<Unit> = runCatching {
        messageDao.updateDatePlayed(messageGuid, System.currentTimeMillis())
    }

    // ===== Pinning & Starring =====

    /**
     * Toggle the pinned status of a message.
     */
    suspend fun toggleMessagePinned(messageGuid: String): Result<Boolean> = runCatching {
        val message = messageDao.getMessageByGuid(messageGuid)
            ?: throw IllegalArgumentException("Message not found: $messageGuid")
        val newPinned = !message.isPinned
        messageDao.updatePinStatus(messageGuid, newPinned)
        Timber.d("Toggled pin status for message $messageGuid to $newPinned")
        newPinned
    }

    /**
     * Set the pinned status of a message.
     */
    suspend fun setMessagePinned(messageGuid: String, isPinned: Boolean): Result<Unit> = runCatching {
        messageDao.updatePinStatus(messageGuid, isPinned)
    }

    /**
     * Toggle the starred (bookmarked) status of a message.
     */
    suspend fun toggleMessageStarred(messageGuid: String): Result<Boolean> = runCatching {
        val message = messageDao.getMessageByGuid(messageGuid)
            ?: throw IllegalArgumentException("Message not found: $messageGuid")
        val newStarred = !message.isBookmarked
        messageDao.updateStarStatus(messageGuid, newStarred)
        Timber.d("Toggled star status for message $messageGuid to $newStarred")
        newStarred
    }

    /**
     * Set the starred (bookmarked) status of a message.
     */
    suspend fun setMessageStarred(messageGuid: String, isStarred: Boolean): Result<Unit> = runCatching {
        messageDao.updateStarStatus(messageGuid, isStarred)
    }

    /**
     * Get all pinned messages for a conversation.
     * Automatically resolves unified chats.
     */
    suspend fun getPinnedMessagesForChat(chatGuid: String): List<MessageEntity> {
        val mergedGuids = resolveUnifiedChatGuids(chatGuid)
        return if (mergedGuids.size == 1) {
            messageDao.getPinnedMessagesForChat(mergedGuids.first())
        } else {
            messageDao.getPinnedMessagesForChats(mergedGuids)
        }
    }

    /**
     * Get all starred messages for a conversation.
     * Automatically resolves unified chats.
     */
    suspend fun getStarredMessagesForChat(chatGuid: String): List<MessageEntity> {
        val mergedGuids = resolveUnifiedChatGuids(chatGuid)
        return if (mergedGuids.size == 1) {
            messageDao.getStarredMessagesForChat(mergedGuids.first())
        } else {
            messageDao.getStarredMessagesForChats(mergedGuids)
        }
    }

    /**
     * Observe starred messages for a unified chat (reactive).
     */
    fun observeStarredMessagesForUnifiedChat(unifiedChatId: String): Flow<List<MessageEntity>> =
        messageDao.observeStarredMessagesForUnifiedChat(unifiedChatId)

    /**
     * Get count of starred messages for a conversation.
     * Automatically resolves unified chats.
     */
    suspend fun getStarredMessageCount(chatGuid: String): Int {
        val mergedGuids = resolveUnifiedChatGuids(chatGuid)
        return if (mergedGuids.size == 1) {
            messageDao.getStarredMessageCount(mergedGuids.first())
        } else {
            messageDao.getStarredMessageCountForChats(mergedGuids)
        }
    }

    // ===== Private Helpers =====

    private fun MessageDto.toEntity(chatGuid: String): MessageEntity {
        // Determine message source based on handle's service or chat GUID prefix
        val source = when {
            handle?.service?.equals("SMS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            handle?.service?.equals("RCS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("SMS;-;") -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("RCS;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("mms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            else -> MessageSource.IMESSAGE.name
        }

        return MessageEntity(
            guid = guid,
            chatGuid = chatGuid,
            handleId = handleId,
            senderAddress = handle?.address,
            text = HtmlEntityDecoder.decode(text),
            subject = HtmlEntityDecoder.decode(subject),
            dateCreated = dateCreated ?: System.currentTimeMillis(),
            dateRead = dateRead,
            dateDelivered = dateDelivered,
            dateEdited = dateEdited,
            datePlayed = datePlayed,
            isFromMe = isFromMe,
            error = error,
            itemType = itemType,
            groupTitle = groupTitle,
            groupActionType = groupActionType,
            balloonBundleId = balloonBundleId,
            associatedMessageGuid = associatedMessageGuid,
            associatedMessagePart = associatedMessagePart,
            associatedMessageType = associatedMessageType,
            expressiveSendStyleId = expressiveSendStyleId,
            threadOriginatorGuid = threadOriginatorGuid,
            threadOriginatorPart = threadOriginatorPart,
            hasAttachments = attachments?.isNotEmpty() == true,
            hasReactions = hasReactions,
            bigEmoji = bigEmoji,
            wasDeliveredQuietly = wasDeliveredQuietly,
            didNotifyRecipient = didNotifyRecipient,
            messageSource = source,
            // Compute is_reaction using centralized logic for efficient SQL queries
            isReactionDb = ReactionClassifier.isReaction(associatedMessageGuid, associatedMessageType)
        )
    }

    // ===== Social Media Link Storage =====

    /**
     * Detects and stores social media links from synced messages.
     * This mirrors the logic in IncomingMessageHandler.storeSocialMediaLinks()
     * but operates on already-synced MessageEntity objects.
     *
     * Only processes non-reaction messages to ensure correct sender attribution.
     */
    private suspend fun storeSocialMediaLinks(
        messages: List<MessageEntity>,
        chatGuid: String,
        messageDtos: List<MessageDto>
    ) {
        val links = mutableListOf<SocialMediaLinkEntity>()
        val dtoMap = messageDtos.associateBy { it.guid }

        for (message in messages) {
            val text = message.text ?: continue

            // Skip reactions - they quote URLs but aren't the original sender
            if (message.associatedMessageType != null) continue

            val dto = dtoMap[message.guid]
            val senderAddress = if (message.isFromMe) null else dto?.handle?.address

            // Find Instagram URLs
            for (match in INSTAGRAM_PATTERN.findAll(text)) {
                val url = match.value
                links.add(
                    SocialMediaLinkEntity(
                        urlHash = hashUrl(url),
                        url = url,
                        messageGuid = message.guid,
                        chatGuid = chatGuid,
                        platform = SocialMediaPlatform.INSTAGRAM.name,
                        senderAddress = senderAddress,
                        isFromMe = message.isFromMe,
                        sentTimestamp = message.dateCreated,
                        isDownloaded = false,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }

            // Find TikTok URLs
            for (match in TIKTOK_PATTERN.findAll(text)) {
                val url = match.value
                links.add(
                    SocialMediaLinkEntity(
                        urlHash = hashUrl(url),
                        url = url,
                        messageGuid = message.guid,
                        chatGuid = chatGuid,
                        platform = SocialMediaPlatform.TIKTOK.name,
                        senderAddress = senderAddress,
                        isFromMe = message.isFromMe,
                        sentTimestamp = message.dateCreated,
                        isDownloaded = false,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }

        if (links.isNotEmpty()) {
            try {
                socialMediaLinkDao.insertAll(links)
                Timber.d("[SocialMedia] Sync stored ${links.size} social media link(s)")
            } catch (e: Exception) {
                Timber.w(e, "[SocialMedia] Failed to store social media links during sync")
            }
        }
    }

    /**
     * Hash URL for deduplication (matches IncomingMessageHandler.hashUrl).
     */
    private fun hashUrl(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
