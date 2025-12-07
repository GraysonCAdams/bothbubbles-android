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
    onSpamClick: () -> Unit = {},
    onCategorizationClick: () -> Unit = {},
    onSyncSettingsClick: () -> Unit = {},
    onSmsSettingsClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onNotificationProviderClick: () -> Unit = {},
    onSwipeSettingsClick: () -> Unit = {},
    onEffectsSettingsClick: () -> Unit = {},
    onTemplatesClick: () -> Unit = {},
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
        SettingsContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            uiState = uiState,
            onServerSettingsClick = onServerSettingsClick,
            onArchivedClick = onArchivedClick,
            onBlockedClick = onBlockedClick,
            onSpamClick = onSpamClick,
            onCategorizationClick = onCategorizationClick,
            onSyncSettingsClick = onSyncSettingsClick,
            onSmsSettingsClick = onSmsSettingsClick,
            onNotificationsClick = onNotificationsClick,
            onNotificationProviderClick = onNotificationProviderClick,
            onSwipeSettingsClick = onSwipeSettingsClick,
            onEffectsSettingsClick = onEffectsSettingsClick,
            onTemplatesClick = onTemplatesClick,
            onAboutClick = onAboutClick,
            viewModel = viewModel
        )
    }

    // Error snackbar
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Show error and clear it
            viewModel.clearError()
        }
    }
}

@Composable
fun SettingsContent(
    modifier: Modifier = Modifier,
    uiState: SettingsUiState,
    onServerSettingsClick: () -> Unit,
    onArchivedClick: () -> Unit,
    onBlockedClick: () -> Unit,
    onSpamClick: () -> Unit,
    onCategorizationClick: () -> Unit,
    onSyncSettingsClick: () -> Unit,
    onExportClick: () -> Unit = {},
    onSmsSettingsClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onNotificationProviderClick: () -> Unit,
    onSwipeSettingsClick: () -> Unit,
    onEffectsSettingsClick: () -> Unit,
    onTemplatesClick: () -> Unit,
    onAboutClick: () -> Unit,
    viewModel: SettingsViewModel
) {
    LazyColumn(
        modifier = modifier,
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Spam protection
                SettingsMenuItem(
                    icon = Icons.Default.Shield,
                    title = "Spam protection",
                    subtitle = "Automatic spam detection settings",
                    onClick = onSpamClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Message categorization
                SettingsMenuItem(
                    icon = Icons.Default.Category,
                    title = "Message categorization",
                    subtitle = "Sort messages into categories with ML",
                    onClick = onCategorizationClick
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

                // Notification Provider (FCM vs Foreground Service)
                SettingsMenuItem(
                    icon = Icons.Default.CloudSync,
                    title = "Notification provider",
                    subtitle = "FCM push or foreground service",
                    onClick = onNotificationProviderClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // SMS/MMS settings
                SettingsMenuItem(
                    icon = Icons.Default.CellTower,
                    title = stringResource(R.string.settings_sms),
                    subtitle = "Local SMS messaging options",
                    onClick = onSmsSettingsClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Quick reply templates
                SettingsMenuItem(
                    icon = Icons.Default.Quickreply,
                    title = "Quick reply templates",
                    subtitle = "Saved responses and smart suggestions",
                    onClick = onTemplatesClick
                )
            }
        }

        // iMessage Features Section
        item {
            SettingsSectionTitle(
                title = if (uiState.isServerConfigured) "iMessage features" else "iMessage features (server required)"
            )
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Private API toggle
                SettingsMenuItem(
                    icon = Icons.Default.VpnKey,
                    title = "Enable Private API",
                    subtitle = when {
                        !uiState.isServerConfigured -> "Configure server to enable"
                        uiState.enablePrivateApi -> "Advanced iMessage features enabled"
                        else -> "Enables typing indicators, reactions, and more"
                    },
                    onClick = {
                        if (uiState.isServerConfigured) {
                            viewModel.setEnablePrivateApi(!uiState.enablePrivateApi)
                        }
                    },
                    enabled = uiState.isServerConfigured,
                    trailingContent = {
                        Switch(
                            checked = uiState.enablePrivateApi && uiState.isServerConfigured,
                            onCheckedChange = { viewModel.setEnablePrivateApi(it) },
                            enabled = uiState.isServerConfigured
                        )
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Send typing indicators toggle
                SettingsMenuItem(
                    icon = Icons.Default.Keyboard,
                    title = "Send typing indicators",
                    subtitle = if (!uiState.isServerConfigured) "Configure server to enable" else "Let others know when you're typing",
                    onClick = {
                        if (uiState.isServerConfigured && uiState.enablePrivateApi) {
                            viewModel.setSendTypingIndicators(!uiState.sendTypingIndicators)
                        }
                    },
                    enabled = uiState.isServerConfigured && uiState.enablePrivateApi,
                    trailingContent = {
                        Switch(
                            checked = uiState.sendTypingIndicators && uiState.enablePrivateApi && uiState.isServerConfigured,
                            onCheckedChange = { viewModel.setSendTypingIndicators(it) },
                            enabled = uiState.isServerConfigured && uiState.enablePrivateApi
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

                // Message sounds toggle
                SettingsMenuItem(
                    icon = Icons.Default.VolumeUp,
                    title = "Message sounds",
                    subtitle = "Play sounds when sending and receiving messages",
                    onClick = { viewModel.setMessageSoundsEnabled(!uiState.messageSoundsEnabled) },
                    trailingContent = {
                        Switch(
                            checked = uiState.messageSoundsEnabled,
                            onCheckedChange = { viewModel.setMessageSoundsEnabled(it) }
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Export messages
                SettingsMenuItem(
                    icon = Icons.Default.Download,
                    title = "Export messages",
                    subtitle = "Save conversations as HTML or PDF",
                    onClick = onExportClick
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
