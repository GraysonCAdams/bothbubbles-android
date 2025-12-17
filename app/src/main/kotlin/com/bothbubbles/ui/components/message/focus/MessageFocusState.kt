package com.bothbubbles.ui.components.message.focus

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import com.bothbubbles.ui.components.message.Tapback
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * State holder for the Message Focus Overlay.
 *
 * This immutable data class represents all the information needed to display
 * the tapback/reaction overlay for a selected message.
 *
 * Design decisions:
 * - Uses [ImmutableSet] for reactions to ensure Compose stability
 * - Does NOT store @Composable lambdas (avoided for recomposition safety)
 * - Uses [messageId] to look up message content at render time
 */
@Immutable
data class MessageFocusState(
    /** Whether the overlay is currently visible */
    val visible: Boolean = false,

    /** The GUID of the focused message (used to look up message data) */
    val messageId: String? = null,

    /** Screen coordinates of the original message bubble */
    val messageBounds: Rect? = null,

    /** Whether the message is from the current user (affects alignment) */
    val isFromMe: Boolean = false,

    /** Set of reactions the user has already applied to this message */
    val myReactions: ImmutableSet<Tapback> = persistentSetOf(),

    /** Whether the message supports tapback reactions (server-origin only) */
    val canReact: Boolean = true,

    /** Whether the message has text content that can be copied */
    val canCopy: Boolean = true,

    /** Whether the message can be forwarded */
    val canForward: Boolean = true,

    /** Whether reply action is available */
    val canReply: Boolean = false
) {
    companion object {
        /** Default empty state */
        val Empty = MessageFocusState()
    }

    /** Returns true if the overlay has valid data to display */
    val isValid: Boolean
        get() = visible && messageId != null && messageBounds != null
}

/**
 * Calculated position information for overlay components.
 */
@Immutable
data class OverlayPosition(
    /** X coordinate for the component */
    val x: Float,
    /** Y coordinate for the component */
    val y: Float,
    /** Whether the component should appear above the message */
    val isAbove: Boolean
)
