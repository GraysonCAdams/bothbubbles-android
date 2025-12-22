package com.bothbubbles.util

/**
 * Generates Discord-style snowflake IDs for unified chats.
 *
 * ID format: 64-bit integer as string (e.g., "17032896543214827")
 * - High bits: timestamp in milliseconds
 * - Low bits: sequence counter (prevents collisions within same millisecond)
 *
 * Benefits:
 * - Unique across all devices/installs
 * - Sortable by creation time
 * - Clearly distinguishable from phone numbers and server GUIDs
 */
object UnifiedChatIdGenerator {
    private val lock = Any()
    private var lastTimestamp = 0L
    private var sequence = 0

    /**
     * Generates a new unique ID for a unified chat.
     *
     * Thread-safe: Uses synchronized block to prevent duplicate IDs
     * when called concurrently.
     *
     * @return A unique string ID (e.g., "17032896543214827")
     */
    fun generate(): String = synchronized(lock) {
        val timestamp = System.currentTimeMillis()

        // Reset sequence if timestamp advanced, otherwise increment
        sequence = if (timestamp == lastTimestamp) {
            sequence + 1
        } else {
            0
        }
        lastTimestamp = timestamp

        // Combine timestamp (high bits) with sequence (low 16 bits)
        // This gives us ~65k unique IDs per millisecond
        val id = (timestamp shl 16) or (sequence.toLong() and 0xFFFF)
        id.toString()
    }
}
