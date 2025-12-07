package com.bothbubbles.ui.settings.templates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.data.local.db.entity.QuickReplyTemplateEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReplyTemplatesScreen(
    onNavigateBack: () -> Unit,
    viewModel: QuickReplyTemplatesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<QuickReplyTemplateEntity?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick reply templates") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add template")
                    }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset to defaults")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.templates.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Quickreply,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No templates yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Create quick reply templates for common responses",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        OutlinedButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add template")
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item {
                        Text(
                            text = "Templates appear as suggestion chips above the message input and in notification quick replies. Favorites and most-used templates are shown first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(
                        items = uiState.templates,
                        key = { it.id }
                    ) { template ->
                        TemplateItem(
                            template = template,
                            onEdit = { showEditDialog = template },
                            onDelete = { viewModel.deleteTemplate(template.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(template.id) }
                        )
                    }
                }
            }
        }
    }

    // Add template dialog
    if (showAddDialog) {
        TemplateDialog(
            title = "Add template",
            initialTitle = "",
            initialText = "",
            onDismiss = { showAddDialog = false },
            onConfirm = { title, text ->
                viewModel.createTemplate(title, text)
                showAddDialog = false
            }
        )
    }

    // Edit template dialog
    showEditDialog?.let { template ->
        TemplateDialog(
            title = "Edit template",
            initialTitle = template.title,
            initialText = template.text,
            onDismiss = { showEditDialog = null },
            onConfirm = { title, text ->
                viewModel.updateTemplate(
                    template.copy(title = title, text = text)
                )
                showEditDialog = null
            }
        )
    }

    // Reset confirmation dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset templates?") },
            text = { Text("This will delete all your templates and restore the defaults.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetToDefaults()
                        showResetDialog = false
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TemplateItem(
    template: QuickReplyTemplateEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                text = template.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = if (template.title != template.text) {
            {
                Text(
                    text = template.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else null,
        leadingContent = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (template.isFavorite) Icons.Default.Star else Icons.Default.StarOutline,
                    contentDescription = if (template.isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (template.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                    )
                }
            }
        }
    )
    HorizontalDivider()
}

@Composable
private fun TemplateDialog(
    title: String,
    initialTitle: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, text: String) -> Unit
) {
    var templateTitle by remember { mutableStateOf(initialTitle) }
    var templateText by remember { mutableStateOf(initialText) }
    var useSameText by remember { mutableStateOf(initialTitle == initialText || initialText.isEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = templateTitle,
                    onValueChange = {
                        templateTitle = it
                        if (useSameText) {
                            templateText = it
                        }
                    },
                    label = { Text("Chip label") },
                    placeholder = { Text("On my way!") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Short text shown on the chip (max ~25 chars)") }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = useSameText,
                        onCheckedChange = {
                            useSameText = it
                            if (it) {
                                templateText = templateTitle
                            }
                        }
                    )
                    Text(
                        text = "Use same text for message",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (!useSameText) {
                    OutlinedTextField(
                        value = templateText,
                        onValueChange = { templateText = it },
                        label = { Text("Full message") },
                        placeholder = { Text("I'm on my way! Be there in a few minutes.") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        supportingText = { Text("Full text inserted when selected") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalText = if (useSameText) templateTitle else templateText
                    onConfirm(templateTitle.trim(), finalText.trim())
                },
                enabled = templateTitle.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
