package com.bothbubbles.util.parsing

import java.text.SimpleDateFormat
import java.util.*

/**
 * Parses absolute date strings (with or without year, time-only)
 * Handles date normalization and smart year injection for dates without explicit years
 */
internal object AbsoluteDateParser {

    /**
     * Parses a date string into a Calendar object
     * @param dateString The date string to parse
     * @param isTimeOnly If true, the string only contains a time (no date)
     * @return Calendar object representing the parsed date, or null if parsing fails
     */
    fun parse(dateString: String, isTimeOnly: Boolean = false): Calendar? {
        // Normalize the string for parsing
        val normalized = normalizeForParsing(dateString)

        // Try time-only parsing first if flagged
        if (isTimeOnly) {
            return parseTimeOnly(normalized) ?: parseTimeOnly(dateString)
        }

        // Try formats with year first
        tryParseWithFormats(normalized, DateFormatters.WITH_YEAR)?.let { return it }
        tryParseWithFormats(dateString, DateFormatters.WITH_YEAR)?.let { return it }

        // Try formats without year (will inject appropriate year)
        parseDateWithoutYear(normalized)?.let { return it }
        parseDateWithoutYear(dateString)?.let { return it }

        // Finally try time-only as fallback
        return parseTimeOnly(normalized) ?: parseTimeOnly(dateString)
    }

    /**
     * Normalizes a date string for parsing
     * Removes extra whitespace, ordinal suffixes, and normalizes AM/PM spacing
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
     * Uses smart logic: if the date has passed this year by more than 7 days, use next year
     */
    private fun parseDateWithoutYear(dateString: String): Calendar? {
        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR)

        for (format in DateFormatters.WITHOUT_YEAR) {
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
     * If the parsed time is earlier than now, it assumes tomorrow
     */
    private fun parseTimeOnly(timeString: String): Calendar? {
        val now = Calendar.getInstance()

        for (format in DateFormatters.TIME_ONLY) {
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
}
