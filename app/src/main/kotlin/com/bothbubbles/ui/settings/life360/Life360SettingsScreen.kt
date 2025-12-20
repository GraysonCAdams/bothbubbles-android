package com.bothbubbles.ui.settings.life360

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bothbubbles.core.model.Life360Member
import com.bothbubbles.core.model.entity.HandleEntity
import com.bothbubbles.core.model.entity.displayNameSimple
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Life360SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: Life360SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val members by viewModel.members.collectAsState()
    val isEnabled by viewModel.isEnabled.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val availableHandles by viewModel.availableHandles.collectAsState()
    val handleDisplayNames by viewModel.handleDisplayNames.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Life360 Integration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isAuthenticated) {
                        IconButton(
                            onClick = viewModel::syncMembers,
                            enabled = !uiState.isSyncing
                        ) {
                            if (uiState.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Sync")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (!uiState.isAuthenticated) {
                TokenEntryScreen(
                    onTokenSubmit = viewModel::storeToken
                )
            } else {
                Life360SettingsContentInternal(
                    uiState = uiState,
                    members = members,
                    isEnabled = isEnabled,
                    isPaused = isPaused,
                    availableHandles = availableHandles,
                    handleDisplayNames = handleDisplayNames,
                    onSetEnabled = viewModel::setEnabled,
                    onSetPaused = viewModel::setPaused,
                    onMapMember = viewModel::mapMemberToContact,
                    onUnmapMember = viewModel::unmapMember,
                    onLogout = viewModel::logout,
                    onClearError = viewModel::clearError
                )
            }
        }
    }
}

/**
 * Life360 settings content for use in SettingsPanel.
 * Shows token entry if not authenticated, otherwise shows settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Life360SettingsContent(
    modifier: Modifier = Modifier,
    viewModel: Life360SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val members by viewModel.members.collectAsState()
    val isEnabled by viewModel.isEnabled.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val availableHandles by viewModel.availableHandles.collectAsState()
    val handleDisplayNames by viewModel.handleDisplayNames.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        if (!uiState.isAuthenticated) {
            TokenEntryScreen(
                onTokenSubmit = viewModel::storeToken
            )
        } else {
            Life360SettingsContentInternal(
                uiState = uiState,
                members = members,
                isEnabled = isEnabled,
                isPaused = isPaused,
                availableHandles = availableHandles,
                handleDisplayNames = handleDisplayNames,
                onSetEnabled = viewModel::setEnabled,
                onSetPaused = viewModel::setPaused,
                onMapMember = viewModel::mapMemberToContact,
                onUnmapMember = viewModel::unmapMember,
                onLogout = viewModel::logout,
                onClearError = viewModel::clearError
            )
        }
    }
}

/**
 * Login method selection for Life360.
 */
private enum class LoginMethod {
    NONE,
    WEBVIEW,
    MANUAL
}

@Composable
private fun TokenEntryScreen(
    onTokenSubmit: (String) -> Unit
) {
    var loginMethod by remember { mutableStateOf(LoginMethod.NONE) }
    var manualToken by remember { mutableStateOf("") }
    var showManualEntry by remember { mutableStateOf(false) }

    when (loginMethod) {
        LoginMethod.WEBVIEW -> {
            Life360LoginWebView(
                onTokenExtracted = onTokenSubmit
            )
        }

        LoginMethod.NONE, LoginMethod.MANUAL -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Connect to Life360",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Sign in to show family member locations in your chats.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Primary option: WebView login
                Button(
                    onClick = { loginMethod = LoginMethod.WEBVIEW },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign in with Life360")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Expandable manual entry section
                if (!showManualEntry) {
                    TextButton(onClick = { showManualEntry = true }) {
                        Text("Enter token manually")
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Manual Token Entry",
                                style = MaterialTheme.typography.titleSmall
                            )

                            Text(
                                text = "Open life360.com in a browser, log in, then find the access_token in the Network tab (Developer Tools).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = manualToken,
                                onValueChange = { manualToken = it },
                                label = { Text("Access Token") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showManualEntry = false }) {
                                    Text("Cancel")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onTokenSubmit(manualToken) },
                                    enabled = manualToken.isNotBlank()
                                ) {
                                    Text("Connect")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Life360SettingsContentInternal(
    uiState: Life360UiState,
    members: ImmutableList<Life360Member>,
    isEnabled: Boolean,
    isPaused: Boolean,
    availableHandles: ImmutableList<HandleEntity>,
    handleDisplayNames: ImmutableMap<Long, String>,
    onSetEnabled: (Boolean) -> Unit,
    onSetPaused: (Boolean) -> Unit,
    onMapMember: (String, Long) -> Unit,
    onUnmapMember: (String) -> Unit,
    onLogout: () -> Unit,
    onClearError: () -> Unit
) {
    var memberToMap by remember { mutableStateOf<Life360Member?>(null) }

    // Contact picker dialog
    memberToMap?.let { member ->
        ContactPickerDialog(
            memberName = member.displayName,
            handles = availableHandles,
            onSelect = { handleId ->
                onMapMember(member.memberId, handleId)
                memberToMap = null
            },
            onDismiss = { memberToMap = null }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Error banner
        uiState.error?.let { error ->
            item {
                Snackbar(
                    action = {
                        TextButton(onClick = onClearError) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(error)
                    }
                }
            }
        }

        // Enable/Disable toggle
        item {
            SettingsRow(
                title = "Enable Life360",
                subtitle = "Show family member locations in conversations"
            ) {
                Switch(checked = isEnabled, onCheckedChange = onSetEnabled)
            }
        }

        // Ghost mode toggle
        item {
            SettingsRow(
                title = "Pause Syncing",
                subtitle = "Temporarily stop updating locations"
            ) {
                Switch(checked = isPaused, onCheckedChange = onSetPaused)
            }
        }

        // Members list with contact mapping
        if (members.isNotEmpty()) {
            item {
                Text(
                    text = "Circle Members",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                Text(
                    text = "Tap a member to link them to a contact",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(members, key = { it.memberId }) { member ->
                MemberCard(
                    member = member,
                    mappedContactName = member.mappedHandleId?.let { handleDisplayNames[it] },
                    onMapClick = { memberToMap = member },
                    onUnmapClick = { onUnmapMember(member.memberId) }
                )
            }
        }

        // Logout button
        item {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect Life360")
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}

@Composable
private fun MemberCard(
    member: Life360Member,
    mappedContactName: String?,
    onMapClick: () -> Unit,
    onUnmapClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onMapClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            if (member.avatarUrl != null) {
                AsyncImage(
                    model = member.avatarUrl,
                    contentDescription = member.displayName,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            } else {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Contact mapping status
                if (mappedContactName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = mappedContactName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = "Not linked to contact",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Unlink button if mapped
            if (mappedContactName != null) {
                IconButton(
                    onClick = onUnmapClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Unlink contact",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Battery indicator
            member.battery?.let { battery ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$battery%",
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (member.isCharging == true) {
                        Text(
                            text = "Charging",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog for picking a contact to map to a Life360 member.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactPickerDialog(
    memberName: String,
    handles: ImmutableList<HandleEntity>,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredHandles = remember(handles, searchQuery) {
        if (searchQuery.isBlank()) {
            handles
        } else {
            handles.filter { handle ->
                handle.displayNameSimple.contains(searchQuery, ignoreCase = true) ||
                    handle.address.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link $memberName to contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search contacts") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(filteredHandles, key = { it.id }) { handle ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = handle.displayNameSimple,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = handle.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = handle.initials,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onSelect(handle.id) }
                        )
                        HorizontalDivider()
                    }

                    if (filteredHandles.isEmpty()) {
                        item {
                            Text(
                                text = if (searchQuery.isBlank()) "No contacts available" else "No matching contacts",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
