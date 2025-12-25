package com.bothbubbles.ui.settings.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.calendar.DeviceCalendar
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.launch

/**
 * Settings screen for managing calendar associations with contacts.
 * Shows all existing associations and allows editing/removing them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: CalendarSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showEditDialog by remember { mutableStateOf<CalendarAssociationWithContact?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar Integrations") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            uiState.associations.isEmpty() -> {
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
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Calendar Associations",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Associate calendars with contacts from their conversation details to show events in chat headers.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
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
                            text = "Calendar events from these contacts will appear in chat headers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(
                        items = uiState.associations,
                        key = { it.association.linkedAddress }
                    ) { item ->
                        CalendarAssociationItem(
                            item = item,
                            onEditClick = { showEditDialog = item },
                            onRemoveClick = { viewModel.removeAssociation(item.association.linkedAddress) }
                        )
                    }
                }
            }
        }
    }

    // Edit dialog
    showEditDialog?.let { item ->
        CalendarEditDialog(
            currentCalendarId = item.association.calendarId,
            contactName = item.contactDisplayName ?: PhoneNumberFormatter.format(item.association.linkedAddress),
            onCalendarSelected = { calendar ->
                viewModel.updateAssociation(item.association.linkedAddress, calendar)
                showEditDialog = null
            },
            onDismiss = { showEditDialog = null },
            getCalendars = viewModel::getAvailableCalendars
        )
    }
}

/**
 * List item for a calendar association.
 */
@Composable
private fun CalendarAssociationItem(
    item: CalendarAssociationWithContact,
    onEditClick: () -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = {
            Text(
                text = item.contactDisplayName
                    ?: PhoneNumberFormatter.format(item.association.linkedAddress)
            )
        },
        supportingContent = {
            Column {
                Text(item.association.calendarDisplayName)
                item.association.accountName?.let { account ->
                    Text(
                        text = account,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        leadingContent = {
            Avatar(
                name = item.contactDisplayName ?: item.association.linkedAddress,
                avatarPath = item.avatarPath,
                size = 40.dp
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEditClick) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRemoveClick) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        modifier = modifier.clickable(onClick = onEditClick)
    )
}

/**
 * Dialog for editing calendar selection.
 */
@Composable
private fun CalendarEditDialog(
    currentCalendarId: Long?,
    contactName: String,
    onCalendarSelected: (DeviceCalendar) -> Unit,
    onDismiss: () -> Unit,
    getCalendars: suspend () -> List<DeviceCalendar>
) {
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            calendars = getCalendars()
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Calendar for $contactName") },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (calendars.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No calendars available")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(calendars) { calendar ->
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
                            trailingContent = if (calendar.id == currentCalendarId) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.CalendarMonth,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else null,
                            modifier = Modifier.clickable { onCalendarSelected(calendar) }
                        )
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
 * Content-only composable for embedding in the settings panel.
 * Excludes Scaffold and TopAppBar.
 */
@Composable
fun CalendarSettingsContent(
    viewModel: CalendarSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showEditDialog by remember { mutableStateOf<CalendarAssociationWithContact?>(null) }

    when {
        uiState.isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        uiState.associations.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Calendar Associations",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Associate calendars with contacts from their conversation details to show events in chat headers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                item {
                    Text(
                        text = "Calendar events from these contacts will appear in chat headers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                items(
                    items = uiState.associations,
                    key = { it.association.linkedAddress }
                ) { item ->
                    CalendarAssociationItem(
                        item = item,
                        onEditClick = { showEditDialog = item },
                        onRemoveClick = { viewModel.removeAssociation(item.association.linkedAddress) }
                    )
                }
            }
        }
    }

    // Edit dialog
    showEditDialog?.let { item ->
        CalendarEditDialog(
            currentCalendarId = item.association.calendarId,
            contactName = item.contactDisplayName ?: PhoneNumberFormatter.format(item.association.linkedAddress),
            onCalendarSelected = { calendar ->
                viewModel.updateAssociation(item.association.linkedAddress, calendar)
                showEditDialog = null
            },
            onDismiss = { showEditDialog = null },
            getCalendars = viewModel::getAvailableCalendars
        )
    }
}
