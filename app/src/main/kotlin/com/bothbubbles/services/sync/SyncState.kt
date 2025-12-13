package com.bothbubbles.services.sync

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
