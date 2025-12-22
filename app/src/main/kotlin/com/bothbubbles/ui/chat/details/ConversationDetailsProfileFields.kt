package com.bothbubbles.ui.chat.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.data.repository.ContactCalendarAssociation

/**
 * A generic profile field row that displays a label, value, and action buttons.
 * Used for displaying custom contact fields like Discord channel ID.
 *
 * @param label The field label (e.g., "Discord Channel")
 * @param value The field value, or null if not configured
 * @param placeholderText Text to show when value is null
 * @param icon The leading icon (either ImageVector or Painter)
 * @param onEditClick Called when the edit button or row is clicked
 * @param onClearClick Called when the clear button is clicked, null to hide the clear button
 */
@Composable
fun ProfileFieldRow(
    label: String,
    value: String?,
    placeholderText: String,
    icon: ImageVector,
    onEditClick: () -> Unit,
    onClearClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                text = value ?: placeholderText,
                color = if (value != null)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (value != null) {
                Row {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (onClearClick != null) {
                        IconButton(onClick = onClearClick) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                TextButton(onClick = onEditClick) {
                    Text("Set up")
                }
            }
        },
        modifier = modifier.clickable(onClick = onEditClick)
    )
}

/**
 * Overload that accepts a Painter for the icon (useful for custom SVG icons).
 */
@Composable
fun ProfileFieldRow(
    label: String,
    value: String?,
    placeholderText: String,
    iconPainter: Painter,
    onEditClick: () -> Unit,
    onClearClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                text = value ?: placeholderText,
                color = if (value != null)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        },
        trailingContent = {
            if (value != null) {
                Row {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (onClearClick != null) {
                        IconButton(onClick = onClearClick) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                TextButton(onClick = onEditClick) {
                    Text("Set up")
                }
            }
        },
        modifier = modifier.clickable(onClick = onEditClick)
    )
}

/**
 * Profile fields section containing custom contact fields.
 * Includes Discord channel ID field and calendar association.
 */
@Composable
fun ProfileFieldsSection(
    discordChannelId: String?,
    isDiscordInstalled: Boolean,
    calendarAssociation: ContactCalendarAssociation?,
    onDiscordEditClick: () -> Unit,
    onDiscordClearClick: () -> Unit,
    onCalendarEditClick: () -> Unit,
    onCalendarClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Show section if Discord is installed or calendar is configured (or to allow setup)
    val hasAnyField = isDiscordInstalled || calendarAssociation != null

    Column(modifier = modifier) {
        // Video Calls section (Discord)
        if (isDiscordInstalled) {
            Text(
                text = "Video Calls",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ProfileFieldRow(
                label = "Discord Channel",
                value = discordChannelId,
                placeholderText = "Not configured",
                iconPainter = painterResource(R.drawable.ic_discord),
                onEditClick = onDiscordEditClick,
                onClearClick = if (discordChannelId != null) onDiscordClearClick else null
            )
        }

        // Integrations section (Calendar)
        Text(
            text = "Integrations",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        CalendarFieldRow(
            calendarAssociation = calendarAssociation,
            onEditClick = onCalendarEditClick,
            onClearClick = onCalendarClearClick
        )
    }
}

/**
 * Calendar association field row with colored indicator.
 */
@Composable
private fun CalendarFieldRow(
    calendarAssociation: ContactCalendarAssociation?,
    onEditClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = { Text("Calendar") },
        supportingContent = {
            if (calendarAssociation != null) {
                Column {
                    Text(calendarAssociation.calendarDisplayName)
                    calendarAssociation.accountName?.let { account ->
                        Text(
                            text = account,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "Show events in chat header",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = calendarAssociation?.calendarColor?.let { Color(it) }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (calendarAssociation != null) {
                Row {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClearClick) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                TextButton(onClick = onEditClick) {
                    Text("Set up")
                }
            }
        },
        modifier = modifier.clickable(onClick = onEditClick)
    )
}
