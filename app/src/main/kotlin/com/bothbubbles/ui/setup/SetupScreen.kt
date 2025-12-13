package com.bothbubbles.ui.setup

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
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
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Calculate start page based on mode and permissions state
    val startPage = remember(skipWelcome, uiState.allPermissionsGranted) {
        when {
            !skipWelcome -> 0  // Full onboarding starts at Welcome
            uiState.allPermissionsGranted -> 2  // Skip to Server Connection
            else -> 1  // Start at Permissions
        }
    }

    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { 7 })

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
        val visiblePages = remember(skipWelcome, skipSmsSetup, uiState.allPermissionsGranted, uiState.isConnectionSuccessful) {
            buildList {
                if (!skipWelcome) add(0)  // Welcome
                if (!skipWelcome || !uiState.allPermissionsGranted) add(1)  // Permissions
                add(2)  // Server Connection (always shown)
                if (!skipSmsSetup) add(3)  // SMS Setup
                if (!skipSmsSetup) add(4)  // Categorization
                if (!skipSmsSetup && uiState.isConnectionSuccessful) add(5)  // Auto-Responder (only if server connected)
                add(6)  // Sync (always shown when server connected)
            }
        }

        // Page indicator - only show dots for visible pages
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false // Disable swipe, use buttons only
        ) { page ->
            when (page) {
                0 -> WelcomePage(
                    onNext = { coroutineScope.launch { pagerState.animateScrollToPage(1) } }
                )
                1 -> PermissionsPage(
                    uiState = uiState,
                    onPermissionsChecked = { viewModel.checkPermissions() },
                    onNext = { coroutineScope.launch { pagerState.animateScrollToPage(2) }; Unit },
                    onBack = if (skipWelcome) onSetupComplete else {
                        { coroutineScope.launch { pagerState.animateScrollToPage(0) }; Unit }
                    }
                )
                2 -> ServerConnectionPage(
                    uiState = uiState,
                    onServerUrlChange = viewModel::updateServerUrl,
                    onPasswordChange = viewModel::updatePassword,
                    onTestConnection = viewModel::testConnection,
                    onShowQrScanner = viewModel::showQrScanner,
                    onNext = {
                        coroutineScope.launch {
                            // Skip SMS setup page if in reconnect mode
                            pagerState.animateScrollToPage(if (skipSmsSetup) 6 else 3)
                        }
                        Unit
                    },
                    onSkip = if (skipSmsSetup) null else {
                        {
                            viewModel.skipServerSetup()
                            coroutineScope.launch { pagerState.animateScrollToPage(3) }
                            Unit
                        }
                    },
                    onBack = if (skipWelcome && uiState.allPermissionsGranted) onSetupComplete else {
                        { coroutineScope.launch { pagerState.animateScrollToPage(1) }; Unit }
                    }
                )
                3 -> SmsSetupPage(
                    uiState = uiState,
                    onSmsEnabledChange = viewModel::updateSmsEnabled,
                    getMissingSmsPermissions = viewModel::getMissingSmsPermissions,
                    getDefaultSmsAppIntent = viewModel::getDefaultSmsAppIntent,
                    onSmsPermissionsResult = viewModel::onSmsPermissionsResult,
                    onDefaultSmsAppResult = viewModel::onDefaultSmsAppResult,
                    onNext = {
                        viewModel.finalizeSmsSettings()
                        coroutineScope.launch { pagerState.animateScrollToPage(4) }
                    },
                    onBack = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                    onFinish = {
                        viewModel.finalizeSmsSettings()
                        viewModel.completeSetupWithoutSync()
                        onSetupComplete()
                    }
                )
                4 -> CategorizationPage(
                    uiState = uiState,
                    onDownloadMlModel = viewModel::downloadMlModel,
                    onSkip = {
                        viewModel.skipMlDownload()
                        // Go to Auto-Responder if server connected, else Sync
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(if (uiState.isConnectionSuccessful) 5 else 6)
                        }
                    },
                    onMlCellularUpdateChange = viewModel::updateMlCellularAutoUpdate,
                    onNext = {
                        // Go to Auto-Responder if server connected, else Sync
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(if (uiState.isConnectionSuccessful) 5 else 6)
                        }
                    },
                    onBack = { coroutineScope.launch { pagerState.animateScrollToPage(3) } }
                )
                5 -> AutoResponderSetupPage(
                    uiState = uiState,
                    onFilterModeChange = viewModel::updateAutoResponderFilter,
                    onEnable = {
                        viewModel.enableAutoResponder()
                        coroutineScope.launch { pagerState.animateScrollToPage(6) }
                    },
                    onSkip = {
                        viewModel.skipAutoResponder()
                        coroutineScope.launch { pagerState.animateScrollToPage(6) }
                    },
                    onBack = { coroutineScope.launch { pagerState.animateScrollToPage(4) } },
                    onLoadPhoneNumber = viewModel::loadUserPhoneNumber
                )
                6 -> SyncPage(
                    uiState = uiState,
                    onSkipEmptyChatsChange = viewModel::updateSkipEmptyChats,
                    onStartSync = viewModel::startSync,
                    onBack = {
                        coroutineScope.launch {
                            // Go back to Auto-Responder if server connected, Categorization if not skipped, else Server
                            val targetPage = when {
                                uiState.isConnectionSuccessful && !skipSmsSetup -> 5  // Auto-Responder
                                !skipSmsSetup -> 4  // Categorization
                                else -> 2  // Server Connection
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
