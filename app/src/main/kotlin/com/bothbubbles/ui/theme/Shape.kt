package com.bothbubbles.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    // Chips, small buttons, badges
    extraSmall = RoundedCornerShape(4.dp),

    // Buttons, text fields
    small = RoundedCornerShape(8.dp),

    // Cards, dialogs
    medium = RoundedCornerShape(12.dp),

    // FAB, navigation containers
    large = RoundedCornerShape(16.dp),

    // Search bar, bottom sheets, full pill shapes
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Message bubble shapes following Google Messages design.
 * Grouped messages use tight corners (4dp) on the sender's side to create
 * visual continuity, with the tail only appearing on the last message.
 *
 * For sent messages (right-aligned): tight corners on the right
 * For received messages (left-aligned): tight corners on the left
 */
object MessageShapes {
    // Standard bubble (no tail, all corners rounded)
    val bubble = RoundedCornerShape(20.dp)

    // ===== SINGLE MESSAGE (standalone, not grouped) =====
    // Same as "last" - has the tail, fully rounded top

    // Sent single: tail on bottom-right
    val sentSingle = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 4.dp
    )

    // Received single: tail on bottom-left
    val receivedSingle = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 4.dp,
        bottomEnd = 20.dp
    )

    // ===== FIRST IN GROUP (visually at top of group) =====
    // Rounded top corners, tight bottom corner on sender's side

    val sentFirst = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 4.dp  // tight on sender's side (right)
    )

    val receivedFirst = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 4.dp,  // tight on sender's side (left)
        bottomEnd = 20.dp
    )

    // ===== MIDDLE OF GROUP =====
    // Tight corners on both top and bottom on sender's side

    val sentMiddle = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 4.dp,       // tight top on sender's side
        bottomStart = 20.dp,
        bottomEnd = 4.dp     // tight bottom on sender's side
    )

    val receivedMiddle = RoundedCornerShape(
        topStart = 4.dp,     // tight top on sender's side
        topEnd = 20.dp,
        bottomStart = 4.dp,  // tight bottom on sender's side
        bottomEnd = 20.dp
    )

    // ===== LAST IN GROUP (visually at bottom of group) =====
    // Tight top corner on sender's side, tail at bottom

    val sentLast = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 4.dp,       // tight top on sender's side
        bottomStart = 20.dp,
        bottomEnd = 4.dp     // tail corner
    )

    val receivedLast = RoundedCornerShape(
        topStart = 4.dp,     // tight top on sender's side
        topEnd = 20.dp,
        bottomStart = 4.dp,  // tail corner
        bottomEnd = 20.dp
    )

    // Legacy aliases for backward compatibility
    val sentWithTail = sentSingle
    val receivedWithTail = receivedSingle
}
