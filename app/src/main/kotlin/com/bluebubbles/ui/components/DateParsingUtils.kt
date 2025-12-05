package com.bluebubbles.ui.components

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
    val hasTime: Boolean
)

/**
 * Utility object for detecting and parsing dates from message text
 */
object DateParsingUtils {

    // Common date patterns to match
    private val DATE_PATTERNS = listOf(
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
        )
    )

    // Date format parsers
    private val DATE_FORMATS = listOf(
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

    /**
     * Detects all dates in the given text
     * @return List of detected dates sorted by their position in the text
     */
    fun detectDates(text: String): List<DetectedDate> {
        val detectedDates = mutableListOf<DetectedDate>()
        val coveredRanges = mutableListOf<IntRange>()

        for (pattern in DATE_PATTERNS) {
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
                    val parsedDate = parseDate(matchedText)

                    if (parsedDate != null) {
                        val hasTime = matchedText.lowercase().contains("at ") ||
                                      matchedText.contains(Regex("\\d{1,2}:\\d{2}"))

                        detectedDates.add(
                            DetectedDate(
                                startIndex = startIndex,
                                endIndex = endIndex,
                                matchedText = matchedText,
                                parsedDate = parsedDate,
                                hasTime = hasTime
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
     */
    private fun parseDate(dateString: String): Calendar? {
        // Normalize the string for parsing
        val normalized = dateString
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(\\d)(AM|PM)", RegexOption.IGNORE_CASE)) {
                "${it.groupValues[1]} ${it.groupValues[2].uppercase()}"
            }
            .trim()

        for (format in DATE_FORMATS) {
            try {
                val date = format.parse(normalized)
                if (date != null) {
                    return Calendar.getInstance().apply { time = date }
                }
            } catch (_: Exception) {
                // Try next format
            }
        }

        // Try with original string
        for (format in DATE_FORMATS) {
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
     * Checks if the given text contains any detectable dates
     */
    fun containsDates(text: String): Boolean {
        return detectDates(text).isNotEmpty()
    }
}
