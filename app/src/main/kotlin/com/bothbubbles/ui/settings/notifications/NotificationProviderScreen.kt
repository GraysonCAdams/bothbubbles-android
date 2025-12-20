package com.bothbubbles.ui.settings.notifications

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.fcm.FcmTokenState
import com.bothbubbles.services.fcm.FirebaseConfigState
import com.bothbubbles.ui.settings.components.SettingsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationProviderScreen(
    onNavigateBack: () -> Unit,
    viewModel: NotificationProviderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Provider") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Information",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Choose how BlueBubbles receives notifications when the app is in the background.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Provider selection
            SettingsCard {
                Column(modifier = Modifier.selectableGroup()) {
                    // FCM option
                    ListItem(
                        headlineContent = { Text("Firebase Cloud Messaging") },
                        supportingContent = {
                            Text("Uses Google's push notification service. Recommended for most users.")
                        },
                        leadingContent = {
                            RadioButton(
                                selected = uiState.selectedProvider == "fcm",
                                onClick = null
                            )
                        },
                        modifier = Modifier.selectable(
                            selected = uiState.selectedProvider == "fcm",
                            onClick = { viewModel.setNotificationProvider("fcm") },
                            role = Role.RadioButton
                        )
                    )

                    HorizontalDivider()

                    // Foreground service option
                    ListItem(
                        headlineContent = { Text("Keep App Alive") },
                        supportingContent = {
                            Text("Maintains a persistent connection. Uses more battery but works without Google services.")
                        },
                        leadingContent = {
                            RadioButton(
                                selected = uiState.selectedProvider == "foreground",
                                onClick = null
                            )
                        },
                        modifier = Modifier.selectable(
                            selected = uiState.selectedProvider == "foreground",
                            onClick = { viewModel.setNotificationProvider("foreground") },
                            role = Role.RadioButton
                        )
                    )
                }
            }

            // Google Play Services warning
            if (!uiState.isGooglePlayServicesAvailable && uiState.selectedProvider == "fcm") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Google Play Services unavailable",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "FCM requires Google Play Services. Switch to \"Keep App Alive\" mode instead.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // FCM Status (when FCM is selected)
            if (uiState.selectedProvider == "fcm") {
                SettingsCard {
                    Column {
                        ListItem(
                            headlineContent = { Text("FCM Status") },
                            supportingContent = {
                                Text(getFcmStatusText(uiState))
                            },
                            leadingContent = {
                                Icon(
                                    when {
                                        uiState.fcmTokenState is FcmTokenState.Registered -> Icons.Default.CheckCircle
                                        uiState.fcmTokenState is FcmTokenState.Error -> Icons.Default.Error
                                        uiState.fcmTokenState is FcmTokenState.Loading -> Icons.Default.Sync
                                        else -> Icons.Default.CloudOff
                                    },
                                    contentDescription = when {
                                        uiState.fcmTokenState is FcmTokenState.Registered -> "FCM registered"
                                        uiState.fcmTokenState is FcmTokenState.Error -> "FCM error"
                                        uiState.fcmTokenState is FcmTokenState.Loading -> "FCM loading"
                                        else -> "FCM offline"
                                    },
                                    tint = when {
                                        uiState.fcmTokenState is FcmTokenState.Registered -> MaterialTheme.colorScheme.primary
                                        uiState.fcmTokenState is FcmTokenState.Error -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                )
                            }
                        )

                        HorizontalDivider()

                        // Re-register button
                        ListItem(
                            headlineContent = { Text("Re-register") },
                            supportingContent = { Text("Re-fetch Firebase config and register device") },
                            leadingContent = {
                                Icon(Icons.Default.Refresh, contentDescription = "Re-register")
                            },
                            trailingContent = {
                                if (uiState.isReRegistering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            },
                            modifier = Modifier.let {
                                if (uiState.isReRegistering) it
                                else it.selectable(
                                    selected = false,
                                    onClick = { viewModel.reRegisterFcm() },
                                    role = Role.Button
                                )
                            }
                        )
                    }
                }
            }

            // Foreground service info (when foreground is selected)
            if (uiState.selectedProvider == "foreground") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.BatteryAlert,
                            contentDescription = "Battery usage",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Battery usage",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "This mode keeps a persistent connection and may use more battery. A notification will appear while the service is running.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getFcmStatusText(state: NotificationProviderUiState): String {
    return when (state.firebaseConfigState) {
        is FirebaseConfigState.NotInitialized -> "Not configured"
        is FirebaseConfigState.Initializing -> "Initializing..."
        is FirebaseConfigState.Error -> "Error: ${state.firebaseConfigState.message}"
        is FirebaseConfigState.Initialized -> {
            when (state.fcmTokenState) {
                is FcmTokenState.Unknown -> "Checking..."
                is FcmTokenState.Disabled -> "Disabled"
                is FcmTokenState.NotConfigured -> "Firebase not configured"
                is FcmTokenState.Loading -> "Getting token..."
                is FcmTokenState.Available -> "Token available, registering..."
                is FcmTokenState.Registered -> "Registered"
                is FcmTokenState.Error -> "Error: ${state.fcmTokenState.message}"
            }
        }
    }
}
