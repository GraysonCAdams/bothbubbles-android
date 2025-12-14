package com.bothbubbles.ui.components.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.bothbubbles.util.parsing.DetectedCode
import com.bothbubbles.util.parsing.DetectedDate
import com.bothbubbles.util.parsing.DetectedPhoneNumber
import com.bothbubbles.util.text.TextNormalization

/**
 * Utility functions for building AnnotatedStrings with clickable elements.
 * Extracted from MessageBubble.kt for reuse across the codebase.
 */

/**
 * Represents a clickable span with its position and type.
 * Used to merge and sort different types of clickable elements.
 */
sealed class ClickableSpan(
    open val startIndex: Int,
    open val endIndex: Int,
    open val matchedText: String,
    open val index: Int
) {
    data class DateSpan(
        override val startIndex: Int,
        override val endIndex: Int,
        override val matchedText: String,
        override val index: Int
    ) : ClickableSpan(startIndex, endIndex, matchedText, index)

    data class PhoneSpan(
        override val startIndex: Int,
        override val endIndex: Int,
        override val matchedText: String,
        override val index: Int
    ) : ClickableSpan(startIndex, endIndex, matchedText, index)

    data class CodeSpan(
        override val startIndex: Int,
        override val endIndex: Int,
        override val matchedText: String,
        override val index: Int
    ) : ClickableSpan(startIndex, endIndex, matchedText, index)
}

/**
 * Builds an AnnotatedString with underlined clickable dates.
 */
@Composable
fun buildAnnotatedStringWithDates(
    text: String,
    detectedDates: List<DetectedDate>,
    textColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var lastIndex = 0

        detectedDates.forEachIndexed { index, date ->
            // Add text before the date
            if (date.startIndex > lastIndex) {
                append(text.substring(lastIndex, date.startIndex))
            }

            // Add the date with underline style and annotation
            pushStringAnnotation(tag = "DATE", annotation = index.toString())
            withStyle(
                SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    color = textColor
                )
            ) {
                append(date.matchedText)
            }
            pop()

            lastIndex = date.endIndex
        }

        // Add remaining text after last date
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

/**
 * Builds an AnnotatedString with all clickable elements: dates, phone numbers, and codes.
 */
@Composable
fun buildAnnotatedStringWithClickables(
    text: String,
    detectedDates: List<DetectedDate>,
    detectedPhoneNumbers: List<DetectedPhoneNumber>,
    detectedCodes: List<DetectedCode>,
    textColor: Color
): AnnotatedString {
    // Combine all clickable spans and sort by position
    val allSpans = mutableListOf<ClickableSpan>()

    detectedDates.forEachIndexed { index, date ->
        allSpans.add(ClickableSpan.DateSpan(date.startIndex, date.endIndex, date.matchedText, index))
    }
    detectedPhoneNumbers.forEachIndexed { index, phone ->
        allSpans.add(ClickableSpan.PhoneSpan(phone.startIndex, phone.endIndex, phone.matchedText, index))
    }
    detectedCodes.forEachIndexed { index, code ->
        allSpans.add(ClickableSpan.CodeSpan(code.startIndex, code.endIndex, code.matchedText, index))
    }

    // Sort by start index and filter overlapping spans (prefer dates > phones > codes)
    val sortedSpans = allSpans.sortedBy { it.startIndex }
    val filteredSpans = mutableListOf<ClickableSpan>()
    var lastEndIndex = 0

    for (span in sortedSpans) {
        if (span.startIndex >= lastEndIndex) {
            filteredSpans.add(span)
            lastEndIndex = span.endIndex
        }
    }

    return buildAnnotatedString {
        var currentIndex = 0

        for (span in filteredSpans) {
            // Add text before the clickable element
            if (span.startIndex > currentIndex) {
                append(text.substring(currentIndex, span.startIndex))
            }

            // Add the clickable element with underline style and annotation
            val tag = when (span) {
                is ClickableSpan.DateSpan -> "DATE"
                is ClickableSpan.PhoneSpan -> "PHONE"
                is ClickableSpan.CodeSpan -> "CODE"
            }

            pushStringAnnotation(tag = tag, annotation = span.index.toString())
            withStyle(
                SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    color = textColor
                )
            ) {
                append(span.matchedText)
            }
            pop()

            currentIndex = span.endIndex
        }

        // Add remaining text after last clickable element
        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}

/**
 * Builds an AnnotatedString with search query matches highlighted using MD3 dynamic colors.
 *
 * @param text The text to search within
 * @param searchQuery The query to highlight
 * @param textColor The base text color (unused but kept for API compatibility)
 * @param isCurrentMatch True for the focused match (uses tertiaryContainer),
 *                       false for other matches (uses secondaryContainer)
 * @param detectedDates Optional list of detected dates (currently unused)
 */
@Composable
fun buildSearchHighlightedText(
    text: String,
    searchQuery: String,
    textColor: Color,
    isCurrentMatch: Boolean = false,
    detectedDates: List<DetectedDate> = emptyList()
): AnnotatedString {
    // MD3 dynamic colors for search highlighting
    val currentMatchBackground = MaterialTheme.colorScheme.tertiaryContainer
    val currentMatchText = MaterialTheme.colorScheme.onTertiaryContainer
    val otherMatchBackground = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    val otherMatchText = MaterialTheme.colorScheme.onSecondaryContainer

    val highlightColor = if (isCurrentMatch) currentMatchBackground else otherMatchBackground
    val highlightTextColor = if (isCurrentMatch) currentMatchText else otherMatchText

    // Use TextNormalization for diacritic-insensitive matching (e.g., "cafe" matches "café")
    val matchRanges = TextNormalization.findMatchRanges(text, searchQuery)

    return buildAnnotatedString {
        var currentIndex = 0

        for (range in matchRanges) {
            // Append text before the match
            if (range.first > currentIndex) {
                append(text.substring(currentIndex, range.first))
            }

            // Append the highlighted match
            withStyle(
                SpanStyle(
                    background = highlightColor,
                    color = highlightTextColor
                )
            ) {
                append(text.substring(range.first, range.last))
            }

            currentIndex = range.last
        }

        // Append remaining text after last match
        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}
