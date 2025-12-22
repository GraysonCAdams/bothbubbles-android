package com.bothbubbles.ui.chat.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bothbubbles.services.calendar.DeviceCalendar
import kotlinx.coroutines.launch

/**
 * Dialog for selecting a device calendar to associate with a contact.
 *
 * @param currentCalendarId The currently selected calendar ID, or null if none
 * @param contactName The contact's name to display in the title
 * @param onCalendarSelected Called when user selects a calendar
 * @param onDismiss Called when dialog is dismissed
 * @param getCalendars Suspend function to fetch available calendars
 */
@Composable
fun CalendarPickerDialog(
    currentCalendarId: Long?,
    contactName: String,
    onCalendarSelected: (DeviceCalendar) -> Unit,
    onDismiss: () -> Unit,
    getCalendars: suspend () -> List<DeviceCalendar>
) {
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Load calendars when dialog opens
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                calendars = getCalendars()
                isLoading = false
            } catch (e: Exception) {
                error = "Failed to load calendars"
                isLoading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Select Calendar for $contactName")
        },
        text = {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                calendars.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No calendars found",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Grant calendar permission or add a calendar account",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        items(calendars) { calendar ->
                            CalendarListItem(
                                calendar = calendar,
                                isSelected = calendar.id == currentCalendarId,
                                onClick = { onCalendarSelected(calendar) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * List item for a calendar in the picker.
 */
@Composable
private fun CalendarListItem(
    calendar: DeviceCalendar,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = { Text(calendar.displayName) },
        supportingContent = calendar.accountName?.let { { Text(it) } },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = calendar.color?.let { Color(it) }
                    ?: MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = if (isSelected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        modifier = modifier.clickable(onClick = onClick)
    )
}
