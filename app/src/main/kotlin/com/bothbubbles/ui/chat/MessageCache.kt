package com.bothbubbles.ui.chat

import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.ui.components.MessageUiModel

/**
 * Cached message with content hashes for change detection.
 * The hashes capture all fields that affect UI rendering.
 */
data class CachedMessage(
    val model: MessageUiModel,
    val sourceHash: Int,        // Hash of MessageEntity fields
    val reactionsHash: Int,     // Hash of reaction GUIDs
    val attachmentsHash: Int    // Hash of attachment states (including download status)
)

/**
 * Cache for MessageUiModel instances that preserves object identity for unchanged messages.
 *
 * This enables Compose to skip recomposition for items that haven't changed,
 * significantly improving scroll performance when updates arrive (reactions,
 * delivery status, read receipts).
 *
 * Signal-inspired pattern: instead of rebuilding the entire list on every
 * database change, we compute a delta and only create new objects for
 * messages that actually changed.
 *
 * Uses LRU eviction to support sparse pagination - messages outside the
 * visible window are evicted by age, not by presence in the current page.
 */
class MessageCache(private val maxSize: Int = 1000) {
    // LRU cache using LinkedHashMap with accessOrder=true
    // When a message is accessed, it moves to the end; eldest entries are evicted first
    private val cache = object : LinkedHashMap<String, CachedMessage>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMessage>?): Boolean {
            return size > maxSize
        }
    }

    /**
     * Update cache with new data and build the result list.
     *
     * For unchanged messages: returns the same MessageUiModel instance (same object reference)
     * For changed messages: creates a new MessageUiModel via the transformer
     *
     * @param entities The current list of message entities from the database
     * @param reactions Map of message GUID to its reaction entities (iMessage tapbacks only)
     * @param attachments Map of message GUID to its attachment entities
     * @param transformer Function to create a new MessageUiModel from entity data
     * @return Pair of (result list, set of changed GUIDs for debugging)
     */
    fun updateAndBuild(
        entities: List<MessageEntity>,
        reactions: Map<String?, List<MessageEntity>>,
        attachments: Map<String, List<AttachmentEntity>>,
        transformer: (MessageEntity, List<MessageEntity>, List<AttachmentEntity>) -> MessageUiModel
    ): Pair<List<MessageUiModel>, Set<String>> {
        val changedGuids = mutableSetOf<String>()

        // NOTE: We don't call retainAll anymore - LRU eviction handles cache size.
        // This is important for sparse pagination where we may jump to distant
        // positions and want to keep previously loaded messages in cache.

        // Build result list, reusing unchanged objects
        val result = entities.map { entity ->
            val entityReactions = reactions[entity.guid].orEmpty()
            val entityAttachments = attachments[entity.guid].orEmpty()

            val newSourceHash = entity.contentHash()
            val newReactionsHash = entityReactions.reactionsHash()
            val newAttachmentsHash = entityAttachments.attachmentsHash()

            val cached = cache[entity.guid]

            if (cached != null &&
                cached.sourceHash == newSourceHash &&
                cached.reactionsHash == newReactionsHash &&
                cached.attachmentsHash == newAttachmentsHash
            ) {
                // Unchanged - return same object reference
                // Compose will see this is the same object and skip recomposition
                cached.model
            } else {
                // Changed or new - create new model
                changedGuids.add(entity.guid)
                val newModel = transformer(entity, entityReactions, entityAttachments)
                cache[entity.guid] = CachedMessage(
                    model = newModel,
                    sourceHash = newSourceHash,
                    reactionsHash = newReactionsHash,
                    attachmentsHash = newAttachmentsHash
                )
                newModel
            }
        }

        return result to changedGuids
    }

    /**
     * Add or update a single message in the cache.
     * Used by direct socket handlers to maintain cache consistency.
     */
    fun put(guid: String, model: MessageUiModel, sourceHash: Int, reactionsHash: Int = 0, attachmentsHash: Int = 0) {
        cache[guid] = CachedMessage(
            model = model,
            sourceHash = sourceHash,
            reactionsHash = reactionsHash,
            attachmentsHash = attachmentsHash
        )
    }

    /**
     * Update an existing cached message's model while preserving hashes.
     * Used for surgical updates like delivery status changes.
     */
    fun updateModel(guid: String, updater: (MessageUiModel) -> MessageUiModel) {
        cache[guid]?.let { existing ->
            cache[guid] = existing.copy(model = updater(existing.model))
        }
    }

    /**
     * Clear all cached messages.
     * Called when leaving the chat screen.
     */
    fun clear() = cache.clear()

    /**
     * Get the current cache size for debugging.
     */
    val size: Int get() = cache.size
}

// ============================================================================
// Hash Extension Functions
// ============================================================================

/**
 * Compute a content hash for a MessageEntity.
 * Includes all fields that affect UI rendering.
 */
fun MessageEntity.contentHash(): Int {
    var hash = guid.hashCode()
    hash = 31 * hash + (text?.hashCode() ?: 0)
    hash = 31 * hash + dateCreated.hashCode()
    hash = 31 * hash + (dateDelivered?.hashCode() ?: 0)
    hash = 31 * hash + (dateRead?.hashCode() ?: 0)
    hash = 31 * hash + error
    hash = 31 * hash + isFromMe.hashCode()
    hash = 31 * hash + (dateEdited?.hashCode() ?: 0)
    hash = 31 * hash + (subject?.hashCode() ?: 0)
    hash = 31 * hash + (expressiveSendStyleId?.hashCode() ?: 0)
    hash = 31 * hash + (threadOriginatorGuid?.hashCode() ?: 0)
    hash = 31 * hash + hasAttachments.hashCode()
    hash = 31 * hash + (smsStatus?.hashCode() ?: 0)
    hash = 31 * hash + (smsErrorMessage?.hashCode() ?: 0)
    return hash
}

/**
 * Compute a hash for iMessage reactions.
 * Order doesn't matter - we use a Set for consistency.
 */
fun List<MessageEntity>.reactionsHash(): Int {
    if (isEmpty()) return 0
    return map { it.guid }.toSet().hashCode()
}

/**
 * Compute a hash for attachments.
 * Includes localPath since download state affects UI (loading indicator vs content).
 */
fun List<AttachmentEntity>.attachmentsHash(): Int {
    if (isEmpty()) return 0
    return map { "${it.guid}:${it.localPath}:${it.thumbnailPath}" }.toSet().hashCode()
}
