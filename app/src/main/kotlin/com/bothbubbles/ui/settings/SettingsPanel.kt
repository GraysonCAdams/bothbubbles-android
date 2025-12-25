package com.bothbubbles.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import com.bothbubbles.ui.theme.MotionTokens
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.settings.about.AboutContent
import com.bothbubbles.ui.settings.about.OpenSourceLicensesContent
import com.bothbubbles.ui.settings.archived.ArchivedChatsContent
import com.bothbubbles.ui.settings.autoresponder.AutoResponderSettingsContent
import com.bothbubbles.ui.settings.blocked.BlockedContactsContent
import com.bothbubbles.ui.settings.categorization.CategorizationSettingsContent
import com.bothbubbles.ui.settings.export.ChatSelectionDialog
import com.bothbubbles.ui.settings.export.DatePickerDialog
import com.bothbubbles.ui.settings.export.ExportCompleteDialog
import com.bothbubbles.ui.settings.export.ExportContent
import com.bothbubbles.ui.settings.export.ExportErrorDialog
import com.bothbubbles.ui.settings.export.ExportProgressDialog
import com.bothbubbles.ui.settings.export.ExportViewModel
import com.bothbubbles.services.export.ExportProgress
import com.bothbubbles.ui.settings.notifications.NotificationSettingsContent
import com.bothbubbles.ui.settings.server.ServerSettingsContent
import com.bothbubbles.ui.settings.sms.SmsBackupContent
import com.bothbubbles.ui.settings.sms.SmsSettingsContent
import com.bothbubbles.ui.settings.sms.SmsSettingsViewModel
import com.bothbubbles.ui.settings.spam.SpamSettingsContent
import com.bothbubbles.ui.settings.swipe.SwipeSettingsContent
import com.bothbubbles.ui.settings.storage.StorageContent
import com.bothbubbles.ui.settings.sync.SyncSettingsContent
import com.bothbubbles.ui.settings.templates.QuickReplyTemplatesContent
import com.bothbubbles.ui.settings.eta.EtaSharingSettingsContent
import com.bothbubbles.ui.settings.media.MediaContentScreen
import com.bothbubbles.ui.settings.calendar.CalendarSettingsContent
import com.bothbubbles.ui.settings.search.SettingsSearchResults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth

/**
 * Settings panel that slides in from the right.
 * Uses internal navigation with AnimatedContent for smooth transitions between settings pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    onClose: () -> Unit,
    onNavigate: (String, Boolean) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = rememberSettingsPanelNavigator()

    // Sync settings dialog state
    var showCleanSyncDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    // Handle system back button
    BackHandler(enabled = true) {
        if (!navigator.navigateBack()) {
            onClose()
        }
    }

    // Get title based on current page
    val title = when (navigator.currentPage) {
        SettingsPanelPage.Main -> "Settings"
        SettingsPanelPage.Server -> "iMessage"
        SettingsPanelPage.Archived -> "Archived"
        SettingsPanelPage.Blocked -> "Blocked contacts"
        SettingsPanelPage.Spam -> "Spam protection"
        SettingsPanelPage.Categorization -> "Message categorization"
        SettingsPanelPage.Sync -> "Sync settings"
        SettingsPanelPage.Export -> "Export messages"
        SettingsPanelPage.Sms -> "SMS/MMS"
        SettingsPanelPage.SmsBackup -> "Backup & Restore"
        SettingsPanelPage.Notifications -> "Notifications"
        SettingsPanelPage.Swipe -> "Swipe actions"
        SettingsPanelPage.Effects -> "Message effects"
        SettingsPanelPage.ImageQuality -> "Image quality"
        SettingsPanelPage.Templates -> "Quick reply templates"
        SettingsPanelPage.AutoResponder -> "Auto-responder"
        SettingsPanelPage.EtaSharing -> "ETA sharing"
        SettingsPanelPage.Life360 -> "Life360"
        SettingsPanelPage.SocialMedia -> "Social media videos"
        SettingsPanelPage.About -> "About"
        SettingsPanelPage.OpenSourceLicenses -> "Open source licenses"
        SettingsPanelPage.Storage -> "Storage"
        SettingsPanelPage.MediaContent -> "Media & content"
        SettingsPanelPage.Calendar -> "Calendar integrations"
    }

    // MD3: Use surfaceContainerLow for panel to distinguish from main content
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            // Exclude bottom insets - progress bar handles nav bar padding
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        if (navigator.isSearchActive) {
                            // Search input field
                            BasicTextField(
                                value = navigator.searchQuery,
                                onValueChange = navigator::updateSearchQuery,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (navigator.searchQuery.isEmpty()) {
                                            Text(
                                                text = "Search settings",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 16.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        } else {
                            Text(title)
                        }
                    },
                    navigationIcon = {
                        if (navigator.canGoBack()) {
                            IconButton(onClick = { navigator.navigateBack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    },
                    actions = {
                        // Search icon - only on main page when not searching
                        if (!navigator.isSearchActive && navigator.currentPage == SettingsPanelPage.Main) {
                            IconButton(onClick = navigator::toggleSearch) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search settings"
                                )
                            }
                        }
                        // Clear button when searching with text
                        if (navigator.isSearchActive && navigator.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { navigator.updateSearchQuery("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                        // Close settings button
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close settings"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }
        ) { padding ->
            // Swipe gesture state for edge-swipe back navigation
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val swipeOffset = remember { Animatable(0f) }
            val swipeThresholdPx = with(density) { 100.dp.toPx() }
            val edgeWidthPx = with(density) { 20.dp.toPx() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(navigator.canGoBack()) {
                        if (!navigator.canGoBack()) return@pointerInput

                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                // Only start drag if starting from left edge
                                if (offset.x > edgeWidthPx) return@detectHorizontalDragGestures
                            },
                            onDragEnd = {
                                scope.launch {
                                    if (swipeOffset.value > swipeThresholdPx) {
                                        // Swipe threshold reached - navigate back
                                        navigator.navigateBack()
                                    }
                                    // Animate back to 0
                                    swipeOffset.animateTo(0f)
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    swipeOffset.animateTo(0f)
                                }
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                scope.launch {
                                    // Only allow positive (rightward) drags
                                    val newValue = (swipeOffset.value + dragAmount).coerceAtLeast(0f)
                                    swipeOffset.snapTo(newValue)
                                }
                            }
                        )
                    }
            ) {
            AnimatedContent(
                targetState = navigator.currentPage,
                transitionSpec = {
                    if (navigator.isNavigatingForward) {
                        // Forward navigation: slide in from right
                        (slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(MotionTokens.Duration.EMPHASIZED, easing = MotionTokens.Easing.Emphasized)
                        ) + fadeIn(tween(MotionTokens.Duration.NORMAL))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(MotionTokens.Duration.EMPHASIZED, easing = MotionTokens.Easing.Emphasized)
                        ) + fadeOut(tween(MotionTokens.Duration.QUICK)))
                    } else {
                        // Backward navigation: slide in from left
                        (slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(MotionTokens.Duration.EMPHASIZED, easing = MotionTokens.Easing.Emphasized)
                        ) + fadeIn(tween(MotionTokens.Duration.NORMAL))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(MotionTokens.Duration.EMPHASIZED, easing = MotionTokens.Easing.Emphasized)
                        ) + fadeOut(tween(MotionTokens.Duration.QUICK)))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    // Apply swipe offset for visual feedback during edge swipe
                    .offset { IntOffset(swipeOffset.value.roundToInt(), 0) },
                label = "settings_page_transition"
            ) { page ->
                when (page) {
                    SettingsPanelPage.Main -> {
                        if (navigator.isSearchActive) {
                            // Show search results when search is active
                            SettingsSearchResults(
                                results = navigator.searchResults,
                                query = navigator.searchQuery,
                                onResultClick = navigator::navigateFromSearch,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            SettingsContent(
                                modifier = Modifier.fillMaxSize(),
                                uiState = uiState,
                                onServerSettingsClick = { navigator.navigateTo(SettingsPanelPage.Server) },
                                onArchivedClick = { navigator.navigateTo(SettingsPanelPage.Archived) },
                                onBlockedClick = { navigator.navigateTo(SettingsPanelPage.Blocked) },
                                onSpamClick = { navigator.navigateTo(SettingsPanelPage.Spam) },
                                onCategorizationClick = { navigator.navigateTo(SettingsPanelPage.Categorization) },
                                onSyncSettingsClick = { navigator.navigateTo(SettingsPanelPage.Sync) },
                                onExportClick = { navigator.navigateTo(SettingsPanelPage.Export) },
                                onSmsSettingsClick = { navigator.navigateTo(SettingsPanelPage.Sms) },
                                onNotificationsClick = { navigator.navigateTo(SettingsPanelPage.Notifications) },
                                onSwipeSettingsClick = { navigator.navigateTo(SettingsPanelPage.Swipe) },
                                onEffectsSettingsClick = { navigator.navigateTo(SettingsPanelPage.Effects) },
                                onImageQualityClick = { navigator.navigateTo(SettingsPanelPage.ImageQuality) },
                                onTemplatesClick = { navigator.navigateTo(SettingsPanelPage.Templates) },
                                onAutoResponderClick = { navigator.navigateTo(SettingsPanelPage.AutoResponder) },
                                onEtaSharingClick = { navigator.navigateTo(SettingsPanelPage.EtaSharing) },
                                onLife360Click = { navigator.navigateTo(SettingsPanelPage.Life360) },
                                onSocialMediaClick = { navigator.navigateTo(SettingsPanelPage.SocialMedia) },
                                onStorageClick = { navigator.navigateTo(SettingsPanelPage.Storage) },
                                onAboutClick = { navigator.navigateTo(SettingsPanelPage.About) },
                                onMediaContentClick = { navigator.navigateTo(SettingsPanelPage.MediaContent) },
                                onCalendarClick = { navigator.navigateTo(SettingsPanelPage.Calendar) },
                                viewModel = viewModel
                            )
                        }
                    }
                    SettingsPanelPage.Server -> {
                        ServerSettingsContent()
                    }
                    SettingsPanelPage.Archived -> {
                        ArchivedChatsContent(
                            onChatClick = { chatGuid ->
                                onClose()
                                onNavigate("chat:$chatGuid", false)
                            }
                        )
                    }
                    SettingsPanelPage.Blocked -> {
                        BlockedContactsContent()
                    }
                    SettingsPanelPage.Spam -> {
                        SpamSettingsContent()
                    }
                    SettingsPanelPage.Categorization -> {
                        CategorizationSettingsContent()
                    }
                    SettingsPanelPage.Sync -> {
                        SyncSettingsContent(
                            showCleanSyncDialog = showCleanSyncDialog,
                            onShowCleanSyncDialog = { showCleanSyncDialog = it },
                            showDisconnectDialog = showDisconnectDialog,
                            onShowDisconnectDialog = { showDisconnectDialog = it }
                        )
                    }
                    SettingsPanelPage.Export -> {
                        ExportPanelContent()
                    }
                    SettingsPanelPage.Sms -> {
                        SmsPanelContent(
                            onBackupRestoreClick = { navigator.navigateTo(SettingsPanelPage.SmsBackup) }
                        )
                    }
                    SettingsPanelPage.SmsBackup -> {
                        SmsBackupContent()
                    }
                    SettingsPanelPage.Notifications -> {
                        NotificationSettingsContent()
                    }
                    SettingsPanelPage.Swipe -> {
                        SwipeSettingsContent()
                    }
                    SettingsPanelPage.Effects -> {
                        EffectsSettingsContent()
                    }
                    SettingsPanelPage.ImageQuality -> {
                        com.bothbubbles.ui.settings.attachments.ImageQualitySettingsContent()
                    }
                    SettingsPanelPage.Templates -> {
                        QuickReplyTemplatesContent()
                    }
                    SettingsPanelPage.AutoResponder -> {
                        AutoResponderSettingsContent()
                    }
                    SettingsPanelPage.EtaSharing -> {
                        EtaSharingSettingsContent()
                    }
                    SettingsPanelPage.Life360 -> {
                        com.bothbubbles.ui.settings.life360.Life360SettingsContent()
                    }
                    SettingsPanelPage.SocialMedia -> {
                        SocialMediaSettingsContent()
                    }
                    SettingsPanelPage.About -> {
                        AboutContent(
                            onOpenSourceLicensesClick = { navigator.navigateTo(SettingsPanelPage.OpenSourceLicenses) }
                        )
                    }
                    SettingsPanelPage.OpenSourceLicenses -> {
                        OpenSourceLicensesContent()
                    }
                    SettingsPanelPage.Storage -> {
                        StorageContent()
                    }
                    SettingsPanelPage.MediaContent -> {
                        MediaContentScreen()
                    }
                    SettingsPanelPage.Calendar -> {
                        CalendarSettingsContent()
                    }
                }
            }
            }  // Close Box
        }
    }

    // Sync settings dialogs - shown when triggered from SyncSettingsContent
    if (showCleanSyncDialog) {
        val syncViewModel: com.bothbubbles.ui.settings.sync.SyncSettingsViewModel = hiltViewModel()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCleanSyncDialog = false },
            icon = { Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clean sync?") },
            text = {
                Text(
                    "This will delete all local messages and conversations, then re-download everything from the server. This cannot be undone."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showCleanSyncDialog = false
                        syncViewModel.cleanSync()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clean sync")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCleanSyncDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDisconnectDialog) {
        val syncViewModel: com.bothbubbles.ui.settings.sync.SyncSettingsViewModel = hiltViewModel()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            icon = { Icon(Icons.Default.Close, contentDescription = null) },
            title = { Text("Disconnect from server?") },
            text = {
                Text(
                    "This will remove all iMessage conversations and messages synced from the BlueBubbles server. " +
                    "Local SMS/MMS messages will be preserved. This cannot be undone."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDisconnectDialog = false
                        syncViewModel.disconnectServer()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Export panel content wrapper with dialogs.
 * Wraps ExportContent with required state and dialog management.
 */
@Composable
private fun ExportPanelContent(
    viewModel: ExportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showChatPicker by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    ExportContent(
        uiState = uiState,
        viewModel = viewModel,
        onShowChatPicker = { showChatPicker = true },
        onShowStartDatePicker = { showStartDatePicker = true },
        onShowEndDatePicker = { showEndDatePicker = true }
    )

    // Chat picker dialog
    if (showChatPicker) {
        ChatSelectionDialog(
            chats = uiState.availableChats,
            selectedChatGuids = uiState.selectedChatGuids,
            onChatToggle = { viewModel.toggleChatSelection(it) },
            onSelectAll = { viewModel.setAllChatsSelected(true) },
            onDeselectAll = { viewModel.setAllChatsSelected(false) },
            onDismiss = { showChatPicker = false }
        )
    }

    // Date picker dialogs
    if (showStartDatePicker) {
        DatePickerDialog(
            title = "Start Date",
            selectedDate = uiState.startDate,
            onDateSelected = { viewModel.setStartDate(it) },
            onDismiss = { showStartDatePicker = false }
        )
    }

    if (showEndDatePicker) {
        DatePickerDialog(
            title = "End Date",
            selectedDate = uiState.endDate,
            onDateSelected = { viewModel.setEndDate(it) },
            onDismiss = { showEndDatePicker = false }
        )
    }

    // Progress/Result dialogs
    when (val progress = uiState.exportProgress) {
        is ExportProgress.Loading,
        is ExportProgress.Generating,
        is ExportProgress.Saving -> {
            ExportProgressDialog(
                progress = progress,
                onCancel = { viewModel.cancelExport() }
            )
        }
        is ExportProgress.Complete -> {
            ExportCompleteDialog(
                result = progress,
                onDismiss = { viewModel.resetExportState() }
            )
        }
        is ExportProgress.Error -> {
            ExportErrorDialog(
                message = progress.message,
                onDismiss = { viewModel.resetExportState() }
            )
        }
        ExportProgress.Cancelled -> {
            LaunchedEffect(Unit) {
                viewModel.resetExportState()
            }
        }
        ExportProgress.Idle -> { /* No dialog */ }
    }
}

/**
 * SMS panel content wrapper with permission launchers.
 * Wraps SmsSettingsContent with required permission request launchers.
 */
@Composable
private fun SmsPanelContent(
    onBackupRestoreClick: () -> Unit,
    viewModel: SmsSettingsViewModel = hiltViewModel()
) {
    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.onPermissionsResult()
    }

    // Default SMS app launcher
    val defaultSmsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onDefaultSmsAppResult()
    }

    SmsSettingsContent(
        viewModel = viewModel,
        onBackupRestoreClick = onBackupRestoreClick,
        onRequestPermissions = { permissionLauncher.launch(viewModel.getMissingPermissions()) },
        onRequestDefaultSmsApp = { defaultSmsLauncher.launch(viewModel.getDefaultSmsAppIntent()) }
    )
}
