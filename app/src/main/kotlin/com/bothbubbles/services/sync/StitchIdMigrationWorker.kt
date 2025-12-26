package com.bothbubbles.services.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.core.model.entity.MessageSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Worker that backfills stitch_id for existing messages.
 *
 * This is a one-time migration worker that runs on app update to ensure all messages
 * have the correct stitch_id value. While MIGRATION_64_65 handles the initial backfill,
 * this worker serves as a safety net to catch any edge cases.
 *
 * Stage 7 of the Seam Migration - Data Migration (Backfill stitch_id)
 *
 * Features:
 * - Idempotent: Safe to run multiple times
 * - Batched processing: Avoids ANRs on large datasets
 * - Progress reporting: Updates WorkManager progress
 * - Battery-friendly: Only runs when battery is not low
 */
@HiltWorker
class StitchIdMigrationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messageDao: MessageDao
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "stitch_id_migration"
        private const val BATCH_SIZE = 1000
        private const val KEY_MIGRATED_COUNT = "migrated_count"

        /**
         * Schedule the stitch_id migration worker.
         * Uses KEEP policy to avoid running multiple times unnecessarily.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<StitchIdMigrationWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)

            Timber.d("StitchIdMigrationWorker scheduled")
        }
    }

    override suspend fun doWork(): Result {
        Timber.i("StitchIdMigrationWorker started")

        return try {
            var totalMigrated = 0

            // Keep processing until no more messages need migration
            do {
                val batch = messageDao.getMessagesWithIncorrectStitchId(BATCH_SIZE)
                if (batch.isEmpty()) break

                batch.forEach { message ->
                    // Determine correct stitch_id based on message_source
                    val correctStitchId = when (message.messageSource) {
                        // BlueBubbles server sources
                        MessageSource.IMESSAGE.name,
                        MessageSource.SERVER_SMS.name -> "bluebubbles"
                        // Local Android sources
                        MessageSource.LOCAL_SMS.name,
                        MessageSource.LOCAL_MMS.name -> "sms"
                        // Unknown source - default to sms (safe default)
                        else -> {
                            Timber.w("Message ${message.guid} has unknown message_source: ${message.messageSource}, defaulting to 'sms'")
                            "sms"
                        }
                    }

                    // Update the stitch_id
                    messageDao.updateStitchId(message.guid, correctStitchId)
                }

                totalMigrated += batch.size

                // Update progress for observers
                setProgress(workDataOf(KEY_MIGRATED_COUNT to totalMigrated))

                Timber.d("StitchIdMigrationWorker: migrated $totalMigrated messages so far")

            } while (batch.size == BATCH_SIZE)

            Timber.i("StitchIdMigrationWorker complete: $totalMigrated messages migrated")
            return Result.success(workDataOf(KEY_MIGRATED_COUNT to totalMigrated))

        } catch (e: Exception) {
            Timber.e(e, "StitchIdMigrationWorker failed")
            return Result.retry()
        }
    }
}
