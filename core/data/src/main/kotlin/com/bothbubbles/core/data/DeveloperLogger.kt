package com.bothbubbles.core.data

/**
 * Interface for developer logging functionality.
 *
 * Feature modules can log developer events without depending on the
 * concrete implementation in the services layer.
 */
interface DeveloperLogger {

    /**
     * Enable or disable developer logging.
     */
    fun setEnabled(enabled: Boolean)

    /**
     * Log a developer event.
     */
    fun log(tag: String, message: String)

    /**
     * Log a developer event with additional data.
     */
    fun log(tag: String, message: String, data: Map<String, Any?>)
}
