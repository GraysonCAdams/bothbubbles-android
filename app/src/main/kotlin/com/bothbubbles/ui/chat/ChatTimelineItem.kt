package com.bothbubbles.ui.chat

import com.bothbubbles.ui.components.message.CalendarEventItem
import com.bothbubbles.ui.components.message.MessageUiModel

/**
 * Represents an item in the chat timeline.
 *
 * The chat timeline can contain:
 * - Messages (regular text, attachments, reactions, etc.)
 * - Calendar events (system-style indicators showing contact calendar activity)
 *
 * Items are sorted by timestamp and merged at display time. Calendar events
 * do NOT affect conversation ordering or trigger notifications.
 */
sealed class ChatTimelineItem {
    /**
     * Timestamp for ordering items in the timeline.
     * Messages use dateCreated, calendar events use eventStartTime.
     */
    abstract val timestamp: Long

    /**
     * Unique key for LazyColumn/LazyList item identification.
     */
    abstract val key: String

    /**
     * A regular message in the conversation.
     */
    data class Message(val message: MessageUiModel) : ChatTimelineItem() {
        override val timestamp: Long = message.dateCreated
        override val key: String = message.guid
    }

    /**
     * A calendar event occurrence displayed as a system indicator.
     */
    data class CalendarEvent(val event: CalendarEventItem) : ChatTimelineItem() {
        override val timestamp: Long = event.eventStartTime
        override val key: String = "cal_${event.id}"
    }

    companion object {
        /**
         * Merge messages and calendar events into a single timeline.
         *
         * Both lists should be sorted in descending order by timestamp (newest first).
         * The result will also be sorted in descending order.
         *
         * @param messages List of messages, sorted by dateCreated DESC
         * @param calendarEvents List of calendar events, sorted by eventStartTime DESC
         * @return Merged timeline with items interleaved by timestamp
         */
        fun mergeTimeline(
            messages: List<MessageUiModel>,
            calendarEvents: List<CalendarEventItem>
        ): List<ChatTimelineItem> {
            // Fast path: no calendar events to merge
            if (calendarEvents.isEmpty()) {
                return messages.map { Message(it) }
            }

            // Fast path: no messages
            if (messages.isEmpty()) {
                return calendarEvents.map { CalendarEvent(it) }
            }

            // Merge sorted lists - both are already sorted DESC by timestamp
            val timeline = mutableListOf<ChatTimelineItem>()
            var messageIndex = 0
            var eventIndex = 0

            while (messageIndex < messages.size || eventIndex < calendarEvents.size) {
                when {
                    messageIndex >= messages.size -> {
                        // No more messages, add remaining events
                        timeline.add(CalendarEvent(calendarEvents[eventIndex]))
                        eventIndex++
                    }
                    eventIndex >= calendarEvents.size -> {
                        // No more events, add remaining messages
                        timeline.add(Message(messages[messageIndex]))
                        messageIndex++
                    }
                    messages[messageIndex].dateCreated >= calendarEvents[eventIndex].eventStartTime -> {
                        // Message is newer or equal, add it first
                        timeline.add(Message(messages[messageIndex]))
                        messageIndex++
                    }
                    else -> {
                        // Event is newer, add it first
                        timeline.add(CalendarEvent(calendarEvents[eventIndex]))
                        eventIndex++
                    }
                }
            }

            return timeline
        }
    }
}
