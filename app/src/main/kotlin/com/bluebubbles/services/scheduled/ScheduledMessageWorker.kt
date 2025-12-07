package com.bluebubbles.services.scheduled

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bluebubbles.data.local.db.dao.ScheduledMessageDao
import com.bluebubbles.data.local.db.entity.ScheduledMessageStatus
import com.bluebubbles.data.repository.MessageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that sends scheduled messages at the specified time.
 *
 * Note: Messages are sent from the client device, so:
 * - Phone must be on and have network connectivity
 * - Messages may be slightly delayed if phone is in Doze mode
 */
@HiltWorker
class ScheduledMessageWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val scheduledMessageDao: ScheduledMessageDao,
    private val messageRepository: MessageRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ScheduledMsgWorker"
        const val KEY_SCHEDULED_MESSAGE_ID = "scheduled_message_id"
        private const val MAX_RETRY_COUNT = 3
    }

    override suspend fun doWork(): Result {
        val scheduledMessageId = inputData.getLong(KEY_SCHEDULED_MESSAGE_ID, -1)

        if (scheduledMessageId == -1L) {
            Log.e(TAG, "Scheduled message ID is missing")
            return Result.failure()
        }

        val scheduledMessage = scheduledMessageDao.getById(scheduledMessageId)
        if (scheduledMessage == null) {
            Log.e(TAG, "Scheduled message not found: $scheduledMessageId")
            return Result.failure()
        }

        // Check if already sent or cancelled
        if (scheduledMessage.status != ScheduledMessageStatus.PENDING) {
            Log.d(TAG, "Scheduled message already processed: ${scheduledMessage.status}")
            return Result.success()
        }

        Log.d(TAG, "Sending scheduled message $scheduledMessageId (attempt ${runAttemptCount + 1})")

        // Mark as sending
        scheduledMessageDao.updateStatus(scheduledMessageId, ScheduledMessageStatus.SENDING)

        return try {
            // Parse attachment URIs if any
            val attachmentUris = scheduledMessage.attachmentUris?.let { urisJson ->
                // Simple JSON array parsing - format: ["uri1","uri2"]
                urisJson.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotEmpty() }
                    .map { android.net.Uri.parse(it) }
            } ?: emptyList()

            // Send the message
            val result = messageRepository.sendMessage(
                chatGuid = scheduledMessage.chatGuid,
                text = scheduledMessage.text ?: "",
                tempGuid = "scheduled-${scheduledMessage.id}-${System.currentTimeMillis()}"
            )

            if (result.isSuccess) {
                Log.i(TAG, "Scheduled message sent successfully: $scheduledMessageId")
                scheduledMessageDao.updateStatus(scheduledMessageId, ScheduledMessageStatus.SENT)
                Result.success()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                Log.w(TAG, "Failed to send scheduled message: $error")

                if (runAttemptCount < MAX_RETRY_COUNT) {
                    scheduledMessageDao.updateStatus(scheduledMessageId, ScheduledMessageStatus.PENDING)
                    Result.retry()
                } else {
                    scheduledMessageDao.updateStatusWithError(
                        scheduledMessageId,
                        ScheduledMessageStatus.FAILED,
                        error
                    )
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending scheduled message", e)

            if (runAttemptCount < MAX_RETRY_COUNT) {
                scheduledMessageDao.updateStatus(scheduledMessageId, ScheduledMessageStatus.PENDING)
                Result.retry()
            } else {
                scheduledMessageDao.updateStatusWithError(
                    scheduledMessageId,
                    ScheduledMessageStatus.FAILED,
                    e.message
                )
                Result.failure()
            }
        }
    }
}
