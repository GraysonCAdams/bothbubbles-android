package com.bothbubbles.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bothbubbles.data.local.db.dao.PendingAttachmentDao
import com.bothbubbles.data.local.db.dao.PendingMessageDao
import com.bothbubbles.data.local.db.entity.PendingAttachmentEntity
import com.bothbubbles.data.local.db.entity.PendingMessageEntity
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.services.messaging.AttachmentPersistenceManager
import com.bothbubbles.services.messaging.MessageSendWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    private val pendingMessageDao: PendingMessageDao,
    private val pendingAttachmentDao: PendingAttachmentDao,
    private val attachmentPersistenceManager: AttachmentPersistenceManager
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
     * @return Local ID of the queued message for UI tracking
     */
    suspend fun queueMessage(
        chatGuid: String,
        text: String?,
        subject: String? = null,
        replyToGuid: String? = null,
        effectId: String? = null,
        attachments: List<Uri> = emptyList(),
        deliveryMode: MessageDeliveryMode = MessageDeliveryMode.AUTO
    ): Result<String> = runCatching {
        val localId = "pending-${UUID.randomUUID()}"

        // 1. Create and insert pending message
        val pendingMessage = PendingMessageEntity(
            localId = localId,
            chatGuid = chatGuid,
            text = text,
            subject = subject,
            replyToGuid = replyToGuid,
            effectId = effectId,
            deliveryMode = deliveryMode.name,
            syncStatus = PendingSyncStatus.PENDING.name,
            createdAt = System.currentTimeMillis()
        )
        val messageId = pendingMessageDao.insert(pendingMessage)
        Log.d(TAG, "Created pending message: $localId (id=$messageId)")

        // 2. Persist attachments if any
        if (attachments.isNotEmpty()) {
            val persistedAttachments = attachments.mapIndexedNotNull { index, uri ->
                val attachmentLocalId = "$localId-att-$index"
                attachmentPersistenceManager.persistAttachment(uri, attachmentLocalId)
                    .onFailure { e ->
                        Log.e(TAG, "Failed to persist attachment: $uri", e)
                    }
                    .getOrNull()
                    ?.let { result ->
                        PendingAttachmentEntity(
                            localId = attachmentLocalId,
                            pendingMessageId = messageId,
                            originalUri = uri.toString(),
                            persistedPath = result.persistedPath,
                            fileName = result.fileName,
                            mimeType = result.mimeType,
                            fileSize = result.fileSize,
                            orderIndex = index
                        )
                    }
            }

            if (persistedAttachments.isNotEmpty()) {
                pendingAttachmentDao.insertAll(persistedAttachments)
                Log.d(TAG, "Persisted ${persistedAttachments.size} attachments for $localId")
            }
        }

        // 3. Enqueue WorkManager job
        enqueueWorker(messageId, localId)

        localId
    }

    /**
     * Enqueue a WorkManager job to send a pending message.
     */
    private fun enqueueWorker(pendingMessageId: Long, localId: String) {
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
        workManager.enqueueUniqueWork(
            "${MessageSendWorker.UNIQUE_WORK_PREFIX}$localId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        // Store work request ID for cancellation
        kotlinx.coroutines.runBlocking {
            pendingMessageDao.updateWorkRequestId(pendingMessageId, workRequest.id.toString())
        }

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

        // Delete from database (CASCADE will delete attachments)
        pendingMessageDao.delete(pending.id)

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
