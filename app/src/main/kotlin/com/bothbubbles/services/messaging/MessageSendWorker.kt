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
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.DependencyState
import com.bothbubbles.data.repository.PendingMessageRepository
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.services.messaging.sender.MessageAlreadyInTransitException
import com.bothbubbles.services.notifications.Notifier
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
    private val chatDao: com.bothbubbles.data.local.db.dao.ChatDao,
    private val chatRepository: ChatRepository,
    private val messageSendingService: MessageSendingService,
    private val attachmentPersistenceManager: AttachmentPersistenceManager,
    private val settingsDataStore: SettingsDataStore,
    private val notifier: Notifier,
    private val pendingMessageRepository: PendingMessageRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "MessageSendWorker"
        const val KEY_PENDING_MESSAGE_ID = "pending_message_id"
        const val UNIQUE_WORK_PREFIX = "send_message_"

        /**
         * Time to wait for BlueBubbles server to confirm message state after successful send.
         * The server may report delivery failure (e.g., error 22 - not registered) via socket event.
         *
         * Set to 15 seconds - if the server hasn't confirmed or reported an error by then,
         * we assume success. For attachments, this timeout only applies AFTER streaming
         * completes successfully (streaming failures are handled immediately).
         */
        private const val SERVER_CONFIRMATION_TIMEOUT_MS = 15_000L // 15 seconds

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
        val pendingMessageId = inputData.getLong(KEY_PENDING_MESSAGE_ID, -1)

        if (pendingMessageId == -1L) {
            Timber.e("Pending message ID is missing")
            return Result.failure()
        }

        val pendingMessage = pendingMessageDao.getById(pendingMessageId)
        if (pendingMessage == null) {
            Timber.e("Pending message not found: $pendingMessageId")
            return Result.failure()
        }

        // Skip if already sent
        if (pendingMessage.syncStatus == PendingSyncStatus.SENT.name) {
            Timber.d("Message already sent: ${pendingMessage.localId}")
            return Result.success()
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // DEPENDENCY CHECK: Ensure previous messages in this chat have been sent.
        // This prevents out-of-order message delivery.
        // ═══════════════════════════════════════════════════════════════════════════
        val dependencyStatus = pendingMessageRepository.getDependencyStatus(pendingMessage.localId)
        if (dependencyStatus != null) {
            when (dependencyStatus.state) {
                DependencyState.SENT, DependencyState.NOT_FOUND -> {
                    // Dependency satisfied or no longer exists - proceed with sending
                    Timber.d("Dependency ${dependencyStatus.dependsOnLocalId} satisfied, proceeding")
                }
                DependencyState.FAILED -> {
                    // Dependency failed - cascade failure to this message
                    Timber.w("Dependency ${dependencyStatus.dependsOnLocalId} failed, cascade failing this message")
                    return handleDependencyFailure(
                        pendingMessageId,
                        pendingMessage.chatGuid,
                        dependencyStatus.errorMessage ?: "Previous message failed to send"
                    )
                }
                DependencyState.PENDING, DependencyState.SENDING -> {
                    // Dependency still in progress - retry later
                    Timber.d("Dependency ${dependencyStatus.dependsOnLocalId} still ${dependencyStatus.state}, retrying later")
                    return Result.retry()
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // DUPLICATE PREVENTION: Check if API already succeeded on a previous attempt.
        // If serverGuid is set, the API call succeeded but worker died during
        // confirmation wait. Skip the API call and go straight to confirmation.
        // ═══════════════════════════════════════════════════════════════════════════
        val existingServerGuid = pendingMessage.serverGuid
        if (existingServerGuid != null) {
            Timber.i("API already succeeded on previous attempt, going to confirmation for serverGuid=$existingServerGuid")

            val confirmationResult = waitForServerConfirmation(
                serverGuid = existingServerGuid,
                pendingMessageId = pendingMessageId,
                chatGuid = pendingMessage.chatGuid
            )

            return if (confirmationResult.isSuccess) {
                Timber.i("Confirmation succeeded for previously-sent message")
                pendingMessageDao.markAsSent(pendingMessageId, existingServerGuid)
                Result.success()
            } else {
                Timber.e("Confirmation failed for previously-sent message")
                handleServerError(pendingMessageId, pendingMessage.chatGuid, confirmationResult.exceptionOrNull())
            }
        }

        Timber.d("Sending message ${pendingMessage.localId} (attempt ${runAttemptCount + 1})")

        // Update status to SENDING
        pendingMessageDao.updateStatusWithTimestamp(
            pendingMessageId,
            PendingSyncStatus.SENDING.name,
            System.currentTimeMillis()
        )

        return try {
            // Get persisted attachments and convert to PendingAttachmentInput
            val attachments = pendingAttachmentDao.getForMessage(pendingMessageId)
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
                    Timber.w("Attachment file missing: ${attachment.persistedPath}")
                    null
                }
            }

            // Determine delivery mode
            // Re-evaluate if the message was queued with IMESSAGE mode but conditions may have changed.
            // Using AUTO for IMESSAGE-queued messages lets the unified routing logic check:
            // - Current server connection state
            // - Fallback tracker status
            // - Chat type (SMS/iMessage prefix)
            // This prevents stale routing decisions when server state changes between queue and send.
            val queuedMode = try {
                MessageDeliveryMode.valueOf(pendingMessage.deliveryMode)
            } catch (e: Exception) {
                MessageDeliveryMode.AUTO
            }
            val deliveryMode = when (queuedMode) {
                // LOCAL_SMS/LOCAL_MMS are explicit user choices - don't override
                MessageDeliveryMode.LOCAL_SMS, MessageDeliveryMode.LOCAL_MMS -> queuedMode
                // IMESSAGE and AUTO should be re-evaluated by unified routing logic
                MessageDeliveryMode.IMESSAGE, MessageDeliveryMode.AUTO -> MessageDeliveryMode.AUTO
            }
            Timber.tag("SendDebug").i("Worker: chatGuid=${pendingMessage.chatGuid}, queuedMode=$queuedMode, deliveryMode=$deliveryMode")

            // Send via MessageSendingService
            // Pass localId as tempGuid to ensure same ID is used across retries
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

            if (result.isSuccess) {
                val sentMessage = result.getOrThrow()
                Timber.i("Message accepted by server: ${pendingMessage.localId} -> ${sentMessage.guid}")

                // ═══════════════════════════════════════════════════════════════════════════
                // CRITICAL: Save serverGuid IMMEDIATELY after API success, BEFORE confirmation.
                // This ensures retries can detect "API already succeeded" even if worker dies
                // during the 2-minute confirmation wait.
                // ═══════════════════════════════════════════════════════════════════════════
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
                    pendingMessageDao.markAsSent(pendingMessageId, sentMessage.guid)

                    // Note: Attachment files are now relocated (not deleted) by IMessageSenderStrategy
                    // during the GUID replacement process. This preserves local previews and prevents
                    // the need to re-download already-uploaded files.
                    Timber.d("Attachments relocated to permanent storage by IMessageSenderStrategy")

                    Result.success()
                } else {
                    // Server reported failure during confirmation wait
                    Timber.e("Server reported failure during confirmation: ${confirmationResult.exceptionOrNull()?.message}")
                    handleServerError(pendingMessageId, pendingMessage.chatGuid, confirmationResult.exceptionOrNull())
                }
            } else {
                Timber.e("sendUnified failed: ${result.exceptionOrNull()?.message}")
                handleFailure(pendingMessageId, pendingMessage.chatGuid, result.exceptionOrNull())
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during send: ${e.message}")
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

        while (System.currentTimeMillis() - startTime < SERVER_CONFIRMATION_TIMEOUT_MS) {
            // Check if worker has been cancelled or stopped
            if (isStopped) {
                Timber.w("Worker stopped during confirmation wait")
                return kotlin.Result.failure(Exception("Worker was stopped"))
            }

            val message = messageDao.getMessageByGuid(serverGuid)

            if (message != null) {
                // Check if server reported an error
                if (message.error != 0) {
                    val errorMessage = message.smsErrorMessage
                        ?: MessageErrorCode.getUserMessage(message.error)
                    Timber.e("Server reported error ${message.error}: $errorMessage")
                    return kotlin.Result.failure(
                        Exception("Delivery failed (error ${message.error}): $errorMessage")
                    )
                }

                // Check if message was delivered (has dateDelivered)
                if (message.dateDelivered != null && message.dateDelivered!! > 0) {
                    Timber.d("Message delivery confirmed by server")
                    return kotlin.Result.success(Unit)
                }
            }

            delay(CONFIRMATION_POLL_INTERVAL_MS)
        }

        // Timeout - no error reported, assume success (server accepted it)
        Timber.d("Confirmation timeout reached with no error - assuming success")
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
            Timber.i("Message already in transit, waiting for confirmation...")
            val guidToCheck = error.serverGuid ?: error.tempGuid

            val confirmationResult = waitForServerConfirmation(
                serverGuid = guidToCheck,
                pendingMessageId = pendingMessageId,
                chatGuid = chatGuid
            )

            return if (confirmationResult.isSuccess) {
                Timber.i("In-transit message confirmed delivered")
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
        Timber.e("Send failed (no retry): $errorMessage")
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

        Timber.e("Server error (no retry): code=$errorCode, message=$errorMessage")

        // Sync attachment errors
        syncAttachmentErrors(pendingMessageId)

        return markFailedAndTriggerSmsFallback(pendingMessageId, chatGuid, errorMessage, errorCode)
    }

    /**
     * Mark message as FAILED.
     * Users can manually retry as SMS using the "Retry as SMS" button.
     *
     * Also cascades failure to any dependent messages (messages queued after this one
     * in the same chat) and shows a grouped notification if multiple messages failed.
     */
    private suspend fun markFailedAndTriggerSmsFallback(
        pendingMessageId: Long,
        chatGuid: String,
        errorMessage: String,
        errorCode: Int
    ): Result {
        // Get the pending message to find the localId (temp GUID) and message text
        val pendingMessage = pendingMessageDao.getById(pendingMessageId)
        val localId = pendingMessage?.localId
        val messagePreview = pendingMessage?.text

        // Mark PendingMessageEntity as FAILED
        pendingMessageDao.updateStatusWithError(
            pendingMessageId,
            PendingSyncStatus.FAILED.name,
            errorMessage,
            System.currentTimeMillis()
        )

        // CRITICAL: Also update the MessageEntity error field so UI shows "failed" not "sending"
        if (localId != null) {
            messageDao.updateMessageError(localId, errorCode, errorMessage)
            Timber.d("Updated MessageEntity error for $localId: code=$errorCode")
        }

        // Cascade failure to dependent messages and show appropriate notification
        try {
            val chat = chatDao.getChatByGuid(chatGuid)
            val chatTitle = if (chat != null) {
                val participants = chatRepository.getParticipantsForChat(chatGuid)
                chatRepository.resolveChatTitle(chat, participants)
            } else {
                "Unknown"
            }

            // Cascade failure to all dependent messages
            val cascadedMessages = if (localId != null) {
                pendingMessageRepository.cascadeFailureToDependents(localId, errorMessage)
            } else {
                emptyList()
            }

            if (cascadedMessages.isNotEmpty()) {
                // Multiple messages failed - show grouped notification
                val totalFailed = 1 + cascadedMessages.size
                notifier.showMessagesFailedNotification(
                    chatGuid = chatGuid,
                    chatTitle = chatTitle,
                    failedCount = totalFailed,
                    errorMessage = errorMessage
                )
            } else {
                // Single message failed - show individual notification
                notifier.showMessageFailedNotification(
                    chatGuid = chatGuid,
                    chatTitle = chatTitle,
                    messagePreview = messagePreview,
                    errorMessage = errorMessage
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to show failure notification")
        }

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

    /**
     * Handle failure when a dependency message failed.
     * This cascades the failure to this message without attempting to send.
     */
    private suspend fun handleDependencyFailure(
        pendingMessageId: Long,
        chatGuid: String,
        dependencyErrorMessage: String
    ): Result {
        val pendingMessage = pendingMessageDao.getById(pendingMessageId)
        val localId = pendingMessage?.localId
        val errorMessage = "Blocked by failed message: $dependencyErrorMessage"

        // Mark as FAILED
        pendingMessageDao.updateStatusWithError(
            pendingMessageId,
            PendingSyncStatus.FAILED.name,
            errorMessage,
            System.currentTimeMillis()
        )

        // Update MessageEntity so UI shows as failed
        if (localId != null) {
            messageDao.updateMessageError(localId, 1, "Previous message failed")
        }

        // Note: We don't show individual notifications for cascade failures.
        // The grouped notification is shown by the original failure handler.
        // This prevents notification spam for cascaded failures.

        return Result.failure()
    }
}
