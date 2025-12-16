package com.bothbubbles.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
import com.bothbubbles.services.messaging.AttachmentPersistenceManager
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.services.messaging.MessageSendWorker
import com.bothbubbles.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bothbubbles.services.messaging.MessageSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val messageSender: dagger.Lazy<MessageSender>  // Lazy to avoid circular dependency
) {
    companion object {
        private const val TAG = "PendingMessageRepo"
    }

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

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
    suspend fun queueMessage(
        chatGuid: String,
        text: String?,
        subject: String? = null,
        replyToGuid: String? = null,
        effectId: String? = null,
        attachments: List<PendingAttachmentInput> = emptyList(),
        deliveryMode: MessageDeliveryMode = MessageDeliveryMode.AUTO,
        forcedLocalId: String? = null
    ): Result<String> = runCatching {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "[SEND_TRACE] ── PendingMessageRepository.queueMessage START ──")
        Log.i(TAG, "[SEND_TRACE] chatGuid=$chatGuid, mode=$deliveryMode, attachments=${attachments.size}")

        // Use "temp-" prefix so MessageEntity.isSent correctly returns false
        val clientGuid = forcedLocalId ?: "temp-${UUID.randomUUID()}"
        val createdAt = System.currentTimeMillis()
        Log.i(TAG, "[SEND_TRACE] clientGuid=$clientGuid +${System.currentTimeMillis() - startTime}ms")

        // Determine message source based on delivery mode
        val messageSource = inferMessageSource(chatGuid, deliveryMode)

        // Persist attachments to internal storage first (outside transaction for I/O)
        val attachPersistStart = System.currentTimeMillis()
        Log.i(TAG, "[SEND_TRACE] Persisting ${attachments.size} attachments +${System.currentTimeMillis() - startTime}ms")
        val persistedAttachments = if (attachments.isNotEmpty()) {
            attachments.mapIndexedNotNull { index, input ->
                val uri = input.uri
                val attachmentLocalId = "$clientGuid-att-$index"
                attachmentPersistenceManager.persistAttachment(uri, attachmentLocalId)
                    .onFailure { e ->
                        Log.e(TAG, "[SEND_TRACE] Failed to persist attachment: $uri", e)
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
        if (attachments.isNotEmpty()) {
            Log.i(TAG, "[SEND_TRACE] Attachment persist DONE: ${System.currentTimeMillis() - attachPersistStart}ms (${attachments.size} files)")
        }

        // Use transaction to ensure atomicity: all-or-nothing for DB operations
        val txStart = System.currentTimeMillis()
        Log.i(TAG, "[SEND_TRACE] Starting DB transaction +${System.currentTimeMillis() - startTime}ms")
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
                createdAt = createdAt
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
            persistedAttachments.forEach { data ->
                val attachmentEntity = AttachmentEntity(
                    guid = data.localId,
                    messageGuid = clientGuid,
                    mimeType = data.mimeType,
                    transferName = data.fileName,
                    totalBytes = data.fileSize,
                    isOutgoing = true,
                    localPath = Uri.fromFile(File(data.persistedPath)).toString(),
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
        Log.i(TAG, "[SEND_TRACE] DB transaction DONE: ${System.currentTimeMillis() - txStart}ms")
        Log.i(TAG, "[SEND_TRACE] Created pending message id=$messageId +${System.currentTimeMillis() - startTime}ms")

        if (persistedAttachments.isNotEmpty()) {
            Log.i(TAG, "[SEND_TRACE] Created ${persistedAttachments.size} attachment echoes")
        }

        // Try immediate send first (bypasses WorkManager 300-400ms scheduling latency)
        // Fall back to WorkManager only if immediate send fails
        Log.i(TAG, "[SEND_TRACE] Launching async IMMEDIATE SEND +${System.currentTimeMillis() - startTime}ms")
        applicationScope.launch {
            val sendStart = System.currentTimeMillis()
            Log.i(TAG, "[SEND_TRACE] [IMMEDIATE] Attempting immediate send...")

            try {
                // Build attachment inputs from persisted data
                val attachmentInputs = persistedAttachments.map { data ->
                    PendingAttachmentInput(
                        uri = Uri.fromFile(File(data.persistedPath)),
                        caption = data.caption,
                        mimeType = data.mimeType,
                        name = data.fileName,
                        size = data.fileSize
                    )
                }

                // Update status to SENDING
                pendingMessageDao.updateStatusWithTimestamp(
                    messageId,
                    PendingSyncStatus.SENDING.name,
                    System.currentTimeMillis()
                )

                // Try to send immediately
                val result = messageSender.get().sendUnified(
                    chatGuid = chatGuid,
                    text = text ?: "",
                    replyToGuid = replyToGuid,
                    effectId = effectId,
                    subject = subject,
                    attachments = attachmentInputs,
                    deliveryMode = deliveryMode,
                    tempGuid = clientGuid
                )

                if (result.isSuccess) {
                    val sentMessage = result.getOrThrow()
                    Log.i(TAG, "[SEND_TRACE] [IMMEDIATE] ✓ SUCCESS in ${System.currentTimeMillis() - sendStart}ms: $clientGuid -> ${sentMessage.guid}")

                    // Mark as sent
                    pendingMessageDao.markAsSent(messageId, sentMessage.guid)

                    // Clean up persisted attachments
                    if (persistedAttachments.isNotEmpty()) {
                        attachmentPersistenceManager.cleanupAttachments(persistedAttachments.map { it.persistedPath })
                    }
                } else {
                    val error = result.exceptionOrNull()
                    Log.w(TAG, "[SEND_TRACE] [IMMEDIATE] ✗ FAILED in ${System.currentTimeMillis() - sendStart}ms: ${error?.message}")
                    Log.i(TAG, "[SEND_TRACE] [IMMEDIATE] Falling back to WorkManager for retry...")

                    // Reset status and enqueue WorkManager for retry
                    pendingMessageDao.updateStatus(messageId, PendingSyncStatus.PENDING.name)
                    enqueueWorker(messageId, clientGuid)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[SEND_TRACE] [IMMEDIATE] ✗ EXCEPTION: ${e.message}")
                Log.i(TAG, "[SEND_TRACE] [IMMEDIATE] Falling back to WorkManager for retry...")

                // Reset status and enqueue WorkManager for retry
                pendingMessageDao.updateStatus(messageId, PendingSyncStatus.PENDING.name)
                enqueueWorker(messageId, clientGuid)
            }
        }

        Log.i(TAG, "[SEND_TRACE] ── PendingMessageRepository.queueMessage RETURNING: ${System.currentTimeMillis() - startTime}ms total ──")
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
     */
    private suspend fun enqueueWorker(pendingMessageId: Long, localId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<MessageSendWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, // 30 seconds initial backoff
                TimeUnit.SECONDS
            )
            .setInputData(workDataOf(MessageSendWorker.KEY_PENDING_MESSAGE_ID to pendingMessageId))
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

        Log.d(TAG, "Enqueued send worker for $localId (workId=${workRequest.id})")
    }

    /**
     * Retry a failed message.
     */
    suspend fun retryMessage(localId: String): Result<Unit> = runCatching {
        val pending = pendingMessageDao.getByLocalId(localId)
            ?: throw Exception("Message not found: $localId")

        if (pending.syncStatus != PendingSyncStatus.FAILED.name) {
            throw Exception("Message is not in failed state: ${pending.syncStatus}")
        }

        // Reset status and re-enqueue
        pendingMessageDao.updateStatus(pending.id, PendingSyncStatus.PENDING.name)
        enqueueWorker(pending.id, localId)

        Log.i(TAG, "Retrying message: $localId")
    }

    /**
     * Cancel a pending message.
     * Also removes the local echo from the messages table.
     */
    suspend fun cancelMessage(localId: String): Result<Unit> = runCatching {
        val pending = pendingMessageDao.getByLocalId(localId)
            ?: throw Exception("Message not found: $localId")

        // Cancel WorkManager job if exists
        pending.workRequestId?.let { workId ->
            try {
                workManager.cancelWorkById(UUID.fromString(workId))
                Log.d(TAG, "Cancelled work request: $workId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel work request", e)
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

        Log.i(TAG, "Cancelled message: $localId")
    }

    /**
     * Observe pending messages for a specific chat.
     * Used by ChatViewModel to display queued messages with status indicators.
     */
    fun observePendingForChat(chatGuid: String): Flow<List<PendingMessageEntity>> =
        pendingMessageDao.observeForChat(chatGuid)

    /**
     * Observe count of pending messages for a chat (for badges).
     */
    fun observePendingCount(chatGuid: String): Flow<Int> =
        pendingMessageDao.observePendingCount(chatGuid)

    /**
     * Re-enqueue all pending messages.
     * Called at app startup to restart stalled jobs.
     */
    suspend fun reEnqueuePendingMessages() {
        // Reset messages stuck in SENDING status (app was killed during send)
        // Consider stale if SENDING for more than 2 minutes
        val staleThreshold = System.currentTimeMillis() - (2 * 60 * 1000)
        val staleSending = pendingMessageDao.getStaleSending(staleThreshold)
        if (staleSending.isNotEmpty()) {
            Log.i(TAG, "Found ${staleSending.size} stale SENDING messages, resetting to PENDING")
            staleSending.forEach { message ->
                pendingMessageDao.updateStatus(message.id, PendingSyncStatus.PENDING.name)
            }
        }

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
            Log.i(TAG, "Re-enqueued $reEnqueuedCount pending messages at startup")
        }

        // Clean up orphaned attachment files
        val referencedPaths = pendingAttachmentDao.getAllPersistedPaths().toSet()
        attachmentPersistenceManager.cleanupOrphanedAttachments(referencedPaths)
    }

    /**
     * Clean up sent messages (run periodically or on startup).
     */
    suspend fun cleanupSentMessages() {
        val sentCount = pendingMessageDao.getByStatus(PendingSyncStatus.SENT.name).size
        if (sentCount > 0) {
            pendingMessageDao.deleteSent()
            Log.d(TAG, "Cleaned up $sentCount sent messages")
        }
    }

    /**
     * Get count of unsent messages (for startup indicator).
     */
    suspend fun getUnsentCount(): Int = pendingMessageDao.getUnsentCount()
}
