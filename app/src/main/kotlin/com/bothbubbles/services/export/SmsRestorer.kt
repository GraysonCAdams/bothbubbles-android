package com.bothbubbles.services.export

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for SMS/MMS backup restore operations.
 * Provides strict interface boundary for this dangerous operation.
 *
 * Restoring SMS/MMS modifies the system's SMS database, so this interface
 * allows testing restore logic without writing to real system databases.
 *
 * Implementation: [SmsRestoreService]
 */
interface SmsRestorer {

    /**
     * Observable state of the current restore operation.
     * UI can collect this to show progress.
     */
    val restoreProgress: StateFlow<SmsRestoreProgress>

    /**
     * Restore SMS/MMS messages from a backup file URI.
     * Performs duplicate detection based on date, address, and type.
     *
     * @param fileUri URI of the backup file (typically from DocumentProvider)
     * @return Flow that emits progress updates during the restore operation
     */
    fun restoreBackup(fileUri: Uri): Flow<SmsRestoreProgress>

    /**
     * Reset the progress state to Idle.
     * Should be called when user dismisses the restore dialog or navigates away.
     */
    fun resetProgress()
}
