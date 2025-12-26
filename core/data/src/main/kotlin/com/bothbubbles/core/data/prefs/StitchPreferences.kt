package com.bothbubbles.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Manages Stitch-related preferences including priority ordering and send mode behavior.
 */
class StitchPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ===== Priority Order =====

    /**
     * Ordered list of Stitch IDs by priority (highest priority first).
     * Empty list means use default ordering (BlueBubbles > SMS > others).
     *
     * Example: ["bluebubbles", "sms"] means iMessage is preferred over SMS.
     */
    val stitchPriorityOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        val jsonString = prefs[Keys.STITCH_PRIORITY_ORDER]
        if (jsonString.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<String>>(jsonString) }
                .getOrElse { emptyList() }
        }
    }

    /**
     * Set the priority order for Stitches.
     * @param orderedStitchIds List of Stitch IDs in priority order (first = highest priority)
     */
    suspend fun setStitchPriorityOrder(orderedStitchIds: List<String>) {
        dataStore.edit { prefs ->
            prefs[Keys.STITCH_PRIORITY_ORDER] = json.encodeToString(orderedStitchIds)
        }
    }

    /**
     * Clear the priority order, reverting to default ordering.
     */
    suspend fun clearStitchPriorityOrder() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.STITCH_PRIORITY_ORDER)
        }
    }

    // ===== Send Mode Behavior =====

    /**
     * How the app selects which Stitch to use for new conversations.
     *
     * - AUTO_PRIORITY: Automatically select the best available Stitch based on priority order
     * - PROMPT_FIRST_TIME: Ask the user which Stitch to use for new conversations
     */
    val sendModeBehavior: Flow<SendModeBehavior> = dataStore.data.map { prefs ->
        prefs[Keys.SEND_MODE_BEHAVIOR]?.let { value ->
            runCatching { SendModeBehavior.valueOf(value) }.getOrNull()
        } ?: SendModeBehavior.AUTO_PRIORITY
    }

    /**
     * Set the send mode behavior for new conversations.
     */
    suspend fun setSendModeBehavior(behavior: SendModeBehavior) {
        dataStore.edit { prefs ->
            prefs[Keys.SEND_MODE_BEHAVIOR] = behavior.name
        }
    }

    private object Keys {
        val STITCH_PRIORITY_ORDER = stringPreferencesKey("stitch_priority_order")
        val SEND_MODE_BEHAVIOR = stringPreferencesKey("send_mode_behavior")
    }
}

/**
 * Controls how the app selects which Stitch to use for new conversations.
 */
enum class SendModeBehavior {
    /**
     * Automatically select the best available Stitch based on:
     * 1. User-defined priority order
     * 2. Contact availability (which Stitches can reach this contact)
     * 3. Connection state (prefer connected Stitches)
     */
    AUTO_PRIORITY,

    /**
     * Ask the user which Stitch to use when starting a NEW conversation
     * (no prior thread with this contact). The choice is always remembered
     * for that conversation unless manually changed in the kebab menu.
     */
    PROMPT_FIRST_TIME
}
