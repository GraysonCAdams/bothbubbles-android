package com.bothbubbles.services.messaging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import timber.log.Timber
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bothbubbles.data.local.db.dao.PendingAttachmentDao
import com.bothbubbles.data.local.db.dao.PendingMessageDao
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.data.model.PendingAttachmentInput
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * WorkManager worker that sends pending messages.
 *
 * This worker is enqueued with network constraints, so it only runs when
 * connectivity is available. It handles retry with exponential backoff.
 *
 * Android 14/16 Compliance:
 * - Uses expedited work with foreground service for user-initiated sends
 * - Declares SHORT_SERVICE type for sends from Android Auto (< 3 min tasks)
 * - Properly handles background start restrictions via active session context
 *
 * Key responsibilities:
 * 1. Load pending message from database
 * 2. Load persisted attachments
 * 3. Send via MessageSendingService
 * 4. Update status to SENT or FAILED
 * 5. Clean up attachment files on success
 */
@HiltWorker
class MessageSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingMessageDao: PendingMessageDao,
    private val pendingAttachmentDao: PendingAttachmentDao,
    private val attachmentDao: com.bothbubbles.data.local.db.dao.AttachmentDao,
    private val messageSendingService: MessageSendingService,
    private val attachmentPersistenceManager: AttachmentPersistenceManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "MessageSendWorker"
        const val KEY_PENDING_MESSAGE_ID = "pending_message_id"
        const val UNIQUE_WORK_PREFIX = "send_message_"
        private const val MAX_RETRY_COUNT = 3

        private const val NOTIFICATION_CHANNEL_ID = "message_send_channel"
        private const val NOTIFICATION_ID = 9001
    }

    /**
     * Provide ForegroundInfo for expedited work on Android 12+.
     *
     * On Android 14+, uses SHORT_SERVICE type for Android Auto sends
     * (tasks guaranteed to complete in < 3 minutes).
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = createNotification()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: Use SHORT_SERVICE type for quick message sends
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13: Use DATA_SYNC type
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Message Sending",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when messages are being sent"
                setShowBadge(false)
            }
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Sending message")
            .setContentText("Your message is being sent...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override suspend fun doWork(): Result {
        val workerStartTime = System.currentTimeMillis()
        Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
        Timber.i("[SEND_TRACE] MessageSendWorker.doWork() STARTED at $workerStartTime")

        val pendingMessageId = inputData.getLong(KEY_PENDING_MESSAGE_ID, -1)

        if (pendingMessageId == -1L) {
            Timber.e("[SEND_TRACE] FATAL: Pending message ID is missing")
            return Result.failure()
        }
        Timber.i("[SEND_TRACE] pendingMessageId=$pendingMessageId +${System.currentTimeMillis() - workerStartTime}ms")

        val pendingMessage = pendingMessageDao.getById(pendingMessageId)
        if (pendingMessage == null) {
            Timber.e("[SEND_TRACE] FATAL: Pending message not found: $pendingMessageId")
            return Result.failure()
        }
        Timber.i("[SEND_TRACE] Loaded pending message: localId=${pendingMessage.localId}, chatGuid=${pendingMessage.chatGuid} +${System.currentTimeMillis() - workerStartTime}ms")

        // Skip if already sent
        if (pendingMessage.syncStatus == PendingSyncStatus.SENT.name) {
            Timber.i("[SEND_TRACE] SKIPPING: Message already sent: ${pendingMessage.localId}")
            return Result.success()
        }

        Timber.i("[SEND_TRACE] Sending message ${pendingMessage.localId} (attempt ${runAttemptCount + 1}) +${System.currentTimeMillis() - workerStartTime}ms")

        // Update status to SENDING
        Timber.i("[SEND_TRACE] Updating status to SENDING +${System.currentTimeMillis() - workerStartTime}ms")
        pendingMessageDao.updateStatusWithTimestamp(
            pendingMessageId,
            PendingSyncStatus.SENDING.name,
            System.currentTimeMillis()
        )

        return try {
            // Get persisted attachments and convert to PendingAttachmentInput
            Timber.i("[SEND_TRACE] Loading attachments +${System.currentTimeMillis() - workerStartTime}ms")
            val attachments = pendingAttachmentDao.getForMessage(pendingMessageId)
            Timber.i("[SEND_TRACE] Found ${attachments.size} attachments +${System.currentTimeMillis() - workerStartTime}ms")
            val attachmentInputs = attachments.mapNotNull { attachment ->
                val file = File(attachment.persistedPath)
                if (file.exists()) {
                    PendingAttachmentInput(
                        uri = Uri.fromFile(file),
                        caption = attachment.caption,
                        mimeType = attachment.mimeType,
                        name = attachment.fileName,
                        size = attachment.fileSize
                    )
                } else {
                    Timber.w("[SEND_TRACE] Attachment file missing: ${attachment.persistedPath}")
                    null
                }
            }

            // Determine delivery mode
            val deliveryMode = try {
                MessageDeliveryMode.valueOf(pendingMessage.deliveryMode)
            } catch (e: Exception) {
                MessageDeliveryMode.AUTO
            }
            Timber.i("[SEND_TRACE] deliveryMode=$deliveryMode, attachments=${attachmentInputs.size} +${System.currentTimeMillis() - workerStartTime}ms")

            // Send via MessageSendingService
            // Pass localId as tempGuid to ensure same ID is used across retries
            Timber.i("[SEND_TRACE] ── Calling MessageSendingService.sendUnified ── +${System.currentTimeMillis() - workerStartTime}ms")
            val sendStart = System.currentTimeMillis()
            val result = messageSendingService.sendUnified(
                chatGuid = pendingMessage.chatGuid,
                text = pendingMessage.text ?: "",
                replyToGuid = pendingMessage.replyToGuid,
                effectId = pendingMessage.effectId,
                subject = pendingMessage.subject,
                attachments = attachmentInputs,
                deliveryMode = deliveryMode,
                tempGuid = pendingMessage.localId
            )
            Timber.i("[SEND_TRACE] sendUnified RETURNED after ${System.currentTimeMillis() - sendStart}ms +${System.currentTimeMillis() - workerStartTime}ms total")

            if (result.isSuccess) {
                val sentMessage = result.getOrThrow()
                Timber.i("[SEND_TRACE] ✓ Message SENT SUCCESSFULLY: ${pendingMessage.localId} -> ${sentMessage.guid}")
                Timber.i("[SEND_TRACE] Server GUID: ${sentMessage.guid}")

                // Mark as sent with server GUID
                Timber.i("[SEND_TRACE] Marking as sent in DB +${System.currentTimeMillis() - workerStartTime}ms")
                pendingMessageDao.markAsSent(pendingMessageId, sentMessage.guid)

                // Clean up persisted attachments
                if (attachments.isNotEmpty()) {
                    Timber.i("[SEND_TRACE] Cleaning up ${attachments.size} attachment files")
                    attachmentPersistenceManager.cleanupAttachments(attachments.map { it.persistedPath })
                }

                Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
                Timber.i("[SEND_TRACE] MessageSendWorker COMPLETE: ${System.currentTimeMillis() - workerStartTime}ms total")
                Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
                Result.success()
            } else {
                Timber.e("[SEND_TRACE] ✗ sendUnified FAILED: ${result.exceptionOrNull()?.message}")
                handleFailure(pendingMessageId, result.exceptionOrNull())
            }
        } catch (e: Exception) {
            Timber.e(e, "[SEND_TRACE] ✗ Exception during send: ${e.message}")
            handleFailure(pendingMessageId, e)
        }
    }

    private suspend fun handleFailure(pendingMessageId: Long, error: Throwable?): Result {
        val errorMessage = error?.message ?: "Unknown error"

        // Sync attachment errors from AttachmentEntity to PendingAttachmentEntity
        try {
            val pendingMessage = pendingMessageDao.getById(pendingMessageId)
            if (pendingMessage != null) {
                val tempGuid = pendingMessage.localId
                val attachments = attachmentDao.getAttachmentsForMessage(tempGuid)
                val pendingAttachments = pendingAttachmentDao.getForMessage(pendingMessageId)

                pendingAttachments.forEach { pendingAtt ->
                    val matchingAtt = attachments.find { it.guid == pendingAtt.localId }
                    if (matchingAtt != null && matchingAtt.errorType != null) {
                        pendingAttachmentDao.updateError(
                            pendingAtt.id,
                            matchingAtt.errorType,
                            matchingAtt.errorMessage
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync attachment errors")
        }

        return if (runAttemptCount < MAX_RETRY_COUNT) {
            // Retry with backoff - mark as PENDING to allow retry
            Timber.w("Send failed, will retry (attempt ${runAttemptCount + 1}/$MAX_RETRY_COUNT): $errorMessage")
            pendingMessageDao.updateStatusWithError(
                pendingMessageId,
                PendingSyncStatus.PENDING.name,
                errorMessage,
                System.currentTimeMillis()
            )
            Result.retry()
        } else {
            // Max retries exceeded - mark as FAILED
            Timber.e("Send failed after $MAX_RETRY_COUNT attempts: $errorMessage")
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
