package com.bluebubbles.ui.theme

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
 * Message bubble shapes following Google Messages design
 * - Standard bubble: 20dp all corners
 * - Tail bubble: 20dp except 4dp on tail corner
 */
object MessageShapes {
    // Standard bubble (no tail, middle of group)
    val bubble = RoundedCornerShape(20.dp)

    // Sent message with tail (bottom-right corner small)
    val sentWithTail = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 4.dp
    )

    // Received message with tail (bottom-left corner small)
    val receivedWithTail = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 4.dp,
        bottomEnd = 20.dp
    )

    // First in group (larger top corners)
    val sentFirst = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 4.dp
    )

    val receivedFirst = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 4.dp,
        bottomEnd = 20.dp
    )

    // Middle of group (small corners on grouping side)
    val sentMiddle = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 4.dp,
        bottomStart = 20.dp,
        bottomEnd = 4.dp
    )

    val receivedMiddle = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 20.dp,
        bottomStart = 4.dp,
        bottomEnd = 20.dp
    )
}
