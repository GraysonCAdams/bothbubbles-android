package com.bothbubbles.ui.settings.eta

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bothbubbles.data.repository.AutoShareRecipient
import com.bothbubbles.data.repository.AutoShareRule
import com.bothbubbles.data.repository.LocationType
import com.bothbubbles.services.contacts.PhoneContact

/**
 * Section displaying auto-share rules in settings.
 */
@Composable
fun AutoShareRulesSection(
    rules: List<AutoShareRule>,
    onAddRule: () -> Unit,
    onEditRule: (AutoShareRule) -> Unit,
    onDeleteRule: (AutoShareRule) -> Unit,
    onToggleRule: (AutoShareRule, Boolean) -> Unit,
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
                text = "AUTO-SHARE RULES",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Rules list
        if (rules.isEmpty()) {
            EmptyRulesCard(onAddRule = onAddRule)
        } else {
            rules.forEach { rule ->
                AutoShareRuleCard(
                    rule = rule,
                    onEdit = { onEditRule(rule) },
                    onDelete = { onDeleteRule(rule) },
                    onToggle = { enabled -> onToggleRule(rule, enabled) }
                )
            }

            // Add rule button
            TextButton(
                onClick = onAddRule,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Auto-Share Rule")
            }
        }
    }
}

@Composable
private fun EmptyRulesCard(
    onAddRule: () -> Unit,
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
                imageVector = Icons.Default.Place,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No auto-share rules yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Automatically share your ETA when navigating to saved destinations",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAddRule) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Rule")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoShareRuleCard(
    rule: AutoShareRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with icon, name, and toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Location type icon
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = rule.locationType.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = rule.destinationName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = rule.recipients.joinToString(", ") { it.displayName },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle
                )
            }

            // Keywords (collapsed view)
            if (rule.keywords.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rule.keywords.take(3).forEach { keyword ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = keyword,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (rule.keywords.size > 3) {
                        Text(
                            text = "+${rule.keywords.size - 3} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Actions
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
                }
                TextButton(
                    onClick = onDelete,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

/**
 * Improved dialog for adding/editing an auto-share rule.
 * Features:
 * - Icon picker dropdown
 * - Clean vertical list for trigger addresses
 * - Contact-based recipients with search
 */
@Composable
fun AddAutoShareRuleDialog(
    existingRule: AutoShareRule?,
    availableContacts: List<PhoneContact>,
    isLoading: Boolean,
    onSave: (destinationName: String, keywords: List<String>, locationType: LocationType, recipients: List<AutoShareRecipient>) -> Unit,
    onDismiss: () -> Unit
) {
    var destinationName by remember { mutableStateOf(existingRule?.destinationName ?: "") }
    var locationType by remember { mutableStateOf(existingRule?.locationType ?: LocationType.CUSTOM) }
    val keywords = remember { mutableStateListOf<String>().apply {
        existingRule?.keywords?.let { addAll(it) }
    }}
    var keywordInput by remember { mutableStateOf("") }
    var showAddKeywordField by remember { mutableStateOf(false) }
    var contactSearchQuery by remember { mutableStateOf("") }

    // Selected contacts - use address (phone/email) as key
    val selectedAddresses = remember { mutableStateListOf<String>().apply {
        existingRule?.recipients?.forEach { add(it.chatGuid) }
    }}

    // Filter contacts based on search
    val filteredContacts by remember(contactSearchQuery, availableContacts) {
        derivedStateOf {
            if (contactSearchQuery.isBlank()) {
                availableContacts
            } else {
                val query = contactSearchQuery.lowercase()
                availableContacts.filter { contact ->
                    contact.displayName.lowercase().contains(query) ||
                    contact.phoneNumbers.any { it.contains(query) } ||
                    contact.emails.any { it.lowercase().contains(query) }
                }
            }
        }
    }

    // Sort selected contacts to top
    val sortedContacts by remember(filteredContacts, selectedAddresses) {
        derivedStateOf {
            filteredContacts.sortedByDescending { contact ->
                contact.phoneNumbers.any { it in selectedAddresses } ||
                contact.emails.any { it in selectedAddresses }
            }
        }
    }

    val isValid = destinationName.isNotBlank() &&
                  keywords.isNotEmpty() &&
                  selectedAddresses.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existingRule == null) "Add Auto-Share Rule" else "Edit Rule")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                // === Rule Details Row (Icon + Name) ===
                Text(
                    text = "Rule Details",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Icon picker dropdown
                    IconPickerDropdown(
                        selectedType = locationType,
                        onTypeSelected = { type ->
                            locationType = type
                            // Auto-fill name if empty
                            if (destinationName.isBlank() && type != LocationType.CUSTOM) {
                                destinationName = type.displayName
                            }
                        },
                        modifier = Modifier.width(80.dp)
                    )

                    // Name field
                    OutlinedTextField(
                        value = destinationName,
                        onValueChange = { destinationName = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g., Home") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === Trigger Addresses Section ===
                Text(
                    text = "Trigger Addresses",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Matches navigation destination",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Addresses list
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        keywords.forEachIndexed { index, keyword ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = keyword,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { keywords.remove(keyword) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (index < keywords.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }

                        // Add address row
                        if (showAddKeywordField) {
                            if (keywords.isNotEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = keywordInput,
                                    onValueChange = { keywordInput = it },
                                    placeholder = { Text("Enter address or phrase") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            if (keywordInput.isNotBlank() && keywordInput !in keywords) {
                                                keywords.add(keywordInput.trim())
                                                keywordInput = ""
                                                showAddKeywordField = false
                                            }
                                        }
                                    )
                                )
                                IconButton(onClick = {
                                    if (keywordInput.isNotBlank() && keywordInput !in keywords) {
                                        keywords.add(keywordInput.trim())
                                        keywordInput = ""
                                    }
                                    showAddKeywordField = false
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "Add")
                                }
                            }
                        } else {
                            TextButton(
                                onClick = { showAddKeywordField = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add address or phrase...")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === Recipients Section ===
                Text(
                    text = "Share with Contacts (${selectedAddresses.size}/5)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Search field
                OutlinedTextField(
                    value = contactSearchQuery,
                    onValueChange = { contactSearchQuery = it },
                    placeholder = { Text("Search contacts...") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Contact list
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
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
                    } else if (sortedContacts.isEmpty()) {
                        Text(
                            text = if (contactSearchQuery.isBlank()) "No contacts available" else "No contacts found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn {
                            items(sortedContacts.take(50)) { contact ->
                                // Get primary address (first phone or email)
                                val primaryAddress = contact.phoneNumbers.firstOrNull() ?: contact.emails.firstOrNull()
                                val isSelected = contact.phoneNumbers.any { it in selectedAddresses } ||
                                                contact.emails.any { it in selectedAddresses }

                                ContactListItem(
                                    contact = contact,
                                    isSelected = isSelected,
                                    onToggle = { selected ->
                                        primaryAddress?.let { address ->
                                            if (selected && selectedAddresses.size < 5) {
                                                selectedAddresses.add(address)
                                            } else if (!selected) {
                                                // Remove any matching address
                                                contact.phoneNumbers.forEach { selectedAddresses.remove(it) }
                                                contact.emails.forEach { selectedAddresses.remove(it) }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Build recipients from selected addresses
                    val recipients = selectedAddresses.mapNotNull { address ->
                        val contact = availableContacts.find { c ->
                            c.phoneNumbers.contains(address) || c.emails.contains(address)
                        }
                        contact?.let {
                            AutoShareRecipient(chatGuid = address, displayName = it.displayName)
                        }
                    }
                    onSave(destinationName, keywords.toList(), locationType, recipients)
                },
                enabled = isValid && !isLoading
            ) {
                Text(if (existingRule == null) "Add" else "Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Icon picker dropdown for location type selection.
 */
@Composable
private fun IconPickerDropdown(
    selectedType: LocationType,
    onTypeSelected: (LocationType) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = selectedType.icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Select icon",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            LocationType.entries.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = type.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(type.displayName)
                        }
                    },
                    onClick = {
                        onTypeSelected(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Contact list item with checkbox.
 */
@Composable
private fun ContactListItem(
    contact: PhoneContact,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val primaryAddress = contact.phoneNumbers.firstOrNull() ?: contact.emails.firstOrNull() ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isSelected) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar placeholder
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
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

        Checkbox(
            checked = isSelected,
            onCheckedChange = onToggle
        )
    }
}

/**
 * Simple chat info for backward compatibility.
 */
data class ChatInfo(
    val guid: String,
    val displayName: String,
    val isGroup: Boolean = false
)

// Extension properties for LocationType
val LocationType.icon: ImageVector
    get() = when (this) {
        LocationType.HOME -> Icons.Default.Home
        LocationType.WORK -> Icons.Default.Work
        LocationType.SCHOOL -> SchoolIcon
        LocationType.GYM -> GymIcon
        LocationType.CUSTOM -> Icons.Default.Place
    }

val LocationType.displayName: String
    get() = when (this) {
        LocationType.HOME -> "Home"
        LocationType.WORK -> "Work"
        LocationType.SCHOOL -> "School"
        LocationType.GYM -> "Gym"
        LocationType.CUSTOM -> "Custom"
    }

// Custom icons using existing Material icons as stand-ins
// School icon - using AccountBalance-like appearance
private val SchoolIcon: ImageVector
    get() = Icons.Default.Place // Will use Place as fallback, can be replaced with proper icon

// Gym icon - using FitnessCenter-like appearance
private val GymIcon: ImageVector
    get() = Icons.Default.Place // Will use Place as fallback, can be replaced with proper icon
