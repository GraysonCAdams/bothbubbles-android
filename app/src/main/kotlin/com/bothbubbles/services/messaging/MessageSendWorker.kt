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
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.PendingAttachmentDao
import com.bothbubbles.data.local.db.dao.PendingMessageDao
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.services.messaging.sender.MessageAlreadyInTransitException
import com.bothbubbles.util.error.MessageErrorCode
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    private val messageDao: MessageDao,
    private val messageSendingService: MessageSendingService,
    private val attachmentPersistenceManager: AttachmentPersistenceManager,
    private val settingsDataStore: SettingsDataStore
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "MessageSendWorker"
        const val KEY_PENDING_MESSAGE_ID = "pending_message_id"
        const val UNIQUE_WORK_PREFIX = "send_message_"

        /**
         * Time to wait for BlueBubbles server to confirm message state after successful send.
         * The server may report delivery failure (e.g., error 22 - not registered) via socket event.
         */
        private const val SERVER_CONFIRMATION_TIMEOUT_MS = 2 * 60 * 1000L // 2 minutes

        /** Poll interval when waiting for server confirmation */
        private const val CONFIRMATION_POLL_INTERVAL_MS = 2000L // 2 seconds

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

        // ═══════════════════════════════════════════════════════════════════════════
        // DUPLICATE PREVENTION: Check if API already succeeded on a previous attempt.
        // If serverGuid is set, the API call succeeded but worker died during
        // confirmation wait. Skip the API call and go straight to confirmation.
        // ═══════════════════════════════════════════════════════════════════════════
        val existingServerGuid = pendingMessage.serverGuid
        if (existingServerGuid != null) {
            Timber.w("[SEND_TRACE] ⚠️ API already succeeded on previous attempt!")
            Timber.w("[SEND_TRACE] ⚠️ serverGuid=$existingServerGuid, skipping API call")
            Timber.w("[SEND_TRACE] ⚠️ Going straight to confirmation wait...")

            val confirmationResult = waitForServerConfirmation(
                serverGuid = existingServerGuid,
                pendingMessageId = pendingMessageId,
                chatGuid = pendingMessage.chatGuid
            )

            return if (confirmationResult.isSuccess) {
                Timber.i("[SEND_TRACE] ✓ Confirmation succeeded for previously-sent message")
                pendingMessageDao.markAsSent(pendingMessageId, existingServerGuid)
                Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
                Timber.i("[SEND_TRACE] MessageSendWorker COMPLETE (resumed): ${System.currentTimeMillis() - workerStartTime}ms")
                Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
                Result.success()
            } else {
                Timber.e("[SEND_TRACE] ✗ Confirmation failed for previously-sent message")
                handleServerError(pendingMessageId, pendingMessage.chatGuid, confirmationResult.exceptionOrNull())
            }
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
                tempGuid = pendingMessage.localId,
                attributedBodyJson = pendingMessage.attributedBodyJson
            )
            Timber.i("[SEND_TRACE] sendUnified RETURNED after ${System.currentTimeMillis() - sendStart}ms +${System.currentTimeMillis() - workerStartTime}ms total")

            if (result.isSuccess) {
                val sentMessage = result.getOrThrow()
                Timber.i("[SEND_TRACE] ✓ Message accepted by server: ${pendingMessage.localId} -> ${sentMessage.guid}")
                Timber.i("[SEND_TRACE] Server GUID: ${sentMessage.guid}")

                // ═══════════════════════════════════════════════════════════════════════════
                // CRITICAL: Save serverGuid IMMEDIATELY after API success, BEFORE confirmation.
                // This ensures retries can detect "API already succeeded" even if worker dies
                // during the 2-minute confirmation wait.
                // ═══════════════════════════════════════════════════════════════════════════
                Timber.i("[SEND_TRACE] 💾 Persisting serverGuid to prevent duplicate sends on retry")
                pendingMessageDao.updateServerGuid(pendingMessageId, sentMessage.guid)

                // Wait for server to confirm delivery (or report failure via socket event)
                // BlueBubbles may report delivery failure (e.g., error 22) asynchronously
                val confirmationResult = waitForServerConfirmation(
                    serverGuid = sentMessage.guid,
                    pendingMessageId = pendingMessageId,
                    chatGuid = pendingMessage.chatGuid
                )

                if (confirmationResult.isSuccess) {
                    // Mark as sent with server GUID
                    Timber.i("[SEND_TRACE] Marking as sent in DB +${System.currentTimeMillis() - workerStartTime}ms")
                    pendingMessageDao.markAsSent(pendingMessageId, sentMessage.guid)

                    // Note: Attachment files are now relocated (not deleted) by IMessageSenderStrategy
                    // during the GUID replacement process. This preserves local previews and prevents
                    // the need to re-download already-uploaded files.
                    Timber.i("[SEND_TRACE] Attachments relocated to permanent storage by IMessageSenderStrategy")

                    Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
                    Timber.i("[SEND_TRACE] MessageSendWorker COMPLETE: ${System.currentTimeMillis() - workerStartTime}ms total")
                    Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
                    Result.success()
                } else {
                    // Server reported failure during confirmation wait
                    Timber.e("[SEND_TRACE] ✗ Server reported failure during confirmation: ${confirmationResult.exceptionOrNull()?.message}")
                    handleServerError(pendingMessageId, pendingMessage.chatGuid, confirmationResult.exceptionOrNull())
                }
            } else {
                Timber.e("[SEND_TRACE] ✗ sendUnified FAILED: ${result.exceptionOrNull()?.message}")
                handleFailure(pendingMessageId, pendingMessage.chatGuid, result.exceptionOrNull())
            }
        } catch (e: Exception) {
            Timber.e(e, "[SEND_TRACE] ✗ Exception during send: ${e.message}")
            handleFailure(pendingMessageId, pendingMessage.chatGuid, e)
        }
    }

    /**
     * Wait for BlueBubbles server to confirm message delivery state.
     *
     * After server accepts a message, it may still fail during actual iMessage delivery
     * (e.g., error 22 - recipient not registered). These failures arrive via socket event
     * and update the message's error field in the database.
     *
     * @return kotlin.Result.success if message delivered or timeout with no error, kotlin.Result.failure if error detected
     */
    private suspend fun waitForServerConfirmation(
        serverGuid: String,
        pendingMessageId: Long,
        chatGuid: String
    ): kotlin.Result<Unit> {
        val startTime = System.currentTimeMillis()
        Timber.i("[SEND_TRACE] Waiting up to ${SERVER_CONFIRMATION_TIMEOUT_MS / 1000}s for server confirmation...")

        while (System.currentTimeMillis() - startTime < SERVER_CONFIRMATION_TIMEOUT_MS) {
            val message = messageDao.getMessageByGuid(serverGuid)

            if (message != null) {
                // Check if server reported an error
                if (message.error != 0) {
                    val errorMessage = message.smsErrorMessage
                        ?: MessageErrorCode.getUserMessage(message.error)
                    Timber.e("[SEND_TRACE] Server reported error ${message.error}: $errorMessage")
                    return kotlin.Result.failure(
                        Exception("Delivery failed (error ${message.error}): $errorMessage")
                    )
                }

                // Check if message was delivered (has dateDelivered)
                if (message.dateDelivered != null && message.dateDelivered!! > 0) {
                    Timber.i("[SEND_TRACE] ✓ Message delivery confirmed by server")
                    return kotlin.Result.success(Unit)
                }
            }

            delay(CONFIRMATION_POLL_INTERVAL_MS)
        }

        // Timeout - no error reported, assume success (server accepted it)
        Timber.i("[SEND_TRACE] Confirmation timeout reached with no error - assuming success")
        return kotlin.Result.success(Unit)
    }

    /**
     * Handle failure when message send fails.
     *
     * No automatic retries - marks as FAILED immediately.
     * Users can manually retry using the Retry button.
     *
     * Special case: If server says message is already in transit (from a previous attempt),
     * wait for confirmation instead of failing immediately.
     */
    private suspend fun handleFailure(
        pendingMessageId: Long,
        chatGuid: String,
        error: Throwable?
    ): Result {
        // Special case: Message already in transit - wait for confirmation
        if (error is MessageAlreadyInTransitException) {
            Timber.i("[SEND_TRACE] Message already in transit, waiting for confirmation...")
            val guidToCheck = error.serverGuid ?: error.tempGuid

            val confirmationResult = waitForServerConfirmation(
                serverGuid = guidToCheck,
                pendingMessageId = pendingMessageId,
                chatGuid = chatGuid
            )

            return if (confirmationResult.isSuccess) {
                Timber.i("[SEND_TRACE] ✓ In-transit message confirmed delivered")
                pendingMessageDao.markAsSent(pendingMessageId, guidToCheck)
                Result.success()
            } else {
                handleServerError(pendingMessageId, chatGuid, confirmationResult.exceptionOrNull())
            }
        }

        val errorMessage = error?.message ?: "Unknown error"
        val errorCode = MessageErrorCode.fromException(error ?: Exception(errorMessage))

        // Sync attachment errors
        syncAttachmentErrors(pendingMessageId)

        // No automatic retries - fail immediately
        Timber.e("[SEND_TRACE] Send failed (no retry): $errorMessage")
        return markFailedAndTriggerSmsFallback(pendingMessageId, chatGuid, errorMessage, errorCode)
    }

    /**
     * Handle failure reported by server during confirmation wait.
     * Server errors are never retried - mark as FAILED and optionally trigger SMS fallback.
     */
    private suspend fun handleServerError(
        pendingMessageId: Long,
        chatGuid: String,
        error: Throwable?
    ): Result {
        val errorMessage = error?.message ?: "Server reported delivery failure"

        // Try to extract error code from message
        val errorCode = MessageErrorCode.parseFromMessage(errorMessage)
            ?: MessageErrorCode.GENERIC_ERROR

        Timber.e("[SEND_TRACE] Server error (no retry): code=$errorCode, message=$errorMessage")

        // Sync attachment errors
        syncAttachmentErrors(pendingMessageId)

        return markFailedAndTriggerSmsFallback(pendingMessageId, chatGuid, errorMessage, errorCode)
    }

    /**
     * Mark message as FAILED.
     * Users can manually retry as SMS using the "Retry as SMS" button.
     */
    private suspend fun markFailedAndTriggerSmsFallback(
        pendingMessageId: Long,
        chatGuid: String,
        errorMessage: String,
        errorCode: Int
    ): Result {
        // Mark as FAILED
        pendingMessageDao.updateStatusWithError(
            pendingMessageId,
            PendingSyncStatus.FAILED.name,
            errorMessage,
            System.currentTimeMillis()
        )

        return Result.failure()
    }

    /**
     * Sync attachment errors from AttachmentEntity to PendingAttachmentEntity.
     */
    private suspend fun syncAttachmentErrors(pendingMessageId: Long) {
        try {
            val pendingMessage = pendingMessageDao.getById(pendingMessageId) ?: return
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
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync attachment errors")
        }
    }
}
