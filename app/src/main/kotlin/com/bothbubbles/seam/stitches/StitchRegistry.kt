package com.bothbubbles.seam.stitches

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all Stitch implementations.
 *
 * Stitches are collected via Dagger's `@IntoSet` multibinding in [StitchModule].
 * Use this registry to query available stitches, their states, and capabilities.
 */
@Singleton
class StitchRegistry @Inject constructor(
    private val stitches: Set<@JvmSuppressWildcards Stitch>
) {
    /**
     * Returns all stitches that are currently enabled.
     */
    fun getEnabledStitches(): List<Stitch> =
        stitches.filter { it.isEnabled.value }

    /**
     * Checks if a stitch can be disabled.
     * At least one stitch must remain enabled at all times.
     */
    fun canDisableStitch(stitch: Stitch): Boolean {
        val enabledCount = getEnabledStitches().size
        return enabledCount > 1 || !stitch.isEnabled.value
    }

    /**
     * Finds the stitch that handles the given chat GUID.
     */
    fun getStitchForChat(chatGuid: String): Stitch? {
        return stitches.find { stitch -> stitch.matchesChatGuid(chatGuid) }
    }

    /**
     * Finds a stitch by its unique ID.
     */
    fun getStitchById(id: String): Stitch? = stitches.find { it.id == id }

    /**
     * Returns all registered stitches.
     */
    fun getAllStitches(): Set<Stitch> = stitches

    /**
     * Returns all stitches that are currently connected.
     */
    fun getConnectedStitches(): List<Stitch> =
        stitches.filter { it.connectionState.value == StitchConnectionState.Connected }

    /**
     * Checks if any stitch is currently connected.
     */
    fun hasAnyConnectedStitch(): Boolean =
        stitches.any { it.connectionState.value == StitchConnectionState.Connected }

    /**
     * Returns a Flow that emits true when at least one stitch is connected.
     */
    fun observeAnyConnected(): Flow<Boolean> {
        if (stitches.isEmpty()) return kotlinx.coroutines.flow.flowOf(false)

        val stateFlows = stitches.map { it.connectionState }
        return combine(stateFlows) { states ->
            states.any { it == StitchConnectionState.Connected }
        }
    }

    /**
     * Returns a Flow that emits the connection state of a specific stitch.
     */
    fun observeConnectionState(stitchId: String): Flow<StitchConnectionState>? {
        return getStitchById(stitchId)?.connectionState
    }

    /**
     * Returns a Flow of the overall messaging availability.
     * Emits true when at least one stitch is enabled and connected.
     */
    fun observeMessagingAvailable(): Flow<Boolean> {
        if (stitches.isEmpty()) return kotlinx.coroutines.flow.flowOf(false)

        val combinedFlows = stitches.flatMap { stitch ->
            listOf(
                stitch.isEnabled.map { enabled -> stitch.id to enabled },
                stitch.connectionState.map { state -> stitch.id to state }
            )
        }

        return combine(combinedFlows) { results ->
            val enabledStitches = mutableSetOf<String>()
            val connectedStitches = mutableSetOf<String>()

            results.forEach { (id, value) ->
                when (value) {
                    is Boolean -> if (value) enabledStitches.add(id)
                    is StitchConnectionState -> if (value == StitchConnectionState.Connected) connectedStitches.add(id)
                }
            }

            enabledStitches.any { it in connectedStitches }
        }
    }

    // ===== Priority-Aware Ordering =====

    /**
     * Returns Stitches sorted by user-defined priority order.
     *
     * Stitches not in the priority list are appended at the end,
     * sorted by their default [Stitch.contactPriority].
     *
     * @param priorityOrder User-defined list of Stitch IDs in priority order (first = highest)
     * @return Stitches sorted by priority
     */
    fun getStitchesByPriority(priorityOrder: List<String>): List<Stitch> {
        if (priorityOrder.isEmpty()) {
            // Default ordering: by contactPriority descending
            return stitches.sortedByDescending { it.contactPriority }
        }

        val priorityMap = priorityOrder.withIndex().associate { it.value to it.index }
        return stitches.sortedWith(compareBy { stitch ->
            // Stitches in priority list get their index, others get MAX_VALUE
            priorityMap[stitch.id] ?: Int.MAX_VALUE
        })
    }

    /**
     * Returns enabled Stitches sorted by priority.
     *
     * @param priorityOrder User-defined priority order
     * @return Enabled Stitches sorted by priority
     */
    fun getEnabledStitchesByPriority(priorityOrder: List<String>): List<Stitch> {
        return getStitchesByPriority(priorityOrder).filter { it.isEnabled.value }
    }

    /**
     * Gets the highest-priority Stitch that is enabled and connected.
     *
     * @param priorityOrder User-defined priority order
     * @return The preferred Stitch, or null if none are available
     */
    fun getPreferredStitch(priorityOrder: List<String>): Stitch? {
        return getStitchesByPriority(priorityOrder)
            .firstOrNull { stitch ->
                stitch.isEnabled.value &&
                stitch.connectionState.value == StitchConnectionState.Connected
            }
            // Fallback: any enabled stitch
            ?: getStitchesByPriority(priorityOrder).firstOrNull { it.isEnabled.value }
    }

    /**
     * Gets Stitches that support the given identifier type, sorted by priority.
     *
     * @param identifierType The contact identifier type
     * @param priorityOrder User-defined priority order
     * @return Stitches that support this identifier type
     */
    fun getStitchesForIdentifierType(
        identifierType: ContactIdentifierType,
        priorityOrder: List<String>
    ): List<Stitch> {
        return getStitchesByPriority(priorityOrder)
            .filter { identifierType in it.supportedIdentifierTypes }
    }
}
