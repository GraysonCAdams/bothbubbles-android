package com.bothbubbles.services.messaging

import timber.log.Timber
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

            val currentStatus = message.syncStatus
            when (currentStatus) {
                PendingSyncStatus.PENDING.name, PendingSyncStatus.FAILED.name -> {
                    // Valid transition
                    pendingMessageDao.updateStatusWithTimestamp(messageId, PendingSyncStatus.SENDING.name, System.currentTimeMillis())
                    Timber.d("Message $messageId: $currentStatus -> SENDING")
                    TransitionResult.Success(PendingSyncStatus.SENDING)
                }
                PendingSyncStatus.SENDING.name -> {
                    // Already sending - could be a retry, allow it
                    Timber.w("Message $messageId already SENDING, allowing re-entry")
                    TransitionResult.Success(PendingSyncStatus.SENDING)
                }
                PendingSyncStatus.SENT.name -> {
                    // Cannot send an already-sent message
                    TransitionResult.InvalidTransition(
                        currentState = PendingSyncStatus.valueOf(currentStatus),
                        requestedState = PendingSyncStatus.SENDING,
                        reason = "Message already sent successfully"
                    )
                }
                else -> {
                    TransitionResult.InvalidTransition(
                        currentState = PendingSyncStatus.PENDING, // fallback
                        requestedState = PendingSyncStatus.SENDING,
                        reason = "Unknown status: $currentStatus"
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error transitioning message $messageId to SENDING")
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

            val currentStatus = message.syncStatus
            when (currentStatus) {
                PendingSyncStatus.SENDING.name -> {
                    // Valid transition
                    pendingMessageDao.markAsSent(messageId, serverGuid)
                    Timber.d("Message $messageId: SENDING -> SENT (server: $serverGuid)")
                    TransitionResult.Success(PendingSyncStatus.SENT)
                }
                PendingSyncStatus.SENT.name -> {
                    // Already sent - idempotent, just return success
                    Timber.w("Message $messageId already SENT")
                    TransitionResult.Success(PendingSyncStatus.SENT)
                }
                else -> {
                    TransitionResult.InvalidTransition(
                        currentState = PendingSyncStatus.valueOf(currentStatus),
                        requestedState = PendingSyncStatus.SENT,
                        reason = "Can only mark SENDING messages as SENT"
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error transitioning message $messageId to SENT")
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

            val currentStatus = message.syncStatus
            when (currentStatus) {
                PendingSyncStatus.SENDING.name -> {
                    // Valid transition
                    pendingMessageDao.updateStatusWithError(
                        messageId,
                        PendingSyncStatus.FAILED.name,
                        errorMessage,
                        System.currentTimeMillis()
                    )
                    Timber.d("Message $messageId: SENDING -> FAILED ($errorMessage)")
                    TransitionResult.Success(PendingSyncStatus.FAILED)
                }
                PendingSyncStatus.FAILED.name -> {
                    // Already failed - update error message
                    pendingMessageDao.updateStatusWithError(
                        messageId,
                        PendingSyncStatus.FAILED.name,
                        errorMessage,
                        System.currentTimeMillis()
                    )
                    Timber.w("Message $messageId already FAILED, updating error")
                    TransitionResult.Success(PendingSyncStatus.FAILED)
                }
                else -> {
                    TransitionResult.InvalidTransition(
                        currentState = PendingSyncStatus.valueOf(currentStatus),
                        requestedState = PendingSyncStatus.FAILED,
                        reason = "Can only mark SENDING messages as FAILED"
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error transitioning message $messageId to FAILED")
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

            val currentStatus = message.syncStatus
            when (currentStatus) {
                PendingSyncStatus.FAILED.name -> {
                    // Valid transition - reset to PENDING for re-queue
                    pendingMessageDao.updateStatus(messageId, PendingSyncStatus.PENDING.name)
                    Timber.d("Message $messageId: FAILED -> PENDING (retry)")
                    TransitionResult.Success(PendingSyncStatus.PENDING)
                }
                PendingSyncStatus.PENDING.name -> {
                    // Already pending - idempotent
                    Timber.w("Message $messageId already PENDING")
                    TransitionResult.Success(PendingSyncStatus.PENDING)
                }
                PendingSyncStatus.SENDING.name -> {
                    // Cannot retry while sending
                    TransitionResult.InvalidTransition(
                        currentState = PendingSyncStatus.SENDING,
                        requestedState = PendingSyncStatus.PENDING,
                        reason = "Cannot retry while message is being sent"
                    )
                }
                PendingSyncStatus.SENT.name -> {
                    // Cannot retry sent messages
                    TransitionResult.InvalidTransition(
                        currentState = PendingSyncStatus.SENT,
                        requestedState = PendingSyncStatus.PENDING,
                        reason = "Cannot retry already-sent message"
                    )
                }
                else -> {
                    TransitionResult.InvalidTransition(
                        currentState = PendingSyncStatus.PENDING, // fallback
                        requestedState = PendingSyncStatus.PENDING,
                        reason = "Unknown status: $currentStatus"
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error retrying message $messageId")
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
            val stuckMessages = pendingMessageDao.getStaleSending(cutoffTime)

            if (stuckMessages.isEmpty()) {
                return 0
            }

            Timber.w("Found ${stuckMessages.size} stuck SENDING messages, resetting to PENDING")
            for (message in stuckMessages) {
                pendingMessageDao.updateStatus(message.id, PendingSyncStatus.PENDING.name)
                Timber.d("Reset stuck message ${message.id} (localId: ${message.localId})")
            }

            stuckMessages.size
        } catch (e: Exception) {
            Timber.e(e, "Error resetting stuck messages")
            0
        }
    }
}
