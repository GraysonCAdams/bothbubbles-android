package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a pending read status sync to the BlueBubbles server.
 *
 * When a chat is marked as read locally, the sync to the server is queued here.
 * WorkManager processes these entries to ensure reliable delivery even if the
 * network is unavailable or the app is killed.
 *
 * Multiple reads of the same chat are deduplicated - only the latest read status
 * matters (there's no point syncing "read" twice for the same chat).
 */
@Entity(
    tableName = "pending_read_status",
    indices = [
        Index(value = ["chat_guid"], unique = true),  // Dedup by chat
        Index(value = ["sync_status"]),
        Index(value = ["created_at"])
    ]
)
data class PendingReadStatusEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Chat GUID to sync read status for.
     * Unique constraint ensures we only have one pending sync per chat.
     */
    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    /**
     * Whether marking as read (true) or unread (false).
     */
    @ColumnInfo(name = "is_read")
    val isRead: Boolean = true,

    /**
     * Current sync status: PENDING, SYNCING, SYNCED, FAILED
     */
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = ReadSyncStatus.PENDING.name,

    /**
     * Number of sync attempts.
     */
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    /**
     * Error message if sync failed.
     */
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    /**
     * When the read status change was queued.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Last sync attempt timestamp.
     */
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null
)

/**
 * Sync status for pending read status changes.
 */
enum class ReadSyncStatus {
    /** Queued, waiting for sync */
    PENDING,
    /** Currently being synced */
    SYNCING,
    /** Successfully synced to server */
    SYNCED,
    /** Failed after max retries */
    FAILED
}
