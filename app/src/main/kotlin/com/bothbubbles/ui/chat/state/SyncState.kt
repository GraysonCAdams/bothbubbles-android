package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.services.messaging.FallbackReason

/**
 * State owned by ChatSyncDelegate.
 * Contains all sync-related UI state (typing, connection, fallback mode).
 */
@Stable
data class SyncState(
    val isTyping: Boolean = false,
    val isServerConnected: Boolean = true,
    val isSyncing: Boolean = false,
    val isInSmsFallbackMode: Boolean = false,
    val fallbackReason: FallbackReason? = null,
    val lastSyncTime: Long = 0L
)
