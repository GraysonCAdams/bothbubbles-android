package com.bothbubbles.util.parsing

import java.text.SimpleDateFormat
import java.util.*

/**
 * Contains all SimpleDateFormat instances for date parsing
 * All formats are configured to be non-lenient
 */
internal object DateFormatters {

    /**
     * Date format parsers that include a year component
     */
    val WITH_YEAR = listOf(
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
     * Date format parsers without a year component
     * Will require injecting current or next year during parsing
     */
    val WITHOUT_YEAR = listOf(
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

    /**
     * Time-only format parsers
     * Will require injecting today or tomorrow during parsing
     */
    val TIME_ONLY = listOf(
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
}
