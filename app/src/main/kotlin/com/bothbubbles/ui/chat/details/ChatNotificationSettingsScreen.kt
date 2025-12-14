package com.bothbubbles.ui.chat.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatNotificationSettingsScreen(
    chatGuid: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatNotificationSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSoundPicker by remember { mutableStateOf(false) }
    var showLockScreenPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with avatar and name
                item {
                    NotificationSettingsHeader(
                        displayName = uiState.displayName,
                        subtitle = uiState.subtitle,
                        isGroup = uiState.chat?.isGroup == true,
                        participantNames = uiState.participants.map { it.displayName },
                        avatarPath = uiState.chat?.customAvatarPath
                    )
                }

                // Show notifications toggle
                item {
                    NotificationToggleCard(
                        isEnabled = uiState.notificationsEnabled,
                        onToggle = { viewModel.setNotificationsEnabled(it) }
                    )
                }

                // Priority selection (only shown when notifications are enabled)
                item {
                    AnimatedVisibility(visible = uiState.notificationsEnabled) {
                        PrioritySelectionCard(
                            selectedPriority = uiState.notificationPriority,
                            onPrioritySelected = { viewModel.setNotificationPriority(it) }
                        )
                    }
                }

                // Bubble and Pop on screen (only shown when notifications are enabled)
                item {
                    AnimatedVisibility(visible = uiState.notificationsEnabled) {
                        BubbleAndPopCard(
                            bubbleEnabled = uiState.bubbleEnabled,
                            popOnScreen = uiState.popOnScreen,
                            onBubbleToggle = { viewModel.setBubbleEnabled(it) },
                            onPopOnScreenToggle = { viewModel.setPopOnScreen(it) }
                        )
                    }
                }

                // Sound, Lock screen, Notification dot, Vibration
                item {
                    AnimatedVisibility(visible = uiState.notificationsEnabled) {
                        AdditionalSettingsCard(
                            soundName = uiState.notificationSoundDisplay,
                            lockScreenVisibility = uiState.lockScreenVisibility,
                            showNotificationDot = uiState.showNotificationDot,
                            vibrationEnabled = uiState.vibrationEnabled,
                            onSoundClick = { showSoundPicker = true },
                            onLockScreenClick = { showLockScreenPicker = true },
                            onNotificationDotToggle = { viewModel.setShowNotificationDot(it) },
                            onVibrationToggle = { viewModel.setVibrationEnabled(it) }
                        )
                    }
                }

                // Bottom spacing
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // Sound picker dialog
    if (showSoundPicker) {
        SoundPickerDialog(
            currentSound = uiState.notificationSound,
            onSoundSelected = { sound ->
                viewModel.setNotificationSound(sound)
                showSoundPicker = false
            },
            onDismiss = { showSoundPicker = false }
        )
    }

    // Lock screen visibility picker dialog
    if (showLockScreenPicker) {
        LockScreenVisibilityDialog(
            currentVisibility = uiState.lockScreenVisibility,
            onVisibilitySelected = { visibility ->
                viewModel.setLockScreenVisibility(visibility)
                showLockScreenPicker = false
            },
            onDismiss = { showLockScreenPicker = false }
        )
    }
}
