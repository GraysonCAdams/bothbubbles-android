package com.bothbubbles.services.calendar

import com.bothbubbles.data.repository.ContactCalendarRepository
import com.bothbubbles.ui.chat.integration.ChatHeaderContent
import com.bothbubbles.ui.chat.integration.ChatHeaderIntegration
import com.bothbubbles.ui.chat.integration.IntegrationIcons
import com.bothbubbles.ui.chat.integration.TapActionData
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat header integration for calendar event display.
 *
 * Shows the current or upcoming calendar event of a contact if:
 * - The chat is a 1:1 (not a group)
 * - The contact has an associated calendar
 * - There's a current event or an event within 4 hours
 *
 * Priority: 80 (shown after location, as events are time-sensitive but less dynamic)
 *
 * Display format:
 * - Current event: "Meeting with Rob"
 * - Upcoming event: "In 2h30m: Meeting with Joe"
 *
 * Tap action: Open the event in the device calendar app
 */
@Singleton
class CalendarHeaderIntegration @Inject constructor(
    private val calendarRepository: ContactCalendarRepository
) : ChatHeaderIntegration {

    companion object {
        private const val TAG = "CalendarHeaderIntegration"

        /** How often to refresh event state (for relative time updates) */
        private const val REFRESH_INTERVAL_MS = 60_000L // 1 minute
    }

    override val id: String = "calendar"
    override val priority: Int = 80

    override fun observeContent(
        participantAddresses: Set<String>,
        isGroup: Boolean
    ): Flow<ChatHeaderContent?> {
        // Only for 1:1 chats
        if (isGroup) {
            return flowOf(null)
        }

        if (participantAddresses.isEmpty()) {
            return flowOf(null)
        }

        // Use the first participant's address
        val address = participantAddresses.first()

        // Poll for current event state (for real-time relative time updates)
        return flow {
            while (currentCoroutineContext().isActive) {
                val content = getContentForAddress(address)
                emit(content)
                delay(REFRESH_INTERVAL_MS)
            }
        }.distinctUntilChanged()
    }

    private suspend fun getContentForAddress(address: String): ChatHeaderContent? {
        // Check if contact has an associated calendar
        val association = calendarRepository.getAssociation(address) ?: return null

        return when (val eventState = calendarRepository.getCurrentEventState(address)) {
            is CurrentEventState.InEvent -> {
                val event = eventState.event
                ChatHeaderContent(
                    text = event.title,
                    sourceId = id,
                    priority = priority,
                    icon = IntegrationIcons.Calendar,
                    triggerCycleOnChange = false, // Don't cycle on every minute update
                    tapActionData = TapActionData.CalendarEvent(
                        eventId = event.id,
                        calendarId = association.calendarId
                    )
                )
            }

            is CurrentEventState.UpcomingEvent -> {
                val event = eventState.event
                val relativeTime = formatRelativeTime(event.timeUntilStart())
                ChatHeaderContent(
                    text = "In $relativeTime: ${event.title}",
                    sourceId = id,
                    priority = priority,
                    icon = IntegrationIcons.Calendar,
                    triggerCycleOnChange = false, // Don't cycle on every minute update
                    tapActionData = TapActionData.CalendarEvent(
                        eventId = event.id,
                        calendarId = association.calendarId
                    )
                )
            }

            CurrentEventState.NoPermission -> {
                Timber.tag(TAG).d("Calendar permission not granted")
                null
            }

            CurrentEventState.NoUpcomingEvents -> {
                null
            }
        }
    }

    /**
     * Format a duration in milliseconds to a human-readable relative time.
     *
     * Examples:
     * - 90 minutes -> "1h30m"
     * - 45 minutes -> "45m"
     * - 2 hours 15 minutes -> "2h15m"
     */
    private fun formatRelativeTime(durationMs: Long): String {
        if (durationMs < 0) return "now"

        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60

        return when {
            hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }
}
