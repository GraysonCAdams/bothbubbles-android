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
import com.bothbubbles.core.data.ConnectionState
import kotlinx.coroutines.launch
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
    // Snackbar host state for disabled click feedback
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Helper to show snackbar with optional action
    fun showDisabledSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed && onAction != null) {
                onAction()
            }
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
        ) {
        // ═══════════════════════════════════════════════════════════════
        // SECTION 1: Connection Status Header
        // ═══════════════════════════════════════════════════════════════
        item {
            val iMessageStatus = when (uiState.connectionState) {
                ConnectionState.CONNECTED -> BadgeStatus.CONNECTED
                ConnectionState.NOT_CONFIGURED -> BadgeStatus.DISABLED
                else -> BadgeStatus.ERROR
            }
            val smsStatus = when {
                !uiState.smsEnabled -> BadgeStatus.DISABLED
                uiState.isSmsFullyFunctional -> BadgeStatus.CONNECTED
                else -> BadgeStatus.ERROR  // Enabled but needs setup
            }

            MessagingSectionHeader(
                iMessageStatus = iMessageStatus,
                smsStatus = smsStatus,
                onIMessageClick = onServerSettingsClick,
                onSmsClick = onSmsSettingsClick
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 2: Connection & Server
        // Focus: The "pipes" that make the app work
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Connection & server")
        }

        item {
            // Determine if we're in a connecting/loading state
            val isConnecting = uiState.connectionState == ConnectionState.CONNECTING
            val isDisconnected = uiState.connectionState == ConnectionState.DISCONNECTED ||
                    uiState.connectionState == ConnectionState.ERROR

            // State for Private API help sheet
            var showPrivateApiHelp by remember { mutableStateOf(false) }

            // Show help bottom sheet
            if (showPrivateApiHelp) {
                PrivateApiHelpSheet(
                    onDismiss = { showPrivateApiHelp = false },
                    onLearnMore = {
                        showPrivateApiHelp = false
                    }
                )
            }

            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // iMessage (BlueBubbles server)
                SettingsMenuItem(
                    icon = Icons.Default.Cloud,
                    title = "iMessage",
                    subtitle = "BlueBubbles server settings",
                    onClick = onServerSettingsClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Private API toggle - nested under iMessage
                SettingsMenuItem(
                    icon = Icons.Default.VpnKey,
                    title = "Enable Private API",
                    subtitle = when {
                        !uiState.isServerConfigured -> "Configure server to enable"
                        isConnecting -> "Connecting to server..."
                        isDisconnected -> "Server disconnected."
                        uiState.enablePrivateApi -> "Advanced iMessage features enabled"
                        else -> "Enables typing indicators, reactions, and more"
                    },
                    subtitleAction = if (isDisconnected && uiState.isServerConfigured) "Tap to reconnect" else null,
                    onSubtitleActionClick = if (isDisconnected && uiState.isServerConfigured) {
                        { viewModel.reconnect() }
                    } else null,
                    onClick = {
                        if (uiState.isServerConfigured && !isConnecting) {
                            viewModel.setEnablePrivateApi(!uiState.enablePrivateApi)
                        }
                    },
                    enabled = uiState.isServerConfigured && !isConnecting,
                    isLoading = isConnecting,
                    onInfoClick = { showPrivateApiHelp = true },
                    onDisabledClick = {
                        if (!uiState.isServerConfigured) {
                            showDisabledSnackbar(
                                message = "Configure iMessage server first",
                                actionLabel = "Configure",
                                onAction = onServerSettingsClick
                            )
                        }
                    },
                    trailingContent = if (!isConnecting) {
                        {
                            SettingsSwitch(
                                checked = uiState.enablePrivateApi && uiState.isServerConfigured,
                                onCheckedChange = { viewModel.setEnablePrivateApi(it) },
                                enabled = uiState.isServerConfigured && !isConnecting
                            )
                        }
                    } else null
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Send typing indicators - nested under Private API
                SettingsMenuItem(
                    icon = Icons.Default.Keyboard,
                    title = "Send typing indicators",
                    subtitle = when {
                        !uiState.isServerConfigured -> "Configure server to enable"
                        !uiState.enablePrivateApi -> "Enable Private API first"
                        else -> "Let others know when you're typing"
                    },
                    onClick = {
                        if (uiState.isServerConfigured && uiState.enablePrivateApi) {
                            viewModel.setSendTypingIndicators(!uiState.sendTypingIndicators)
                        }
                    },
                    enabled = uiState.isServerConfigured && uiState.enablePrivateApi,
                    onDisabledClick = {
                        when {
                            !uiState.isServerConfigured -> {
                                showDisabledSnackbar(
                                    message = "Configure iMessage server first",
                                    actionLabel = "Configure",
                                    onAction = onServerSettingsClick
                                )
                            }
                            !uiState.enablePrivateApi -> {
                                showDisabledSnackbar(
                                    message = "Enable Private API to use this feature"
                                )
                            }
                        }
                    },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.sendTypingIndicators && uiState.enablePrivateApi && uiState.isServerConfigured,
                            onCheckedChange = { viewModel.setSendTypingIndicators(it) },
                            enabled = uiState.isServerConfigured && uiState.enablePrivateApi,
                            showIcons = false
                        )
                    }
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

                // Sync settings
                SettingsMenuItem(
                    icon = Icons.Default.Sync,
                    title = "Sync settings",
                    subtitle = "Last synced: ${uiState.lastSyncFormatted}",
                    onClick = onSyncSettingsClick
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 3: Notifications & Alerts
        // Focus: How the app gets your attention
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Notifications & alerts")
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

        // ═══════════════════════════════════════════════════════════════
        // SECTION 4: Appearance & Interaction
        // Focus: Visual customization and tactile feedback
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Appearance & interaction")
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

                // Message effects
                SettingsMenuItem(
                    icon = Icons.Default.AutoAwesome,
                    title = "Message effects",
                    subtitle = "Animations for screen and bubble effects",
                    onClick = onEffectsSettingsClick
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

                // Haptic feedback toggle
                SettingsMenuItem(
                    icon = Icons.Default.Vibration,
                    title = "Haptic feedback",
                    subtitle = if (uiState.hapticsEnabled) "Vibration feedback enabled" else "Vibration feedback disabled",
                    onClick = { viewModel.setHapticsEnabled(!uiState.hapticsEnabled) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.hapticsEnabled,
                            onCheckedChange = { viewModel.setHapticsEnabled(it) },
                            showIcons = false
                        )
                    }
                )

                // Audio-haptic sync toggle (only show when haptics are enabled)
                if (uiState.hapticsEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    SettingsMenuItem(
                        icon = Icons.Default.GraphicEq,
                        title = "Sync haptics with sounds",
                        subtitle = "Play haptic patterns matched to sound effects",
                        onClick = { viewModel.setAudioHapticSyncEnabled(!uiState.audioHapticSyncEnabled) },
                        trailingContent = {
                            SettingsSwitch(
                                checked = uiState.audioHapticSyncEnabled,
                                onCheckedChange = { viewModel.setAudioHapticSyncEnabled(it) },
                                showIcons = false
                            )
                        }
                    )
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 5: Messaging Features
        // Focus: Enhancements to the composing and sending experience
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Messaging features")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Image quality
                SettingsMenuItem(
                    icon = Icons.Default.HighQuality,
                    title = "Image quality",
                    subtitle = "Compression settings for photo attachments",
                    onClick = onImageQualityClick
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 6: Privacy & Organization
        // Focus: Managing the inbox and security
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Privacy & organization")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Blocked contacts
                SettingsMenuItem(
                    icon = Icons.Default.Block,
                    title = "Blocked contacts",
                    subtitle = if (!uiState.isDefaultSmsApp) "Requires default SMS app" else null,
                    onClick = onBlockedClick,
                    enabled = uiState.isDefaultSmsApp,
                    onDisabledClick = {
                        showDisabledSnackbar(
                            message = "Set as default SMS app to manage blocked contacts",
                            actionLabel = "SMS Settings",
                            onAction = onSmsSettingsClick
                        )
                    }
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
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 7: Data & Backup
        // Focus: Long-term data management
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Data & backup")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Export messages
                SettingsMenuItem(
                    icon = Icons.Default.Download,
                    title = "Export messages",
                    subtitle = "Save conversations as HTML or PDF",
                    onClick = onExportClick
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 8: About
        // Focus: App information (always at bottom)
        // ═══════════════════════════════════════════════════════════════
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

        // Snackbar host for disabled click feedback
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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

/**
 * Help bottom sheet explaining the Private API feature.
 * Provides context on what it enables and requirements.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateApiHelpSheet(
    onDismiss: () -> Unit,
    onLearnMore: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Title
            Text(
                text = "What is Private API?",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Description
            Text(
                text = "The Private API enables advanced iMessage features by accessing macOS system frameworks on your BlueBubbles server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Features section
            Text(
                text = "Enables:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            val features = listOf(
                "Typing indicators",
                "Read receipts",
                "Message reactions (tapbacks)",
                "Reply to specific messages",
                "Message editing & unsend",
                "Scheduled sends"
            )

            features.forEach { feature ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = feature,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Requirements section
            Text(
                text = "Requirements:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            val requirements = listOf(
                "SIP (System Integrity Protection) disabled on Mac",
                "BlueBubbles server configured for Private API",
                "macOS 11 (Big Sur) or later recommended"
            )

            requirements.forEach { requirement ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = requirement,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onLearnMore) {
                    Text("Learn more")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onDismiss) {
                    Text("Got it")
                }
            }
        }
    }
}
