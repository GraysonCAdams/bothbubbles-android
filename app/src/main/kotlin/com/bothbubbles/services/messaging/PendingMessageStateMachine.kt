package com.bothbubbles.services.messaging

import android.util.Log
import com.bothbubbles.data.local.db.dao.PendingMessageDao
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State machine for managing pending message lifecycle transitions.
 *
 * Valid state transitions:
 * ```
 *                 ┌──────────────────────────────────────┐
 *                 │                                      │
 *                 ▼                                      │
 *     ┌─────────────────┐                               │
 *     │    PENDING      │◄──────────────────────────────┤
 *     │  (queued)       │                               │
 *     └────────┬────────┘                               │
 *              │ startSending()                         │
 *              ▼                                        │
 *     ┌─────────────────┐                               │
 *     │    SENDING      │───────────────────────────────┤
 *     │  (in-flight)    │         retry()               │
 *     └────────┬────────┘                               │
 *              │                                        │
 *     ┌────────┴────────┐                               │
 *     │                 │                               │
 *     ▼                 ▼                               │
 * ┌────────┐       ┌────────┐                          │
 * │  SENT  │       │ FAILED │──────────────────────────┘
 * │(done)  │       │(error) │
 * └────────┘       └────────┘
 * ```
 *
 * This centralizes state transition logic that was previously spread across
 * MessageSendWorker, PendingMessageRepository, and PendingMessageDao.
 */
@Singleton
class PendingMessageStateMachine @Inject constructor(
    private val pendingMessageDao: PendingMessageDao
) {
    companion object {
        private const val TAG = "PendingMessageStateMachine"
    }

    /**
     * Result of a state transition attempt.
     */
    sealed class TransitionResult {
        /** Transition succeeded */
        data class Success(val newState: PendingSyncStatus) : TransitionResult()

        /** Message not found in database */
        object NotFound : TransitionResult()

        /** Invalid state transition (e.g., SENT -> SENDING) */
        data class InvalidTransition(
            val currentState: PendingSyncStatus,
            val requestedState: PendingSyncStatus,
            val reason: String
        ) : TransitionResult()

        /** Database error during transition */
        data class Error(val exception: Exception) : TransitionResult()
    }

    /**
     * Transition message from PENDING to SENDING.
     * Valid from: PENDING, FAILED (retry case)
     *
     * @param messageId The pending message's database ID
     * @return TransitionResult indicating success or failure reason
     */
    suspend fun startSending(messageId: Long): TransitionResult {
        return try {
            val message = pendingMessageDao.getById(messageId)
                ?: return TransitionResult.NotFound

            when (message.syncStatus) {
                PendingSyncStatus.PENDING, PendingSyncStatus.FAILED -> {
                    // Valid transition
                    pendingMessageDao.updateStatus(messageId, PendingSyncStatus.SENDING)
                    pendingMessageDao.updateLastAttemptAt(messageId, System.currentTimeMillis())
                    Log.d(TAG, "Message $messageId: ${message.syncStatus} -> SENDING")
                    TransitionResult.Success(PendingSyncStatus.SENDING)
                }
                PendingSyncStatus.SENDING -> {
                    // Already sending - could be a retry, allow it
                    Log.w(TAG, "Message $messageId already SENDING, allowing re-entry")
                    TransitionResult.Success(PendingSyncStatus.SENDING)
                }
                PendingSyncStatus.SENT -> {
                    // Cannot send an already-sent message
                    TransitionResult.InvalidTransition(
                        currentState = message.syncStatus,
                        requestedState = PendingSyncStatus.SENDING,
                        reason = "Message already sent successfully"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error transitioning message $messageId to SENDING", e)
            TransitionResult.Error(e)
        }
    }

    /**
     * Transition message from SENDING to SENT.
     * Valid from: SENDING only
     *
     * @param messageId The pending message's database ID
     * @param serverGuid The GUID returned by the server
     * @return TransitionResult indicating success or failure reason
     */
    suspend fun markSent(messageId: Long, serverGuid: String): TransitionResult {
        return try {
            val message = pendingMessageDao.getById(messageId)
                ?: return TransitionResult.NotFound

            when (message.syncStatus) {
                PendingSyncStatus.SENDING -> {
                    // Valid transition
                    pendingMessageDao.updateStatusAndServerGuid(
                        messageId,
                        PendingSyncStatus.SENT,
                        serverGuid
                    )
                    Log.d(TAG, "Message $messageId: SENDING -> SENT (server: $serverGuid)")
                    TransitionResult.Success(PendingSyncStatus.SENT)
                }
                PendingSyncStatus.SENT -> {
                    // Already sent - idempotent, just return success
                    Log.w(TAG, "Message $messageId already SENT")
                    TransitionResult.Success(PendingSyncStatus.SENT)
                }
                else -> {
                    TransitionResult.InvalidTransition(
                        currentState = message.syncStatus,
                        requestedState = PendingSyncStatus.SENT,
                        reason = "Can only mark SENDING messages as SENT"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error transitioning message $messageId to SENT", e)
            TransitionResult.Error(e)
        }
    }

    /**
     * Transition message from SENDING to FAILED.
     * Valid from: SENDING only
     *
     * @param messageId The pending message's database ID
     * @param errorMessage Human-readable error description
     * @return TransitionResult indicating success or failure reason
     */
    suspend fun markFailed(messageId: Long, errorMessage: String): TransitionResult {
        return try {
            val message = pendingMessageDao.getById(messageId)
                ?: return TransitionResult.NotFound

            when (message.syncStatus) {
                PendingSyncStatus.SENDING -> {
                    // Valid transition
                    pendingMessageDao.updateStatusWithError(
                        messageId,
                        PendingSyncStatus.FAILED,
                        errorMessage,
                        message.retryCount + 1
                    )
                    Log.d(TAG, "Message $messageId: SENDING -> FAILED ($errorMessage)")
                    TransitionResult.Success(PendingSyncStatus.FAILED)
                }
                PendingSyncStatus.FAILED -> {
                    // Already failed - update error message
                    pendingMessageDao.updateStatusWithError(
                        messageId,
                        PendingSyncStatus.FAILED,
                        errorMessage,
                        message.retryCount
                    )
                    Log.w(TAG, "Message $messageId already FAILED, updating error")
                    TransitionResult.Success(PendingSyncStatus.FAILED)
                }
                else -> {
                    TransitionResult.InvalidTransition(
                        currentState = message.syncStatus,
                        requestedState = PendingSyncStatus.FAILED,
                        reason = "Can only mark SENDING messages as FAILED"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error transitioning message $messageId to FAILED", e)
            TransitionResult.Error(e)
        }
    }

    /**
     * Transition message from FAILED back to PENDING for retry.
     * Valid from: FAILED only
     *
     * @param messageId The pending message's database ID
     * @return TransitionResult indicating success or failure reason
     */
    suspend fun retry(messageId: Long): TransitionResult {
        return try {
            val message = pendingMessageDao.getById(messageId)
                ?: return TransitionResult.NotFound

            when (message.syncStatus) {
                PendingSyncStatus.FAILED -> {
                    // Valid transition - reset to PENDING for re-queue
                    pendingMessageDao.updateStatus(messageId, PendingSyncStatus.PENDING)
                    Log.d(TAG, "Message $messageId: FAILED -> PENDING (retry)")
                    TransitionResult.Success(PendingSyncStatus.PENDING)
                }
                PendingSyncStatus.PENDING -> {
                    // Already pending - idempotent
                    Log.w(TAG, "Message $messageId already PENDING")
                    TransitionResult.Success(PendingSyncStatus.PENDING)
                }
                PendingSyncStatus.SENDING -> {
                    // Cannot retry while sending
                    TransitionResult.InvalidTransition(
                        currentState = message.syncStatus,
                        requestedState = PendingSyncStatus.PENDING,
                        reason = "Cannot retry while message is being sent"
                    )
                }
                PendingSyncStatus.SENT -> {
                    // Cannot retry sent messages
                    TransitionResult.InvalidTransition(
                        currentState = message.syncStatus,
                        requestedState = PendingSyncStatus.PENDING,
                        reason = "Cannot retry already-sent message"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrying message $messageId", e)
            TransitionResult.Error(e)
        }
    }

    /**
     * Reset stuck SENDING messages back to PENDING.
     * Called on app startup to recover from crashes during send.
     *
     * @param stuckThresholdMs Consider SENDING messages stuck if older than this (default 2 minutes)
     * @return Number of messages reset
     */
    suspend fun resetStuckMessages(stuckThresholdMs: Long = 120_000L): Int {
        return try {
            val cutoffTime = System.currentTimeMillis() - stuckThresholdMs
            val stuckMessages = pendingMessageDao.getStuckSendingMessages(cutoffTime)

            if (stuckMessages.isEmpty()) {
                return 0
            }

            Log.w(TAG, "Found ${stuckMessages.size} stuck SENDING messages, resetting to PENDING")
            for (message in stuckMessages) {
                pendingMessageDao.updateStatus(message.id, PendingSyncStatus.PENDING)
                Log.d(TAG, "Reset stuck message ${message.id} (localId: ${message.localId})")
            }

            stuckMessages.size
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting stuck messages", e)
            0
        }
    }
}
