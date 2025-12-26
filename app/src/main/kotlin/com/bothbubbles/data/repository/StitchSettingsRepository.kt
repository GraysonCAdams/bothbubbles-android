package com.bothbubbles.data.repository

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.bothbubbles.core.data.prefs.SendModeBehavior
import com.bothbubbles.core.data.prefs.StitchPreferences
import com.bothbubbles.core.model.entity.StitchCustomColorEntity
import com.bothbubbles.data.local.db.dao.StitchCustomColorDao
import com.bothbubbles.ui.theme.StitchDefaultColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing Stitch settings including priority order and custom colors.
 *
 * This repository combines DataStore preferences (for priority order) and Room database
 * (for custom colors) to provide a unified API for Stitch configuration.
 */
@Singleton
class StitchSettingsRepository @Inject constructor(
    private val stitchPreferences: StitchPreferences,
    private val stitchCustomColorDao: StitchCustomColorDao
) {

    // ===== Priority Order =====

    /**
     * Observe the user-defined Stitch priority order.
     * Empty list means use default ordering.
     */
    fun observePriorityOrder(): Flow<List<String>> = stitchPreferences.stitchPriorityOrder

    /**
     * Set the priority order for Stitches.
     * @param orderedStitchIds Stitch IDs in priority order (first = highest priority)
     */
    suspend fun setPriorityOrder(orderedStitchIds: List<String>): Result<Unit> = runCatching {
        stitchPreferences.setStitchPriorityOrder(orderedStitchIds)
    }

    /**
     * Reset priority order to defaults.
     */
    suspend fun resetPriorityOrder(): Result<Unit> = runCatching {
        stitchPreferences.clearStitchPriorityOrder()
    }

    // ===== Send Mode Behavior =====

    /**
     * Observe the send mode behavior setting.
     */
    fun observeSendModeBehavior(): Flow<SendModeBehavior> = stitchPreferences.sendModeBehavior

    /**
     * Set the send mode behavior.
     */
    suspend fun setSendModeBehavior(behavior: SendModeBehavior): Result<Unit> = runCatching {
        stitchPreferences.setSendModeBehavior(behavior)
    }

    // ===== Custom Colors =====

    /**
     * Observe all custom colors as a map of stitchId -> entity.
     */
    fun observeAllCustomColors(): Flow<Map<String, StitchCustomColorEntity>> =
        stitchCustomColorDao.observeAllCustomColors().map { list ->
            list.associateBy { it.stitchId }
        }

    /**
     * Observe effective colors for all known Stitches.
     * Returns custom color if set, otherwise the default color.
     *
     * @param isDarkTheme Whether dark theme is active
     * @param knownStitchIds Set of Stitch IDs to include
     * @return Map of stitchId -> effective Color
     */
    fun observeEffectiveColors(
        isDarkTheme: Boolean,
        knownStitchIds: Set<String>
    ): Flow<Map<String, Color>> = observeAllCustomColors().map { customColors ->
        buildEffectiveColorMap(customColors, isDarkTheme, knownStitchIds)
    }

    /**
     * Get all effective colors (snapshot, not reactive).
     */
    suspend fun getAllEffectiveColors(
        isDarkTheme: Boolean,
        knownStitchIds: Set<String>
    ): Map<String, Color> {
        val customColors = stitchCustomColorDao.getAllCustomColors()
            .associateBy { it.stitchId }
        return buildEffectiveColorMap(customColors, isDarkTheme, knownStitchIds)
    }

    /**
     * Get the effective color for a specific Stitch.
     * Returns custom color if set, otherwise the default.
     */
    suspend fun getEffectiveColor(stitchId: String, isDarkTheme: Boolean): Color {
        val custom = stitchCustomColorDao.getCustomColor(stitchId)
        return if (custom != null) {
            parseColorOrDefault(custom.bubbleColor, stitchId, isDarkTheme)
        } else {
            StitchDefaultColors.getDefaultBubbleColor(stitchId, isDarkTheme)
        }
    }

    /**
     * Observe the effective color for a specific Stitch.
     */
    fun observeEffectiveColor(stitchId: String, isDarkTheme: Boolean): Flow<Color> =
        stitchCustomColorDao.observeCustomColor(stitchId).map { custom ->
            if (custom != null) {
                parseColorOrDefault(custom.bubbleColor, stitchId, isDarkTheme)
            } else {
                StitchDefaultColors.getDefaultBubbleColor(stitchId, isDarkTheme)
            }
        }

    /**
     * Check if a Stitch has a custom color set.
     */
    suspend fun hasCustomColor(stitchId: String): Boolean {
        return stitchCustomColorDao.getCustomColor(stitchId) != null
    }

    /**
     * Set a custom color for a Stitch.
     *
     * @param stitchId The Stitch identifier
     * @param color The custom color to set
     */
    suspend fun setCustomColor(stitchId: String, color: Color): Result<Unit> = runCatching {
        stitchCustomColorDao.setCustomColor(
            StitchCustomColorEntity(
                stitchId = stitchId,
                bubbleColor = colorToHex(color),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Reset a Stitch's color to its default.
     */
    suspend fun resetColorToDefault(stitchId: String): Result<Unit> = runCatching {
        stitchCustomColorDao.deleteCustomColor(stitchId)
    }

    /**
     * Reset all Stitch colors to their defaults.
     */
    suspend fun resetAllColorsToDefault(): Result<Unit> = runCatching {
        stitchCustomColorDao.deleteAllCustomColors()
    }

    // ===== Private Helpers =====

    private fun buildEffectiveColorMap(
        customColors: Map<String, StitchCustomColorEntity>,
        isDarkTheme: Boolean,
        knownStitchIds: Set<String>
    ): Map<String, Color> {
        val result = mutableMapOf<String, Color>()

        for (stitchId in knownStitchIds) {
            val custom = customColors[stitchId]
            result[stitchId] = if (custom != null) {
                parseColorOrDefault(custom.bubbleColor, stitchId, isDarkTheme)
            } else {
                StitchDefaultColors.getDefaultBubbleColor(stitchId, isDarkTheme)
            }
        }

        return result
    }

    private fun colorToHex(color: Color): String {
        return String.format("#%08X", color.toArgb())
    }

    private fun parseColorOrDefault(hex: String, stitchId: String, isDarkTheme: Boolean): Color {
        return runCatching {
            Color(android.graphics.Color.parseColor(hex))
        }.getOrElse {
            StitchDefaultColors.getDefaultBubbleColor(stitchId, isDarkTheme)
        }
    }
}
