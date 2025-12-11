package com.bothbubbles.data.local.db.entity

/**
 * Transfer state for attachments.
 *
 * Tracks the lifecycle of attachment transfers for both uploads (outbound) and downloads (inbound).
 * This enables "snappy" UI rendering where:
 * - Outbound attachments display immediately from local file while uploading
 * - Inbound attachments show blurhash placeholders while downloading
 */
enum class TransferState {
    /**
     * Initial state for inbound attachments - remote file exists but not downloaded.
     * UI shows blurhash placeholder with download button/auto-download.
     */
    PENDING,

    /**
     * Outbound attachment is being uploaded to server.
     * UI shows local file with upload progress overlay.
     */
    UPLOADING,

    /**
     * Upload complete - server has the file.
     * For outbound attachments that have finished uploading.
     */
    UPLOADED,

    /**
     * Inbound attachment is being downloaded.
     * UI shows blurhash placeholder with download progress.
     */
    DOWNLOADING,

    /**
     * Download complete - local file is available.
     * This is the terminal state for successfully transferred attachments.
     */
    DOWNLOADED,

    /**
     * Transfer failed (upload or download).
     * UI shows error state with retry option.
     */
    FAILED;

    companion object {
        /**
         * Parse a transfer state from string, defaulting to DOWNLOADED for backwards compatibility.
         */
        fun fromString(value: String?): TransferState {
            return when (value) {
                null -> DOWNLOADED
                else -> try {
                    valueOf(value)
                } catch (e: IllegalArgumentException) {
                    DOWNLOADED
                }
            }
        }
    }
}
