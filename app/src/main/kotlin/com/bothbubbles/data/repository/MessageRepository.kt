package com.bothbubbles.data.repository

import android.content.Context
import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.ReactionClassifier
import com.bothbubbles.data.local.db.entity.SyncSource
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.MessageDto
import com.bothbubbles.data.remote.api.dto.MessageQueryRequest
import com.bothbubbles.services.sync.SyncRangeTracker
import com.bothbubbles.util.NetworkConfig
import com.bothbubbles.util.retryWithBackoff
import com.bothbubbles.util.retryWithRateLimitAwareness
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val api: BothBubblesApi,
    private val settingsDataStore: SettingsDataStore,
    private val syncRangeTracker: SyncRangeTracker,
    private val attachmentRepository: AttachmentRepository
) {
    companion object {
        private const val TAG = "MessageRepository"
    }

    // ===== Local Query Operations =====

    fun observeMessagesForChat(chatGuid: String, limit: Int, offset: Int): Flow<List<MessageEntity>> =
        messageDao.observeMessagesForChat(chatGuid, limit, offset)

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
     * Get messages containing URLs for link gallery.
     */
    fun getMessagesWithUrlsForChat(chatGuid: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesWithUrlsForChat(chatGuid)

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
    suspend fun deleteAllMessages() {
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

            val messages = body.data.orEmpty().map { it.toEntity(chatGuid) }

            // Batch insert messages (more efficient than individual inserts)
            messageDao.insertMessages(messages)

            // Sync attachments via AttachmentRepository (data layer, no service dependency)
            body.data.orEmpty().forEach { messageDto ->
                attachmentRepository.syncAttachmentsFromDto(messageDto)
            }

            // Record the synced range for sparse pagination tracking
            if (messages.isNotEmpty()) {
                val oldestTimestamp = messages.minOf { it.dateCreated }
                val newestTimestamp = messages.maxOf { it.dateCreated }
                syncRangeTracker.recordSyncedRange(
                    chatGuid = chatGuid,
                    startTimestamp = oldestTimestamp,
                    endTimestamp = newestTimestamp,
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
            Log.d(TAG, "Range already synced for $chatGuid: $startTimestamp - $endTimestamp")
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
                    Log.w(TAG, "Message ${messageDto.guid} has no associated chat, skipping")
                    null
                }
            }

            // Batch insert all valid messages (more efficient than individual inserts)
            val messages = messagesWithChats.map { it.second }
            messageDao.insertMessages(messages)

            // Sync attachments for all messages via AttachmentRepository (data layer, no service dependency)
            messagesWithChats.forEach { (messageDto, _) ->
                attachmentRepository.syncAttachmentsFromDto(messageDto)
            }

            val syncedCount = messagesWithChats.size
            Log.i(TAG, "Global message sync: fetched ${messageDtos.size}, synced $syncedCount")
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
     * Delete a message locally
     */
    suspend fun deleteMessageLocally(messageGuid: String) {
        messageDao.deleteMessage(messageGuid)
    }

    /**
     * Mark a message as failed with an error message.
     * Called when server sends a message-send-error event.
     */
    suspend fun markMessageAsFailed(messageGuid: String, errorMessage: String) {
        // Update the message error code and error message
        messageDao.updateMessageError(messageGuid, 1, errorMessage)
        Log.d(TAG, "Marked message $messageGuid as failed: $errorMessage")
    }

    /**
     * Mark a message's effect as played by setting the datePlayed timestamp.
     */
    suspend fun markEffectPlayed(messageGuid: String) {
        messageDao.updateDatePlayed(messageGuid, System.currentTimeMillis())
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
            text = text,
            subject = subject,
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
}
