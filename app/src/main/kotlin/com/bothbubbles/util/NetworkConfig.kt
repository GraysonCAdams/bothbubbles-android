package com.bothbubbles.util

object NetworkConfig {
    // Default retry configuration
    const val DEFAULT_RETRY_ATTEMPTS = 3
    const val DEFAULT_INITIAL_DELAY_MS = 500L
    const val DEFAULT_MAX_DELAY_MS = 5000L
    const val DEFAULT_BACKOFF_FACTOR = 2.0

    // Sync-specific (more patient)
    const val SYNC_RETRY_ATTEMPTS = 5
    const val SYNC_INITIAL_DELAY_MS = 1000L
    const val SYNC_MAX_DELAY_MS = 30000L

    // Attachment downloads (more retries, longer waits)
    const val ATTACHMENT_RETRY_ATTEMPTS = 5
    const val ATTACHMENT_INITIAL_DELAY_MS = 2000L
    const val ATTACHMENT_MAX_DELAY_MS = 60000L
}
