package com.bothbubbles.util.parsing

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAccessor
import java.util.Calendar

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
    private fun tryParseWithFormats(dateString: String, formats: List<DateTimeFormatter>): Calendar? {
        for (format in formats) {
            try {
                val temporal = format.parse(dateString)

                // Try to extract LocalDateTime if time is present
                val localDateTime = try {
                    LocalDateTime.from(temporal)
                } catch (e: Exception) {
                    // No time component, just date
                    val localDate = LocalDate.from(temporal)
                    localDate.atStartOfDay()
                }

                return Calendar.getInstance().apply {
                    timeInMillis = localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            } catch (_: DateTimeParseException) {
                // Try next format
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
        val now = LocalDateTime.now()
        val currentYear = now.year

        for (format in DateFormatters.WITHOUT_YEAR) {
            try {
                val temporal = format.parse(dateString)

                // Try to extract LocalDateTime if time is present
                val parsedDateTime = try {
                    // Has time component
                    val time = LocalTime.from(temporal)
                    val monthDay = try {
                        java.time.MonthDay.from(temporal)
                    } catch (e: Exception) {
                        continue
                    }
                    LocalDateTime.of(currentYear, monthDay.month, monthDay.dayOfMonth, time.hour, time.minute)
                } catch (e: Exception) {
                    // No time component, just date
                    val monthDay = try {
                        java.time.MonthDay.from(temporal)
                    } catch (e: Exception) {
                        continue
                    }
                    LocalDateTime.of(currentYear, monthDay.month, monthDay.dayOfMonth, 0, 0)
                }

                // If the date has already passed this year by more than 7 days, use next year
                var finalDateTime = parsedDateTime
                if (parsedDateTime.isBefore(now)) {
                    val daysDiff = java.time.Duration.between(parsedDateTime, now).toDays()
                    if (daysDiff > 7) {
                        finalDateTime = parsedDateTime.plusYears(1)
                    }
                }

                return Calendar.getInstance().apply {
                    timeInMillis = finalDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            } catch (_: DateTimeParseException) {
                // Try next format
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
        val now = LocalDateTime.now()

        for (format in DateFormatters.TIME_ONLY) {
            try {
                val temporal = format.parse(timeString)
                val time = LocalTime.from(temporal)

                // Create a datetime for today with the parsed time
                var result = LocalDateTime.of(now.toLocalDate(), time)

                // If this time has already passed today, use tomorrow
                if (result.isBefore(now)) {
                    result = result.plusDays(1)
                }

                return Calendar.getInstance().apply {
                    timeInMillis = result.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            } catch (_: DateTimeParseException) {
                // Try next format
            } catch (_: Exception) {
                // Try next format
            }
        }
        return null
    }
}
