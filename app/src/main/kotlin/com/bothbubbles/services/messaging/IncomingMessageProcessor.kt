package com.bothbubbles.services.messaging

import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.remote.api.dto.MessageDto

/**
 * Interface for processing incoming messages from the server.
 * Allows mocking in tests without modifying the concrete implementation.
 *
 * This interface defines the contract for handling messages received via
 * Socket.IO or FCM push notifications, ensuring proper deduplication and
 * coordination with the local database.
 *
 * Implementation: [IncomingMessageHandler]
 */
interface IncomingMessageProcessor {

    /**
     * Handle a new message from server (via Socket.IO or push).
     *
     * This method is safe against duplicate processing - if the same message arrives
     * via both FCM and Socket.IO, the unread count will only be incremented once.
     *
     * @param messageDto The message DTO from the server
     * @param chatGuid The chat GUID this message belongs to
     * @return The saved MessageEntity
     */
    suspend fun handleIncomingMessage(messageDto: MessageDto, chatGuid: String): MessageEntity

    /**
     * Handle message update (read receipt, delivery, edit, etc.).
     *
     * @param messageDto The updated message DTO from the server
     * @param chatGuid The chat GUID this message belongs to
     */
    suspend fun handleMessageUpdate(messageDto: MessageDto, chatGuid: String)

    /**
     * Sync attachments for an incoming message to local database.
     * Incoming attachments are marked as PENDING for auto-download.
     *
     * @param messageDto The message DTO containing attachment information
     * @param tempMessageGuid Optional temp GUID to clean up when replacing with server message
     */
    suspend fun syncIncomingAttachments(messageDto: MessageDto, tempMessageGuid: String? = null)
}
