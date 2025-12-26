package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.core.model.entity.StitchCustomColorEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing custom bubble colors per Stitch.
 *
 * Custom colors override the Stitch's default bubble color.
 * Deleting a color resets to the Stitch's default.
 */
@Dao
interface StitchCustomColorDao {

    // ===== Queries =====

    /**
     * Observe all custom colors as a reactive Flow.
     * Used by the theme to update bubble colors in real-time.
     */
    @Query("SELECT * FROM stitch_custom_colors ORDER BY stitch_id ASC")
    fun observeAllCustomColors(): Flow<List<StitchCustomColorEntity>>

    /**
     * Get custom color for a specific Stitch.
     * Returns null if no custom color is set (use default).
     */
    @Query("SELECT * FROM stitch_custom_colors WHERE stitch_id = :stitchId LIMIT 1")
    suspend fun getCustomColor(stitchId: String): StitchCustomColorEntity?

    /**
     * Observe custom color for a specific Stitch.
     * Emits null when no custom color is set.
     */
    @Query("SELECT * FROM stitch_custom_colors WHERE stitch_id = :stitchId LIMIT 1")
    fun observeCustomColor(stitchId: String): Flow<StitchCustomColorEntity?>

    /**
     * Get all custom colors (non-reactive snapshot).
     */
    @Query("SELECT * FROM stitch_custom_colors")
    suspend fun getAllCustomColors(): List<StitchCustomColorEntity>

    // ===== Inserts/Updates =====

    /**
     * Set or update custom color for a Stitch.
     * Uses REPLACE strategy to handle both insert and update.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCustomColor(entity: StitchCustomColorEntity)

    // ===== Deletes =====

    /**
     * Remove custom color for a Stitch, reverting to default.
     */
    @Query("DELETE FROM stitch_custom_colors WHERE stitch_id = :stitchId")
    suspend fun deleteCustomColor(stitchId: String)

    /**
     * Remove all custom colors, reverting all Stitches to defaults.
     */
    @Query("DELETE FROM stitch_custom_colors")
    suspend fun deleteAllCustomColors()
}
