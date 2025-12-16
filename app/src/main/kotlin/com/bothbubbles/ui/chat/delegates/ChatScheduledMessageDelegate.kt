package com.bothbubbles.ui.chat.delegates

import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bothbubbles.data.local.db.entity.ScheduledMessageEntity
import com.bothbubbles.data.local.db.entity.ScheduledMessageStatus
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.data.repository.ScheduledMessageRepository
import com.bothbubbles.services.scheduled.ScheduledMessageWorker
import com.bothbubbles.ui.chat.state.ScheduledMessagesState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Delegate responsible for scheduled message management.
 * Handles:
 * - Scheduling messages for future delivery
 * - Observing scheduled messages for the current chat
 * - Canceling scheduled messages
 * - Updating scheduled message time
 *
 * Uses AssistedInject to receive runtime parameters at construction time,
 * eliminating the need for a separate initialize() call.
 */
class ChatScheduledMessageDelegate @AssistedInject constructor(
    private val scheduledMessageRepository: ScheduledMessageRepository,
    private val workManager: WorkManager,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatScheduledMessageDelegate
    }

    companion object {
        private const val TAG = "ChatScheduledMsgDelegate"
    }

    // ============================================================================
    // SCHEDULED MESSAGES STATE
    // ============================================================================
    private val _state = MutableStateFlow(ScheduledMessagesState())
    val state: StateFlow<ScheduledMessagesState> = _state.asStateFlow()

    init {
        observeScheduledMessages()
    }

    /**
     * Observe scheduled messages for the current chat.
     */
    private fun observeScheduledMessages() {
        scope.launch {
            scheduledMessageRepository.observeForChat(chatGuid).collect { messages ->
                _state.update {
                    it.copy(
                        scheduledMessages = messages,
                        pendingCount = messages.count { msg -> msg.status == ScheduledMessageStatus.PENDING }
                    )
                }
            }
        }
    }

    /**
     * Schedule a message to be sent at a later time.
     *
     * Note: This uses client-side scheduling with WorkManager.
     * The phone must be on and have network connectivity for the message to send.
     *
     * @param text The message text
     * @param attachments The attachments to send (optional)
     * @param sendAt The timestamp when the message should be sent
     */
    fun scheduleMessage(text: String, attachments: List<PendingAttachmentInput>, sendAt: Long) {
        scope.launch {
            Log.d(TAG, "Scheduling message for chat $chatGuid at $sendAt")

            // Convert attachments to JSON array string (extract URIs from PendingAttachmentInput)
            val attachmentUrisJson = if (attachments.isNotEmpty()) {
                attachments.joinToString(",", "[", "]") { "\"${it.uri}\"" }
            } else {
                null
            }

            // Create scheduled message entity
            val scheduledMessage = ScheduledMessageEntity(
                chatGuid = chatGuid,
                text = text.ifBlank { null },
                attachmentUris = attachmentUrisJson,
                scheduledAt = sendAt
            )

            // Insert into database
            val id = scheduledMessageRepository.insert(scheduledMessage)
            Log.d(TAG, "Scheduled message inserted with id $id")

            // Calculate delay
            val delay = sendAt - System.currentTimeMillis()

            // Schedule WorkManager job
            val workRequest = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
                .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(ScheduledMessageWorker.KEY_SCHEDULED_MESSAGE_ID to id)
                )
                .build()

            workManager.enqueue(workRequest)
            Log.d(TAG, "WorkManager job enqueued with id ${workRequest.id}")

            // Save the work request ID for potential cancellation
            scheduledMessageRepository.updateWorkRequestId(id, workRequest.id.toString())
        }
    }

    /**
     * Cancel a scheduled message.
     *
     * @param id The ID of the scheduled message to cancel
     */
    fun cancelScheduledMessage(id: Long) {
        scope.launch {
            Log.d(TAG, "Canceling scheduled message $id")

            val scheduledMessage = scheduledMessageRepository.getById(id)
            if (scheduledMessage == null) {
                Log.w(TAG, "Scheduled message $id not found")
                return@launch
            }

            // Cancel the WorkManager job if it exists
            scheduledMessage.workRequestId?.let { workRequestId ->
                try {
                    workManager.cancelWorkById(UUID.fromString(workRequestId))
                    Log.d(TAG, "WorkManager job $workRequestId canceled")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cancel WorkManager job: ${e.message}")
                }
            }

            // Update status to cancelled
            scheduledMessageRepository.updateStatus(id, ScheduledMessageStatus.CANCELLED)
            Log.d(TAG, "Scheduled message $id marked as cancelled")
        }
    }

    /**
     * Update the scheduled time for a message.
     *
     * @param id The ID of the scheduled message to update
     * @param newSendAt The new timestamp when the message should be sent
     */
    fun updateScheduledTime(id: Long, newSendAt: Long) {
        scope.launch {
            Log.d(TAG, "Updating scheduled time for message $id to $newSendAt")

            val scheduledMessage = scheduledMessageRepository.getById(id)
            if (scheduledMessage == null) {
                Log.w(TAG, "Scheduled message $id not found")
                return@launch
            }

            if (scheduledMessage.status != ScheduledMessageStatus.PENDING) {
                Log.w(TAG, "Cannot update non-pending scheduled message (status: ${scheduledMessage.status})")
                return@launch
            }

            // Cancel existing WorkManager job
            scheduledMessage.workRequestId?.let { workRequestId ->
                try {
                    workManager.cancelWorkById(UUID.fromString(workRequestId))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cancel existing WorkManager job: ${e.message}")
                }
            }

            // Create new WorkManager job with updated delay
            val delay = newSendAt - System.currentTimeMillis()
            val workRequest = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
                .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(ScheduledMessageWorker.KEY_SCHEDULED_MESSAGE_ID to id)
                )
                .build()

            workManager.enqueue(workRequest)

            // Update the entity
            scheduledMessageRepository.update(
                scheduledMessage.copy(
                    scheduledAt = newSendAt,
                    workRequestId = workRequest.id.toString()
                )
            )
            Log.d(TAG, "Scheduled message $id updated with new time $newSendAt")
        }
    }

    /**
     * Delete a scheduled message (removes from database).
     * Use this for messages that have been sent or failed.
     *
     * @param id The ID of the scheduled message to delete
     */
    fun deleteScheduledMessage(id: Long) {
        scope.launch {
            Log.d(TAG, "Deleting scheduled message $id")

            val scheduledMessage = scheduledMessageRepository.getById(id)
            if (scheduledMessage == null) {
                Log.w(TAG, "Scheduled message $id not found")
                return@launch
            }

            // Cancel WorkManager job if pending
            if (scheduledMessage.status == ScheduledMessageStatus.PENDING) {
                scheduledMessage.workRequestId?.let { workRequestId ->
                    try {
                        workManager.cancelWorkById(UUID.fromString(workRequestId))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to cancel WorkManager job: ${e.message}")
                    }
                }
            }

            // Delete from database
            scheduledMessageRepository.delete(id)
            Log.d(TAG, "Scheduled message $id deleted")
        }
    }

    /**
     * Retry a failed scheduled message.
     *
     * @param id The ID of the failed scheduled message to retry
     * @param newSendAt The timestamp when the message should be sent (defaults to now)
     */
    fun retryScheduledMessage(id: Long, newSendAt: Long = System.currentTimeMillis()) {
        scope.launch {
            Log.d(TAG, "Retrying scheduled message $id")

            val scheduledMessage = scheduledMessageRepository.getById(id)
            if (scheduledMessage == null) {
                Log.w(TAG, "Scheduled message $id not found")
                return@launch
            }

            if (scheduledMessage.status != ScheduledMessageStatus.FAILED) {
                Log.w(TAG, "Can only retry failed scheduled messages (status: ${scheduledMessage.status})")
                return@launch
            }

            // Reset status to pending and clear error
            scheduledMessageRepository.updateStatusWithError(id, ScheduledMessageStatus.PENDING, null)

            // Create new WorkManager job
            val delay = newSendAt - System.currentTimeMillis()
            val workRequest = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
                .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(ScheduledMessageWorker.KEY_SCHEDULED_MESSAGE_ID to id)
                )
                .build()

            workManager.enqueue(workRequest)

            // Update work request ID
            scheduledMessageRepository.updateWorkRequestId(id, workRequest.id.toString())
            Log.d(TAG, "Scheduled message $id retried with new work request ${workRequest.id}")
        }
    }

    /**
     * Clean up sent scheduled messages from the database.
     */
    fun cleanupSentMessages() {
        scope.launch {
            Log.d(TAG, "Cleaning up sent scheduled messages")
            scheduledMessageRepository.deleteSent()
        }
    }
}
