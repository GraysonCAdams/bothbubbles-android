package com.bothbubbles.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.R
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.core.network.api.dto.FindMyDeviceDto
import com.bothbubbles.core.network.api.dto.FindMyFriendDto
import kotlinx.coroutines.launch
import com.bothbubbles.services.sound.SoundTheme
import com.bothbubbles.ui.settings.components.BadgeStatus
import com.bothbubbles.ui.settings.components.SettingsCard
import com.bothbubbles.ui.settings.components.SettingsIconColors
import com.bothbubbles.ui.settings.components.SettingsMenuItem
import com.bothbubbles.ui.settings.components.SettingsSectionTitle
import com.bothbubbles.ui.settings.components.SettingsSwitch
import com.bothbubbles.ui.settings.components.StatusBadge

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
    onLife360Click: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onSocialMediaClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    onMediaContentClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Contacts permission launcher - opens settings if permission was permanently denied
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshContactsPermission()
        if (!granted) {
            // Check if we should show rationale - if false, user permanently denied
            val activity = context.findActivity()
            val shouldShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.READ_CONTACTS)
            } ?: true

            if (!shouldShowRationale) {
                // Permission permanently denied - open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    // Re-check contacts permission when returning from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshContactsPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Search state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchVisible by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

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
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            isSearchVisible = isSearchVisible,
            onSearchVisibilityChange = { isSearchVisible = it },
            focusRequester = focusRequester,
            listState = listState,
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
            onLife360Click = onLife360Click,
            onCalendarClick = onCalendarClick,
            onSocialMediaClick = onSocialMediaClick,
            onStorageClick = onStorageClick,
            onAboutClick = onAboutClick,
            onMediaContentClick = onMediaContentClick,
            onRequestContactsPermission = {
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            },
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
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    isSearchVisible: Boolean = false,
    onSearchVisibilityChange: (Boolean) -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    listState: LazyListState = rememberLazyListState(),
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
    onLife360Click: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onSocialMediaClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onAboutClick: () -> Unit,
    onMediaContentClick: () -> Unit = {},
    onRequestContactsPermission: () -> Unit = {},
    viewModel: SettingsViewModel
) {
    // Snackbar host state for disabled click feedback
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Track cumulative drag for pull-to-reveal
    var cumulativeDrag by remember { mutableFloatStateOf(0f) }
    val revealThreshold = 80f // dp threshold to reveal search

    // Focus search field when it becomes visible
    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            focusRequester.requestFocus()
        }
    }

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

    // Searchable settings items - each has a title, keywords for matching, and section
    data class SearchableItem(
        val key: String,
        val title: String,
        val keywords: List<String>,
        val section: String
    )

    val searchableItems = remember {
        listOf(
            // Connectivity
            SearchableItem("imessage", "iMessage", listOf("bluebubbles", "server", "mac", "apple"), "Connectivity"),
            SearchableItem("private_api", "Private API", listOf("typing", "reactions", "tapback", "read receipts", "advanced"), "Connectivity"),
            SearchableItem("typing_indicators", "Send typing indicators", listOf("typing", "bubble"), "Connectivity"),
            SearchableItem("sms", "SMS/MMS", listOf("text", "messaging", "cellular", "carrier"), "Connectivity"),
            SearchableItem("sync", "Sync settings", listOf("refresh", "update", "data"), "Connectivity"),
            // Notifications
            SearchableItem("notifications", "Notifications", listOf("alert", "sound", "vibration", "badge"), "Notifications"),
            SearchableItem("message_sounds", "Message sounds", listOf("audio", "tone", "ring"), "Notifications"),
            SearchableItem("sound_theme", "Sound theme", listOf("tone", "ringtone"), "Notifications"),
            // Appearance
            SearchableItem("app_title", "Simple app title", listOf("name", "messages", "bothbubbles"), "Appearance"),
            SearchableItem("unread_count", "Show unread count", listOf("badge", "number"), "Appearance"),
            SearchableItem("effects", "Message effects", listOf("animation", "bubble", "screen", "confetti", "fireworks"), "Appearance"),
            SearchableItem("swipe", "Swipe actions", listOf("gesture", "left", "right"), "Appearance"),
            SearchableItem("haptics", "Haptic feedback", listOf("vibration", "vibrate", "touch"), "Appearance"),
            SearchableItem("audio_haptic", "Sync haptics with sounds", listOf("vibration", "audio"), "Appearance"),
            // Messaging
            SearchableItem("templates", "Quick reply templates", listOf("saved", "responses", "canned"), "Messaging"),
            SearchableItem("auto_responder", "Auto-responder", listOf("automatic", "reply", "greeting"), "Messaging"),
            SearchableItem("media", "Media & content", listOf("link", "preview", "image", "video", "quality", "social"), "Messaging"),
            // Sharing & Social
            SearchableItem("eta", "ETA sharing", listOf("location", "navigation", "arrival", "maps"), "Sharing"),
            SearchableItem("life360", "Life360", listOf("location", "family", "friends", "tracking"), "Sharing"),
            SearchableItem("calendar", "Calendar integrations", listOf("events", "schedule", "busy"), "Sharing"),
            // Privacy
            SearchableItem("contacts", "Contacts access", listOf("permission", "phone", "address book"), "Privacy"),
            SearchableItem("blocked", "Blocked contacts", listOf("block", "ignore"), "Privacy"),
            SearchableItem("spam", "Spam protection", listOf("filter", "junk", "unwanted"), "Privacy"),
            SearchableItem("categorization", "Message categorization", listOf("sort", "organize", "ml", "ai"), "Privacy"),
            SearchableItem("archived", "Archived", listOf("hidden", "old"), "Privacy"),
            // Data
            SearchableItem("storage", "Storage", listOf("cache", "files", "space", "clear"), "Data"),
            SearchableItem("export", "Export messages", listOf("backup", "save", "html", "pdf"), "Data"),
            // About
            SearchableItem("about", "About", listOf("version", "license", "help", "info"), "About")
        )
    }

    // Filter items based on search query
    val filteredItems = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            emptySet()
        } else {
            val query = searchQuery.lowercase()
            searchableItems.filter { item ->
                item.title.lowercase().contains(query) ||
                item.keywords.any { it.contains(query) }
            }.map { it.key }.toSet()
        }
    }

    val isFiltering = searchQuery.isNotBlank()

    // Helper to check if any item in a section matches
    fun sectionMatches(vararg keys: String): Boolean {
        if (!isFiltering) return true
        return keys.any { it in filteredItems }
    }

    // Check which sections should be visible
    val showConnectivity = sectionMatches("imessage", "private_api", "typing_indicators", "sms", "sync")
    val showNotifications = sectionMatches("notifications", "message_sounds", "sound_theme")
    val showAppearance = sectionMatches("app_title", "unread_count", "effects", "swipe", "haptics", "audio_haptic")
    val showMessaging = sectionMatches("templates", "auto_responder", "media")
    val showSharing = sectionMatches("eta", "life360", "calendar")
    val showPrivacy = sectionMatches("contacts", "blocked", "spam", "categorization", "archived")
    val showData = sectionMatches("storage", "export")
    val showAbout = sectionMatches("about")

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Pull-to-reveal search bar
            AnimatedVisibility(
                visible = isSearchVisible,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                PullToRevealSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClose = {
                        onSearchQueryChange("")
                        onSearchVisibilityChange(false)
                    },
                    focusRequester = focusRequester,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Pull indicator when at top and not searching
            if (!isSearchVisible) {
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (cumulativeDrag > 20f) (cumulativeDrag / revealThreshold).coerceIn(0f, 1f) else 0f,
                    label = "indicatorAlpha"
                )
                if (indicatorAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(indicatorAlpha)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Pull to search",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isSearchVisible) {
                        if (!isSearchVisible) {
                            detectVerticalDragGestures(
                                onDragStart = { cumulativeDrag = 0f },
                                onDragEnd = {
                                    if (cumulativeDrag >= revealThreshold && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                                        onSearchVisibilityChange(true)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    cumulativeDrag = 0f
                                },
                                onDragCancel = { cumulativeDrag = 0f },
                                onVerticalDrag = { _, dragAmount ->
                                    // Only track downward drags when at the top
                                    if (dragAmount > 0 && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                                        cumulativeDrag += dragAmount
                                    } else if (dragAmount < 0) {
                                        cumulativeDrag = (cumulativeDrag + dragAmount).coerceAtLeast(0f)
                                    }
                                }
                            )
                        }
                    },
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
            ) {
        // ═══════════════════════════════════════════════════════════════
        // SECTION 1: Connectivity
        // Focus: The "pipes" that make the app work
        // ═══════════════════════════════════════════════════════════════
        if (showConnectivity) {
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connectivity",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadge(
                        label = "iMessage",
                        status = iMessageStatus,
                        onClick = onServerSettingsClick
                    )
                    StatusBadge(
                        label = "SMS",
                        status = smsStatus,
                        onClick = onSmsSettingsClick
                    )
                }
            }
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
                    iconTint = SettingsIconColors.Connectivity,
                    subtitle = "BlueBubbles server settings",
                    onClick = onServerSettingsClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Private API toggle - nested under iMessage
                SettingsMenuItem(
                    icon = Icons.Default.VpnKey,
                    title = "Enable Private API",
                    iconTint = SettingsIconColors.Connectivity,
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
                                enabled = uiState.isServerConfigured && !isConnecting,
                                showIcons = false
                            )
                        }
                    } else null
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Send typing indicators - nested under Private API
                SettingsMenuItem(
                    icon = Icons.Default.Keyboard,
                    title = "Send typing indicators",
                    iconTint = SettingsIconColors.Connectivity,
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
                    iconTint = SettingsIconColors.Connectivity,
                    subtitle = "Local SMS messaging options",
                    onClick = onSmsSettingsClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Sync settings
                SettingsMenuItem(
                    icon = Icons.Default.Sync,
                    title = "Sync settings",
                    iconTint = SettingsIconColors.Connectivity,
                    subtitle = "Last synced: ${uiState.lastSyncFormatted}",
                    onClick = onSyncSettingsClick
                )
            }
        }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 3: Notifications & Alerts
        // Focus: How the app gets your attention
        // ═══════════════════════════════════════════════════════════════
        if (showNotifications) {
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
                    iconTint = SettingsIconColors.Notifications,
                    subtitle = "Sound, vibration, and display",
                    onClick = onNotificationsClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Message sounds toggle
                SettingsMenuItem(
                    icon = Icons.Default.VolumeUp,
                    title = "Message sounds",
                    iconTint = SettingsIconColors.Notifications,
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
                        iconTint = SettingsIconColors.Notifications,
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
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 4: Appearance & Interaction
        // Focus: Visual customization and tactile feedback
        // ═══════════════════════════════════════════════════════════════
        if (showAppearance) {
        item {
            SettingsSectionTitle(title = "Appearance & interaction")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Message effects - most visually impactful, first
                SettingsMenuItem(
                    icon = Icons.Default.AutoAwesome,
                    title = "Message effects",
                    iconTint = SettingsIconColors.Appearance,
                    subtitle = "Animations for screen and bubble effects",
                    onClick = onEffectsSettingsClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Swipe gestures - frequently used
                SettingsMenuItem(
                    icon = Icons.Default.SwipeRight,
                    title = "Swipe actions",
                    iconTint = SettingsIconColors.Appearance,
                    subtitle = "Customize conversation swipe gestures",
                    onClick = onSwipeSettingsClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Haptic feedback toggle - commonly adjusted
                SettingsMenuItem(
                    icon = Icons.Default.Vibration,
                    title = "Haptic feedback",
                    iconTint = SettingsIconColors.Appearance,
                    subtitle = if (uiState.hapticsEnabled) "Vibration feedback enabled" else "Vibration feedback disabled",
                    onClick = {
                        val enablingHaptics = !uiState.hapticsEnabled
                        viewModel.setHapticsEnabled(enablingHaptics)
                        if (enablingHaptics) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.hapticsEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setHapticsEnabled(enabled)
                                if (enabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
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
                        iconTint = SettingsIconColors.Appearance,
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Show unread count in header toggle
                SettingsMenuItem(
                    icon = Icons.Default.MarkUnreadChatAlt,
                    title = "Show unread count",
                    iconTint = SettingsIconColors.Appearance,
                    subtitle = if (uiState.showUnreadCountInHeader) "Badge visible in header" else "Badge hidden",
                    onClick = { viewModel.setShowUnreadCountInHeader(!uiState.showUnreadCountInHeader) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.showUnreadCountInHeader,
                            onCheckedChange = { viewModel.setShowUnreadCountInHeader(it) },
                            showIcons = false
                        )
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Simple app title toggle - niche setting, last
                SettingsMenuItem(
                    icon = Icons.Default.TextFields,
                    title = "Simple app title",
                    iconTint = SettingsIconColors.Appearance,
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
            }
        }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 5: Messaging
        // Focus: Composing and sending enhancements
        // ═══════════════════════════════════════════════════════════════
        if (showMessaging) {
        item {
            SettingsSectionTitle(title = "Messaging")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Media & content - most commonly used, first
                SettingsMenuItem(
                    icon = Icons.Default.PermMedia,
                    title = "Media & content",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = "Link previews, social media videos, image quality",
                    onClick = onMediaContentClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Quick reply templates
                SettingsMenuItem(
                    icon = Icons.Default.Quickreply,
                    title = "Quick reply templates",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = "Saved responses and smart suggestions",
                    onClick = onTemplatesClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Auto-responder - least common, last
                SettingsMenuItem(
                    icon = Icons.Default.SmartToy,
                    title = "Auto-responder",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = "Greet first-time iMessage contacts",
                    onClick = onAutoResponderClick
                )
            }
        }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 5.5: Sharing & Social
        // Focus: Location sharing and social integrations
        // ═══════════════════════════════════════════════════════════════
        if (showSharing) {
        item {
            SettingsSectionTitle(title = "Sharing & Social")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // ETA Sharing
                SettingsMenuItem(
                    icon = Icons.Outlined.Navigation,
                    title = "ETA sharing",
                    iconTint = SettingsIconColors.Location,
                    subtitle = "Share arrival time while navigating",
                    onClick = onEtaSharingClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Life360 Integration
                SettingsMenuItem(
                    icon = Icons.Outlined.LocationOn,
                    title = "Life360",
                    iconTint = SettingsIconColors.Location,
                    subtitle = "Show friends and family locations",
                    onClick = onLife360Click
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Calendar Integrations
                SettingsMenuItem(
                    icon = Icons.Default.CalendarMonth,
                    title = "Calendar integrations",
                    iconTint = SettingsIconColors.Location,
                    subtitle = "Show contact calendars in chat headers",
                    onClick = onCalendarClick
                )
            }
        }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 6: Privacy & Permissions
        // Focus: Managing the inbox, security, and app permissions
        // ═══════════════════════════════════════════════════════════════
        if (showPrivacy) {
        item {
            SettingsSectionTitle(title = "Privacy & permissions")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Spam protection - security first
                SettingsMenuItem(
                    icon = Icons.Default.Shield,
                    title = "Spam protection",
                    iconTint = SettingsIconColors.Privacy,
                    subtitle = "Automatic spam detection settings",
                    onClick = onSpamClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Blocked contacts - managing who can contact you
                SettingsMenuItem(
                    icon = Icons.Default.Block,
                    title = "Blocked contacts",
                    iconTint = SettingsIconColors.Privacy,
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

                // Message categorization - organizing messages
                SettingsMenuItem(
                    icon = Icons.Default.Category,
                    title = "Message categorization",
                    iconTint = SettingsIconColors.Privacy,
                    subtitle = "Sort messages into categories with ML",
                    onClick = onCategorizationClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Contacts access - permission management
                SettingsMenuItem(
                    icon = Icons.Default.Contacts,
                    title = "Contacts access",
                    iconTint = SettingsIconColors.Privacy,
                    subtitle = if (uiState.hasContactsPermission) {
                        "Tap to refresh contact names and photos"
                    } else {
                        "Tap to enable contact names and photos"
                    },
                    onClick = {
                        if (uiState.hasContactsPermission) {
                            // Force refresh contact info
                            viewModel.forceRefreshContactInfo()
                        } else {
                            onRequestContactsPermission()
                        }
                    },
                    trailingContent = if (uiState.hasContactsPermission) {
                        {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Granted",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        {
                            FilledTonalButton(onClick = onRequestContactsPermission) {
                                Text("Grant")
                            }
                        }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Archived - viewing hidden chats, least important
                SettingsMenuItem(
                    icon = Icons.Outlined.Archive,
                    title = "Archived",
                    iconTint = SettingsIconColors.Privacy,
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
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 7: Data & Backup
        // Focus: Long-term data management
        // ═══════════════════════════════════════════════════════════════
        if (showData) {
        item {
            SettingsSectionTitle(title = "Data & backup")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Storage management
                SettingsMenuItem(
                    icon = Icons.Default.Storage,
                    title = "Storage",
                    iconTint = SettingsIconColors.Data,
                    subtitle = "Manage cached files and app storage",
                    onClick = onStorageClick
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Export messages
                SettingsMenuItem(
                    icon = Icons.Default.Download,
                    title = "Export messages",
                    iconTint = SettingsIconColors.Data,
                    subtitle = "Save conversations as HTML or PDF",
                    onClick = onExportClick
                )
            }
        }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION 8: About
        // Focus: App information (always at bottom)
        // ═══════════════════════════════════════════════════════════════
        if (showAbout) {
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
                    iconTint = SettingsIconColors.About,
                    subtitle = "Version, licenses, and help",
                    onClick = onAboutClick
                )
            }
        }
        }

        // Show "no results" message when filtering returns nothing
        if (isFiltering && filteredItems.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "No settings found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Try a different search term",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
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
 * Pull-to-reveal search bar for Settings screen.
 * Shows a search field with close button when revealed.
 */
@Composable
private fun PullToRevealSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Search field container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search settings",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                        )
                    }
                    if (query.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Close button
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close search",
                    tint = MaterialTheme.colorScheme.onSurface
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

/**
 * Dialog to display Find My debug data in plain text.
 * Shows WHO (device/person name) and WHERE (location) for each entry.
 */
@Composable
private fun FindMyDebugDialog(
    devices: List<FindMyDeviceDto>,
    friends: List<FindMyFriendDto>,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find My Debug") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        Text(
                            text = "Error: $error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    devices.isEmpty() && friends.isEmpty() -> {
                        Text(
                            text = "No Find My data available.\n\nMake sure Find My is enabled on your Mac and the server has the Private API helper connected.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> {
                        // Devices section
                        if (devices.isNotEmpty()) {
                            Text(
                                text = "DEVICES (${devices.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            devices.forEach { device ->
                                val who = device.name ?: device.deviceDisplayName ?: device.modelDisplayName ?: "Unknown Device"
                                val where = buildString {
                                    device.address?.mapItemFullAddress?.let { append(it) }
                                    if (isEmpty()) {
                                        device.location?.let { loc ->
                                            if (loc.latitude != null && loc.longitude != null) {
                                                append("${loc.latitude}, ${loc.longitude}")
                                            }
                                        }
                                    }
                                    if (isEmpty()) append("Location unavailable")
                                }

                                Text(
                                    text = "WHO: $who",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "WHERE: $where",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                device.batteryLevel?.let { battery ->
                                    Text(
                                        text = "BATTERY: ${(battery * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        // Friends section
                        if (friends.isNotEmpty()) {
                            if (devices.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }

                            Text(
                                text = "FRIENDS (${friends.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            friends.forEach { friend ->
                                val who = friend.title ?: friend.handle ?: "Unknown Person"
                                val where = buildString {
                                    friend.longAddress?.let { append(it) }
                                    if (isEmpty()) {
                                        friend.shortAddress?.let { append(it) }
                                    }
                                    if (isEmpty()) {
                                        friend.coordinates?.let { coords ->
                                            if (coords.size >= 2) {
                                                append("${coords[0]}, ${coords[1]}")
                                            }
                                        }
                                    }
                                    if (isEmpty()) append("Location unavailable")
                                }

                                Text(
                                    text = "WHO: $who",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                friend.subtitle?.let { subtitle ->
                                    Text(
                                        text = "($subtitle)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "WHERE: $where",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                friend.status?.let { status ->
                                    Text(
                                        text = "STATUS: $status",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Social media settings content for use in SettingsPanel.
 * Contains TikTok, Instagram video playback settings with section headers.
 */
@Composable
fun SocialMediaSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Determine section enabled states
    val downloadSectionEnabled = uiState.tiktokDownloaderEnabled || uiState.instagramDownloaderEnabled
    val tiktokQualityEnabled = uiState.tiktokDownloaderEnabled
    val reelsSectionEnabled = uiState.socialMediaBackgroundDownloadEnabled

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
    ) {
        // Reels concept preview
        item {
            com.bothbubbles.ui.settings.socialmedia.ReelsConceptPreview(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION: Platforms
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Platforms")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // TikTok video downloading
                SettingsMenuItem(
                    icon = Icons.Default.PlayCircle,
                    title = "TikTok video playback",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = if (uiState.tiktokDownloaderEnabled) {
                        "Play TikTok videos inline"
                    } else {
                        "Open TikTok links in browser"
                    },
                    onClick = { viewModel.setTiktokDownloaderEnabled(!uiState.tiktokDownloaderEnabled) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.tiktokDownloaderEnabled,
                            onCheckedChange = viewModel::setTiktokDownloaderEnabled,
                            showIcons = false
                        )
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Instagram video downloading
                SettingsMenuItem(
                    icon = Icons.Default.PlayCircle,
                    title = "Instagram video playback",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = if (uiState.instagramDownloaderEnabled) {
                        "Play Instagram Reels inline"
                    } else {
                        "Open Instagram links in browser"
                    },
                    onClick = { viewModel.setInstagramDownloaderEnabled(!uiState.instagramDownloaderEnabled) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.instagramDownloaderEnabled,
                            onCheckedChange = viewModel::setInstagramDownloaderEnabled,
                            showIcons = false
                        )
                    }
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION: Download behavior
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Download behavior")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Auto-download videos
                SettingsMenuItem(
                    icon = Icons.Default.CloudDownload,
                    title = "Auto-download videos",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = if (!downloadSectionEnabled) {
                        "Enable TikTok or Instagram first"
                    } else if (uiState.socialMediaBackgroundDownloadEnabled) {
                        "Videos download automatically when received"
                    } else {
                        "Videos download only when you tap to play"
                    },
                    onClick = {
                        if (downloadSectionEnabled) {
                            viewModel.setSocialMediaBackgroundDownloadEnabled(!uiState.socialMediaBackgroundDownloadEnabled)
                        }
                    },
                    enabled = downloadSectionEnabled,
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.socialMediaBackgroundDownloadEnabled,
                            onCheckedChange = {
                                if (downloadSectionEnabled) viewModel.setSocialMediaBackgroundDownloadEnabled(it)
                            },
                            enabled = downloadSectionEnabled,
                            showIcons = false
                        )
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Download on cellular
                SettingsMenuItem(
                    icon = Icons.Default.SignalCellularAlt,
                    title = "Download on cellular",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = if (!downloadSectionEnabled) {
                        "Enable TikTok or Instagram first"
                    } else if (uiState.socialMediaDownloadOnCellularEnabled) {
                        "Downloads use Wi-Fi and mobile data"
                    } else {
                        "Downloads only on Wi-Fi"
                    },
                    onClick = {
                        if (downloadSectionEnabled) {
                            viewModel.setSocialMediaDownloadOnCellularEnabled(!uiState.socialMediaDownloadOnCellularEnabled)
                        }
                    },
                    enabled = downloadSectionEnabled,
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.socialMediaDownloadOnCellularEnabled,
                            onCheckedChange = {
                                if (downloadSectionEnabled) viewModel.setSocialMediaDownloadOnCellularEnabled(it)
                            },
                            enabled = downloadSectionEnabled,
                            showIcons = false
                        )
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // TikTok video quality
                SettingsMenuItem(
                    icon = Icons.Default.Hd,
                    title = "TikTok video quality",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = if (!tiktokQualityEnabled) {
                        "Enable TikTok first"
                    } else if (uiState.tiktokVideoQuality == "hd") {
                        "HD - Higher quality, larger downloads"
                    } else {
                        "SD - Lower quality, faster downloads"
                    },
                    onClick = {
                        if (tiktokQualityEnabled) {
                            val newQuality = if (uiState.tiktokVideoQuality == "hd") "sd" else "hd"
                            viewModel.setTiktokVideoQuality(newQuality)
                        }
                    },
                    enabled = tiktokQualityEnabled,
                    trailingContent = {
                        Text(
                            text = if (uiState.tiktokVideoQuality == "hd") "HD" else "SD",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (tiktokQualityEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION: Viewing experience
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Viewing experience")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Reels feed
                SettingsMenuItem(
                    icon = Icons.Default.Slideshow,
                    title = "Reels experience",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = if (!reelsSectionEnabled) {
                        "Enable auto-download first"
                    } else if (uiState.reelsFeedEnabled) {
                        "Swipe vertically through videos full-screen"
                    } else {
                        "Videos play inline in the chat"
                    },
                    onClick = {
                        if (reelsSectionEnabled) {
                            viewModel.setReelsFeedEnabled(!uiState.reelsFeedEnabled)
                        }
                    },
                    enabled = reelsSectionEnabled,
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.reelsFeedEnabled,
                            onCheckedChange = {
                                if (reelsSectionEnabled) viewModel.setReelsFeedEnabled(it)
                            },
                            enabled = reelsSectionEnabled,
                            showIcons = false
                        )
                    }
                )

                // Include video attachments in reels
                val reelsAttachmentsEnabled = uiState.reelsFeedEnabled
                SettingsMenuItem(
                    icon = Icons.Default.AttachFile,
                    title = "Include video attachments",
                    iconTint = SettingsIconColors.Messaging,
                    subtitle = if (uiState.reelsIncludeVideoAttachments) {
                        "All videos in chat appear in Reels"
                    } else {
                        "Only social media videos in Reels"
                    },
                    onClick = {
                        if (reelsAttachmentsEnabled) {
                            viewModel.setReelsIncludeVideoAttachments(!uiState.reelsIncludeVideoAttachments)
                        }
                    },
                    enabled = reelsAttachmentsEnabled,
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.reelsIncludeVideoAttachments,
                            onCheckedChange = {
                                if (reelsAttachmentsEnabled) viewModel.setReelsIncludeVideoAttachments(it)
                            },
                            enabled = reelsAttachmentsEnabled,
                            showIcons = false
                        )
                    }
                )
            }
        }
    }
}

/**
 * Find the Activity from a Context by unwrapping ContextWrappers.
 */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
