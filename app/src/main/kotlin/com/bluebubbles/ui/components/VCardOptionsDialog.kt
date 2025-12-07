package com.bluebubbles.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bluebubbles.services.contacts.VCardService

/**
 * Dialog for selecting which contact fields to include in a vCard before sharing
 */
@Composable
fun VCardOptionsDialog(
    visible: Boolean,
    contactData: VCardService.ContactData?,
    onDismiss: () -> Unit,
    onConfirm: (VCardService.FieldOptions) -> Unit
) {
    if (!visible || contactData == null) return

    var options by remember { mutableStateOf(VCardService.FieldOptions()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Share Contact")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Contact name (always included)
                Text(
                    text = contactData.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Select information to share:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Phone numbers
                if (contactData.phoneNumbers.isNotEmpty()) {
                    FieldToggleItem(
                        icon = Icons.Outlined.Phone,
                        label = "Phone Numbers",
                        detail = contactData.phoneNumbers.joinToString(", ") { it.number },
                        checked = options.includePhones,
                        onCheckedChange = { options = options.copy(includePhones = it) }
                    )
                }

                // Email addresses
                if (contactData.emails.isNotEmpty()) {
                    FieldToggleItem(
                        icon = Icons.Outlined.Email,
                        label = "Email Addresses",
                        detail = contactData.emails.joinToString(", ") { it.address },
                        checked = options.includeEmails,
                        onCheckedChange = { options = options.copy(includeEmails = it) }
                    )
                }

                // Organization
                if (!contactData.organization.isNullOrBlank() || !contactData.title.isNullOrBlank()) {
                    val orgText = listOfNotNull(contactData.title, contactData.organization)
                        .joinToString(" at ")
                    FieldToggleItem(
                        icon = Icons.Outlined.Business,
                        label = "Organization",
                        detail = orgText,
                        checked = options.includeOrganization,
                        onCheckedChange = { options = options.copy(includeOrganization = it) }
                    )
                }

                // Addresses
                if (contactData.addresses.isNotEmpty()) {
                    val addressText = contactData.addresses.firstOrNull()?.let { addr ->
                        listOfNotNull(addr.street, addr.city, addr.region).joinToString(", ")
                    } ?: "Address"
                    FieldToggleItem(
                        icon = Icons.Outlined.LocationOn,
                        label = "Addresses",
                        detail = if (contactData.addresses.size > 1) {
                            "$addressText (+${contactData.addresses.size - 1} more)"
                        } else {
                            addressText
                        },
                        checked = options.includeAddresses,
                        onCheckedChange = { options = options.copy(includeAddresses = it) }
                    )
                }

                // Note
                if (!contactData.note.isNullOrBlank()) {
                    FieldToggleItem(
                        icon = Icons.Outlined.Notes,
                        label = "Note",
                        detail = contactData.note.take(50) + if (contactData.note.length > 50) "..." else "",
                        checked = options.includeNote,
                        onCheckedChange = { options = options.copy(includeNote = it) }
                    )
                }

                // Photo
                if (contactData.photo != null) {
                    FieldToggleItem(
                        icon = Icons.Outlined.Photo,
                        label = "Photo",
                        detail = "Contact photo",
                        checked = options.includePhoto,
                        onCheckedChange = { options = options.copy(includePhoto = it) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(options) }) {
                Text("Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun FieldToggleItem(
    icon: ImageVector,
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
