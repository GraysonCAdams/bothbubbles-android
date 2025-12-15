package com.bothbubbles.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
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
import com.bothbubbles.ui.settings.sync.SyncSettingsContent
import com.bothbubbles.ui.settings.templates.QuickReplyTemplatesContent
import com.bothbubbles.ui.settings.eta.EtaSharingSettingsContent

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
        SettingsPanelPage.About -> "About"
        SettingsPanelPage.OpenSourceLicenses -> "Open source licenses"
    }

    // MD3: Use surfaceContainerLow for panel to distinguish from main content
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            topBar = {
                TopAppBar(
                    title = { Text(title) },
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
            // Determine if navigation is forward or backward for animation direction
            val isForward = remember { mutableStateOf(true) }

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
                                        isForward.value = false
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
                    if (isForward.value) {
                        // Forward navigation: slide in from right
                        (slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                        ) + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                        ) + fadeOut(tween(150)))
                    } else {
                        // Backward navigation: slide in from left
                        (slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                        ) + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                        ) + fadeOut(tween(150)))
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
                        SettingsContent(
                            modifier = Modifier.fillMaxSize(),
                            uiState = uiState,
                            onServerSettingsClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Server)
                            },
                            onArchivedClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Archived)
                            },
                            onBlockedClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Blocked)
                            },
                            onSpamClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Spam)
                            },
                            onCategorizationClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Categorization)
                            },
                            onSyncSettingsClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Sync)
                            },
                            onExportClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Export)
                            },
                            onSmsSettingsClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Sms)
                            },
                            onNotificationsClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Notifications)
                            },
                            onSwipeSettingsClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Swipe)
                            },
                            onEffectsSettingsClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Effects)
                            },
                            onImageQualityClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.ImageQuality)
                            },
                            onTemplatesClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.Templates)
                            },
                            onAutoResponderClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.AutoResponder)
                            },
                            onEtaSharingClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.EtaSharing)
                            },
                            onAboutClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.About)
                            },
                            viewModel = viewModel
                        )
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
                        SyncSettingsContent()
                    }
                    SettingsPanelPage.Export -> {
                        ExportPanelContent()
                    }
                    SettingsPanelPage.Sms -> {
                        SmsPanelContent(
                            onBackupRestoreClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.SmsBackup)
                            }
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
                    SettingsPanelPage.About -> {
                        AboutContent(
                            onOpenSourceLicensesClick = {
                                isForward.value = true
                                navigator.navigateTo(SettingsPanelPage.OpenSourceLicenses)
                            }
                        )
                    }
                    SettingsPanelPage.OpenSourceLicenses -> {
                        OpenSourceLicensesContent()
                    }
                }
            }
            }  // Close Box
        }
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
