package com.bothbubbles.services.sync

import androidx.compose.runtime.Immutable

/**
 * Sync state with detailed progress information
 */
sealed class SyncState {
    data object Idle : SyncState()

    data class Syncing(
        val progress: Float,
        val stage: String,
        val totalChats: Int = 0,
        val processedChats: Int = 0,
        val syncedMessages: Int = 0,
        val currentChatName: String? = null,
        val isInitialSync: Boolean = false,
        // Separate progress tracking for iMessage and SMS
        val iMessageProgress: Float = 0f,
        val iMessageComplete: Boolean = false,
        val smsProgress: Float = 0f,
        val smsComplete: Boolean = false,
        val smsCurrent: Int = 0,
        val smsTotal: Int = 0
    ) : SyncState()

    data object Completed : SyncState()

    data class Error(
        val message: String,
        val isCorrupted: Boolean = false,
        val canRetry: Boolean = true
    ) : SyncState()
}

// ============================================================================
// Unified Sync Progress Model (for consolidated UI display)
// ============================================================================

/**
 * Status of an individual sync stage.
 */
enum class StageStatus {
    /** Stage has not started yet */
    WAITING,
    /** Stage is currently in progress */
    IN_PROGRESS,
    /** Stage completed successfully */
    COMPLETE,
    /** Stage failed with an error */
    ERROR,
    /** Stage was skipped (e.g., SMS disabled) */
    SKIPPED
}

/**
 * Identifies the type of sync stage for icon/color selection.
 */
enum class SyncStageType {
    IMESSAGE,
    SMS_IMPORT,
    CATEGORIZATION
}

/**
 * Progress for an individual sync stage.
 *
 * @param type The type of sync stage (for icon/color)
 * @param name Display name for the stage (e.g., "iMessage sync")
 * @param status Current status of the stage
 * @param progress Progress value 0.0-1.0 for this stage
 * @param weight Weight of this stage in overall progress (0.0-1.0, all weights should sum to 1.0)
 * @param detail Optional detail text (e.g., "12 of 26 threads")
 * @param errorMessage Error message if status is ERROR
 */
@Immutable
data class StageProgress(
    val type: SyncStageType,
    val name: String,
    val status: StageStatus,
    val progress: Float = 0f,
    val weight: Float = 0.33f,
    val detail: String? = null,
    val errorMessage: String? = null,
    val isCorrupted: Boolean = false
)

/**
 * Unified sync progress for consolidated UI display.
 * Combines multiple sync operations into a single progress model.
 *
 * @param overallProgress Combined progress 0.0-1.0 (weighted sum of all stages)
 * @param currentStage Display text for the current active stage
 * @param stages List of all sync stages with their individual progress
 * @param isExpanded Whether the detail view is expanded
 * @param hasError Whether any stage has an error
 * @param canRetry Whether the failed operation can be retried
 */
@Immutable
data class UnifiedSyncProgress(
    val overallProgress: Float,
    val currentStage: String,
    val stages: List<StageProgress> = emptyList(),
    val isExpanded: Boolean = false,
    val hasError: Boolean = false,
    val canRetry: Boolean = true
) {
    /**
     * Get the currently active stage (first IN_PROGRESS stage, or first ERROR stage).
     */
    val activeStage: StageProgress?
        get() = stages.firstOrNull { it.status == StageStatus.IN_PROGRESS }
            ?: stages.firstOrNull { it.status == StageStatus.ERROR }

    /**
     * Get the error message from the failed stage, if any.
     */
    val errorMessage: String?
        get() = stages.firstOrNull { it.status == StageStatus.ERROR }?.errorMessage

    /**
     * Get the failed stage type for retry logic.
     */
    val failedStageType: SyncStageType?
        get() = stages.firstOrNull { it.status == StageStatus.ERROR }?.type

    /**
     * Check if any error stage has corruption flag set.
     */
    val isCorrupted: Boolean
        get() = stages.any { it.status == StageStatus.ERROR && it.isCorrupted }

    companion object {
        /**
         * Calculate weighted overall progress from stages.
         * Normalizes by total active weight so progress reaches 100% when all active stages complete.
         */
        fun calculateOverallProgress(stages: List<StageProgress>): Float {
            if (stages.isEmpty()) return 0f

            // Calculate total weight of active stages (not WAITING or SKIPPED)
            val activeWeight = stages.sumOf { stage ->
                when (stage.status) {
                    StageStatus.COMPLETE, StageStatus.IN_PROGRESS, StageStatus.ERROR -> stage.weight.toDouble()
                    StageStatus.WAITING, StageStatus.SKIPPED -> 0.0
                }
            }.toFloat()

            if (activeWeight <= 0f) return 0f

            val weightedProgress = stages.sumOf { stage ->
                val stageContribution = when (stage.status) {
                    StageStatus.COMPLETE -> stage.weight
                    StageStatus.IN_PROGRESS -> stage.weight * stage.progress
                    StageStatus.ERROR -> stage.weight * stage.progress
                    StageStatus.WAITING, StageStatus.SKIPPED -> 0f
                }
                stageContribution.toDouble()
            }.toFloat()

            // Normalize by active weight so we reach 100% when all active stages complete
            return (weightedProgress / activeWeight).coerceIn(0f, 1f)
        }
    }
}
