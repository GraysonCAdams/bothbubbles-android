package com.bothbubbles.ui.chat.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun ActionButtonsRow(
    hasContact: Boolean,
    isStarred: Boolean,
    isGroup: Boolean,
    onCallClick: () -> Unit,
    onVideoClick: () -> Unit,
    onContactInfoClick: () -> Unit,
    onAddContactClick: () -> Unit,
    onStarClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ActionButton(
            icon = Icons.Outlined.Phone,
            label = "Call",
            onClick = onCallClick
        )
        ActionButton(
            icon = Icons.Outlined.Videocam,
            label = "Video",
            onClick = onVideoClick
        )
        ActionButton(
            icon = if (hasContact) Icons.Outlined.Person else Icons.Outlined.PersonAdd,
            label = if (hasContact) "Contact info" else "Add contact",
            onClick = if (hasContact) onContactInfoClick else onAddContactClick
        )
        // Favorite button - only show for non-group chats with saved contacts
        if (!isGroup && hasContact) {
            ActionButton(
                icon = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = if (isStarred) "Favorited" else "Favorite",
                onClick = onStarClick
            )
        }
        ActionButton(
            icon = Icons.Outlined.Search,
            label = "Search",
            onClick = onSearchClick
        )
    }
}

@Composable
fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
