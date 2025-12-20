package com.bothbubbles.util.parsing

import java.util.Calendar

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
 *
 * This is the main entry point for date detection. It delegates to specialized parsers:
 * - [DatePatterns] for regex pattern matching
 * - [AbsoluteDateParser] for parsing dates with/without year and time-only
 * - [RelativeDateParser] for parsing relative dates like "tomorrow", "next week"
 */
object DateParsingUtils {

    /**
     * Detects all dates in the given text
     * @param text The text to search for dates
     * @return List of detected dates sorted by their position in the text
     */
    fun detectDates(text: String): List<DetectedDate> {
        val detectedDates = mutableListOf<DetectedDate>()
        val coveredRanges = mutableListOf<IntRange>()

        DatePatterns.ALL_PATTERNS.forEachIndexed { patternIndex, pattern ->
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
                    val isTimeOnly = patternIndex >= DatePatterns.TIME_ONLY_PATTERN_START_INDEX
                    val isRelativeDate = patternIndex in DatePatterns.RELATIVE_DATE_PATTERN_START_INDEX until DatePatterns.TIME_ONLY_PATTERN_START_INDEX

                    val parsedDate = when {
                        isRelativeDate -> RelativeDateParser.parse(matchedText)
                        else -> AbsoluteDateParser.parse(matchedText, isTimeOnly)
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
     * Checks if the given text contains any detectable dates
     * @param text The text to check
     * @return true if any dates are detected, false otherwise
     */
    fun containsDates(text: String): Boolean {
        return detectDates(text).isNotEmpty()
    }
}
