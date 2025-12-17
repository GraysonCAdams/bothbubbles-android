package com.bothbubbles.core.data

/**
 * Interface for controlling developer mode event logging.
 *
 * Feature modules depend on this interface rather than the concrete DeveloperEventLog
 * implementation, allowing for better decoupling and testability.
 *
 * The DeveloperEventLog in the app module implements this interface.
 */
interface DeveloperModeTracker {
    /**
     * Enable or disable developer mode event logging.
     * When disabled, no new events are logged (but existing events are retained).
     */
    fun setEnabled(enabled: Boolean)
}
