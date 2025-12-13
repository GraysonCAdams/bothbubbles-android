package com.bothbubbles.ui.chat.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bothbubbles.ui.components.common.ConversationAvatar

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

@Composable
private fun NotificationSettingsHeader(
    displayName: String,
    subtitle: String,
    isGroup: Boolean,
    participantNames: List<String>,
    avatarPath: String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 16.dp)
    ) {
        ConversationAvatar(
            displayName = displayName,
            isGroup = isGroup,
            participantNames = participantNames,
            avatarPath = avatarPath,
            size = 80.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NotificationToggleCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!isEnabled) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Show notifications",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun PrioritySelectionCard(
    selectedPriority: NotificationPriority,
    onPrioritySelected: (NotificationPriority) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            NotificationPriority.entries.forEach { priority ->
                PriorityOption(
                    priority = priority,
                    isSelected = priority == selectedPriority,
                    onClick = { onPrioritySelected(priority) }
                )
            }
        }
    }
}

@Composable
private fun PriorityOption(
    priority: NotificationPriority,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (priority) {
        NotificationPriority.PRIORITY -> Icons.Outlined.PriorityHigh
        NotificationPriority.DEFAULT -> Icons.Outlined.Notifications
        NotificationPriority.SILENT -> Icons.Outlined.NotificationsOff
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = priority.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
            if (priority.description != null) {
                Text(
                    text = priority.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        RadioButton(
            selected = isSelected,
            onClick = onClick
        )
    }
}

@Composable
private fun BubbleAndPopCard(
    bubbleEnabled: Boolean,
    popOnScreen: Boolean,
    onBubbleToggle: (Boolean) -> Unit,
    onPopOnScreenToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // Bubble conversation
            SettingRowWithSwitch(
                icon = Icons.Outlined.ChatBubbleOutline,
                title = "Bubble this conversation",
                subtitle = "Show floating icon on top of apps",
                isChecked = bubbleEnabled,
                onCheckedChange = onBubbleToggle
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Pop on screen
            SettingRowWithSwitch(
                icon = Icons.Outlined.PhoneAndroid,
                title = "Pop on screen",
                subtitle = "When device is unlocked, show notifications as a banner across the top of the screen",
                isChecked = popOnScreen,
                onCheckedChange = onPopOnScreenToggle
            )
        }
    }
}

@Composable
private fun AdditionalSettingsCard(
    soundName: String,
    lockScreenVisibility: LockScreenVisibility,
    showNotificationDot: Boolean,
    vibrationEnabled: Boolean,
    onSoundClick: () -> Unit,
    onLockScreenClick: () -> Unit,
    onNotificationDotToggle: (Boolean) -> Unit,
    onVibrationToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // Sound
            SettingRowClickable(
                icon = Icons.Outlined.Notifications,
                title = "Sound",
                subtitle = soundName,
                onClick = onSoundClick
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Lock screen
            SettingRowClickable(
                icon = Icons.Outlined.Lock,
                title = "Lock screen",
                subtitle = lockScreenVisibility.displayName,
                onClick = onLockScreenClick
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Show notification dot
            SettingRowWithSwitch(
                icon = Icons.Outlined.Circle,
                title = "Show notification dot",
                subtitle = null,
                isChecked = showNotificationDot,
                onCheckedChange = onNotificationDotToggle
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Vibration
            SettingRowWithSwitch(
                icon = Icons.Outlined.Vibration,
                title = "Vibration",
                subtitle = null,
                isChecked = vibrationEnabled,
                onCheckedChange = onVibrationToggle
            )
        }
    }
}

@Composable
private fun SettingRowWithSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingRowClickable(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SoundPickerDialog(
    currentSound: String?,
    onSoundSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sounds = listOf(
        null to "Default",
        "silent" to "Silent",
        "cute_bamboo" to "Cute Bamboo",
        "gentle_chime" to "Gentle Chime",
        "soft_bell" to "Soft Bell",
        "message_tone" to "Message Tone"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification sound") },
        text = {
            Column {
                sounds.forEach { (soundId, soundName) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSoundSelected(soundId) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSound == soundId,
                            onClick = { onSoundSelected(soundId) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = soundName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun LockScreenVisibilityDialog(
    currentVisibility: LockScreenVisibility,
    onVisibilitySelected: (LockScreenVisibility) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lock screen") },
        text = {
            Column {
                LockScreenVisibility.entries.forEach { visibility ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVisibilitySelected(visibility) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentVisibility == visibility,
                            onClick = { onVisibilitySelected(visibility) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = visibility.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
