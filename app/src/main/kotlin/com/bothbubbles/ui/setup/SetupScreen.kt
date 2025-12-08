package com.bothbubbles.ui.setup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bothbubbles.ui.components.QrCodeScanner
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

    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { 6 })

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
        val visiblePages = remember(skipWelcome, skipSmsSetup, uiState.allPermissionsGranted) {
            buildList {
                if (!skipWelcome) add(0)  // Welcome
                if (!skipWelcome || !uiState.allPermissionsGranted) add(1)  // Permissions
                add(2)  // Server Connection (always shown)
                if (!skipSmsSetup) add(3)  // SMS Setup
                if (!skipSmsSetup) add(4)  // Categorization
                add(5)  // Sync (always shown when server connected)
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
                            pagerState.animateScrollToPage(if (skipSmsSetup) 5 else 3)
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
                        coroutineScope.launch { pagerState.animateScrollToPage(5) }
                    },
                    onMlCellularUpdateChange = viewModel::updateMlCellularAutoUpdate,
                    onNext = { coroutineScope.launch { pagerState.animateScrollToPage(5) } },
                    onBack = { coroutineScope.launch { pagerState.animateScrollToPage(3) } }
                )
                5 -> SyncPage(
                    uiState = uiState,
                    onMessagesPerChatChange = viewModel::updateMessagesPerChat,
                    onSkipEmptyChatsChange = viewModel::updateSkipEmptyChats,
                    onStartSync = viewModel::startSync,
                    onBack = {
                        coroutineScope.launch {
                            // Go back to Categorization if SMS setup was not skipped, else Server Connection
                            pagerState.animateScrollToPage(if (skipSmsSetup) 2 else 4)
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

@Composable
private fun WelcomePage(
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App icon with both bubble types
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Two overlapping message bubbles
                Icon(
                    Icons.AutoMirrored.Filled.Message,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .offset(x = (-8).dp, y = (-4).dp),
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.Message,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .offset(x = 8.dp, y = 4.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Welcome to BothBubbles",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "The best of both worlds: iMessage and SMS/MMS in one app.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Connect to your BlueBubbles server for iMessage, with SMS/MMS fallback for weak connections or non-iMessage recipients.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Get Started")
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun PermissionsPage(
    uiState: SetupUiState,
    onPermissionsChecked: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Re-check permissions when screen resumes (after returning from settings)
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                onPermissionsChecked()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onPermissionsChecked() }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onPermissionsChecked() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Permissions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "BothBubbles needs a few permissions to work properly",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionItem(
                icon = Icons.Default.Notifications,
                title = "Notifications",
                description = "Receive message notifications",
                isGranted = uiState.hasNotificationPermission,
                onRequest = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            )
        }

        // Contacts permission
        PermissionItem(
            icon = Icons.Default.Contacts,
            title = "Contacts",
            description = "Show contact names in conversations",
            isGranted = uiState.hasContactsPermission,
            onRequest = {
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        )

        // Battery optimization
        PermissionItem(
            icon = Icons.Default.BatteryFull,
            title = "Battery Optimization",
            description = "Disable to receive notifications reliably",
            isGranted = uiState.isBatteryOptimizationDisabled,
            onRequest = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.weight(1f))

        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }

            Button(onClick = onNext) {
                Text("Continue")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            if (isGranted) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                FilledTonalButton(onClick = onRequest) {
                    Text("Grant")
                }
            }
        }
    }
}

@Composable
private fun ServerConnectionPage(
    uiState: SetupUiState,
    onServerUrlChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onShowQrScanner: () -> Unit,
    onNext: () -> Unit,
    onSkip: (() -> Unit)?,
    onBack: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "BlueBubbles Server",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Connect to your BlueBubbles server for iMessage support",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Info card about BlueBubbles
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "BlueBubbles requires a Mac running the BlueBubbles server app. Visit bluebubbles.app to set it up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // QR Code button
        OutlinedButton(
            onClick = onShowQrScanner,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan QR Code")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = "or enter manually",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Server URL
        OutlinedTextField(
            value = uiState.serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL") },
            placeholder = { Text("https://your-server.ngrok.io") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            leadingIcon = {
                Icon(Icons.Default.Link, contentDescription = null)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Password
        OutlinedTextField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            leadingIcon = {
                Icon(Icons.Default.Lock, contentDescription = null)
            },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Test connection button
        Button(
            onClick = {
                keyboardController?.hide()
                onTestConnection()
            },
            enabled = uiState.serverUrl.isNotBlank() && uiState.password.isNotBlank() && !uiState.isTestingConnection,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isTestingConnection) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Testing...")
            } else {
                Text("Test Connection")
            }
        }

        // Connection status
        AnimatedVisibility(visible = uiState.connectionError != null || uiState.isConnectionSuccessful) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (uiState.isConnectionSuccessful) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (uiState.isConnectionSuccessful) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (uiState.isConnectionSuccessful) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (uiState.isConnectionSuccessful) {
                            "Connection successful!"
                        } else {
                            uiState.connectionError ?: "Connection failed"
                        },
                        color = if (uiState.isConnectionSuccessful) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Skip option - only shown in full onboarding mode
        if (onSkip != null) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip for now (SMS/MMS only)")
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }

            Button(
                onClick = onNext,
                enabled = uiState.canProceedFromConnection
            ) {
                Text("Continue")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun SmsSetupPage(
    uiState: SetupUiState,
    onSmsEnabledChange: (Boolean) -> Unit,
    getMissingSmsPermissions: () -> Array<String>,
    getDefaultSmsAppIntent: () -> Intent,
    onSmsPermissionsResult: () -> Unit,
    onDefaultSmsAppResult: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val smsStatus = uiState.smsCapabilityStatus

    // Track if we should auto-prompt for default SMS after permissions granted
    var shouldPromptDefaultSms by remember { mutableStateOf(false) }

    // Launcher to set as default SMS app
    val setDefaultSmsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onDefaultSmsAppResult()
    }

    // Permission launcher for SMS permissions
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onSmsPermissionsResult()
        // If all permissions were granted, prompt for default SMS app
        if (results.values.all { it }) {
            shouldPromptDefaultSms = true
        }
    }

    // Auto-launch default SMS prompt after permissions are granted
    LaunchedEffect(shouldPromptDefaultSms, smsStatus) {
        if (shouldPromptDefaultSms &&
            smsStatus?.missingPermissions?.isEmpty() == true &&
            smsStatus.isDefaultSmsApp == false) {
            shouldPromptDefaultSms = false
            setDefaultSmsLauncher.launch(getDefaultSmsAppIntent())
        }
    }

    // On initial page load, check if we should prompt for default SMS app
    // (permissions already granted but not default app yet)
    var hasPromptedOnLoad by remember { mutableStateOf(false) }
    LaunchedEffect(smsStatus, uiState.smsEnabled) {
        if (!hasPromptedOnLoad &&
            uiState.smsEnabled &&
            smsStatus?.missingPermissions?.isEmpty() == true &&
            smsStatus.isDefaultSmsApp == false) {
            hasPromptedOnLoad = true
            // Small delay to let the UI settle
            kotlinx.coroutines.delay(500)
            setDefaultSmsLauncher.launch(getDefaultSmsAppIntent())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SMS/MMS Setup",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enable SMS/MMS as a fallback or primary messaging option",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // SMS toggle card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Sms,
                    contentDescription = null,
                    tint = if (uiState.smsEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable SMS/MMS",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Send and receive text messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                Switch(
                    checked = uiState.smsEnabled,
                    onCheckedChange = onSmsEnabledChange
                )
            }
        }

        // SMS capability status
        if (uiState.smsEnabled && smsStatus != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = when {
                    smsStatus.isFullyFunctional -> MaterialTheme.colorScheme.primaryContainer
                    !smsStatus.deviceSupportsSms -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when {
                                smsStatus.isFullyFunctional -> Icons.Default.CheckCircle
                                !smsStatus.deviceSupportsSms -> Icons.Default.PhonelinkOff
                                else -> Icons.Default.Warning
                            },
                            contentDescription = null,
                            tint = when {
                                smsStatus.isFullyFunctional -> MaterialTheme.colorScheme.primary
                                !smsStatus.deviceSupportsSms -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.tertiary
                            }
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = when {
                                smsStatus.isFullyFunctional -> "SMS Fully Configured"
                                !smsStatus.deviceSupportsSms -> "Device Doesn't Support SMS"
                                else -> "SMS Setup Required"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = when {
                                smsStatus.isFullyFunctional -> MaterialTheme.colorScheme.onPrimaryContainer
                                !smsStatus.deviceSupportsSms -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                    }

                    // Status indicators
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatusIndicator("Read", smsStatus.canReadSms)
                        StatusIndicator("Send", smsStatus.canSendSms)
                        StatusIndicator("Receive", smsStatus.canReceiveSms)
                    }

                    // Show setup buttons if needed
                    if (smsStatus.needsSetup) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Request permissions if missing
                        if (smsStatus.missingPermissions.isNotEmpty()) {
                            Button(
                                onClick = {
                                    smsPermissionLauncher.launch(getMissingSmsPermissions())
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant SMS Permissions (${smsStatus.missingPermissions.size} needed)")
                            }
                        }

                        // Request default SMS app if we have receive permission but aren't default
                        if (!smsStatus.isDefaultSmsApp && smsStatus.hasReceivePermission) {
                            if (smsStatus.missingPermissions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Button(
                                onClick = {
                                    setDefaultSmsLauncher.launch(getDefaultSmsAppIntent())
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Sms, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Set as Default SMS App")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SMS usage explanation
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "When to use SMS/MMS",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val useCases = listOf(
                    "When BlueBubbles server is unreachable",
                    "Messaging non-iMessage contacts",
                    "As a backup for important messages",
                    "When you don't have a Mac available"
                )

                useCases.forEach { useCase ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = useCase,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }

            if (uiState.isConnectionSuccessful) {
                // If server is connected, go to sync
                Button(onClick = onNext) {
                    Text("Continue")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            } else {
                // If server skipped, finish setup
                Button(onClick = onFinish) {
                    Text("Finish Setup")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.Check, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun CategorizationPage(
    uiState: SetupUiState,
    onDownloadMlModel: () -> Unit,
    onSkip: () -> Unit,
    onMlCellularUpdateChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Icon
        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Smart Message Categorization",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Automatically organize messages from businesses and services into helpful categories.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Category preview cards
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                CategoryPreviewItem(
                    icon = Icons.Default.Receipt,
                    title = "Transactions",
                    description = "Bank alerts, payments, receipts"
                )
                Spacer(modifier = Modifier.height(12.dp))
                CategoryPreviewItem(
                    icon = Icons.Default.LocalShipping,
                    title = "Deliveries",
                    description = "Package tracking, shipping updates"
                )
                Spacer(modifier = Modifier.height(12.dp))
                CategoryPreviewItem(
                    icon = Icons.Default.LocalOffer,
                    title = "Promotions",
                    description = "Marketing, deals, offers"
                )
                Spacer(modifier = Modifier.height(12.dp))
                CategoryPreviewItem(
                    icon = Icons.Default.Alarm,
                    title = "Reminders",
                    description = "Appointments, verification codes"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Download info card (if ML model not yet downloaded)
        if (!uiState.mlModelDownloaded) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = if (uiState.isOnWifi) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (uiState.isOnWifi) Icons.Default.Wifi else Icons.Default.SignalCellularAlt,
                            contentDescription = null,
                            tint = if (uiState.isOnWifi) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Requires ~20 MB download",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (uiState.isOnWifi) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                    }

                    if (!uiState.isOnWifi) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = uiState.mlEnableCellularUpdates,
                                onCheckedChange = onMlCellularUpdateChange
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Allow downloads on cellular",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            // Error message
            if (uiState.mlDownloadError != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.mlDownloadError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }

            if (uiState.mlModelDownloaded || uiState.mlSetupComplete) {
                // Already downloaded, just continue
                Button(
                    onClick = onNext,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Continue")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            } else {
                // Download or skip options
                Column(horizontalAlignment = Alignment.End) {
                    Button(
                        onClick = onDownloadMlModel,
                        enabled = !uiState.mlDownloading,
                        modifier = Modifier.height(48.dp)
                    ) {
                        if (uiState.mlDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Downloading...")
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enable")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onSkip,
                        enabled = !uiState.mlDownloading
                    ) {
                        Text("Skip", fontSize = 14.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Note about enabling later
        if (!uiState.mlModelDownloaded && !uiState.mlSetupComplete) {
            Text(
                text = "You can enable this later in Settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun CategoryPreviewItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SyncPage(
    uiState: SetupUiState,
    onMessagesPerChatChange: (Int) -> Unit,
    onSkipEmptyChatsChange: (Boolean) -> Unit,
    onStartSync: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            // Syncing view
            uiState.isSyncing -> {
                Spacer(modifier = Modifier.weight(1f))

                CircularProgressIndicator(
                    progress = { uiState.syncProgress },
                    modifier = Modifier.size(120.dp),
                    strokeWidth = 8.dp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "${(uiState.syncProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Syncing your messages...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.weight(1f))
            }

            // Error view
            uiState.syncError != null -> {
                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Sync Failed",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = uiState.syncError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(onClick = onStartSync) {
                    Text("Retry")
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            // Settings view (before sync)
            else -> {
                Text(
                    text = "Sync Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Configure how iMessages are synced from your server",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Messages per chat slider
                Text(
                    text = "Messages per conversation: ${uiState.messagesPerChat}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Slider(
                    value = uiState.messagesPerChat.toFloat(),
                    onValueChange = { onMessagesPerChatChange(it.toInt()) },
                    valueRange = 10f..100f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "More messages = longer sync time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Skip empty chats toggle
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Skip empty conversations",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Don't sync conversations with no messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = uiState.skipEmptyChats,
                            onCheckedChange = onSkipEmptyChatsChange
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Navigation buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back")
                    }

                    Button(
                        onClick = onStartSync,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Sync")
                    }
                }
            }
        }
    }
}

@Composable
private fun MlDownloadSection(
    uiState: SetupUiState,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onCellularUpdateChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.5f))

        // Icon
        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Smart Message Categorization",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Enable automatic categorization of messages into Transactions, Deliveries, Promotions, and Reminders.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Download info card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = if (uiState.isOnWifi) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (uiState.isOnWifi) Icons.Default.Wifi else Icons.Default.SignalCellularAlt,
                        contentDescription = null,
                        tint = if (uiState.isOnWifi) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (uiState.isOnWifi) {
                            "Ready to download (~20 MB)"
                        } else {
                            "Cellular download (~20 MB)"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (uiState.isOnWifi) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        }
                    )
                }

                if (!uiState.isOnWifi) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "You're on cellular data. Downloading will use approximately 20 MB of your data plan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Checkbox for enabling cellular auto-updates
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.mlEnableCellularUpdates,
                            onCheckedChange = onCellularUpdateChange
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Also enable automatic ML updates on cellular",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        // Error message
        if (uiState.mlDownloadError != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.mlDownloadError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Download button
        Button(
            onClick = onDownload,
            enabled = !uiState.mlDownloading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (uiState.mlDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Downloading...")
            } else {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download ML Model")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Skip button
        TextButton(
            onClick = onSkip,
            enabled = !uiState.mlDownloading
        ) {
            Text("Skip for now")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun QrScannerOverlay(
    onQrCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Full-screen QR scanner using CameraX + ML Kit
    QrCodeScanner(
        onQrCodeScanned = onQrCodeScanned,
        onDismiss = onDismiss
    )
}

@Composable
private fun StatusIndicator(label: String, enabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            if (enabled) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    }
}
