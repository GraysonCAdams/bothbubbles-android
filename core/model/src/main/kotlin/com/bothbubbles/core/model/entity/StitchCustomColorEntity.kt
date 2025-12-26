package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores custom bubble colors for each Stitch platform.
 *
 * Colors are stored as ARGB hex strings (e.g., "#FF007AFF").
 * When no custom color is set for a Stitch, the Stitch's default color is used.
 *
 * The stitchId corresponds to [com.bothbubbles.seam.stitches.Stitch.id]:
 * - "bluebubbles" for iMessage via BlueBubbles server
 * - "sms" for local SMS/MMS
 */
@Entity(tableName = "stitch_custom_colors")
data class StitchCustomColorEntity(
    /**
     * Stitch ID (e.g., "bluebubbles", "sms").
     * Primary key since each Stitch can only have one custom color.
     */
    @PrimaryKey
    @ColumnInfo(name = "stitch_id")
    val stitchId: String,

    /**
     * Custom bubble color as ARGB hex string (e.g., "#FF007AFF").
     */
    @ColumnInfo(name = "bubble_color")
    val bubbleColor: String,

    /**
     * Timestamp when this color was last updated.
     * Used for sorting and debugging.
     */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
