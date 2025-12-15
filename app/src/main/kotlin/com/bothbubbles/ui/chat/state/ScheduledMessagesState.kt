package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.data.local.db.entity.ScheduledMessageEntity

/**
 * State owned by ChatScheduledMessageDelegate.
 * Contains all scheduled message-related UI state.
 */
@Stable
data class ScheduledMessagesState(
    val scheduledMessages: List<ScheduledMessageEntity> = emptyList(),
    val pendingCount: Int = 0
)
