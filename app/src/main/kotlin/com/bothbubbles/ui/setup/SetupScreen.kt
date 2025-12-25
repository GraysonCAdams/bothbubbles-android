package com.bothbubbles.ui.setup

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupScreen(
    skipWelcome: Boolean = false,
    skipSmsSetup: Boolean = false,
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    // Calculate start page based on mode and permissions state
    // Page indices: 0=Welcome, 1=Contacts, 2=Permissions, 3=Server, 4=SMS, 5=Categorization, 6=AutoResponder, 7=Sync
    val startPage = remember(skipWelcome, uiState.allPermissionsGranted, uiState.hasContactsPermission) {
        when {
            !skipWelcome -> 0  // Full onboarding starts at Welcome
            uiState.allPermissionsGranted -> 3  // Skip to Server Connection
            !uiState.hasContactsPermission -> 1  // Start at Contacts if not granted
            else -> 2  // Start at Permissions
        }
    }

    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { 8 })

    // Sync pager state with viewmodel
    LaunchedEffect(uiState.currentPage) {
        if (pagerState.currentPage != uiState.currentPage) {
            pagerState.animateScrollToPage(uiState.currentPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (uiState.currentPage != pagerState.currentPage) {
            viewModel.setPage(pagerState.currentPage)
        }
    }

    // Navigate to conversations when setup is complete
    LaunchedEffect(uiState.isSyncComplete) {
        if (uiState.isSyncComplete) {
            onSetupComplete()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Calculate visible pages based on mode
        // Page indices: 0=Welcome, 1=Contacts, 2=Permissions, 3=Server, 4=SMS, 5=Categorization, 6=AutoResponder, 7=Sync
        val visiblePages = remember(skipWelcome, skipSmsSetup, uiState.allPermissionsGranted, uiState.hasContactsPermission, uiState.isConnectionSuccessful) {
            buildList {
                if (!skipWelcome) add(0)  // Welcome
                if (!skipWelcome || !uiState.hasContactsPermission) add(1)  // Contacts (first permission, always shown if not granted)
                if (!skipWelcome || !uiState.allPermissionsGranted) add(2)  // Permissions (notifications + battery)
                add(3)  // Server Connection (always shown)
                if (!skipSmsSetup) add(4)  // SMS Setup
                if (!skipSmsSetup) add(5)  // Categorization
                if (!skipSmsSetup && uiState.isConnectionSuccessful) add(6)  // Auto-Responder (only if server connected)
                add(7)  // Sync (always shown when server connected)
            }
        }

        // Page indicator - only show dots for visible pages
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            visiblePages.forEach { pageIndex ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (pageIndex == pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                )
            }
        }

        // Pages
        // Page indices: 0=Welcome, 1=Contacts, 2=Permissions, 3=Server, 4=SMS, 5=Categorization, 6=AutoResponder, 7=Sync
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false // Disable swipe, use buttons only
        ) { page ->
            when (page) {
                0 -> WelcomePage(
                    onNext = { coroutineScope.launch { pagerState.animateScrollToPage(1) } }
                )
                1 -> ContactsPage(
                    hasContactsPermission = uiState.hasContactsPermission,
                    onPermissionsChecked = { viewModel.checkPermissions() },
                    onNext = { coroutineScope.launch { pagerState.animateScrollToPage(2) }; Unit },
                    onBack = if (skipWelcome) onSetupComplete else {
                        { coroutineScope.launch { pagerState.animateScrollToPage(0) }; Unit }
                    }
                )
                2 -> PermissionsPage(
                    uiState = uiState,
                    onPermissionsChecked = { viewModel.checkPermissions() },
                    onNext = { coroutineScope.launch { pagerState.animateScrollToPage(3) }; Unit },
                    onBack = { coroutineScope.launch { pagerState.animateScrollToPage(1) }; Unit }
                )
                3 -> ServerConnectionPage(
                    uiState = uiState,
                    onServerUrlChange = viewModel::updateServerUrl,
                    onPasswordChange = viewModel::updatePassword,
                    onTestConnection = viewModel::testConnection,
                    onShowQrScanner = viewModel::showQrScanner,
                    onNext = {
                        coroutineScope.launch {
                            // Skip SMS setup page if in reconnect mode
                            pagerState.animateScrollToPage(if (skipSmsSetup) 7 else 4)
                        }
                        Unit
                    },
                    onSkip = if (skipSmsSetup) null else {
                        {
                            viewModel.skipServerSetup()
                            coroutineScope.launch { pagerState.animateScrollToPage(4) }
                            Unit
                        }
                    },
                    onBack = if (skipWelcome && uiState.allPermissionsGranted) onSetupComplete else {
                        { coroutineScope.launch { pagerState.animateScrollToPage(2) }; Unit }
                    }
                )
                4 -> SmsSetupPage(
                    uiState = uiState,
                    onSmsEnabledChange = viewModel::updateSmsEnabled,
                    getMissingSmsPermissions = viewModel::getMissingSmsPermissions,
                    getDefaultSmsAppIntent = viewModel::getDefaultSmsAppIntent,
                    onSmsPermissionsResult = viewModel::onSmsPermissionsResult,
                    onDefaultSmsAppResult = viewModel::onDefaultSmsAppResult,
                    onNext = {
                        viewModel.finalizeSmsSettings()
                        coroutineScope.launch { pagerState.animateScrollToPage(5) }
                    },
                    onBack = { coroutineScope.launch { pagerState.animateScrollToPage(3) } },
                    onFinish = {
                        viewModel.finalizeSmsSettings()
                        viewModel.completeSetupWithoutSync()
                        onSetupComplete()
                    }
                )
                5 -> CategorizationPage(
                    uiState = uiState,
                    onDownloadMlModel = viewModel::downloadMlModel,
                    onSkip = {
                        viewModel.skipMlDownload()
                        // Go to Auto-Responder if server connected, else Sync
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(if (uiState.isConnectionSuccessful) 6 else 7)
                        }
                    },
                    onMlCellularUpdateChange = viewModel::updateMlCellularAutoUpdate,
                    onNext = {
                        // Go to Auto-Responder if server connected, else Sync
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(if (uiState.isConnectionSuccessful) 6 else 7)
                        }
                    },
                    onBack = { coroutineScope.launch { pagerState.animateScrollToPage(4) } }
                )
                6 -> AutoResponderSetupPage(
                    uiState = uiState,
                    onFilterModeChange = viewModel::updateAutoResponderFilter,
                    onEnable = {
                        viewModel.enableAutoResponder()
                        coroutineScope.launch { pagerState.animateScrollToPage(7) }
                    },
                    onSkip = {
                        viewModel.skipAutoResponder()
                        coroutineScope.launch { pagerState.animateScrollToPage(7) }
                    },
                    onBack = { coroutineScope.launch { pagerState.animateScrollToPage(5) } },
                    onLoadPhoneNumber = viewModel::loadUserPhoneNumber
                )
                7 -> SyncPage(
                    uiState = uiState,
                    onStartSync = viewModel::startSync,
                    onBack = {
                        coroutineScope.launch {
                            // Go back to Auto-Responder if server connected, Categorization if not skipped, else Server
                            val targetPage = when {
                                uiState.isConnectionSuccessful && !skipSmsSetup -> 6  // Auto-Responder
                                !skipSmsSetup -> 5  // Categorization
                                else -> 3  // Server Connection
                            }
                            pagerState.animateScrollToPage(targetPage)
                        }
                    }
                )
            }
        }
    }

    // QR Scanner overlay
    if (uiState.showQrScanner) {
        QrScannerOverlay(
            onQrCodeScanned = viewModel::onQrCodeScanned,
            onDismiss = viewModel::hideQrScanner
        )
    }
}
