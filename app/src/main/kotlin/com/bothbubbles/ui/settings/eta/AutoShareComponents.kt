package com.bothbubbles.ui.settings.eta

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.data.repository.AutoShareContact
import com.bothbubbles.services.contacts.PhoneContact

/**
 * Section displaying auto-share contacts in settings.
 * Simplified model: just a list of contacts (max 5) who receive ETA updates
 * when navigation starts (if ETA meets minimum threshold).
 */
@Composable
fun AutoShareContactsSection(
    contacts: List<AutoShareContact>,
    canAddMore: Boolean,
    onAddContact: () -> Unit,
    onRemoveContact: (AutoShareContact) -> Unit,
    onToggleContact: (AutoShareContact, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Section header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUTO-SHARE CONTACTS (${contacts.size}/5)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            if (canAddMore) {
                IconButton(onClick = onAddContact) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add contact",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Description
        Text(
            text = "Automatically share ETA with these contacts when navigation starts:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Contacts list
        if (contacts.isEmpty()) {
            EmptyContactsCard(onAddContact = onAddContact)
        } else {
            contacts.forEach { contact ->
                AutoShareContactCard(
                    contact = contact,
                    onRemove = { onRemoveContact(contact) },
                    onToggle = { enabled -> onToggleContact(contact, enabled) }
                )
            }

            // Add contact button (if not at max)
            if (canAddMore) {
                TextButton(
                    onClick = onAddContact,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Contact")
                }
            }
        }
    }
}

@Composable
private fun EmptyContactsCard(
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No auto-share contacts",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Add contacts to automatically share your ETA when you start navigating",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAddContact) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Contact")
            }
        }
    }
}

@Composable
private fun AutoShareContactCard(
    contact: AutoShareContact,
    onRemove: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.displayName.firstOrNull()?.uppercase()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            // Toggle
            val haptic = LocalHapticFeedback.current
            Switch(
                checked = contact.enabled,
                onCheckedChange = {
                    HapticUtils.onConfirm(haptic)
                    onToggle(it)
                }
            )

            // Delete button
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Simple dialog for selecting a contact to add to auto-share list.
 */
@Composable
fun AddAutoShareContactDialog(
    availableContacts: List<PhoneContact>,
    existingContactGuids: Set<String>,
    isLoading: Boolean,
    onSelectContact: (chatGuid: String, displayName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // Filter contacts based on search (exclude already added ones)
    val filteredContacts by remember(searchQuery, availableContacts, existingContactGuids) {
        derivedStateOf {
            val baseContacts = availableContacts.filter { contact ->
                // Exclude contacts already in the auto-share list
                val primaryAddress = contact.phoneNumbers.firstOrNull() ?: contact.emails.firstOrNull()
                primaryAddress != null && primaryAddress !in existingContactGuids
            }

            if (searchQuery.isBlank()) {
                baseContacts
            } else {
                val query = searchQuery.lowercase()
                baseContacts.filter { contact ->
                    contact.displayName.lowercase().contains(query) ||
                    contact.phoneNumbers.any { it.contains(query) } ||
                    contact.emails.any { it.lowercase().contains(query) }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Auto-Share Contact") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search contacts...") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Contact list
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (isLoading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading contacts...")
                        }
                    } else if (filteredContacts.isEmpty()) {
                        Text(
                            text = if (searchQuery.isBlank()) "No contacts available" else "No contacts found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn {
                            items(filteredContacts.take(50)) { contact ->
                                val primaryAddress = contact.phoneNumbers.firstOrNull() ?: contact.emails.firstOrNull()
                                if (primaryAddress != null) {
                                    ContactSelectionItem(
                                        contact = contact,
                                        onClick = {
                                            onSelectContact(primaryAddress, contact.displayName)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ContactSelectionItem(
    contact: PhoneContact,
    onClick: () -> Unit
) {
    val primaryAddress = contact.phoneNumbers.firstOrNull() ?: contact.emails.firstOrNull() ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.displayName.firstOrNull()?.uppercase()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = primaryAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
