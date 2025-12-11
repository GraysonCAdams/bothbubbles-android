package com.bothbubbles.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks synced timestamp ranges for each chat.
 *
 * This entity records what message ranges have been fetched from the BlueBubbles server,
 * allowing us to avoid redundant API calls when scrolling through already-synced regions.
 *
 * For Signal-style pagination:
 * - When user scrolls to old messages, we check if that range is synced
 * - If not synced, we fetch from server and record the range
 * - On subsequent scrolls, we skip server calls for synced ranges
 *
 * Ranges are stored as timestamp pairs (startTimestamp, endTimestamp) where:
 * - startTimestamp = oldest message date in the range
 * - endTimestamp = newest message date in the range
 */
@Entity(
    tableName = "sync_ranges",
    indices = [
        Index(value = ["chat_guid"]),
        Index(value = ["chat_guid", "start_timestamp", "end_timestamp"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["guid"],
            childColumns = ["chat_guid"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SyncRangeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * The chat GUID this range belongs to.
     */
    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    /**
     * Oldest message timestamp in this synced range.
     */
    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long,

    /**
     * Newest message timestamp in this synced range.
     */
    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long,

    /**
     * When this range was synced (for cache invalidation).
     */
    @ColumnInfo(name = "synced_at")
    val syncedAt: Long = System.currentTimeMillis(),

    /**
     * Source of the sync (initial, incremental, on-demand).
     */
    @ColumnInfo(name = "sync_source")
    val syncSource: String = SyncSource.ON_DEMAND.name
)

enum class SyncSource {
    INITIAL,      // Initial sync during setup
    INCREMENTAL,  // Periodic background sync
    ON_DEMAND,    // User scrolled to unsynced region
    SOCKET        // Real-time message via socket
}
