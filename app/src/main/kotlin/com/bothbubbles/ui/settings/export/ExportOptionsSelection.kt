package com.bothbubbles.ui.settings.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.settings.components.SettingsCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversations selection card (all or selected)
 */
@Composable
fun ConversationsSelection(
    exportAllChats: Boolean,
    availableChatsCount: Int,
    selectedChatGuidsCount: Int,
    onExportAllChatsChanged: (Boolean) -> Unit,
    onShowChatPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Conversations",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // All conversations option
            ListItem(
                headlineContent = { Text("All conversations") },
                supportingContent = { Text("$availableChatsCount conversations") },
                leadingContent = {
                    RadioButton(
                        selected = exportAllChats,
                        onClick = { onExportAllChatsChanged(true) }
                    )
                },
                modifier = Modifier.clickable { onExportAllChatsChanged(true) }
            )

            HorizontalDivider()

            // Selected conversations option
            ListItem(
                headlineContent = { Text("Selected conversations") },
                supportingContent = {
                    Text(
                        if (selectedChatGuidsCount == 0) {
                            "None selected"
                        } else {
                            "$selectedChatGuidsCount selected"
                        }
                    )
                },
                leadingContent = {
                    RadioButton(
                        selected = !exportAllChats,
                        onClick = { onExportAllChatsChanged(false) }
                    )
                },
                trailingContent = if (!exportAllChats) {
                    {
                        IconButton(onClick = onShowChatPicker) {
                            Icon(Icons.Default.Edit, contentDescription = "Select")
                        }
                    }
                } else null,
                modifier = Modifier.clickable { onExportAllChatsChanged(false) }
            )
        }
    }
}

/**
 * Date range selection card (all time or custom range)
 */
@Composable
fun DateRangeSelection(
    dateRangeEnabled: Boolean,
    startDate: Long?,
    endDate: Long?,
    onDateRangeEnabledChanged: (Boolean) -> Unit,
    onShowStartDatePicker: () -> Unit,
    onShowEndDatePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    SettingsCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Date Range",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // All time option
            ListItem(
                headlineContent = { Text("All time") },
                leadingContent = {
                    RadioButton(
                        selected = !dateRangeEnabled,
                        onClick = { onDateRangeEnabledChanged(false) }
                    )
                },
                modifier = Modifier.clickable { onDateRangeEnabledChanged(false) }
            )

            HorizontalDivider()

            // Custom range option
            ListItem(
                headlineContent = { Text("Custom range") },
                leadingContent = {
                    RadioButton(
                        selected = dateRangeEnabled,
                        onClick = { onDateRangeEnabledChanged(true) }
                    )
                },
                modifier = Modifier.clickable { onDateRangeEnabledChanged(true) }
            )

            // Date pickers (shown when custom range is selected)
            if (dateRangeEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onShowStartDatePicker,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = startDate?.let { dateFormatter.format(Date(it)) } ?: "Start date",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onShowEndDatePicker,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = endDate?.let { dateFormatter.format(Date(it)) } ?: "End date",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Date picker dialog wrapper
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(
    title: String,
    selectedDate: Long?,
    onDateSelected: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate ?: System.currentTimeMillis()
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { onDateSelected(it) }
                    onDismiss()
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(
            state = datePickerState,
            title = { Text(title, modifier = Modifier.padding(16.dp)) }
        )
    }
}
