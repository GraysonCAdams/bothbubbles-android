package com.bothbubbles.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ForwardToInbox
import androidx.compose.material.icons.outlined.Quickreply
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * MD3-styled action menu for message operations.
 *
 * Displays a vertical list of actions with leading icons:
 * - Reply (if supported)
 * - Copy
 * - Forward
 *
 * Uses surfaceContainer color and standard MD3 list item styling.
 */
@Composable
fun MessageActionMenu(
    visible: Boolean,
    canReply: Boolean = false,
    canCopy: Boolean = true,
    canForward: Boolean = true,
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = spring(stiffness = Spring.StiffnessMedium)
        ) + expandVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            expandFrom = Alignment.Top
        ),
        exit = fadeOut(
            animationSpec = spring(stiffness = Spring.StiffnessHigh)
        ) + shrinkVertically(
            animationSpec = spring(stiffness = Spring.StiffnessHigh),
            shrinkTowards = Alignment.Top
        ),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column {
                // Reply action
                if (canReply) {
                    ActionMenuItem(
                        icon = Icons.Outlined.Quickreply,
                        label = "Reply",
                        onClick = onReply
                    )
                    if (canCopy || canForward) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // Copy action
                if (canCopy) {
                    ActionMenuItem(
                        icon = Icons.Outlined.ContentCopy,
                        label = "Copy",
                        onClick = onCopy
                    )
                    if (canForward) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // Forward action
                if (canForward) {
                    ActionMenuItem(
                        icon = Icons.Outlined.ForwardToInbox,
                        label = "Forward",
                        onClick = onForward
                    )
                }
            }
        }
    }
}

/**
 * Individual action menu item with icon and label.
 */
@Composable
private fun ActionMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
