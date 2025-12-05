package com.bluebubbles.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluebubbles.R
import com.bluebubbles.services.socket.ConnectionState
import com.bluebubbles.ui.settings.components.ProfileHeader
import com.bluebubbles.ui.settings.components.SettingsCard
import com.bluebubbles.ui.settings.components.SettingsMenuItem
import com.bluebubbles.ui.settings.components.SettingsSectionTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onServerSettingsClick: () -> Unit = {},
    onArchivedClick: () -> Unit = {},
    onBlockedClick: () -> Unit = {},
    onSyncSettingsClick: () -> Unit = {},
    onSmsSettingsClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onSwipeSettingsClick: () -> Unit = {},
    onEffectsSettingsClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Profile Header
            item {
                ProfileHeader(
                    serverUrl = uiState.serverUrl,
                    connectionState = uiState.connectionState,
                    onManageServerClick = onServerSettingsClick
                )
            }

            // Quick Actions Card
            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Mark all as read (most common action first)
                    SettingsMenuItem(
                        icon = Icons.Default.DoneAll,
                        title = "Mark all as read",
                        subtitle = if (uiState.unreadCount > 0) {
                            "${uiState.unreadCount} unread conversation${if (uiState.unreadCount > 1) "s" else ""}"
                        } else {
                            "All caught up!"
                        },
                        onClick = { viewModel.markAllAsRead() },
                        enabled = !uiState.isMarkingAllRead && uiState.unreadCount > 0,
                        trailingContent = if (uiState.isMarkingAllRead) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else null
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Archived
                    SettingsMenuItem(
                        icon = Icons.Outlined.Archive,
                        title = "Archived",
                        onClick = onArchivedClick,
                        trailingContent = if (uiState.archivedCount > 0) {
                            {
                                Text(
                                    text = uiState.archivedCount.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else null
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Blocked contacts
                    SettingsMenuItem(
                        icon = Icons.Default.Block,
                        title = "Blocked contacts",
                        onClick = onBlockedClick
                    )
                }
            }

            // Messaging Section
            item {
                SettingsSectionTitle(title = "Messaging")
            }

            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    // Notifications
                    SettingsMenuItem(
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.settings_notifications),
                        subtitle = "Sound, vibration, and display",
                        onClick = onNotificationsClick
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // SMS/MMS settings
                    SettingsMenuItem(
                        icon = Icons.Default.Sms,
                        title = stringResource(R.string.settings_sms),
                        subtitle = "Local SMS messaging options",
                        onClick = onSmsSettingsClick
                    )
                }
            }

            // iMessage Features Section
            item {
                SettingsSectionTitle(title = "iMessage features")
            }

            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    // Private API toggle
                    SettingsMenuItem(
                        icon = Icons.Default.VpnKey,
                        title = "Enable Private API",
                        subtitle = if (uiState.enablePrivateApi) "Advanced iMessage features enabled" else "Enables typing indicators, reactions, and more",
                        onClick = { viewModel.setEnablePrivateApi(!uiState.enablePrivateApi) },
                        trailingContent = {
                            Switch(
                                checked = uiState.enablePrivateApi,
                                onCheckedChange = { viewModel.setEnablePrivateApi(it) }
                            )
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Send typing indicators toggle
                    SettingsMenuItem(
                        icon = Icons.Default.Keyboard,
                        title = "Send typing indicators",
                        subtitle = "Let others know when you're typing",
                        onClick = {
                            if (uiState.enablePrivateApi) {
                                viewModel.setSendTypingIndicators(!uiState.sendTypingIndicators)
                            }
                        },
                        enabled = uiState.enablePrivateApi,
                        trailingContent = {
                            Switch(
                                checked = uiState.sendTypingIndicators && uiState.enablePrivateApi,
                                onCheckedChange = { viewModel.setSendTypingIndicators(it) },
                                enabled = uiState.enablePrivateApi
                            )
                        }
                    )
                }
            }

            // Appearance & Behavior Section
            item {
                SettingsSectionTitle(title = "Appearance & behavior")
            }

            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    // Simple app title toggle
                    SettingsMenuItem(
                        icon = Icons.Default.TextFields,
                        title = "Simple app title",
                        subtitle = if (uiState.useSimpleAppTitle) "Showing \"Messages\"" else "Showing \"BothBubbles\"",
                        onClick = { viewModel.setUseSimpleAppTitle(!uiState.useSimpleAppTitle) },
                        trailingContent = {
                            Switch(
                                checked = uiState.useSimpleAppTitle,
                                onCheckedChange = { viewModel.setUseSimpleAppTitle(it) }
                            )
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Swipe gestures
                    SettingsMenuItem(
                        icon = Icons.Default.SwipeRight,
                        title = "Swipe actions",
                        subtitle = "Customize conversation swipe gestures",
                        onClick = onSwipeSettingsClick
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Message effects
                    SettingsMenuItem(
                        icon = Icons.Default.AutoAwesome,
                        title = "Message effects",
                        subtitle = "Animations for screen and bubble effects",
                        onClick = onEffectsSettingsClick
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Send sound toggle
                    SettingsMenuItem(
                        icon = Icons.Default.VolumeUp,
                        title = "Send sound",
                        subtitle = "Play sound when sending messages",
                        onClick = { viewModel.setSendSoundEnabled(!uiState.sendSoundEnabled) },
                        trailingContent = {
                            Switch(
                                checked = uiState.sendSoundEnabled,
                                onCheckedChange = { viewModel.setSendSoundEnabled(it) }
                            )
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Receive sound toggle
                    SettingsMenuItem(
                        icon = Icons.Default.VolumeDown,
                        title = "Receive sound",
                        subtitle = "Play sound when receiving messages",
                        onClick = { viewModel.setReceiveSoundEnabled(!uiState.receiveSoundEnabled) },
                        trailingContent = {
                            Switch(
                                checked = uiState.receiveSoundEnabled,
                                onCheckedChange = { viewModel.setReceiveSoundEnabled(it) }
                            )
                        }
                    )
                }
            }

            // Connection & Data Section
            item {
                SettingsSectionTitle(title = "Connection & data")
            }

            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    // Server pairing
                    SettingsMenuItem(
                        icon = Icons.Default.QrCodeScanner,
                        title = "Server pairing",
                        subtitle = "Reconnect or pair new server",
                        onClick = onServerSettingsClick
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Sync settings
                    SettingsMenuItem(
                        icon = Icons.Default.Sync,
                        title = "Sync settings",
                        subtitle = "Last synced: ${uiState.lastSyncFormatted}",
                        onClick = onSyncSettingsClick
                    )
                }
            }

            // About (always at bottom)
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    SettingsMenuItem(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.settings_about),
                        subtitle = "Version, licenses, and help",
                        onClick = onAboutClick
                    )
                }
            }
        }
    }

    // Error snackbar
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Show error and clear it
            viewModel.clearError()
        }
    }
}
