package com.bothbubbles.ui.chat.paging

import timber.log.Timber
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.ui.chat.MessageCache
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.ReplyPreviewData
import com.bothbubbles.ui.components.message.normalizeAddress
import com.bothbubbles.ui.components.message.resolveSenderName
import com.bothbubbles.ui.components.message.toUiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Room-backed implementation of MessageDataSource for Signal-style pagination.
 * Handles loading messages by position and transforming them to UI models.
 *
 * This class moves the transformation logic from ChatViewModel into a reusable
 * data source that can be used by MessagePagingController.
 */
class RoomMessageDataSource(
    private val chatGuids: List<String>,
    private val messageRepository: MessageRepository,
    private val attachmentRepository: AttachmentRepository,
    private val handleRepository: HandleRepository,
    private val chatRepository: ChatRepository,
    private val messageCache: MessageCache,
    private val syncTrigger: SyncTrigger?
) : MessageDataSource {

    // Cached participant data for sender name resolution
    private var cachedParticipants: List<HandleEntity> = emptyList()
    private var handleIdToName: Map<Long, String> = emptyMap()
    private var addressToName: Map<String, String> = emptyMap()
    private var addressToAvatarPath: Map<String, String?> = emptyMap()

    override suspend fun size(): Int {
        return messageRepository.getMessageCountForChats(chatGuids)
    }

    override fun observeSize(): Flow<Int> {
        return messageRepository.observeMessageCountForChats(chatGuids)
    }

    override suspend fun load(start: Int, count: Int): List<MessageUiModel> {
        Timber.d("Loading messages: start=$start, count=$count")

        // Fetch messages by position
        val entities = messageRepository.getMessagesByPosition(chatGuids, count, start)
        Timber.d("Loaded ${entities.size} entities from Room")

        // If we got fewer than expected, might need to sync from server
        if (entities.size < count && syncTrigger != null) {
            Timber.d("Got fewer messages than expected, triggering sync")
            syncTrigger.requestSyncForRange(chatGuids, start, count)
        }

        if (entities.isEmpty()) {
            return emptyList()
        }

        // DEFENSIVE: Deduplicate by GUID (should never happen with proper DB constraints)
        val dedupedEntities = entities.distinctBy { it.guid }
        if (dedupedEntities.size != entities.size) {
            Timber.w("DEDUP: Found ${entities.size - dedupedEntities.size} duplicate GUIDs in Room query result")
        }

        // Ensure participant data is loaded
        ensureParticipantsLoaded()

        // Transform to UI models
        return transformToUiModels(dedupedEntities)
    }

    override suspend fun loadByKey(guid: String): MessageUiModel? {
        val entity = messageRepository.getMessageByGuid(guid) ?: return null

        // Ensure participant data is loaded
        ensureParticipantsLoaded()

        // Transform single entity
        return transformToUiModels(listOf(entity)).firstOrNull()
    }

    override suspend fun getKey(position: Int): String? {
        val entities = messageRepository.getMessagesByPosition(chatGuids, 1, position)
        return entities.firstOrNull()?.guid
    }

    override suspend fun getMessagePosition(guid: String): Int {
        // First check if the message exists
        val message = messageRepository.getMessageByGuid(guid) ?: return -1

        // Verify the message belongs to our chat(s)
        if (message.chatGuid !in chatGuids) return -1

        // Get position using the repository query (counts messages newer than target)
        return messageRepository.getMessagePosition(chatGuids, guid)
    }

    /**
     * Refresh participant data from Room.
     * Call this when participants might have changed.
     */
    suspend fun refreshParticipants() {
        loadParticipants()
    }

    private suspend fun ensureParticipantsLoaded() {
        if (cachedParticipants.isEmpty()) {
            loadParticipants()
        }
    }

    private suspend fun loadParticipants() {
        cachedParticipants = chatRepository.observeParticipantsForChats(chatGuids).first()

        // Build lookup maps
        handleIdToName = cachedParticipants.associate { it.id to it.displayName }.toMutableMap()
        addressToName = cachedParticipants.associate { normalizeAddress(it.address) to it.displayName }
        addressToAvatarPath = cachedParticipants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }

        Timber.d("Loaded ${cachedParticipants.size} participants")
    }

    /**
     * Transform message entities to UI models.
     * This is the core transformation logic moved from ChatViewModel.
     * Note: entities are already filtered to exclude reactions by the SQL query.
     */
    private suspend fun transformToUiModels(entities: List<MessageEntity>): List<MessageUiModel> {
        if (entities.isEmpty()) return emptyList()

        // Entities are already non-reactions (filtered by SQL query for position consistency)
        // Fetch reactions separately for these messages
        val messageGuids = entities.map { it.guid }
        val iMessageReactions = if (messageGuids.isNotEmpty()) {
            messageRepository.getReactionsForMessages(messageGuids)
        } else {
            emptyList()
        }

        // Group iMessage reactions by their associated message GUID
        // Note: associatedMessageGuid may have "p:X/" prefix (e.g., "p:0/MESSAGE_GUID")
        // Strip the prefix to match against plain message GUIDs
        val reactionsByMessage = iMessageReactions.groupBy { reaction ->
            reaction.associatedMessageGuid?.let { guid ->
                if (guid.contains("/")) guid.substringAfter("/") else guid
            }
        }

        // Batch load all attachments in a single query
        val allAttachments = attachmentRepository.getAttachmentsForMessages(messageGuids)
            .groupBy { it.messageGuid }

        // Build reply preview data
        val replyPreviewMap = buildReplyPreviewMap(entities)

        // Fetch missing handles for sender name resolution
        val mutableHandleIdToName = handleIdToName.toMutableMap()
        val missingHandleIds = entities
            .filter { !it.isFromMe && it.handleId != null && it.handleId !in mutableHandleIdToName }
            .mapNotNull { it.handleId }
            .distinct()

        if (missingHandleIds.isNotEmpty()) {
            val handles = handleRepository.getHandlesByIds(missingHandleIds)
            handles.forEach { handle ->
                val normalizedAddress = normalizeAddress(handle.address)
                val matchingName = addressToName[normalizedAddress]
                if (matchingName != null) {
                    mutableHandleIdToName[handle.id] = matchingName
                } else {
                    mutableHandleIdToName[handle.id] = handle.displayName
                }
            }
        }

        // Use MessageCache for incremental updates
        val (messageModels, changedGuids) = messageCache.updateAndBuild(
            entities = entities,
            reactions = reactionsByMessage,
            attachments = allAttachments
        ) { entity, reactions, attachments ->
            val replyPreview = entity.threadOriginatorGuid?.let { replyPreviewMap[it] }
            entity.toUiModel(
                reactions = reactions,
                attachments = attachments,
                handleIdToName = mutableHandleIdToName,
                addressToName = addressToName,
                addressToAvatarPath = addressToAvatarPath,
                replyPreview = replyPreview
            )
        }

        if (changedGuids.isNotEmpty()) {
            Timber.d("Incremental update: ${changedGuids.size} changed, ${messageModels.size - changedGuids.size} reused")
        }

        return messageModels
    }

    private suspend fun buildReplyPreviewMap(messages: List<MessageEntity>): Map<String, ReplyPreviewData> {
        val replyGuids = messages
            .mapNotNull { it.threadOriginatorGuid }
            .distinct()

        if (replyGuids.isEmpty()) return emptyMap()

        val loadedMessagesMap = messages.associateBy { it.guid }

        val missingGuids = replyGuids.filter { it !in loadedMessagesMap }
        val fetchedOriginals = if (missingGuids.isNotEmpty()) {
            messageRepository.getMessagesByGuids(missingGuids).associateBy { it.guid }
        } else {
            emptyMap()
        }

        val allMessagesMap = loadedMessagesMap + fetchedOriginals

        return replyGuids.mapNotNull { originGuid ->
            val originalMessage = allMessagesMap[originGuid]
            if (originalMessage != null) {
                originGuid to ReplyPreviewData(
                    originalGuid = originGuid,
                    previewText = originalMessage.text?.take(50),
                    senderName = resolveSenderName(
                        originalMessage.senderAddress,
                        originalMessage.handleId
                    ),
                    isFromMe = originalMessage.isFromMe,
                    hasAttachment = originalMessage.hasAttachments,
                    isNotLoaded = false
                )
            } else {
                originGuid to ReplyPreviewData(
                    originalGuid = originGuid,
                    previewText = null,
                    senderName = null,
                    isFromMe = false,
                    hasAttachment = false,
                    isNotLoaded = true
                )
            }
        }.toMap()
    }

    private fun resolveSenderName(senderAddress: String?, handleId: Long?): String? {
        // Try address first
        senderAddress?.let { addr ->
            val normalized = normalizeAddress(addr)
            addressToName[normalized]?.let { return it }
        }

        // Fall back to handleId
        handleId?.let { id ->
            handleIdToName[id]?.let { return it }
        }

        return null
    }

    private fun normalizeAddress(address: String): String {
        return address.filter { it.isDigit() || it == '+' }
    }
}
