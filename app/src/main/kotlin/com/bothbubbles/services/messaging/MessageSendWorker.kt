package com.bothbubbles.services.messaging

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bothbubbles.data.local.db.dao.PendingAttachmentDao
import com.bothbubbles.data.local.db.dao.PendingMessageDao
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.data.repository.MessageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * WorkManager worker that sends pending messages.
 *
 * This worker is enqueued with network constraints, so it only runs when
 * connectivity is available. It handles retry with exponential backoff.
 *
 * Key responsibilities:
 * 1. Load pending message from database
 * 2. Load persisted attachments
 * 3. Send via MessageRepository
 * 4. Update status to SENT or FAILED
 * 5. Clean up attachment files on success
 */
@HiltWorker
class MessageSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingMessageDao: PendingMessageDao,
    private val pendingAttachmentDao: PendingAttachmentDao,
    private val messageRepository: MessageRepository,
    private val attachmentPersistenceManager: AttachmentPersistenceManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "MessageSendWorker"
        const val KEY_PENDING_MESSAGE_ID = "pending_message_id"
        const val UNIQUE_WORK_PREFIX = "send_message_"
        private const val MAX_RETRY_COUNT = 3
    }

    override suspend fun doWork(): Result {
        val pendingMessageId = inputData.getLong(KEY_PENDING_MESSAGE_ID, -1)

        if (pendingMessageId == -1L) {
            Log.e(TAG, "Pending message ID is missing")
            return Result.failure()
        }

        val pendingMessage = pendingMessageDao.getById(pendingMessageId)
        if (pendingMessage == null) {
            Log.e(TAG, "Pending message not found: $pendingMessageId")
            return Result.failure()
        }

        // Skip if already sent
        if (pendingMessage.syncStatus == PendingSyncStatus.SENT.name) {
            Log.d(TAG, "Message already sent: ${pendingMessage.localId}")
            return Result.success()
        }

        Log.d(TAG, "Sending message ${pendingMessage.localId} (attempt ${runAttemptCount + 1})")

        // Update status to SENDING
        pendingMessageDao.updateStatusWithTimestamp(
            pendingMessageId,
            PendingSyncStatus.SENDING.name,
            System.currentTimeMillis()
        )

        return try {
            // Get persisted attachments and convert to file:// URIs
            val attachments = pendingAttachmentDao.getForMessage(pendingMessageId)
            val attachmentUris = attachments.mapNotNull { attachment ->
                val file = File(attachment.persistedPath)
                if (file.exists()) {
                    Uri.fromFile(file)
                } else {
                    Log.w(TAG, "Attachment file missing: ${attachment.persistedPath}")
                    null
                }
            }

            // Determine delivery mode
            val deliveryMode = try {
                MessageDeliveryMode.valueOf(pendingMessage.deliveryMode)
            } catch (e: Exception) {
                MessageDeliveryMode.AUTO
            }

            // Send via repository
            // Pass localId as tempGuid to ensure same ID is used across retries
            val result = messageRepository.sendUnified(
                chatGuid = pendingMessage.chatGuid,
                text = pendingMessage.text ?: "",
                replyToGuid = pendingMessage.replyToGuid,
                effectId = pendingMessage.effectId,
                subject = pendingMessage.subject,
                attachments = attachmentUris,
                deliveryMode = deliveryMode,
                tempGuid = pendingMessage.localId
            )

            if (result.isSuccess) {
                val sentMessage = result.getOrThrow()
                Log.i(TAG, "Message sent successfully: ${pendingMessage.localId} -> ${sentMessage.guid}")

                // Mark as sent with server GUID
                pendingMessageDao.markAsSent(pendingMessageId, sentMessage.guid)

                // Clean up persisted attachments
                attachmentPersistenceManager.cleanupAttachments(attachments.map { it.persistedPath })

                Result.success()
            } else {
                handleFailure(pendingMessageId, result.exceptionOrNull())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            handleFailure(pendingMessageId, e)
        }
    }

    private suspend fun handleFailure(pendingMessageId: Long, error: Throwable?): Result {
        val errorMessage = error?.message ?: "Unknown error"

        return if (runAttemptCount < MAX_RETRY_COUNT) {
            // Retry with backoff - mark as PENDING to allow retry
            Log.w(TAG, "Send failed, will retry (attempt ${runAttemptCount + 1}/$MAX_RETRY_COUNT): $errorMessage")
            pendingMessageDao.updateStatusWithError(
                pendingMessageId,
                PendingSyncStatus.PENDING.name,
                errorMessage,
                System.currentTimeMillis()
            )
            Result.retry()
        } else {
            // Max retries exceeded - mark as FAILED
            Log.e(TAG, "Send failed after $MAX_RETRY_COUNT attempts: $errorMessage")
            pendingMessageDao.updateStatusWithError(
                pendingMessageId,
                PendingSyncStatus.FAILED.name,
                errorMessage,
                System.currentTimeMillis()
            )
            Result.failure()
        }
    }
}
