package com.bothbubbles.util.text

import java.text.Normalizer

/**
 * Utilities for text normalization in search.
 * Supports diacritic-insensitive matching (e.g., "cafe" matches "café").
 */
object TextNormalization {

    // Pre-compiled regex patterns for performance
    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Normalize text for search by:
     * 1. Converting to lowercase
     * 2. Removing diacritics (accents)
     * 3. Normalizing whitespace
     *
     * Examples:
     * - "Café" → "cafe"
     * - "naïve" → "naive"
     * - "résumé" → "resume"
     * - "Hello   World" → "hello world"
     */
    fun normalizeForSearch(text: String): String {
        if (text.isEmpty()) return text

        return Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    /**
     * Check if text contains query with normalization.
     * Both text and query are normalized before comparison.
     */
    fun containsNormalized(text: String?, query: String): Boolean {
        if (text.isNullOrEmpty() || query.isEmpty()) return false
        return normalizeForSearch(text).contains(normalizeForSearch(query))
    }

    /**
     * Find all indices where normalized query appears in normalized text.
     * Returns indices in the original (non-normalized) text.
     *
     * Note: Due to normalization, the indices may not perfectly align
     * with original text positions when diacritics are involved.
     * Use findMatchRanges() for more accurate highlighting.
     */
    fun findNormalizedMatches(text: String, query: String): List<Int> {
        if (text.isEmpty() || query.isEmpty()) return emptyList()

        val normalizedText = normalizeForSearch(text)
        val normalizedQuery = normalizeForSearch(query)

        val matches = mutableListOf<Int>()
        var startIndex = 0

        while (startIndex < normalizedText.length) {
            val matchIndex = normalizedText.indexOf(normalizedQuery, startIndex)
            if (matchIndex == -1) break

            matches.add(matchIndex)
            startIndex = matchIndex + 1
        }

        return matches
    }

    /**
     * Find match ranges in the original text that correspond to normalized matches.
     * This handles cases where diacritics change the character count.
     *
     * @return List of IntRange representing [start, end) positions in original text
     */
    fun findMatchRanges(text: String, query: String): List<IntRange> {
        if (text.isEmpty() || query.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        val normalizedQuery = normalizeForSearch(query)
        val queryLength = query.length

        var searchStart = 0
        while (searchStart < text.length) {
            // Try exact match first (faster)
            var matchIndex = lowerText.indexOf(lowerQuery, searchStart)

            if (matchIndex != -1) {
                ranges.add(matchIndex until matchIndex + queryLength)
                searchStart = matchIndex + 1
                continue
            }

            // Try normalized match (handles diacritics)
            matchIndex = findNormalizedMatchStart(text, searchStart, normalizedQuery)
            if (matchIndex != -1) {
                // Find the end position by matching character by character
                val endIndex = findNormalizedMatchEnd(text, matchIndex, normalizedQuery)
                if (endIndex > matchIndex) {
                    ranges.add(matchIndex until endIndex)
                    searchStart = matchIndex + 1
                    continue
                }
            }

            break
        }

        return ranges
    }

    /**
     * Find where a normalized query starts in the original text.
     */
    private fun findNormalizedMatchStart(text: String, fromIndex: Int, normalizedQuery: String): Int {
        for (i in fromIndex until text.length) {
            val remainingText = text.substring(i)
            val normalizedRemaining = normalizeForSearch(remainingText)
            if (normalizedRemaining.startsWith(normalizedQuery)) {
                return i
            }
        }
        return -1
    }

    /**
     * Find where a normalized query ends in the original text.
     */
    private fun findNormalizedMatchEnd(text: String, startIndex: Int, normalizedQuery: String): Int {
        var matchedLength = 0
        var textIndex = startIndex

        while (textIndex < text.length && matchedLength < normalizedQuery.length) {
            val char = text[textIndex]
            val normalizedChar = normalizeForSearch(char.toString())

            if (normalizedChar.isEmpty()) {
                // Diacritic mark, skip it
                textIndex++
                continue
            }

            if (normalizedQuery[matchedLength] == normalizedChar[0]) {
                matchedLength++
                textIndex++
            } else {
                break
            }
        }

        return if (matchedLength == normalizedQuery.length) textIndex else startIndex
    }
}
