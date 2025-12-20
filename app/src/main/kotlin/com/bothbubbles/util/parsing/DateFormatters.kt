package com.bothbubbles.util.parsing

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Contains all DateTimeFormatter instances for date parsing.
 * DateTimeFormatter is thread-safe, unlike SimpleDateFormat.
 * Uses default SMART resolver style which handles common date variations.
 */
internal object DateFormatters {

    /**
     * Date format parsers that include a year component
     */
    val WITH_YEAR = listOf(
        DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mma", Locale.US),
        DateTimeFormatter.ofPattern("MMMM d yyyy 'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("MMMM d yyyy 'at' h:mma", Locale.US),
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US),
        DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.US),
        DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mma", Locale.US),
        DateTimeFormatter.ofPattern("MMM d yyyy 'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("MMM d yyyy 'at' h:mma", Locale.US),
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US),
        DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US),
        DateTimeFormatter.ofPattern("M/d/yyyy 'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("M/d/yyyy 'at' h:mma", Locale.US),
        DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    )

    /**
     * Date format parsers without a year component
     * Will require injecting current or next year during parsing
     */
    val WITHOUT_YEAR = listOf(
        DateTimeFormatter.ofPattern("MMMM d 'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("MMMM d 'at' h:mma", Locale.US),
        DateTimeFormatter.ofPattern("MMMM d", Locale.US),
        DateTimeFormatter.ofPattern("MMM d 'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("MMM d 'at' h:mma", Locale.US),
        DateTimeFormatter.ofPattern("MMM d", Locale.US),
        DateTimeFormatter.ofPattern("M/d 'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("M/d 'at' h:mma", Locale.US),
        DateTimeFormatter.ofPattern("M/d", Locale.US)
    )

    /**
     * Time-only format parsers
     * Will require injecting today or tomorrow during parsing
     */
    val TIME_ONLY = listOf(
        DateTimeFormatter.ofPattern("'at' h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("'at' h:mma", Locale.US),
        DateTimeFormatter.ofPattern("'at' h a", Locale.US),
        DateTimeFormatter.ofPattern("'at' ha", Locale.US),
        DateTimeFormatter.ofPattern("'at' HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("h:mma", Locale.US),
        DateTimeFormatter.ofPattern("h a", Locale.US),
        DateTimeFormatter.ofPattern("ha", Locale.US),
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    )
}
