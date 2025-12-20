package com.bothbubbles.util.parsing

import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolveStyle
import java.util.Locale

/**
 * Contains all DateTimeFormatter instances for date parsing
 * All formats are configured to be non-lenient (strict)
 * DateTimeFormatter is thread-safe, unlike SimpleDateFormat
 */
internal object DateFormatters {

    /**
     * Date format parsers that include a year component
     */
    val WITH_YEAR = listOf(
        DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMMM d yyyy 'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMMM d yyyy 'at' h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMM d yyyy 'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMM d yyyy 'at' h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("M/d/yyyy 'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("M/d/yyyy 'at' h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH:mm", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withResolverStyle(ResolveStyle.STRICT)
    )

    /**
     * Date format parsers without a year component
     * Will require injecting current or next year during parsing
     */
    val WITHOUT_YEAR = listOf(
        DateTimeFormatter.ofPattern("MMMM d 'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMMM d 'at' h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMMM d", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMM d 'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMM d 'at' h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("MMM d", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("M/d 'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("M/d 'at' h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("M/d", Locale.US).withResolverStyle(ResolveStyle.STRICT)
    )

    /**
     * Time-only format parsers
     * Will require injecting today or tomorrow during parsing
     */
    val TIME_ONLY = listOf(
        DateTimeFormatter.ofPattern("'at' h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("'at' h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("'at' h a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("'at' ha", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("'at' HH:mm", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("h:mm a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("h:mma", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("h a", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("ha", Locale.US).withResolverStyle(ResolveStyle.STRICT),
        DateTimeFormatter.ofPattern("HH:mm", Locale.US).withResolverStyle(ResolveStyle.STRICT)
    )
}
