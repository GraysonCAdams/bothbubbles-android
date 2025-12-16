package com.bothbubbles.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Dialog for setting up or editing a Discord channel ID for a contact.
 *
 * @param currentChannelId The current channel ID if editing, null if setting up for the first time
 * @param contactName The display name of the contact
 * @param onSave Called when the user saves a valid channel ID
 * @param onClear Called when the user clears an existing channel ID (only shown when editing)
 * @param onDismiss Called when the dialog is dismissed
 * @param onShowHelp Called when the user taps the help text
 */
@Composable
fun DiscordChannelSetupDialog(
    currentChannelId: String?,
    contactName: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onShowHelp: () -> Unit
) {
    var channelId by remember(currentChannelId) { mutableStateOf(currentChannelId ?: "") }
    val isEditing = currentChannelId != null

    // Validate: 17-19 digit number
    val isValid = channelId.trim().matches(Regex("^\\d{17,19}$"))
    val showError = channelId.isNotEmpty() && !isValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) "Edit Discord Channel" else "Set Up Discord"
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter the Discord DM channel ID for $contactName.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = channelId,
                    onValueChange = { newValue ->
                        // Only allow digits
                        channelId = newValue.filter { it.isDigit() }
                    },
                    label = { Text("Channel ID") },
                    placeholder = { Text("e.g., 1234567890123456789") },
                    isError = showError,
                    supportingText = {
                        Column {
                            if (showError) {
                                Text(
                                    text = "Channel ID must be 17-19 digits",
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            // Clickable help text
                            val helpText = buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline
                                    )
                                ) {
                                    append("How to find your channel ID")
                                }
                            }
                            ClickableText(
                                text = helpText,
                                onClick = { onShowHelp() },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(channelId.trim()) },
                enabled = isValid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (isEditing) {
                    TextButton(
                        onClick = onClear,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
