package com.bothbubbles.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
        SettingsPanelPage.About -> "About"
        SettingsPanelPage.OpenSourceLicenses -> "Open source licenses"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Scaffold(
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
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            // Determine if navigation is forward or backward for animation direction
            val isForward = remember { mutableStateOf(true) }

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
                    .padding(padding),
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
                        ExportContent()
                    }
                    SettingsPanelPage.Sms -> {
                        SmsSettingsContent(
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
        }
    }
}
