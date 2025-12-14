package com.bothbubbles.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.R
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.sound.SoundTheme
import com.bothbubbles.ui.settings.components.BadgeStatus
import com.bothbubbles.ui.settings.components.MessagingSectionHeader
import com.bothbubbles.ui.settings.components.SettingsCard
import com.bothbubbles.ui.settings.components.SettingsMenuItem
import com.bothbubbles.ui.settings.components.SettingsSectionTitle
import com.bothbubbles.ui.settings.components.SettingsSwitch

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
    onExportClick: () -> Unit = {},
    onSmsSettingsClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onSwipeSettingsClick: () -> Unit = {},
    onEffectsSettingsClick: () -> Unit = {},
    onImageQualityClick: () -> Unit = {},
    onTemplatesClick: () -> Unit = {},
    onAutoResponderClick: () -> Unit = {},
    onEtaSharingClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // MD3 LargeTopAppBar with scroll-to-collapse behavior
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
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
            onExportClick = onExportClick,
            onSmsSettingsClick = onSmsSettingsClick,
            onNotificationsClick = onNotificationsClick,
            onSwipeSettingsClick = onSwipeSettingsClick,
            onEffectsSettingsClick = onEffectsSettingsClick,
            onImageQualityClick = onImageQualityClick,
            onTemplatesClick = onTemplatesClick,
            onAutoResponderClick = onAutoResponderClick,
            onEtaSharingClick = onEtaSharingClick,
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
    onSwipeSettingsClick: () -> Unit,
    onEffectsSettingsClick: () -> Unit,
    onImageQualityClick: () -> Unit,
    onTemplatesClick: () -> Unit,
    onAutoResponderClick: () -> Unit,
    onEtaSharingClick: () -> Unit = {},
    onAboutClick: () -> Unit,
    viewModel: SettingsViewModel
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
    ) {
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
            val iMessageStatus = when (uiState.connectionState) {
                ConnectionState.CONNECTED -> BadgeStatus.CONNECTED
                ConnectionState.NOT_CONFIGURED -> BadgeStatus.DISABLED
                else -> BadgeStatus.ERROR
            }
            val smsStatus = if (uiState.smsEnabled) BadgeStatus.CONNECTED else BadgeStatus.DISABLED

            MessagingSectionHeader(
                iMessageStatus = iMessageStatus,
                smsStatus = smsStatus,
                onIMessageClick = onServerSettingsClick,
                onSmsClick = onSmsSettingsClick
            )
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

                // iMessage (BlueBubbles server)
                SettingsMenuItem(
                    icon = Icons.Default.Cloud,
                    title = "iMessage",
                    subtitle = "BlueBubbles server settings",
                    onClick = onServerSettingsClick
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Auto-responder
                SettingsMenuItem(
                    icon = Icons.Default.SmartToy,
                    title = "Auto-responder",
                    subtitle = "Greet first-time iMessage contacts",
                    onClick = onAutoResponderClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // ETA Sharing
                SettingsMenuItem(
                    icon = Icons.Outlined.Navigation,
                    title = "ETA sharing",
                    subtitle = "Share arrival time while navigating",
                    onClick = onEtaSharingClick
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
                // Private API toggle - uses icon switch for emphasis
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
                        SettingsSwitch(
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
                        SettingsSwitch(
                            checked = uiState.sendTypingIndicators && uiState.enablePrivateApi && uiState.isServerConfigured,
                            onCheckedChange = { viewModel.setSendTypingIndicators(it) },
                            enabled = uiState.isServerConfigured && uiState.enablePrivateApi,
                            showIcons = false  // Secondary toggle, no icons
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
                        SettingsSwitch(
                            checked = uiState.useSimpleAppTitle,
                            onCheckedChange = { viewModel.setUseSimpleAppTitle(it) },
                            showIcons = false
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

                // Image quality
                SettingsMenuItem(
                    icon = Icons.Default.HighQuality,
                    title = "Image quality",
                    subtitle = "Compression settings for photo attachments",
                    onClick = onImageQualityClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Message sounds toggle
                SettingsMenuItem(
                    icon = Icons.Default.VolumeUp,
                    title = "Message sounds",
                    subtitle = "Play sounds when sending and receiving messages",
                    onClick = { viewModel.setMessageSoundsEnabled(!uiState.messageSoundsEnabled) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.messageSoundsEnabled,
                            onCheckedChange = { viewModel.setMessageSoundsEnabled(it) },
                            showIcons = false
                        )
                    }
                )

                // Sound theme picker (only show when sounds are enabled)
                if (uiState.messageSoundsEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    var showSoundPicker by remember { mutableStateOf(false) }

                    SettingsMenuItem(
                        icon = Icons.Default.MusicNote,
                        title = "Sound theme",
                        subtitle = uiState.soundTheme.displayName,
                        onClick = { showSoundPicker = true }
                    )

                    if (showSoundPicker) {
                        SoundThemePickerDialog(
                            currentTheme = uiState.soundTheme,
                            onThemeSelected = { theme ->
                                viewModel.setSoundTheme(theme)
                                showSoundPicker = false
                            },
                            onDismiss = { showSoundPicker = false }
                        )
                    }
                }
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Link previews toggle
                SettingsMenuItem(
                    icon = Icons.Default.Link,
                    title = "Link previews",
                    subtitle = if (uiState.linkPreviewsEnabled) "Show rich previews for URLs" else "Disabled to improve performance",
                    onClick = { viewModel.setLinkPreviewsEnabled(!uiState.linkPreviewsEnabled) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.linkPreviewsEnabled,
                            onCheckedChange = { viewModel.setLinkPreviewsEnabled(it) },
                            showIcons = false
                        )
                    }
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

/**
 * Dialog for selecting a sound theme.
 * Plays both inbound and outbound sounds as a preview when a theme is selected.
 */
@Composable
private fun SoundThemePickerDialog(
    currentTheme: SoundTheme,
    onThemeSelected: (SoundTheme) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sound theme") },
        text = {
            Column {
                SoundTheme.entries.forEach { theme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = theme == currentTheme,
                                onClick = { onThemeSelected(theme) }
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = theme == currentTheme,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = theme.displayName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
