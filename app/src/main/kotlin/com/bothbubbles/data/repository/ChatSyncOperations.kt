package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.dto.ChatDto
import com.bothbubbles.core.network.api.dto.ChatQueryRequest
import com.bothbubbles.util.NetworkConfig
import com.bothbubbles.util.UnifiedChatIdGenerator
import com.bothbubbles.util.retryWithBackoff
import com.bothbubbles.util.retryWithRateLimitAwareness
import com.bothbubbles.util.error.NetworkError
import com.bothbubbles.util.error.safeCall
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles chat synchronization operations between remote server and local database.
 * Extracted from ChatRepository to keep files under 500 lines.
 */
@Singleton
class ChatSyncOperations @Inject constructor(
    private val chatDao: ChatDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val api: BothBubblesApi,
    private val participantOps: ChatParticipantOperations,
    private val groupPhotoSyncManager: GroupPhotoSyncManager
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

            // Log group chats with names for debugging sync issues
            val namedGroups = chatDtos.filter {
                (it.participants?.size ?: 0) > 1 && !it.displayName.isNullOrBlank()
            }
            if (namedGroups.isNotEmpty()) {
                Timber.tag(TAG).d("Synced ${namedGroups.size} named group chats: ${namedGroups.map { it.displayName }.joinToString(", ")}")
            }

            // Link to unified chats first, then build entities with unified_chat_id
            val chatsWithUnifiedIds = chatDtos.map { chatDto ->
                val unifiedChatId = linkToUnifiedChatIfNeeded(chatDto)
                chatDto.toEntity(unifiedChatId)
            }

            // Prepare all participant data
            val participantData = chatDtos.zip(chatsWithUnifiedIds).map { (chatDto, chat) ->
                val handleIds = participantOps.upsertHandlesForChat(chatDto)
                chat to handleIds
            }

            // Single transactional call
            chatDao.syncChatsWithParticipants(participantData)

            // Sync group photos for group chats that have them
            val groupPhotos = chatDtos
                .filter { it.groupPhotoGuid != null }
                .associate { it.guid to it.groupPhotoGuid }
            if (groupPhotos.isNotEmpty()) {
                Timber.tag(TAG).i("Found ${groupPhotos.size} chats with group photos to sync")
                groupPhotoSyncManager.syncGroupPhotos(groupPhotos)
            }

            chatsWithUnifiedIds
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

            // Link to unified chat and get the ID
            val unifiedChatId = linkToUnifiedChatIfNeeded(chatDto)
            val chat = chatDto.toEntity(unifiedChatId)

            // Atomically sync chat with participants
            syncChatParticipants(chatDto, chat)

            // Sync group photo if available
            groupPhotoSyncManager.syncGroupPhoto(chatDto.guid, chatDto.groupPhotoGuid)

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
    }

    /**
     * Link a non-group chat to a unified chat if it represents a 1:1 conversation.
     * @return The unified chat ID if linked, null otherwise.
     */
    private suspend fun linkToUnifiedChatIfNeeded(chatDto: ChatDto): String? {
        // Only link 1:1 chats (not group chats)
        val isGroup = (chatDto.participants?.size ?: 0) > 1
        if (isGroup) return null

        // Get the normalized address for this conversation
        val chatIdentifier = chatDto.chatIdentifier
        if (chatIdentifier.isNullOrBlank()) return null

        // Normalize address for unified chat lookup
        val normalizedAddress = if (chatIdentifier.contains("@")) {
            chatIdentifier.lowercase()
        } else {
            chatIdentifier.replace(Regex("[^0-9+]"), "")
        }

        if (normalizedAddress.isBlank()) return null

        return try {
            val unifiedChat = unifiedChatDao.getOrCreate(
                UnifiedChatEntity(
                    id = UnifiedChatIdGenerator.generate(),
                    normalizedAddress = normalizedAddress,
                    sourceId = chatDto.guid
                )
            )

            // If existing unified chat has SMS source but this chat is iMessage, prefer iMessage
            // This ensures users navigate to iMessage when available for better experience
            val currentSourceId = unifiedChat.sourceId
            val newChatGuid = chatDto.guid
            val isCurrentSourceSms = currentSourceId.startsWith("sms;", ignoreCase = true) ||
                                      currentSourceId.startsWith("mms;", ignoreCase = true)
            val isNewChatIMessage = !newChatGuid.startsWith("sms;", ignoreCase = true) &&
                                     !newChatGuid.startsWith("mms;", ignoreCase = true)

            if (isCurrentSourceSms && isNewChatIMessage) {
                unifiedChatDao.updateSourceId(unifiedChat.id, newChatGuid)
                Timber.tag(TAG).i("Upgraded unified chat ${unifiedChat.id} source from SMS to iMessage: $newChatGuid")
            }

            unifiedChat.id
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to link chat ${chatDto.guid} to unified chat")
            null
        }
    }

    private fun ChatDto.toEntity(unifiedChatId: String?): ChatEntity {
        // Clean the displayName: strip service suffixes and validate
        val cleanedDisplayName = displayName
            ?.let { com.bothbubbles.util.PhoneNumberFormatter.stripServiceSuffix(it) }
            ?.takeIf { it.isValidDisplayName() }

        return ChatEntity(
            guid = guid,
            unifiedChatId = unifiedChatId,
            chatIdentifier = chatIdentifier,
            displayName = cleanedDisplayName,
            style = style,
            isGroup = (participants?.size ?: 0) > 1,
            latestMessageDate = lastMessage?.dateCreated
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
