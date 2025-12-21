package com.bothbubbles.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import timber.log.Timber
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bothbubbles.data.local.db.BothBubblesDatabase
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.PendingAttachmentDao
import com.bothbubbles.data.local.db.dao.PendingMessageDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.data.local.db.entity.PendingAttachmentEntity
import com.bothbubbles.data.local.db.entity.PendingMessageEntity
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.services.messaging.AttachmentPersistenceManager
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.services.messaging.MessageSendWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing pending (queued) messages.
 *
 * This provides an offline-first message sending experience:
 * 1. Messages are immediately persisted to Room when send is requested
 * 2. Attachments are copied to app-internal storage
 * 3. WorkManager is used to guarantee delivery when network is available
 * 4. UI observes pending messages to show status indicators
 *
 * Benefits:
 * - Messages survive app kills and device reboots
 * - Users can queue messages without network connectivity
 * - Automatic retry with exponential backoff
 */
@Singleton
class PendingMessageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BothBubblesDatabase,
    private val pendingMessageDao: PendingMessageDao,
    private val pendingAttachmentDao: PendingAttachmentDao,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val chatDao: ChatDao,
    private val attachmentPersistenceManager: AttachmentPersistenceManager,
    private val api: BothBubblesApi
) : PendingMessageSource {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    // ============================================================================
    // GLOBAL DUPLICATE DETECTION (Singleton-level tracking across all chats)
    // ============================================================================
    private data class GlobalRecentSend(
        val chatGuid: String,
        val textHash: Int,
        val textPreview: String,
        val timestamp: Long,
        val localId: String
    )
    private val globalRecentSends = mutableListOf<GlobalRecentSend>()
    private val maxGlobalRecentSends = 50
    private val globalDuplicateWindowMs = 10 * 60 * 1000L // 10 minutes

    /**
     * Queue a message for sending.
     *
     * The message is immediately written to the database, then a WorkManager job
     * is enqueued to send it when network is available.
     *
     * **Optimistic UI**: This method creates a local echo in the `messages` table
     * immediately, so the message bubble appears instantly in the chat without
     * waiting for WorkManager to start. The bubble will show a "Sending" indicator
     * until the server confirms receipt.
     *
     * @return Local ID (temp GUID) of the queued message for UI tracking
     */
    override suspend fun queueMessage(
        chatGuid: String,
        text: String?,
        subject: String?,
        replyToGuid: String?,
        effectId: String?,
        attachments: List<PendingAttachmentInput>,
        deliveryMode: MessageDeliveryMode,
        forcedLocalId: String?,
        attributedBodyJson: String?
    ): Result<String> = runCatching {
        val createdAt = System.currentTimeMillis()

        // Generate clientGuid and check for duplicates inside synchronized block
        // to prevent race condition where two threads send the same message
        val clientGuid = synchronized(globalRecentSends) {
            // Use "temp-" prefix so MessageEntity.isSent correctly returns false
            val guid = forcedLocalId ?: "temp-${UUID.randomUUID()}"

            // Track recent sends for potential duplicate detection
            val textForHash = text?.trim() ?: ""
            if (textForHash.isNotBlank()) {
                val textHash = textForHash.hashCode()
                val textPreview = textForHash.take(30)

                // Clean up old entries
                val cutoffTime = createdAt - globalDuplicateWindowMs
                globalRecentSends.removeAll { it.timestamp < cutoffTime }

                // Check for duplicate (same chat + same text hash + within window)
                val duplicate = globalRecentSends.find {
                    it.chatGuid == chatGuid && it.textHash == textHash
                }
                if (duplicate != null) {
                    Timber.w("Potential duplicate send detected! chatGuid=$chatGuid, text='$textPreview', existing=${duplicate.localId}")
                    // Return existing localId to prevent duplicate
                    return@runCatching duplicate.localId
                }

                // Record this queue
                globalRecentSends.add(GlobalRecentSend(
                    chatGuid = chatGuid,
                    textHash = textHash,
                    textPreview = textPreview,
                    timestamp = createdAt,
                    localId = guid
                ))
                if (globalRecentSends.size > maxGlobalRecentSends) {
                    globalRecentSends.removeAt(0)
                }
            }

            guid
        }

        // Determine message source based on delivery mode
        val messageSource = inferMessageSource(chatGuid, deliveryMode)

        // Persist attachments to internal storage first (outside transaction for I/O)
        val persistedAttachments = if (attachments.isNotEmpty()) {
            attachments.mapIndexedNotNull { index, input ->
                val uri = input.uri
                val attachmentLocalId = "$clientGuid-att-$index"
                attachmentPersistenceManager.persistAttachment(uri, attachmentLocalId)
                    .onFailure { e ->
                        Timber.e(e, "Failed to persist attachment: $uri")
                    }
                    .getOrNull()
                    ?.let { result ->
                        PersistedAttachmentData(
                            localId = attachmentLocalId,
                            originalUri = uri.toString(),
                            persistedPath = result.persistedPath,
                            fileName = result.fileName,
                            mimeType = result.mimeType,
                            fileSize = result.fileSize,
                            orderIndex = index,
                            caption = input.caption
                        )
                    }
            }
        } else {
            emptyList()
        }

        // Use transaction to ensure atomicity: all-or-nothing for DB operations
        val messageId = database.withTransaction {
            // 1. Create pending message (durability/retry engine)
            val pendingMessage = PendingMessageEntity(
                localId = clientGuid,
                chatGuid = chatGuid,
                text = text,
                subject = subject,
                replyToGuid = replyToGuid,
                effectId = effectId,
                deliveryMode = deliveryMode.name,
                syncStatus = PendingSyncStatus.PENDING.name,
                createdAt = createdAt,
                attributedBodyJson = attributedBodyJson
            )
            val pendingId = pendingMessageDao.insert(pendingMessage)

            // 2. Insert pending attachments if any
            if (persistedAttachments.isNotEmpty()) {
                val pendingAttachmentEntities = persistedAttachments.map { data ->
                    PendingAttachmentEntity(
                        localId = data.localId,
                        pendingMessageId = pendingId,
                        originalUri = data.originalUri,
                        persistedPath = data.persistedPath,
                        fileName = data.fileName,
                        mimeType = data.mimeType,
                        fileSize = data.fileSize,
                        orderIndex = data.orderIndex,
                        caption = data.caption
                    )
                }
                pendingAttachmentDao.insertAll(pendingAttachmentEntities)
            }

            // 3. Create local echo in messages table (instant UI feedback)
            val localEcho = MessageEntity(
                guid = clientGuid,
                chatGuid = chatGuid,
                text = text,
                subject = subject,
                dateCreated = createdAt,
                isFromMe = true,
                hasAttachments = persistedAttachments.isNotEmpty(),
                threadOriginatorGuid = replyToGuid,
                expressiveSendStyleId = effectId,
                messageSource = messageSource.name
            )
            messageDao.insertMessage(localEcho)

            // 4. Create attachment entities for immediate display
            // Store raw absolute paths (not file:// URIs) so downstream code works consistently
            persistedAttachments.forEach { data ->
                val attachmentEntity = AttachmentEntity(
                    guid = data.localId,
                    messageGuid = clientGuid,
                    mimeType = data.mimeType,
                    transferName = data.fileName,
                    totalBytes = data.fileSize,
                    isOutgoing = true,
                    localPath = data.persistedPath,  // Raw path, not file:// URI
                    transferState = TransferState.UPLOADING.name,
                    transferProgress = 0f
                )
                attachmentDao.insertAttachment(attachmentEntity)
            }

            // 5. Update chat's last message for conversation list
            chatDao.updateLastMessage(
                chatGuid,
                createdAt,
                text ?: if (persistedAttachments.isNotEmpty()) "[Attachment]" else ""
            )

            pendingId
        }

        // Enqueue expedited WorkManager job for reliable, single-path delivery
        enqueueWorker(messageId, clientGuid)

        clientGuid
    }

    /**
     * Infer the message source based on chat GUID and delivery mode.
     */
    private fun inferMessageSource(chatGuid: String, deliveryMode: MessageDeliveryMode): MessageSource {
        return when (deliveryMode) {
            MessageDeliveryMode.LOCAL_SMS -> MessageSource.LOCAL_SMS
            MessageDeliveryMode.LOCAL_MMS -> MessageSource.LOCAL_MMS
            MessageDeliveryMode.IMESSAGE -> MessageSource.IMESSAGE
            MessageDeliveryMode.AUTO -> {
                // Infer from chat GUID prefix
                when {
                    chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.LOCAL_SMS
                    chatGuid.startsWith("mms;-;", ignoreCase = true) -> MessageSource.LOCAL_MMS
                    chatGuid.startsWith("RCS;-;", ignoreCase = true) -> MessageSource.LOCAL_SMS
                    else -> MessageSource.IMESSAGE
                }
            }
        }
    }

    /**
     * Internal data class for persisted attachment info (used during queue transaction).
     */
    private data class PersistedAttachmentData(
        val localId: String,
        val originalUri: String,
        val persistedPath: String,
        val fileName: String,
        val mimeType: String,
        val fileSize: Long,
        val orderIndex: Int,
        val caption: String?
    )

    /**
     * Enqueue a WorkManager job to send a pending message.
     * Uses expedited work for minimal latency while maintaining reliability.
     */
    private suspend fun enqueueWorker(pendingMessageId: Long, localId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<MessageSendWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, // 30 seconds initial backoff
                TimeUnit.SECONDS
            )
            .setInputData(workDataOf(MessageSendWorker.KEY_PENDING_MESSAGE_ID to pendingMessageId))
            .addTag("message_sending")
            .build()

        // Use unique work to prevent duplicate sends
        // KEEP policy prevents replacing an in-flight job, avoiding race conditions
        workManager.enqueueUniqueWork(
            "${MessageSendWorker.UNIQUE_WORK_PREFIX}$localId",
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        // Store work request ID for cancellation
        pendingMessageDao.updateWorkRequestId(pendingMessageId, workRequest.id.toString())
    }

    /**
     * Retry a failed message.
     */
    override suspend fun retryMessage(localId: String): Result<Unit> = runCatching {
        val pending = pendingMessageDao.getByLocalId(localId)
            ?: throw Exception("Message not found: $localId")

        if (pending.syncStatus != PendingSyncStatus.FAILED.name) {
            throw Exception("Message is not in failed state: ${pending.syncStatus}")
        }

        // Reset status and re-enqueue
        pendingMessageDao.updateStatus(pending.id, PendingSyncStatus.PENDING.name)
        enqueueWorker(pending.id, localId)

        Timber.i("Retrying message: $localId")
    }

    /**
     * Cancel a pending message.
     * Also removes the local echo from the messages table.
     */
    override suspend fun cancelMessage(localId: String): Result<Unit> = runCatching {
        val pending = pendingMessageDao.getByLocalId(localId)
            ?: throw Exception("Message not found: $localId")

        // Cancel WorkManager job if exists
        pending.workRequestId?.let { workId ->
            try {
                workManager.cancelWorkById(UUID.fromString(workId))
                Timber.d("Cancelled work request: $workId")
            } catch (e: Exception) {
                Timber.w(e, "Failed to cancel work request")
            }
        }

        // Clean up persisted attachments
        val attachments = pendingAttachmentDao.getForMessage(pending.id)
        attachmentPersistenceManager.cleanupAttachments(attachments.map { it.persistedPath })

        // Delete from database (use transaction to ensure atomicity)
        database.withTransaction {
            // Delete local echo from messages table (CASCADE will delete attachments)
            messageDao.deleteMessage(localId)

            // Delete pending message (CASCADE will delete pending attachments)
            pendingMessageDao.delete(pending.id)
        }

        Timber.i("Cancelled message: $localId")
    }

    /**
     * Observe pending messages for a specific chat.
     * Used by ChatViewModel to display queued messages with status indicators.
     */
    override fun observePendingForChat(chatGuid: String): Flow<List<PendingMessageEntity>> =
        pendingMessageDao.observeForChat(chatGuid)

    /**
     * Observe count of pending messages for a chat (for badges).
     */
    override fun observePendingCount(chatGuid: String): Flow<Int> =
        pendingMessageDao.observePendingCount(chatGuid)

    /**
     * Re-enqueue all pending messages.
     * Called at app startup to restart stalled jobs.
     *
     * NOTE: verifyAndFailStuckMessages() should be called BEFORE this method
     * to handle SENDING messages that got interrupted. This method only
     * re-enqueues PENDING messages, not FAILED ones (let user manually retry those).
     */
    override suspend fun reEnqueuePendingMessages(): Result<Unit> = runCatching {
        val pending = pendingMessageDao.getPendingAndFailed()
        var reEnqueuedCount = 0

        pending.forEach { message ->
            // Only re-enqueue PENDING messages (not FAILED - let user manually retry those)
            if (message.syncStatus == PendingSyncStatus.PENDING.name) {
                enqueueWorker(message.id, message.localId)
                reEnqueuedCount++
            }
        }

        if (reEnqueuedCount > 0) {
            Timber.i("Re-enqueued $reEnqueuedCount pending messages at startup")
        }

        // Clean up orphaned attachment files
        val referencedPaths = pendingAttachmentDao.getAllPersistedPaths().toSet()
        attachmentPersistenceManager.cleanupOrphanedAttachments(referencedPaths)
    }

    /**
     * Clean up sent messages (run periodically or on startup).
     */
    override suspend fun cleanupSentMessages(): Result<Unit> = runCatching {
        val sentCount = pendingMessageDao.getByStatus(PendingSyncStatus.SENT.name).size
        if (sentCount > 0) {
            pendingMessageDao.deleteSent()
            Timber.d("Cleaned up $sentCount sent messages")
        }
    }

    /**
     * Clean up orphaned temp messages that weren't properly replaced.
     * This handles race conditions where both temp and server messages exist.
     * Called at app startup.
     */
    override suspend fun cleanupOrphanedTempMessages(): Result<Unit> = runCatching {
        // Find temp messages older than 5 minutes (legitimate temps should be replaced within seconds)
        val staleThreshold = System.currentTimeMillis() - (5 * 60 * 1000)
        val orphanedTemps = messageDao.getOrphanedTempMessages(staleThreshold)

        var cleanedCount = 0
        for (tempMessage in orphanedTemps) {
            // Check if a "real" message exists with same chat + similar timestamp
            val possibleDuplicate = messageDao.findMatchingMessage(
                chatGuid = tempMessage.chatGuid,
                text = tempMessage.text,
                isFromMe = true,
                dateCreated = tempMessage.dateCreated,
                toleranceMs = 30_000,  // 30 second window
                excludeGuid = tempMessage.guid  // Exclude self
            )

            if (possibleDuplicate != null && !possibleDuplicate.guid.startsWith("temp-")) {
                // Found the real message - safe to delete temp
                Timber.i("Cleaning up orphaned temp message: ${tempMessage.guid}")
                messageDao.deleteMessage(tempMessage.guid)
                cleanedCount++
            }
        }

        // Also clean up orphaned temp attachments
        val orphanedAttachments = attachmentDao.deleteOrphanedTempAttachments()

        if (cleanedCount > 0 || orphanedAttachments > 0) {
            Timber.i("Cleaned up $cleanedCount orphaned temp messages and $orphanedAttachments orphaned temp attachments")
        }
    }

    /**
     * Get count of unsent messages (for startup indicator).
     */
    override suspend fun getUnsentCount(): Int = pendingMessageDao.getUnsentCount()

    /**
     * Verify stuck SENDING messages with the server on app startup.
     * Also syncs FAILED pending messages where the MessageEntity error wasn't set.
     *
     * For each message in SENDING state:
     * - If serverGuid exists, check with server if it was delivered
     * - If delivered (error = 0) → mark as SENT
     * - If not delivered or no serverGuid → mark as FAILED
     *
     * For each message in FAILED state:
     * - Ensure the MessageEntity error field is set (sync from pending to messages table)
     *
     * This prevents messages from being stuck in "sending" state forever after app kill.
     * Does NOT re-attempt sending - just verifies and marks appropriately.
     */
    override suspend fun verifyAndFailStuckMessages(): Int {
        try {
            var verifiedCount = 0

            // 1. Handle SENDING messages - verify with server
            val sendingMessages = pendingMessageDao.getByStatus(PendingSyncStatus.SENDING.name)
            if (sendingMessages.isNotEmpty()) {
                Timber.i("Found ${sendingMessages.size} stuck SENDING messages, verifying with server...")

                for (message in sendingMessages) {
                    val serverGuid = message.serverGuid
                    val localId = message.localId

                    if (serverGuid != null) {
                        // Server GUID exists - API call succeeded before interruption
                        // Check with server if the message was actually delivered
                        try {
                            val response = api.getMessage(serverGuid)
                            if (response.isSuccessful) {
                                val serverMessage = response.body()?.data
                                if (serverMessage != null && serverMessage.error == 0) {
                                    // Message was delivered successfully!
                                    Timber.i("Message $localId was delivered (serverGuid=$serverGuid)")
                                    pendingMessageDao.markAsSent(message.id, serverGuid)
                                    // Update MessageEntity to show as sent
                                    messageDao.replaceGuid(localId, serverGuid)
                                    verifiedCount++
                                    continue
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to verify message $localId with server")
                        }
                    }

                    // Either no serverGuid, server unreachable, or message not delivered
                    // Mark as FAILED
                    val errorMessage = if (serverGuid == null) {
                        "Send interrupted before server response"
                    } else {
                        "Message not confirmed delivered by server"
                    }

                    Timber.w("Marking stuck message as FAILED: $localId (serverGuid=$serverGuid)")
                    pendingMessageDao.updateStatusWithError(
                        message.id,
                        PendingSyncStatus.FAILED.name,
                        errorMessage,
                        System.currentTimeMillis()
                    )
                    // Update MessageEntity so UI shows as failed
                    messageDao.updateMessageError(localId, 1, errorMessage)
                    verifiedCount++
                }
            }

            // 2. Sync FAILED pending messages - ensure MessageEntity error is set
            // This handles messages that failed before the fix was installed
            val failedMessages = pendingMessageDao.getByStatus(PendingSyncStatus.FAILED.name)
            if (failedMessages.isNotEmpty()) {
                Timber.d("Syncing ${failedMessages.size} FAILED messages to ensure UI shows error...")

                for (message in failedMessages) {
                    val localId = message.localId
                    // Check if the MessageEntity has error=0 (not synced yet)
                    val messageEntity = messageDao.getMessageByGuid(localId)
                    if (messageEntity != null && messageEntity.error == 0) {
                        val errorMessage = message.errorMessage ?: "Message failed to send"
                        Timber.i("Syncing error to MessageEntity: $localId")
                        messageDao.updateMessageError(localId, 1, errorMessage)
                        verifiedCount++
                    }
                }
            }

            if (verifiedCount > 0) {
                Timber.i("Verified/synced $verifiedCount stuck messages")
            }
            return verifiedCount
        } catch (e: Exception) {
            Timber.e(e, "Error verifying stuck messages")
            return 0
        }
    }
}
