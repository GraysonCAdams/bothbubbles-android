package com.bothbubbles.ui.components.common

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
 * Builds an AnnotatedString with search query matches highlighted.
 */
@Composable
fun buildSearchHighlightedText(
    text: String,
    searchQuery: String,
    textColor: Color,
    detectedDates: List<DetectedDate> = emptyList()
): AnnotatedString {
    val highlightColor = Color(0xFFFFEB3B) // Yellow highlight
    // Use dark text on yellow highlight for readability in both light and dark mode
    val highlightTextColor = Color(0xFF1C1C1C)

    return buildAnnotatedString {
        var currentIndex = 0
        val lowerText = text.lowercase()
        val lowerQuery = searchQuery.lowercase()

        while (currentIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)

            if (matchIndex == -1) {
                // No more matches, append remaining text
                append(text.substring(currentIndex))
                break
            }

            // Append text before the match
            if (matchIndex > currentIndex) {
                append(text.substring(currentIndex, matchIndex))
            }

            // Append the highlighted match
            withStyle(
                SpanStyle(
                    background = highlightColor,
                    color = highlightTextColor
                )
            ) {
                append(text.substring(matchIndex, matchIndex + searchQuery.length))
            }

            currentIndex = matchIndex + searchQuery.length
        }
    }
}
