package com.bothbubbles.util.parsing

import java.util.regex.Pattern

/**
 * Contains all regex patterns for date detection
 * Patterns are ordered from most specific to least specific
 */
internal object DatePatterns {

    /**
     * Index ranges for special pattern types
     */
    // 8 patterns with year (0-7) + 6 patterns without year (8-13) = 14 is start of relative
    const val RELATIVE_DATE_PATTERN_START_INDEX = 14
    // 6 new combined patterns (14-19) + 14 original relative patterns (20-33) = 34 is start of time-only
    const val TIME_ONLY_PATTERN_START_INDEX = 34

    /**
     * All date patterns ordered from most specific to least specific
     */
    val ALL_PATTERNS = listOf(
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
}
