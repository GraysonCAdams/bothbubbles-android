package com.bothbubbles.services.context

import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents Android's Do Not Disturb (DND) mode types.
 *
 * These map to Android's [NotificationManager] interruption filter constants:
 * - [PRIORITY_ONLY] = INTERRUPTION_FILTER_PRIORITY (priority notifications only)
 * - [ALARMS_ONLY] = INTERRUPTION_FILTER_ALARMS (only alarms)
 * - [TOTAL_SILENCE] = INTERRUPTION_FILTER_NONE (complete silence)
 */
enum class DndModeType {
    PRIORITY_ONLY,
    ALARMS_ONLY,
    TOTAL_SILENCE
}

/**
 * Provides the current Do Not Disturb state from the system.
 *
 * Used by auto-responder rules to trigger responses only when
 * specific DND modes are active.
 *
 * Requires ACCESS_NOTIFICATION_POLICY permission to read DND state.
 */
@Singleton
class DndStateProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    /**
     * Get the current DND mode type.
     *
     * @return The active [DndModeType], or null if DND is off
     *         (INTERRUPTION_FILTER_ALL means all notifications allowed)
     */
    fun getCurrentDndMode(): DndModeType? {
        return when (notificationManager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndModeType.PRIORITY_ONLY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndModeType.ALARMS_ONLY
            NotificationManager.INTERRUPTION_FILTER_NONE -> DndModeType.TOTAL_SILENCE
            else -> null // INTERRUPTION_FILTER_ALL or UNKNOWN = DND is off
        }
    }

    /**
     * Check if DND is currently active (any mode).
     */
    fun isDndActive(): Boolean = getCurrentDndMode() != null

    /**
     * Check if current DND mode matches any of the specified modes.
     *
     * @param modes Comma-separated mode names (e.g., "PRIORITY_ONLY,TOTAL_SILENCE")
     * @return true if current mode matches any specified mode
     */
    fun matchesModes(modes: String): Boolean {
        val currentMode = getCurrentDndMode() ?: return false
        return currentMode.name in modes.split(",").map { it.trim() }
    }
}
