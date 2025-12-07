package com.bothbubbles.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a user-saved quick reply template.
 *
 * Templates are displayed as suggestion chips above the message input
 * and in notification quick reply actions.
 */
@Entity(
    tableName = "quick_reply_templates",
    indices = [
        Index(value = ["usage_count"]),
        Index(value = ["is_favorite"]),
        Index(value = ["display_order"])
    ]
)
data class QuickReplyTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Short display label for chips (recommended max ~25 chars).
     * Shown in notification quick reply chips and in-app suggestion chips.
     */
    @ColumnInfo(name = "title")
    val title: String,

    /**
     * Full message text to insert when the template is selected.
     * Can be longer than the title for detailed messages.
     */
    @ColumnInfo(name = "text")
    val text: String,

    /**
     * Number of times this template has been used.
     * Used for "most used" sorting.
     */
    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0,

    /**
     * Timestamp of last use, for "recently used" sorting.
     */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,

    /**
     * Timestamp when the template was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Manual display order for user-defined sorting.
     */
    @ColumnInfo(name = "display_order")
    val displayOrder: Int = 0,

    /**
     * Whether this template is marked as a favorite (pinned to top).
     */
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false
)
