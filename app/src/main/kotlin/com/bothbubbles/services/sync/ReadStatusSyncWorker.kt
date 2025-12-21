package com.bothbubbles.services.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.data.local.db.dao.PendingReadStatusDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.model.entity.ReadSyncStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * WorkManager worker that syncs pending read status changes to the BlueBubbles server.
 *
 * Features:
 * - Batch processing: Syncs all pending read status changes in one execution
 * - Network constraint: Only runs when connected
 * - Exponential backoff retry: Handled by WorkManager
 * - Deduplication: Database unique constraint ensures only latest status per chat
 *
 * When a chat is marked as read locally, the change is queued in pending_read_status table.
 * This worker processes all pending entries, ensuring reliable delivery even when:
 * - Network is temporarily unavailable
 * - App is killed or device reboots
 * - Server is temporarily down
 */
@HiltWorker
class ReadStatusSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val api: BothBubblesApi,
    private val pendingReadStatusDao: PendingReadStatusDao,
    private val settingsDataStore: SettingsDataStore
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ReadStatusSyncWorker"
        const val UNIQUE_WORK_NAME = "read_status_sync"

        /** Maximum retry attempts per entry before marking as permanently failed */
        private const val MAX_RETRY_COUNT = 5
    }

    override suspend fun doWork(): Result {
        // Check if server is configured and setup is complete
        val serverAddress = settingsDataStore.serverAddress.first()
        if (serverAddress.isBlank()) {
            Timber.tag(TAG).d("Server not configured, skipping read status sync")
            return Result.success()
        }

        val setupComplete = settingsDataStore.isSetupComplete.first()
        if (!setupComplete) {
            Timber.tag(TAG).d("Setup not complete, skipping read status sync")
            return Result.success()
        }

        // Get all pending read status syncs
        val pending = pendingReadStatusDao.getPendingAndFailed()
        if (pending.isEmpty()) {
            Timber.tag(TAG).d("No pending read status syncs")
            return Result.success()
        }

        Timber.tag(TAG).d("Processing ${pending.size} pending read status syncs")

        var successCount = 0
        var failCount = 0

        for (entry in pending) {
            // Skip entries that have exceeded max retries
            if (entry.retryCount >= MAX_RETRY_COUNT) {
                Timber.tag(TAG).w("Skipping chat ${entry.chatGuid} - exceeded max retries")
                pendingReadStatusDao.updateStatusWithError(
                    id = entry.id,
                    status = ReadSyncStatus.FAILED.name,
                    error = "Exceeded max retry count"
                )
                failCount++
                continue
            }

            // Mark as syncing
            pendingReadStatusDao.updateStatus(
                id = entry.id,
                status = ReadSyncStatus.SYNCING.name
            )

            try {
                val response = if (entry.isRead) {
                    api.markChatRead(entry.chatGuid)
                } else {
                    api.markChatUnread(entry.chatGuid)
                }

                if (response.isSuccessful) {
                    Timber.tag(TAG).d("Successfully synced read status for ${entry.chatGuid}")
                    // Delete on success - no need to keep synced entries
                    pendingReadStatusDao.delete(entry.id)
                    successCount++
                } else {
                    val errorCode = response.code()
                    Timber.tag(TAG).w("Failed to sync read status for ${entry.chatGuid}: HTTP $errorCode")

                    // Mark as failed with retry count increment
                    pendingReadStatusDao.updateStatusWithError(
                        id = entry.id,
                        status = if (entry.retryCount + 1 >= MAX_RETRY_COUNT) {
                            ReadSyncStatus.FAILED.name
                        } else {
                            ReadSyncStatus.PENDING.name  // Will be retried
                        },
                        error = "HTTP $errorCode"
                    )

                    // 404 means chat doesn't exist on server - don't retry
                    if (errorCode == 404) {
                        pendingReadStatusDao.delete(entry.id)
                        Timber.tag(TAG).d("Chat ${entry.chatGuid} not found on server, removing from queue")
                    } else {
                        failCount++
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error syncing read status for ${entry.chatGuid}")

                pendingReadStatusDao.updateStatusWithError(
                    id = entry.id,
                    status = if (entry.retryCount + 1 >= MAX_RETRY_COUNT) {
                        ReadSyncStatus.FAILED.name
                    } else {
                        ReadSyncStatus.PENDING.name  // Will be retried
                    },
                    error = e.message?.take(100)  // Truncate long error messages
                )
                failCount++
            }
        }

        Timber.tag(TAG).d("Read status sync complete: $successCount succeeded, $failCount failed")

        // If any entries failed but can still be retried, request a retry
        val remainingPending = pendingReadStatusDao.getPendingCount()
        return if (remainingPending > 0) {
            Timber.tag(TAG).d("$remainingPending entries still pending, requesting retry")
            Result.retry()
        } else {
            Result.success()
        }
    }
}
