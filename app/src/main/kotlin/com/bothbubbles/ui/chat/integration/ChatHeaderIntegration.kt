package com.bothbubbles.ui.chat.integration

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.Flow

/**
 * Interface for chat header subtext content providers.
 *
 * Implementations provide contextual information about a chat participant
 * (e.g., location, calendar events) that can be displayed in the header subtext area.
 *
 * ## Adding a New Integration
 *
 * 1. Create a class implementing this interface
 * 2. Inject required dependencies via constructor
 * 3. Add a Hilt binding in IntegrationModule using @IntoSet
 *
 * ## Priority Guidelines
 *
 * - 100-199: Real-time location (Life360)
 * - 80-99: Time-sensitive events (Calendar)
 * - 60-79: Status indicators
 * - 0-59: Static info
 */
interface ChatHeaderIntegration {
    /**
     * Unique identifier for this integration (e.g., "life360", "calendar").
     */
    val id: String

    /**
     * Display priority - higher values are shown first when cycling.
     */
    val priority: Int

    /**
     * Observe content for the given participant addresses.
     *
     * @param participantAddresses Phone numbers/emails of chat participants
     * @param isGroup Whether this is a group chat (integrations may skip groups)
     * @return Flow emitting content when available, null when unavailable
     */
    fun observeContent(
        participantAddresses: Set<String>,
        isGroup: Boolean
    ): Flow<ChatHeaderContent?>
}

/**
 * Content provided by an integration for display in the chat header.
 */
@Stable
data class ChatHeaderContent(
    /** The text to display (e.g., "At Home", "In 2h30m: Meeting with Rob") */
    val text: String,

    /** Source integration ID for debugging and tap routing */
    val sourceId: String,

    /** Priority for sorting (from integration) */
    val priority: Int,

    /** Icon to display before the text */
    val icon: ImageVector? = null,

    /** Whether this content should trigger an immediate cycle when changed */
    val triggerCycleOnChange: Boolean = false,

    /** Data for tap action - type-specific payload (e.g., event ID, member ID) */
    val tapActionData: TapActionData? = null
)

/**
 * Type-safe tap action data for integrations.
 */
sealed class TapActionData {
    /**
     * Navigate to Life360 location details.
     */
    data class Life360Location(val memberId: String) : TapActionData()

    /**
     * Open calendar event in device calendar.
     */
    data class CalendarEvent(val eventId: Long, val calendarId: Long) : TapActionData()
}

/**
 * Common icons for integrations.
 */
object IntegrationIcons {
    val Location: ImageVector = Icons.Default.LocationOn
    val Calendar: ImageVector = Icons.Default.CalendarToday
}
