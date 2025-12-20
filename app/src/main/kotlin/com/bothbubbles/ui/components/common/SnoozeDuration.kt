package com.bothbubbles.ui.components.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Represents the available snooze durations for conversations.
 * @param label The human-readable label to display in the UI
 * @param durationMs Duration in milliseconds, or -1 for indefinite snooze
 */
enum class SnoozeDuration(val label: String, val durationMs: Long) {
    ONE_HOUR("1 hour", 1 * 60 * 60 * 1000L),
    EIGHT_HOURS("8 hours", 8 * 60 * 60 * 1000L),
    ONE_DAY("1 day", 24 * 60 * 60 * 1000L),
    ONE_WEEK("1 week", 7 * 24 * 60 * 60 * 1000L),
    INDEFINITE("Indefinitely", -1L);

    companion object {
        // Thread-safe DateTimeFormatter (replaces SimpleDateFormat)
        private val LONG_DURATION_FORMAT = DateTimeFormatter.ofPattern("MMM d 'at' h:mm a", Locale.getDefault())

        /**
         * Format the remaining snooze time as a human-readable string.
         * @param snoozeUntil The epoch timestamp when snooze expires, or -1 for indefinite
         * @return A formatted string like "2 hours", "until 5:00 PM", or "Indefinitely"
         */
        fun formatRemainingTime(snoozeUntil: Long): String {
            if (snoozeUntil == -1L) return "Indefinitely"

            val now = System.currentTimeMillis()
            val remainingMs = snoozeUntil - now

            if (remainingMs <= 0) return "Expired"

            val remainingMinutes = remainingMs / (1000 * 60)
            val remainingHours = remainingMinutes / 60
            val remainingDays = remainingHours / 24

            return when {
                remainingMinutes < 1 -> "Less than a minute"
                remainingMinutes < 60 -> "$remainingMinutes minute${if (remainingMinutes != 1L) "s" else ""}"
                remainingHours < 24 -> "$remainingHours hour${if (remainingHours != 1L) "s" else ""}"
                remainingDays < 7 -> "$remainingDays day${if (remainingDays != 1L) "s" else ""}"
                else -> {
                    // Format as absolute date for longer durations
                    val instant = Instant.ofEpochMilli(snoozeUntil)
                    "until ${LONG_DURATION_FORMAT.format(instant.atZone(ZoneId.systemDefault()))}"
                }
            }
        }

        // Thread-safe DateTimeFormatter for time-only display
        private val TIME_ONLY_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

        /**
         * Format the snooze end time as an absolute time.
         * @param snoozeUntil The epoch timestamp when snooze expires, or -1 for indefinite
         * @return A formatted string like "5:00 PM" or "Dec 15 at 9:00 AM"
         */
        fun formatEndTime(snoozeUntil: Long): String {
            if (snoozeUntil == -1L) return "Never"

            val snoozeInstant = Instant.ofEpochMilli(snoozeUntil)
            val snoozeZoned = snoozeInstant.atZone(ZoneId.systemDefault())
            val today = java.util.Calendar.getInstance()
            val snoozeDay = java.util.Calendar.getInstance().apply { timeInMillis = snoozeUntil }

            val isSameDay = today.get(java.util.Calendar.YEAR) == snoozeDay.get(java.util.Calendar.YEAR) &&
                    today.get(java.util.Calendar.DAY_OF_YEAR) == snoozeDay.get(java.util.Calendar.DAY_OF_YEAR)

            val isTomorrow = run {
                val tomorrow = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
                tomorrow.get(java.util.Calendar.YEAR) == snoozeDay.get(java.util.Calendar.YEAR) &&
                        tomorrow.get(java.util.Calendar.DAY_OF_YEAR) == snoozeDay.get(java.util.Calendar.DAY_OF_YEAR)
            }

            return when {
                isSameDay -> {
                    TIME_ONLY_FORMAT.format(snoozeZoned)
                }
                isTomorrow -> {
                    "Tomorrow ${TIME_ONLY_FORMAT.format(snoozeZoned)}"
                }
                else -> {
                    LONG_DURATION_FORMAT.format(snoozeZoned)
                }
            }
        }
    }
}
