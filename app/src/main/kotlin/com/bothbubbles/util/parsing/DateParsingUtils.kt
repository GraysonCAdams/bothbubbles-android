package com.bothbubbles.util.parsing

import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * Represents a detected date in message text with its position and parsed value
 */
data class DetectedDate(
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String,
    val parsedDate: Calendar,
    val hasTime: Boolean,
    val isTimeOnly: Boolean = false
)

/**
 * Utility object for detecting and parsing dates from message text
 */
object DateParsingUtils {

    // Common date patterns to match (ordered from most specific to least specific)
    private val DATE_PATTERNS = listOf(
        // === PATTERNS WITH YEAR ===
        // "November 25, 2025 at 02:40PM" or "November 25, 2025 at 02:40 PM"
        Pattern.compile(
            "\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+" +
            "(\\d{1,2}),?\\s+(\\d{4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "December 21, 2025"
        Pattern.compile(
            "\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+" +
            "(\\d{1,2}),?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "Nov 25, 2025 at 2:40PM"
        Pattern.compile(
            "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+" +
            "(\\d{1,2}),?\\s+(\\d{4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "Nov 25, 2025"
        Pattern.compile(
            "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+" +
            "(\\d{1,2}),?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "12/25/2025 at 3:00PM"
        Pattern.compile(
            "\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "12/25/2025"
        Pattern.compile(
            "\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b"
        ),
        // "2025-12-25 at 15:00"
        Pattern.compile(
            "\\b(\\d{4})-(\\d{2})-(\\d{2})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*[APap]?[Mm]?)\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "2025-12-25"
        Pattern.compile(
            "\\b(\\d{4})-(\\d{2})-(\\d{2})\\b"
        ),

        // === PATTERNS WITHOUT YEAR ===
        // "November 25 at 02:40PM" (full month with time, no year)
        Pattern.compile(
            "\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+" +
            "(\\d{1,2})(?:st|nd|rd|th)?\\s+at\\s+(\\d{1,2}:\\d{2}\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "November 25" or "November 25th" (full month, no year)
        Pattern.compile(
            "\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+" +
            "(\\d{1,2})(?:st|nd|rd|th)?\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "Nov 25 at 2:40PM" (abbreviated month with time, no year)
        Pattern.compile(
            "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+" +
            "(\\d{1,2})(?:st|nd|rd|th)?\\s+at\\s+(\\d{1,2}:\\d{2}\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "Nov 25" or "Nov 25th" (abbreviated month, no year)
        Pattern.compile(
            "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+" +
            "(\\d{1,2})(?:st|nd|rd|th)?\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "12/25 at 3:00PM" (numeric with time, no year)
        Pattern.compile(
            "\\b(\\d{1,2})/(\\d{1,2})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "12/25" (numeric, no year) - must not be followed by another /
        Pattern.compile(
            "\\b(\\d{1,2})/(\\d{1,2})(?!/)"
        ),

        // === RELATIVE DATE PATTERNS ===
        // Combined patterns: time BEFORE relative date (e.g., "at 2pm tomorrow")
        // "at 2pm tomorrow" or "at 2:30pm tomorrow"
        Pattern.compile(
            "\\bat\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\s+tomorrow\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "at 2pm today"
        Pattern.compile(
            "\\bat\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\s+today\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "at 2pm next week/month/year"
        Pattern.compile(
            "\\bat\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\s+next\\s+(week|month|year)\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "at 2pm next Monday/Tuesday/etc."
        Pattern.compile(
            "\\bat\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\s+next\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "at 2pm this Monday/Tuesday/etc."
        Pattern.compile(
            "\\bat\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\s+this\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "at 2pm in X days/weeks/months"
        Pattern.compile(
            "\\bat\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\s+in\\s+(\\d+)\\s+(days?|weeks?|months?|years?)\\b",
            Pattern.CASE_INSENSITIVE
        ),

        // Combined patterns: relative date BEFORE time (e.g., "tomorrow at 2pm")
        // "tomorrow at 2pm" or "tomorrow at 2:30 PM"
        Pattern.compile(
            "\\btomorrow\\s+at\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "tomorrow"
        Pattern.compile(
            "\\btomorrow\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "today at 2pm"
        Pattern.compile(
            "\\btoday\\s+at\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "today"
        Pattern.compile(
            "\\btoday\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "next week/month/year at time"
        Pattern.compile(
            "\\bnext\\s+(week|month|year)\\s+at\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "next week/month/year"
        Pattern.compile(
            "\\bnext\\s+(week|month|year)\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "next Monday/Tuesday/etc. at time"
        Pattern.compile(
            "\\bnext\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+at\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "next Monday/Tuesday/etc."
        Pattern.compile(
            "\\bnext\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "this Monday/Tuesday/etc. at time"
        Pattern.compile(
            "\\bthis\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+at\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "this Monday/Tuesday/etc."
        Pattern.compile(
            "\\bthis\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "in X days/weeks/months/years at time"
        Pattern.compile(
            "\\bin\\s+(\\d+)\\s+(days?|weeks?|months?|years?)\\s+at\\s+(\\d{1,2}(?::\\d{2})?\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "in X days/weeks/months/years"
        Pattern.compile(
            "\\bin\\s+(\\d+)\\s+(days?|weeks?|months?|years?)\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "this weekend"
        Pattern.compile(
            "\\bthis\\s+weekend\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "next weekend"
        Pattern.compile(
            "\\bnext\\s+weekend\\b",
            Pattern.CASE_INSENSITIVE
        ),

        // === TIME-ONLY PATTERNS ===
        // "at 2:30pm" or "at 2:30 PM"
        Pattern.compile(
            "\\bat\\s+(\\d{1,2}:\\d{2}\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "at 2pm" or "at 2 PM" (no minutes)
        Pattern.compile(
            "\\bat\\s+(\\d{1,2})\\s*([APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // "at 14:00" (24-hour format)
        Pattern.compile(
            "\\bat\\s+(\\d{1,2}:\\d{2})\\b"
        ),
        // Standalone time "2:30pm" or "2:30 PM" (with word boundary before)
        Pattern.compile(
            "(?<=\\s|^)(\\d{1,2}:\\d{2}\\s*[APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        ),
        // Standalone "2pm" or "2 PM"
        Pattern.compile(
            "(?<=\\s|^)(\\d{1,2})\\s*([APap][Mm])\\b",
            Pattern.CASE_INSENSITIVE
        )
    )

    // Index ranges for special pattern types
    // 8 patterns with year (0-7) + 6 patterns without year (8-13) = 14 is start of relative
    private val RELATIVE_DATE_PATTERN_START_INDEX = 14
    // 6 new combined patterns (14-19) + 14 original relative patterns (20-33) = 34 is start of time-only
    private val TIME_ONLY_PATTERN_START_INDEX = 34

    // Date format parsers (with year)
    private val DATE_FORMATS_WITH_YEAR = listOf(
        SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.US),
        SimpleDateFormat("MMMM d, yyyy 'at' h:mma", Locale.US),
        SimpleDateFormat("MMMM d yyyy 'at' h:mm a", Locale.US),
        SimpleDateFormat("MMMM d yyyy 'at' h:mma", Locale.US),
        SimpleDateFormat("MMMM d, yyyy", Locale.US),
        SimpleDateFormat("MMMM d yyyy", Locale.US),
        SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US),
        SimpleDateFormat("MMM d, yyyy 'at' h:mma", Locale.US),
        SimpleDateFormat("MMM d yyyy 'at' h:mm a", Locale.US),
        SimpleDateFormat("MMM d yyyy 'at' h:mma", Locale.US),
        SimpleDateFormat("MMM d, yyyy", Locale.US),
        SimpleDateFormat("MMM d yyyy", Locale.US),
        SimpleDateFormat("M/d/yyyy 'at' h:mm a", Locale.US),
        SimpleDateFormat("M/d/yyyy 'at' h:mma", Locale.US),
        SimpleDateFormat("M/d/yyyy", Locale.US),
        SimpleDateFormat("yyyy-MM-dd 'at' HH:mm", Locale.US),
        SimpleDateFormat("yyyy-MM-dd 'at' h:mm a", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    ).onEach { it.isLenient = false }

    // Date format parsers (without year - will inject current/next year)
    private val DATE_FORMATS_NO_YEAR = listOf(
        SimpleDateFormat("MMMM d 'at' h:mm a", Locale.US),
        SimpleDateFormat("MMMM d 'at' h:mma", Locale.US),
        SimpleDateFormat("MMMM d", Locale.US),
        SimpleDateFormat("MMM d 'at' h:mm a", Locale.US),
        SimpleDateFormat("MMM d 'at' h:mma", Locale.US),
        SimpleDateFormat("MMM d", Locale.US),
        SimpleDateFormat("M/d 'at' h:mm a", Locale.US),
        SimpleDateFormat("M/d 'at' h:mma", Locale.US),
        SimpleDateFormat("M/d", Locale.US)
    ).onEach { it.isLenient = false }

    // Time-only format parsers
    private val TIME_ONLY_FORMATS = listOf(
        SimpleDateFormat("'at' h:mm a", Locale.US),
        SimpleDateFormat("'at' h:mma", Locale.US),
        SimpleDateFormat("'at' h a", Locale.US),
        SimpleDateFormat("'at' ha", Locale.US),
        SimpleDateFormat("'at' HH:mm", Locale.US),
        SimpleDateFormat("h:mm a", Locale.US),
        SimpleDateFormat("h:mma", Locale.US),
        SimpleDateFormat("h a", Locale.US),
        SimpleDateFormat("ha", Locale.US),
        SimpleDateFormat("HH:mm", Locale.US)
    ).onEach { it.isLenient = false }

    /**
     * Detects all dates in the given text
     * @return List of detected dates sorted by their position in the text
     */
    fun detectDates(text: String): List<DetectedDate> {
        val detectedDates = mutableListOf<DetectedDate>()
        val coveredRanges = mutableListOf<IntRange>()

        DATE_PATTERNS.forEachIndexed { patternIndex, pattern ->
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val startIndex = matcher.start()
                val endIndex = matcher.end()

                // Check if this range overlaps with already detected dates
                val overlaps = coveredRanges.any { range ->
                    startIndex < range.last && endIndex > range.first
                }

                if (!overlaps) {
                    val matchedText = matcher.group()
                    val isTimeOnly = patternIndex >= TIME_ONLY_PATTERN_START_INDEX
                    val isRelativeDate = patternIndex in RELATIVE_DATE_PATTERN_START_INDEX until TIME_ONLY_PATTERN_START_INDEX

                    val parsedDate = when {
                        isRelativeDate -> parseRelativeDate(matchedText)
                        else -> parseDate(matchedText, isTimeOnly)
                    }

                    if (parsedDate != null) {
                        val hasTime = matchedText.lowercase().contains("at ") ||
                                      matchedText.contains(Regex("\\d{1,2}:\\d{2}")) ||
                                      matchedText.contains(Regex("\\d{1,2}\\s*[APap][Mm]"))

                        detectedDates.add(
                            DetectedDate(
                                startIndex = startIndex,
                                endIndex = endIndex,
                                matchedText = matchedText,
                                parsedDate = parsedDate,
                                hasTime = hasTime,
                                isTimeOnly = isTimeOnly
                            )
                        )
                        coveredRanges.add(startIndex until endIndex)
                    }
                }
            }
        }

        return detectedDates.sortedBy { it.startIndex }
    }

    /**
     * Parses a date string into a Calendar object
     * @param isTimeOnly If true, the string only contains a time (no date)
     */
    private fun parseDate(dateString: String, isTimeOnly: Boolean = false): Calendar? {
        // Normalize the string for parsing
        val normalized = normalizeForParsing(dateString)

        // Try time-only parsing first if flagged
        if (isTimeOnly) {
            return parseTimeOnly(normalized) ?: parseTimeOnly(dateString)
        }

        // Try formats with year first
        tryParseWithFormats(normalized, DATE_FORMATS_WITH_YEAR)?.let { return it }
        tryParseWithFormats(dateString, DATE_FORMATS_WITH_YEAR)?.let { return it }

        // Try formats without year (will inject appropriate year)
        parseDateWithoutYear(normalized)?.let { return it }
        parseDateWithoutYear(dateString)?.let { return it }

        // Finally try time-only as fallback
        return parseTimeOnly(normalized) ?: parseTimeOnly(dateString)
    }

    /**
     * Normalizes a date string for parsing
     */
    private fun normalizeForParsing(dateString: String): String {
        return dateString
            .replace(Regex("\\s+"), " ")
            // Remove ordinal suffixes (1st, 2nd, 3rd, 4th, etc.)
            .replace(Regex("(\\d)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)) { it.groupValues[1] }
            // Normalize AM/PM spacing
            .replace(Regex("(\\d)(AM|PM)", RegexOption.IGNORE_CASE)) {
                "${it.groupValues[1]} ${it.groupValues[2].uppercase()}"
            }
            .trim()
    }

    /**
     * Try to parse with a list of formats
     */
    private fun tryParseWithFormats(dateString: String, formats: List<SimpleDateFormat>): Calendar? {
        for (format in formats) {
            try {
                val date = format.parse(dateString)
                if (date != null) {
                    return Calendar.getInstance().apply { time = date }
                }
            } catch (_: Exception) {
                // Try next format
            }
        }
        return null
    }

    /**
     * Parses a date without year, injecting current or next year as appropriate
     */
    private fun parseDateWithoutYear(dateString: String): Calendar? {
        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR)

        for (format in DATE_FORMATS_NO_YEAR) {
            try {
                val date = format.parse(dateString)
                if (date != null) {
                    val parsedCal = Calendar.getInstance().apply { time = date }

                    // Set to current year initially
                    parsedCal.set(Calendar.YEAR, currentYear)

                    // If the date has already passed this year, use next year
                    if (parsedCal.before(now)) {
                        // Check if it's within a reasonable window (don't bump dates from yesterday to next year)
                        val daysDiff = (now.timeInMillis - parsedCal.timeInMillis) / (1000 * 60 * 60 * 24)
                        if (daysDiff > 7) {
                            parsedCal.set(Calendar.YEAR, currentYear + 1)
                        }
                    }

                    return parsedCal
                }
            } catch (_: Exception) {
                // Try next format
            }
        }
        return null
    }

    /**
     * Parses time-only strings, using today or tomorrow based on whether the time has passed
     */
    private fun parseTimeOnly(timeString: String): Calendar? {
        val now = Calendar.getInstance()

        for (format in TIME_ONLY_FORMATS) {
            try {
                val date = format.parse(timeString)
                if (date != null) {
                    val parsedCal = Calendar.getInstance().apply { time = date }

                    // Create a calendar for today with the parsed time
                    val result = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, parsedCal.get(Calendar.HOUR_OF_DAY))
                        set(Calendar.MINUTE, parsedCal.get(Calendar.MINUTE))
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }

                    // If this time has already passed today, use tomorrow
                    if (result.before(now)) {
                        result.add(Calendar.DAY_OF_MONTH, 1)
                    }

                    return result
                }
            } catch (_: Exception) {
                // Try next format
            }
        }
        return null
    }

    /**
     * Parses relative date expressions like "tomorrow", "next week", "in 3 days"
     * Also handles combined patterns like "at 2pm tomorrow"
     */
    private fun parseRelativeDate(dateString: String): Calendar? {
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

    /**
     * Checks if the given text contains any detectable dates
     */
    fun containsDates(text: String): Boolean {
        return detectDates(text).isNotEmpty()
    }
}
