package com.bothbubbles.ui.chat.composer

import com.bothbubbles.core.network.api.dto.AttributedBodyDto
import com.bothbubbles.core.network.api.dto.AttributedRunDto

/**
 * Builds AttributedBodyDto for sending messages with mentions via BlueBubbles API.
 *
 * The attributedBody format follows Apple's NSAttributedString structure:
 * - `string`: The plain text of the message
 * - `runs`: Array of ranges with attributes
 *
 * For mentions, the key attribute is `__kIMMessagePartAttributeName` which contains
 * the mentioned person's address (phone number or email).
 */
object AttributedBodyBuilder {

    // The attribute key used by iMessage for mentions
    private const val MENTION_ATTRIBUTE_KEY = "__kIMMessagePartAttributeName"

    /**
     * Build an AttributedBodyDto from text and mentions.
     *
     * @param text The message text
     * @param mentions List of mentions in the text
     * @return AttributedBodyDto if there are mentions, null otherwise
     */
    fun build(text: String, mentions: List<MentionSpan>): AttributedBodyDto? {
        if (mentions.isEmpty()) {
            return null
        }

        // Sort mentions by start index to process in order
        val sortedMentions = mentions.sortedBy { it.startIndex }

        // Validate mentions are within bounds
        val validMentions = sortedMentions.filter { mention ->
            mention.startIndex >= 0 &&
                mention.endIndex <= text.length &&
                mention.length > 0
        }

        if (validMentions.isEmpty()) {
            return null
        }

        // Build runs for each mention
        val runs = validMentions.map { mention ->
            AttributedRunDto(
                range = listOf(mention.startIndex, mention.length),
                attributes = mapOf(
                    MENTION_ATTRIBUTE_KEY to mention.participantAddress
                )
            )
        }

        return AttributedBodyDto(
            string = text,
            runs = runs
        )
    }

    /**
     * Parse mentions from a received message's attributedBody.
     *
     * @param attributedBody The raw attributedBody data from the API
     * @return List of parsed mentions
     */
    @Suppress("UNCHECKED_CAST")
    fun parseMentions(attributedBody: List<Map<String, Any>>?): List<ParsedMention> {
        if (attributedBody.isNullOrEmpty()) {
            return emptyList()
        }

        val mentions = mutableListOf<ParsedMention>()

        for (part in attributedBody) {
            // Each part has "string" and "runs"
            val runs = part["runs"] as? List<Map<String, Any>> ?: continue

            for (run in runs) {
                val attributes = run["attributes"] as? Map<String, Any> ?: continue
                val mentionedAddress = attributes[MENTION_ATTRIBUTE_KEY] as? String ?: continue

                // Get the range [startIndex, length]
                val range = run["range"] as? List<Number> ?: continue
                if (range.size != 2) continue

                val startIndex = range[0].toInt()
                val length = range[1].toInt()

                if (startIndex >= 0 && length > 0) {
                    mentions.add(
                        ParsedMention(
                            startIndex = startIndex,
                            length = length,
                            mentionedAddress = mentionedAddress
                        )
                    )
                }
            }
        }

        return mentions
    }

    /**
     * Parse mentions from an already-parsed AttributedBodyDto.
     *
     * @param attributedBody The parsed DTO
     * @return List of parsed mentions
     */
    fun parseMentionsFromDto(attributedBody: AttributedBodyDto?): List<ParsedMention> {
        if (attributedBody == null) {
            return emptyList()
        }

        return attributedBody.runs.mapNotNull { run ->
            val mentionedAddress = run.attributes[MENTION_ATTRIBUTE_KEY] as? String
                ?: return@mapNotNull null

            if (run.range.size != 2) return@mapNotNull null

            val startIndex = run.range[0]
            val length = run.range[1]

            if (startIndex >= 0 && length > 0) {
                ParsedMention(
                    startIndex = startIndex,
                    length = length,
                    mentionedAddress = mentionedAddress
                )
            } else {
                null
            }
        }
    }
}
