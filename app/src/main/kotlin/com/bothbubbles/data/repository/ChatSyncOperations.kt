package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.ChatDto
import com.bothbubbles.data.remote.api.dto.ChatQueryRequest
import com.bothbubbles.util.NetworkConfig
import com.bothbubbles.util.retryWithBackoff
import com.bothbubbles.util.retryWithRateLimitAwareness
import com.bothbubbles.util.error.NetworkError
import com.bothbubbles.util.error.safeCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles chat synchronization operations between remote server and local database.
 * Extracted from ChatRepository to keep files under 500 lines.
 */
@Singleton
class ChatSyncOperations @Inject constructor(
    private val chatDao: ChatDao,
    private val api: BothBubblesApi,
    private val participantOps: ChatParticipantOperations,
    private val unifiedGroupOps: UnifiedGroupOperations
) {
    companion object {
        private const val TAG = "ChatSyncOperations"
    }

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
                val handleIds = participantOps.upsertHandlesForChat(chatDto)
                chat to handleIds
            }

            // Single transactional call
            chatDao.syncChatsWithParticipants(participantData)

            // Post-sync linking
            chatDtos.forEach { chatDto ->
                unifiedGroupOps.linkChatToUnifiedGroupIfNeeded(chatDto)
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
     * Sync a chat's participants atomically.
     * This ensures all participants are updated together or not at all,
     * preventing partial state if the app crashes during sync.
     */
    private suspend fun syncChatParticipants(chatDto: ChatDto, chat: ChatEntity) {
        // Step 1: Upsert all handles and collect their IDs (handles exist independently)
        val handleIds = participantOps.upsertHandlesForChat(chatDto)

        // Step 2: Atomically sync chat with participants (transactional)
        chatDao.syncChatWithParticipants(chat, handleIds)

        // Step 3: For single-contact iMessage chats, link to unified group
        // (This has its own transaction handling in UnifiedChatGroupDao)
        unifiedGroupOps.linkChatToUnifiedGroupIfNeeded(chatDto)
    }

    private fun ChatDto.toEntity(): ChatEntity {
        // Clean the displayName: strip service suffixes and validate
        val cleanedDisplayName = displayName
            ?.let { com.bothbubbles.util.PhoneNumberFormatter.stripServiceSuffix(it) }
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
internal fun String.isValidDisplayName(): Boolean {
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
