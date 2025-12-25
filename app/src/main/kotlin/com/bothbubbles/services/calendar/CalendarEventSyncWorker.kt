package com.bothbubbles.services.calendar

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bothbubbles.data.local.db.dao.ContactCalendarDao
import com.bothbubbles.data.repository.CalendarEventOccurrenceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically syncs calendar events for contacts with calendar associations.
 *
 * Features:
 * - Syncs upcoming events (next 24 hours) for all contacts with calendar associations
 * - Records event occurrences in the database for display in chat
 * - Does NOT bump conversations or trigger notifications
 * - Cleans up old event occurrences (older than 7 days)
 *
 * @see CalendarEventOccurrenceRepository for sync logic
 * @see CalendarEventSyncPreferences for sync time tracking
 */
@HiltWorker
class CalendarEventSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val calendarEventOccurrenceRepository: CalendarEventOccurrenceRepository,
    private val contactCalendarDao: ContactCalendarDao,
    private val syncPreferences: CalendarEventSyncPreferences
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "CalendarEventSyncWorker"
        private const val WORK_NAME = "calendar_event_sync"

        /**
         * Schedule the calendar event sync worker.
         *
         * @param context Application context
         * @param intervalMinutes How often to sync (minutes, minimum 15)
         */
        fun schedule(context: Context, intervalMinutes: Int = 30) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<CalendarEventSyncWorker>(
                intervalMinutes.toLong().coerceAtLeast(15), // Android minimum is 15 min
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15, TimeUnit.MINUTES
                )
                .addTag("calendar_event_sync")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            Timber.tag(TAG).i("Calendar event sync scheduled (every $intervalMinutes minutes)")
        }

        /**
         * Cancel the calendar event sync worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.tag(TAG).i("Calendar event sync cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Timber.tag(TAG).d("Starting calendar event sync")

        return try {
            // Get all contacts with calendar associations
            val associations = contactCalendarDao.getAll()

            if (associations.isEmpty()) {
                Timber.tag(TAG).d("No calendar associations, skipping sync")
                return Result.success()
            }

            var totalSynced = 0
            var failedCount = 0

            for (association in associations) {
                val result = calendarEventOccurrenceRepository.syncEventsForContact(
                    association.linkedAddress
                )
                result.fold(
                    onSuccess = { count ->
                        totalSynced += count
                        // Record last sync time
                        syncPreferences.setLastSyncTime(association.linkedAddress)
                    },
                    onFailure = { error ->
                        Timber.tag(TAG).w(error, "Failed to sync events for ${association.linkedAddress}")
                        failedCount++
                    }
                )
            }

            // Cleanup old events
            calendarEventOccurrenceRepository.cleanupOldEvents()

            Timber.tag(TAG).d(
                "Calendar event sync complete: $totalSynced new events, $failedCount failures"
            )
            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Calendar event sync failed with exception")
            Result.retry()
        }
    }
}
