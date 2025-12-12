package com.bothbubbles.data.local.db.entity

/**
 * Centralized logic for detecting iMessage reactions/tapbacks.
 *
 * iMessage reactions can come in several formats:
 * - Numeric codes: "2000"-"2005" (add), "3000"-"3005" (remove)
 * - Text names: "love", "like", "dislike", "laugh", "emphasize", "question"
 * - Remove text: "-love", "-like", etc.
 * - With prefixes: "reaction/2000", "tapback/love", etc.
 *
 * This object provides both Kotlin functions for use in code and SQL fragments
 * for use in Room @Query annotations, ensuring consistency across all detection.
 */
object ReactionClassifier {

    /**
     * List of reaction text names (add operations).
     */
    val REACTION_NAMES = listOf("love", "like", "dislike", "laugh", "emphasize", "question")

    /**
     * List of reaction removal text names.
     */
    val REMOVAL_NAMES = REACTION_NAMES.map { "-$it" }

    /**
     * All reaction text values (add and remove).
     */
    val ALL_REACTION_TEXT_VALUES = REACTION_NAMES + REMOVAL_NAMES

    /**
     * SQL fragment for detecting reactions.
     * Use in WHERE clauses: WHERE $IS_REACTION_SQL
     *
     * Note: With the is_reaction column, use `is_reaction = 1` instead for better performance.
     */
    const val IS_REACTION_SQL = """
        associated_message_guid IS NOT NULL
        AND associated_message_type IS NOT NULL
        AND (
            associated_message_type LIKE '%200%'
            OR associated_message_type LIKE '%300%'
            OR associated_message_type LIKE '%reaction%'
            OR associated_message_type LIKE '%tapback%'
            OR associated_message_type IN ('love', 'like', 'dislike', 'laugh', 'emphasize', 'question', '-love', '-like', '-dislike', '-laugh', '-emphasize', '-question')
        )
    """

    /**
     * SQL fragment for excluding reactions (non-reaction messages only).
     * Use in WHERE clauses: WHERE $NOT_REACTION_SQL
     *
     * Note: With the is_reaction column, use `is_reaction = 0` instead for better performance.
     */
    const val NOT_REACTION_SQL = """
        NOT (
            associated_message_guid IS NOT NULL
            AND associated_message_type IS NOT NULL
            AND (
                associated_message_type LIKE '%200%'
                OR associated_message_type LIKE '%300%'
                OR associated_message_type LIKE '%reaction%'
                OR associated_message_type LIKE '%tapback%'
                OR associated_message_type IN ('love', 'like', 'dislike', 'laugh', 'emphasize', 'question', '-love', '-like', '-dislike', '-laugh', '-emphasize', '-question')
            )
        )
    """

    /**
     * Kotlin function to check if a message is a reaction.
     * Use this when creating MessageEntity instances to compute the is_reaction column value.
     *
     * @param associatedMessageGuid The GUID of the message this reaction is associated with
     * @param associatedMessageType The type of association (reaction code or name)
     * @return true if this message is a reaction, false otherwise
     */
    fun isReaction(associatedMessageGuid: String?, associatedMessageType: String?): Boolean {
        if (associatedMessageGuid == null || associatedMessageType == null) {
            return false
        }

        return associatedMessageType.contains("200") ||
                associatedMessageType.contains("300") ||
                associatedMessageType.contains("reaction") ||
                associatedMessageType.contains("tapback") ||
                associatedMessageType in ALL_REACTION_TEXT_VALUES
    }

    /**
     * Check if this is a reaction removal (as opposed to adding a reaction).
     *
     * @param associatedMessageType The type of association
     * @return true if this removes a reaction, false if it adds one
     */
    fun isRemoval(associatedMessageType: String?): Boolean {
        if (associatedMessageType == null) return false

        return associatedMessageType.contains("300") ||
                associatedMessageType.startsWith("-") ||
                associatedMessageType in REMOVAL_NAMES
    }

    /**
     * Parse the reaction type from an associatedMessageType string.
     *
     * @param associatedMessageType The type string to parse
     * @return The Tapback enum value, or null if not a valid reaction
     */
    fun parseType(associatedMessageType: String?): Tapback? {
        if (associatedMessageType == null) return null

        // Check text names first
        val cleanType = associatedMessageType.removePrefix("-").lowercase()
        Tapback.entries.find { it.name.lowercase() == cleanType }?.let { return it }

        // Check numeric codes
        return when {
            associatedMessageType.contains("2000") || associatedMessageType.contains("3000") -> Tapback.LOVE
            associatedMessageType.contains("2001") || associatedMessageType.contains("3001") -> Tapback.LIKE
            associatedMessageType.contains("2002") || associatedMessageType.contains("3002") -> Tapback.DISLIKE
            associatedMessageType.contains("2003") || associatedMessageType.contains("3003") -> Tapback.LAUGH
            associatedMessageType.contains("2004") || associatedMessageType.contains("3004") -> Tapback.EMPHASIZE
            associatedMessageType.contains("2005") || associatedMessageType.contains("3005") -> Tapback.QUESTION
            else -> null
        }
    }

    /**
     * iMessage tapback/reaction types.
     */
    enum class Tapback {
        LOVE,
        LIKE,
        DISLIKE,
        LAUGH,
        EMPHASIZE,
        QUESTION
    }
}
