package com.bothbubbles.ui.chat.composer

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Represents a mention span within the message text.
 * Tracks the position and target of a mention.
 *
 * @param startIndex The starting character index in the text
 * @param length The length of the mention text
 * @param participantAddress The address (phone/email) of the mentioned participant
 * @param displayText The visible text of the mention (e.g., "John" or "John Smith")
 */
@Immutable
data class MentionSpan(
    val startIndex: Int,
    val length: Int,
    val participantAddress: String,
    val displayText: String
) {
    /**
     * The end index of this mention (exclusive).
     */
    val endIndex: Int get() = startIndex + length

    /**
     * Check if this mention overlaps with a given range.
     */
    fun overlaps(rangeStart: Int, rangeEnd: Int): Boolean {
        return startIndex < rangeEnd && endIndex > rangeStart
    }

    /**
     * Check if a position is within this mention.
     */
    fun contains(position: Int): Boolean {
        return position in startIndex until endIndex
    }
}

/**
 * Represents a participant suggestion for the mention popup.
 *
 * @param address The participant's address (phone/email)
 * @param fullName The participant's full display name
 * @param firstName The participant's first name (for matching)
 * @param avatarPath Path to the participant's cached avatar (if any)
 */
@Immutable
data class MentionSuggestion(
    val address: String,
    val fullName: String,
    val firstName: String?,
    val avatarPath: String?
)

/**
 * State for the mention suggestion popup.
 *
 * @param isVisible Whether the popup is currently visible
 * @param triggerPosition The character position where the mention trigger started
 * @param query The current search query (text after the trigger)
 * @param suggestions The filtered list of participant suggestions
 * @param selectedIndex The currently highlighted suggestion index (-1 for none)
 */
@Immutable
data class MentionPopupState(
    val isVisible: Boolean = false,
    val triggerPosition: Int = 0,
    val query: String = "",
    val suggestions: ImmutableList<MentionSuggestion> = persistentListOf(),
    val selectedIndex: Int = -1
) {
    companion object {
        val Hidden = MentionPopupState()
    }
}

/**
 * Parsed mention from a received message's attributedBody.
 * Used for rendering mentions in message bubbles.
 *
 * @param startIndex The starting character index in the message text
 * @param length The length of the mention
 * @param mentionedAddress The address of the mentioned person (from __kIMMessagePartAttributeName)
 */
@Immutable
data class ParsedMention(
    val startIndex: Int,
    val length: Int,
    val mentionedAddress: String
) {
    val endIndex: Int get() = startIndex + length
}
