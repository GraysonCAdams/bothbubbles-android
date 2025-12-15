package com.bothbubbles.services.auto

/**
 * Utility functions for Android Auto features.
 */
object AutoUtils {

    /**
     * Map of iMessage reaction verbs to their corresponding emoji.
     * Used to convert reaction text (e.g., "Loved an image") to emoji format (e.g., "❤️ an image").
     */
    private val reactionEmojiMap = mapOf(
        "Loved" to "❤️",
        "Liked" to "👍",
        "Disliked" to "👎",
        "Laughed at" to "😂",
        "Emphasized" to "‼️",
        "Questioned" to "❓",
        // Also handle lowercase variants
        "loved" to "❤️",
        "liked" to "👍",
        "disliked" to "👎",
        "laughed at" to "😂",
        "emphasized" to "‼️",
        "questioned" to "❓"
    )

    /**
     * Parses reaction text and replaces verb prefixes with emoji.
     *
     * Examples:
     * - "Loved an image" -> "❤️ an image"
     * - "Liked a message" -> "👍 a message"
     * - "Laughed at \"hello\"" -> "😂 \"hello\""
     *
     * @param text The message text to parse
     * @return The text with reaction verbs replaced by emoji, or the original text if no reaction found
     */
    fun parseReactionText(text: String?): String {
        if (text.isNullOrEmpty()) return ""

        var parsed: String = text
        for ((verb, emoji) in reactionEmojiMap) {
            if (parsed.startsWith("$verb ")) {
                parsed = parsed.replaceFirst("$verb ", "$emoji ")
                break
            }
        }
        return parsed
    }

    /**
     * Checks if the message text represents a reaction.
     *
     * @param text The message text to check
     * @return true if the text starts with a known reaction verb
     */
    fun isReactionMessage(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return reactionEmojiMap.keys.any { verb -> text.startsWith("$verb ") }
    }
}
