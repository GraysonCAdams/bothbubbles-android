package com.bothbubbles.util

import com.bothbubbles.ui.components.message.EmojiAnalysis

/**
 * Utility functions for emoji analysis.
 * Extracted from MessageBubble.kt for reuse across the codebase.
 */
object EmojiUtils {

    /**
     * Analyzes text to determine if it contains only emojis and counts them.
     * Uses Unicode emoji ranges and variation selectors.
     * Public so it can be called from ViewModel during message transformation.
     */
    fun analyzeEmojis(text: String?): EmojiAnalysis {
        if (text.isNullOrBlank()) return EmojiAnalysis(false, 0)

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return EmojiAnalysis(false, 0)

        var emojiCount = 0
        var i = 0

        while (i < trimmed.length) {
            val codePoint = trimmed.codePointAt(i)
            val charCount = Character.charCount(codePoint)

            if (isEmojiCodePoint(codePoint)) {
                emojiCount++
                i += charCount
                // Skip variation selectors and zero-width joiners following emojis
                while (i < trimmed.length) {
                    val nextCodePoint = trimmed.codePointAt(i)
                    if (isVariationSelector(nextCodePoint) || isZeroWidthJoiner(nextCodePoint) || isSkinToneModifier(nextCodePoint)) {
                        i += Character.charCount(nextCodePoint)
                    } else if (isEmojiCodePoint(nextCodePoint)) {
                        // Part of a compound emoji (e.g., family emoji)
                        // Don't count separately, just skip
                        i += Character.charCount(nextCodePoint)
                    } else {
                        break
                    }
                }
            } else if (Character.isWhitespace(codePoint)) {
                // Whitespace is allowed between emojis
                i += charCount
            } else {
                // Non-emoji, non-whitespace character found
                return EmojiAnalysis(false, 0)
            }
        }

        return EmojiAnalysis(emojiCount > 0, emojiCount)
    }

    /**
     * Check if a code point represents an emoji character.
     */
    fun isEmojiCodePoint(codePoint: Int): Boolean {
        return when {
            // Emoticons
            codePoint in 0x1F600..0x1F64F -> true
            // Miscellaneous Symbols and Pictographs
            codePoint in 0x1F300..0x1F5FF -> true
            // Transport and Map Symbols
            codePoint in 0x1F680..0x1F6FF -> true
            // Supplemental Symbols and Pictographs
            codePoint in 0x1F900..0x1F9FF -> true
            // Symbols and Pictographs Extended-A
            codePoint in 0x1FA00..0x1FA6F -> true
            // Symbols and Pictographs Extended-B
            codePoint in 0x1FA70..0x1FAFF -> true
            // Dingbats
            codePoint in 0x2700..0x27BF -> true
            // Miscellaneous Symbols
            codePoint in 0x2600..0x26FF -> true
            // Regional Indicator Symbols (flags)
            codePoint in 0x1F1E0..0x1F1FF -> true
            // Various common emoji
            codePoint in 0x2300..0x23FF -> true // Miscellaneous Technical
            codePoint in 0x2B50..0x2B55 -> true // Stars, circles
            codePoint == 0x00A9 || codePoint == 0x00AE -> true // (c) (r)
            codePoint == 0x2122 -> true // TM
            codePoint in 0x203C..0x3299 -> true // Various symbols
            else -> false
        }
    }

    /**
     * Check if a code point is a variation selector (used to modify emoji presentation).
     */
    fun isVariationSelector(codePoint: Int): Boolean {
        return codePoint in 0xFE00..0xFE0F || codePoint == 0x20E3
    }

    /**
     * Check if a code point is a zero-width joiner (used in compound emojis).
     */
    fun isZeroWidthJoiner(codePoint: Int): Boolean {
        return codePoint == 0x200D
    }

    /**
     * Check if a code point is a skin tone modifier.
     */
    fun isSkinToneModifier(codePoint: Int): Boolean {
        return codePoint in 0x1F3FB..0x1F3FF
    }
}

// Extension function for convenient calling
fun String?.analyzeEmojis(): EmojiAnalysis = EmojiUtils.analyzeEmojis(this)
