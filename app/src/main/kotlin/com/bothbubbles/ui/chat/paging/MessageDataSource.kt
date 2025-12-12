package com.bothbubbles.ui.chat.paging

import com.bothbubbles.ui.components.message.MessageUiModel
import kotlinx.coroutines.flow.Flow

/**
 * Data source interface for Signal-style pagination.
 * Based on Signal's PagedDataSource pattern.
 *
 * The data source is responsible for:
 * 1. Providing the total message count (for BitSet sizing)
 * 2. Loading messages by position range (for gap filling)
 * 3. Loading individual messages by key (for updates)
 * 4. Observing size changes (for new message detection)
 */
interface MessageDataSource {

    /**
     * Get the total number of messages in the conversation.
     * Used to initialize the BitSet size for sparse pagination.
     */
    suspend fun size(): Int

    /**
     * Observe changes to the total message count.
     * Emits when messages are added or deleted.
     * Used to resize the BitSet and detect new messages.
     */
    fun observeSize(): Flow<Int>

    /**
     * Load messages at the specified position range.
     *
     * @param start The starting position (0 = newest message)
     * @param count The number of messages to load
     * @return List of MessageUiModel, may be smaller than count if near end of conversation
     */
    suspend fun load(start: Int, count: Int): List<MessageUiModel>

    /**
     * Load a single message by its GUID.
     * Used for updating individual messages (reactions, delivery status, etc.)
     *
     * @param guid The message GUID
     * @return The MessageUiModel, or null if not found
     */
    suspend fun loadByKey(guid: String): MessageUiModel?

    /**
     * Get the key (GUID) for a message at the given position.
     * Used for maintaining guid-to-position mapping.
     *
     * @param position The message position (0 = newest)
     * @return The message GUID, or null if position is out of bounds
     */
    suspend fun getKey(position: Int): String?

    /**
     * Get the position (index) of a specific message within the sorted conversation.
     * Used for jump-to-message (search results, deep links, scroll to reply).
     *
     * @param guid The message GUID to find
     * @return The position (0 = newest), or -1 if not found
     */
    suspend fun getMessagePosition(guid: String): Int
}

/**
 * Interface for triggering sync when data is missing from Room.
 * Implemented by the sync layer to fetch from server/SMS when gaps are detected.
 */
interface SyncTrigger {

    /**
     * Request sync for a specific position range.
     * Called when the data source detects that Room is missing messages
     * for the requested range (returned fewer items than expected).
     *
     * @param chatGuids The chat GUIDs to sync
     * @param startPosition The starting position that needs data
     * @param count The number of positions that need data
     */
    suspend fun requestSyncForRange(chatGuids: List<String>, startPosition: Int, count: Int)

    /**
     * Request sync for a specific message.
     * Called when loadByKey returns null for a message we expected to exist.
     *
     * @param chatGuids The chat GUIDs to search
     * @param messageGuid The GUID of the message to sync
     */
    suspend fun requestSyncForMessage(chatGuids: List<String>, messageGuid: String)
}
