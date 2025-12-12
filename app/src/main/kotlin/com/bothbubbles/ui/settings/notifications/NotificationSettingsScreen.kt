package com.bothbubbles.ui.settings.notifications

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.settings.components.SettingsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Check system notification permission
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PermissionChecker.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        NotificationSettingsContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            hasNotificationPermission = hasNotificationPermission,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            viewModel = viewModel
        )
    }
}

@Composable
fun NotificationSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
    uiState: NotificationSettingsUiState = viewModel.uiState.collectAsStateWithLifecycle().value,
    hasNotificationPermission: Boolean = true,
    onRequestPermission: () -> Unit = {}
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            // System permission card (if needed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.NotificationsOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Notifications disabled",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Allow notifications to receive message alerts",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Enable")
                        }
                    }
                }
            }

            // Main notifications toggle
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Enable notifications") },
                    supportingContent = { Text("Receive alerts for new messages") },
                    leadingContent = {
                        Icon(Icons.Default.Notifications, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(
                            checked = uiState.notificationsEnabled,
                            onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                        )
                    }
                )
            }

            // Additional notification settings
            if (uiState.notificationsEnabled) {
                SettingsCard {
                    Column {
                        ListItem(
                            headlineContent = { Text("Notify on chat list") },
                            supportingContent = { Text("Show notifications even when viewing conversations") },
                            leadingContent = {
                                Icon(Icons.Default.Forum, contentDescription = null)
                            },
                            trailingContent = {
                                Switch(
                                    checked = uiState.notifyOnChatList,
                                    onCheckedChange = { viewModel.setNotifyOnChatList(it) }
                                )
                            }
                        )
                    }
                }

                // Chat bubbles filter setting
                var showBubbleFilterDialog by remember { mutableStateOf(false) }

                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Chat bubbles") },
                        supportingContent = {
                            Text(getBubbleFilterDescription(uiState.bubbleFilterMode))
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable { showBubbleFilterDialog = true }
                    )
                }

                if (showBubbleFilterDialog) {
                    BubbleFilterDialog(
                        currentMode = uiState.bubbleFilterMode,
                        onModeSelected = { mode ->
                            viewModel.setBubbleFilterMode(mode)
                            showBubbleFilterDialog = false
                        },
                        onDismiss = { showBubbleFilterDialog = false }
                    )
                }

                // System notification settings
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Notification channels") },
                        supportingContent = { Text("Configure sounds and vibration") },
                        leadingContent = {
                            Icon(Icons.Default.Tune, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }

private fun getBubbleFilterDescription(mode: String): String {
    return when (mode) {
        "all" -> "All conversations"
        "favorites" -> "Favorite contacts only"
        "selected" -> "Selected conversations"
        "none" -> "Disabled"
        else -> "All conversations"
    }
}

@Composable
private fun BubbleFilterDialog(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        "all" to "All conversations",
        "favorites" to "Favorite contacts only",
        "selected" to "Selected conversations",
        "none" to "Disabled"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show chat bubbles for") },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentMode == mode,
                                onClick = { onModeSelected(mode) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = null // null because parent handles click
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (mode == "favorites") {
                                Text(
                                    text = "Based on starred contacts in Android",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
