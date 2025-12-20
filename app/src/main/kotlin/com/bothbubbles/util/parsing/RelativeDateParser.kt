package com.bothbubbles.util.parsing

import java.util.Calendar

/**
 * Parses relative date expressions like "tomorrow", "next week", "in 3 days"
 * Also handles combined patterns like "at 2pm tomorrow" or "tomorrow at 2pm"
 */
internal object RelativeDateParser {

    /**
     * Parses a relative date string into a Calendar object
     * @param dateString String containing relative date expression
     * @return Calendar object representing the parsed date, or null if parsing fails
     */
    fun parse(dateString: String): Calendar? {
        val lower = dateString.lowercase().trim()
        val now = Calendar.getInstance()

        // Extract time if present (e.g., "tomorrow at 2pm" or "at 2pm tomorrow")
        val timeMatch = Regex("at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*([APap][Mm])").find(lower)

        fun Calendar.applyTime(): Calendar {
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toInt()
                val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
                val amPm = timeMatch.groupValues[3].lowercase()

                // Convert to 24-hour format
                if (amPm == "pm" && hour != 12) hour += 12
                if (amPm == "am" && hour == 12) hour = 0

                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return this
        }

        return when {
            // "today" or "today at X" or "at X today"
            lower.contains("today") -> {
                Calendar.getInstance().applyTime()
            }

            // "tomorrow" or "tomorrow at X" or "at X tomorrow"
            lower.contains("tomorrow") -> {
                Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, 1)
                }.applyTime()
            }

            // "next week/month/year" (or "at X next week")
            lower.contains("next week") -> {
                Calendar.getInstance().apply {
                    add(Calendar.WEEK_OF_YEAR, 1)
                    // Set to Monday of next week
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                }.applyTime()
            }

            lower.contains("next month") -> {
                Calendar.getInstance().apply {
                    add(Calendar.MONTH, 1)
                    set(Calendar.DAY_OF_MONTH, 1)
                }.applyTime()
            }

            lower.contains("next year") -> {
                Calendar.getInstance().apply {
                    add(Calendar.YEAR, 1)
                    set(Calendar.MONTH, Calendar.JANUARY)
                    set(Calendar.DAY_OF_MONTH, 1)
                }.applyTime()
            }

            // "next Monday/Tuesday/etc." (or "at X next Monday")
            lower.contains(Regex("next\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)")) -> {
                val dayMatch = Regex("next\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)").find(lower)
                dayMatch?.let {
                    val targetDay = dayNameToCalendarDay(it.groupValues[1])
                    Calendar.getInstance().apply {
                        // Move to next week first, then find the day
                        add(Calendar.WEEK_OF_YEAR, 1)
                        set(Calendar.DAY_OF_WEEK, targetDay)
                    }.applyTime()
                }
            }

            // "this Monday/Tuesday/etc." (or "at X this Monday")
            lower.contains(Regex("this\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)")) -> {
                val dayMatch = Regex("this\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)").find(lower)
                dayMatch?.let {
                    val targetDay = dayNameToCalendarDay(it.groupValues[1])
                    val currentDay = now.get(Calendar.DAY_OF_WEEK)
                    Calendar.getInstance().apply {
                        val daysUntil = (targetDay - currentDay + 7) % 7
                        // If it's today or already passed this week, still use this week's day
                        add(Calendar.DAY_OF_MONTH, if (daysUntil == 0 && targetDay == currentDay) 0 else daysUntil)
                    }.applyTime()
                }
            }

            // "this weekend"
            lower.contains("this weekend") -> {
                Calendar.getInstance().apply {
                    val currentDay = get(Calendar.DAY_OF_WEEK)
                    val daysUntilSaturday = (Calendar.SATURDAY - currentDay + 7) % 7
                    add(Calendar.DAY_OF_MONTH, if (daysUntilSaturday == 0) 0 else daysUntilSaturday)
                }
            }

            // "next weekend"
            lower.contains("next weekend") -> {
                Calendar.getInstance().apply {
                    add(Calendar.WEEK_OF_YEAR, 1)
                    set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
                }
            }

            // "in X days/weeks/months/years" (or "at X in Y days")
            lower.contains(Regex("in\\s+\\d+\\s+(days?|weeks?|months?|years?)")) -> {
                val inMatch = Regex("in\\s+(\\d+)\\s+(days?|weeks?|months?|years?)").find(lower)
                inMatch?.let {
                    val amount = it.groupValues[1].toInt()
                    val unit = it.groupValues[2].lowercase().trimEnd('s')
                    Calendar.getInstance().apply {
                        when (unit) {
                            "day" -> add(Calendar.DAY_OF_MONTH, amount)
                            "week" -> add(Calendar.WEEK_OF_YEAR, amount)
                            "month" -> add(Calendar.MONTH, amount)
                            "year" -> add(Calendar.YEAR, amount)
                        }
                    }.applyTime()
                }
            }

            else -> null
        }
    }

    /**
     * Converts day name to Calendar day constant
     */
    private fun dayNameToCalendarDay(dayName: String): Int {
        return when (dayName.lowercase()) {
            "sunday" -> Calendar.SUNDAY
            "monday" -> Calendar.MONDAY
            "tuesday" -> Calendar.TUESDAY
            "wednesday" -> Calendar.WEDNESDAY
            "thursday" -> Calendar.THURSDAY
            "friday" -> Calendar.FRIDAY
            "saturday" -> Calendar.SATURDAY
            else -> Calendar.MONDAY
        }
    }
}
