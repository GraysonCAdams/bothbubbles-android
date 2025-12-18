package com.bothbubbles.ui.chat.composer

/**
 * Utility for tracking and adjusting mention positions as text is edited.
 *
 * Handles:
 * - Position shifting when text is inserted/deleted before mentions
 * - Partial deletion within mentions (shrinking but keeping valid if still matches)
 * - Complete removal of mentions when text no longer matches any participant
 */
object MentionPositionTracker {

    /**
     * Result of adjusting mentions after a text change.
     */
    data class AdjustmentResult(
        val adjustedMentions: List<MentionSpan>,
        val removedMentions: List<MentionSpan>
    )

    /**
     * Adjust mention positions after a text change.
     *
     * @param oldText The text before the change
     * @param newText The text after the change
     * @param mentions Current list of mentions
     * @param participants Map of participant addresses to their first names (for validation)
     * @param editPosition The position where the edit occurred
     * @return Adjusted list of mentions
     */
    fun adjustMentions(
        oldText: String,
        newText: String,
        mentions: List<MentionSpan>,
        participants: Map<String, ParticipantName>,
        editPosition: Int
    ): AdjustmentResult {
        if (mentions.isEmpty()) {
            return AdjustmentResult(emptyList(), emptyList())
        }

        val delta = newText.length - oldText.length
        val editEnd = if (delta >= 0) editPosition else editPosition - delta

        val adjustedMentions = mutableListOf<MentionSpan>()
        val removedMentions = mutableListOf<MentionSpan>()

        for (mention in mentions) {
            when {
                // Edit is entirely after the mention - no adjustment needed
                editPosition >= mention.endIndex -> {
                    adjustedMentions.add(mention)
                }

                // Edit is entirely before the mention - shift position
                editEnd <= mention.startIndex -> {
                    adjustedMentions.add(
                        mention.copy(startIndex = mention.startIndex + delta)
                    )
                }

                // Edit overlaps with the mention - validate and possibly shrink or remove
                else -> {
                    val result = handleOverlappingEdit(
                        mention = mention,
                        newText = newText,
                        editPosition = editPosition,
                        delta = delta,
                        participants = participants
                    )
                    if (result != null) {
                        adjustedMentions.add(result)
                    } else {
                        removedMentions.add(mention)
                    }
                }
            }
        }

        return AdjustmentResult(adjustedMentions, removedMentions)
    }

    /**
     * Handle an edit that overlaps with a mention.
     *
     * If the remaining text still matches the participant's first name (or full name),
     * the mention is shrunk. Otherwise, it's removed.
     *
     * @return The adjusted mention, or null if it should be removed
     */
    private fun handleOverlappingEdit(
        mention: MentionSpan,
        newText: String,
        editPosition: Int,
        delta: Int,
        participants: Map<String, ParticipantName>
    ): MentionSpan? {
        // Calculate the new bounds of the mention after the edit
        val newStart = mention.startIndex.coerceAtMost(editPosition)
        val newEnd = (mention.endIndex + delta).coerceAtLeast(editPosition)
            .coerceIn(newStart, newText.length)

        if (newEnd <= newStart) {
            return null // Mention was completely deleted
        }

        // Extract the text at the mention position after the edit
        val newMentionText = newText.substring(newStart, newEnd)

        // Check if the new text still matches the participant
        val participant = participants[mention.participantAddress]
        if (participant != null && isValidMentionText(newMentionText, participant)) {
            return mention.copy(
                startIndex = newStart,
                length = newEnd - newStart,
                displayText = newMentionText
            )
        }

        // Text no longer matches - remove the mention
        return null
    }

    /**
     * Check if text is a valid mention for a participant.
     * Valid if it matches the start of their first name or full name.
     */
    private fun isValidMentionText(text: String, participant: ParticipantName): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // Check if it's a prefix of the first name
        participant.firstName?.let { firstName ->
            if (firstName.startsWith(trimmed, ignoreCase = true)) {
                return true
            }
        }

        // Check if it's a prefix of the full name
        if (participant.fullName.startsWith(trimmed, ignoreCase = true)) {
            return true
        }

        return false
    }

    /**
     * Detect if the current text/cursor position should trigger a mention popup.
     *
     * @param text Current text
     * @param cursorPosition Current cursor position
     * @param participants List of participants to match against
     * @param existingMentions Current mentions (to avoid re-triggering inside existing mentions)
     * @return MentionPopupState if a popup should be shown, null otherwise
     */
    fun detectMentionTrigger(
        text: String,
        cursorPosition: Int,
        participants: List<MentionSuggestion>,
        existingMentions: List<MentionSpan>
    ): MentionPopupState? {
        if (participants.isEmpty() || cursorPosition == 0) return null

        // Check if cursor is inside an existing mention
        if (existingMentions.any { it.contains(cursorPosition - 1) }) {
            return null
        }

        // Find word start (scan backwards from cursor)
        var wordStart = cursorPosition
        while (wordStart > 0 && !text[wordStart - 1].isWhitespace()) {
            wordStart--
        }

        if (wordStart == cursorPosition) return null

        val word = text.substring(wordStart, cursorPosition)
        if (word.isEmpty()) return null

        // Check for explicit '@' trigger
        val isExplicitTrigger = word.startsWith("@")
        val query = if (isExplicitTrigger) word.substring(1) else word

        // For explicit trigger, allow empty query to show all participants
        // For implicit trigger, require at least 2 characters to match first name
        if (!isExplicitTrigger && query.length < 2) return null

        // Match against participants
        val matches = filterParticipants(query, participants, isExplicitTrigger)

        if (matches.isEmpty()) return null

        return MentionPopupState(
            isVisible = true,
            triggerPosition = wordStart,
            query = query,
            suggestions = kotlinx.collections.immutable.persistentListOf<MentionSuggestion>()
                .addAll(matches.take(5))
        )
    }

    /**
     * Filter participants based on query.
     */
    private fun filterParticipants(
        query: String,
        participants: List<MentionSuggestion>,
        isExplicitTrigger: Boolean
    ): List<MentionSuggestion> {
        if (query.isEmpty() && isExplicitTrigger) {
            // Show all participants for just "@"
            return participants
        }

        return participants.filter { participant ->
            if (isExplicitTrigger) {
                // Explicit @ allows matching any part of name or address
                participant.fullName.contains(query, ignoreCase = true) ||
                    participant.address.contains(query, ignoreCase = true)
            } else {
                // Implicit: require FULL first name match (case insensitive)
                // Only suggest when the entire first name has been typed
                participant.firstName?.equals(query, ignoreCase = true) == true
            }
        }
    }

    /**
     * Insert a mention at the trigger position, replacing the typed query.
     *
     * @param text Current text
     * @param triggerPosition Where the mention trigger started
     * @param cursorPosition Current cursor position
     * @param suggestion The selected suggestion
     * @return Pair of (new text, new cursor position)
     */
    fun insertMention(
        text: String,
        triggerPosition: Int,
        cursorPosition: Int,
        suggestion: MentionSuggestion
    ): Triple<String, Int, MentionSpan> {
        val displayText = suggestion.firstName ?: suggestion.fullName
        val before = text.substring(0, triggerPosition)
        val after = text.substring(cursorPosition)

        // Add a space after the mention
        val newText = "$before$displayText $after"
        val newCursor = triggerPosition + displayText.length + 1

        val mention = MentionSpan(
            startIndex = triggerPosition,
            length = displayText.length,
            participantAddress = suggestion.address,
            displayText = displayText
        )

        return Triple(newText, newCursor, mention)
    }
}

/**
 * Simple holder for participant name data used in mention validation.
 */
data class ParticipantName(
    val firstName: String?,
    val fullName: String
)
